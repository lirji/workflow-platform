package com.lrj.workflow.server.outbox;

import com.lrj.workflow.core.outbox.OutboxEventRepository;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final WorkflowMetrics metrics = mock(WorkflowMetrics.class);
    private final OutboxPublisher publisher = new OutboxPublisher(outbox, kafka, metrics);

    @BeforeEach
    void config() {
        publisher.batchSize = 10;
        publisher.leaseSeconds = 30;
        publisher.sendTimeoutSeconds = 2;
        publisher.producerMaxBlockMs = 5_000;
        publisher.producerDeliveryTimeoutMs = 1_500;
        publisher.maxAttempts = 3;
        publisher.retryBackoffSeconds = 5;
        when(outbox.renewLease(eq("evt-1"), anyString(), eq(30))).thenReturn(true);
    }

    private static OutboxEventRepository.OutboxRow row(int attempt) {
        return row("evt-1", attempt);
    }

    private static OutboxEventRepository.OutboxRow row(String eventId, int attempt) {
        return new OutboxEventRepository.OutboxRow(
                eventId, "workflow.action.requested.v1", "key-1", "event-v1", "{}", attempt);
    }

    @Test
    void successfulSendMarksSentWithTheExactOneTimeLeaseToken() {
        when(outbox.claimBatch(eq(10), anyString(), eq(30))).thenReturn(List.of(row(0)));
        when(kafka.send("workflow.action.requested.v1", "key-1", "{}"))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(outbox.markSent(eq("evt-1"), anyString())).thenReturn(true);

        publisher.publishBatch();

        ArgumentCaptor<String> claimOwner = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> commitOwner = ArgumentCaptor.forClass(String.class);
        verify(outbox).claimBatch(eq(10), claimOwner.capture(), eq(30));
        verify(outbox).markSent(eq("evt-1"), commitOwner.capture());
        assertThat(commitOwner.getValue()).isEqualTo(claimOwner.getValue());
        assertThat(claimOwner.getValue()).hasSize(36);
    }

    @Test
    void transientFailureReschedulesWithCasAndExponentialBackoff() {
        when(outbox.claimBatch(eq(10), anyString(), eq(30))).thenReturn(List.of(row(1)));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failed);
        when(outbox.reschedule(eq("evt-1"), anyString(), eq(10), anyString())).thenReturn(true);

        publisher.publishBatch();

        verify(outbox).reschedule(eq("evt-1"), anyString(), eq(10),
                org.mockito.ArgumentMatchers.contains("broker unavailable"));
        verify(outbox, never()).markFailed(anyString(), anyString(), anyString());
        verify(metrics, never()).outboxFailed(anyString());
    }

    @Test
    void finalFailureMovesToFailedAndEmitsMetric() {
        when(outbox.claimBatch(eq(10), anyString(), eq(30))).thenReturn(List.of(row(2)));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("permanent failure"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failed);
        when(outbox.markFailed(eq("evt-1"), anyString(), anyString())).thenReturn(true);

        publisher.publishBatch();

        verify(outbox).markFailed(eq("evt-1"), anyString(),
                org.mockito.ArgumentMatchers.contains("permanent failure"));
        verify(outbox, never()).reschedule(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
        verify(metrics).outboxFailed("workflow.action.requested.v1");
    }

    @Test
    void brokerAckTimeoutMovesToDeliveryUnknownWithoutAutomaticRetry() throws Exception {
        when(outbox.claimBatch(eq(10), anyString(), eq(30))).thenReturn(List.of(row(0)));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> pending = mock(CompletableFuture.class);
        when(pending.get(2, TimeUnit.SECONDS)).thenThrow(new TimeoutException("ack timeout"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(pending);
        when(outbox.markDeliveryUnknown(eq("evt-1"), anyString(), anyString())).thenReturn(true);

        publisher.publishBatch();

        verify(pending).get(2, TimeUnit.SECONDS);
        verify(outbox).markDeliveryUnknown(eq("evt-1"), anyString(),
                org.mockito.ArgumentMatchers.contains("ack timeout"));
        verify(outbox, never()).reschedule(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
        verify(metrics).outboxDeliveryUnknown("workflow.action.requested.v1");
    }

    @Test
    void kafkaDeliveryTimeoutWrappedByFutureIsUnknownNotRetryableFailure() throws Exception {
        when(outbox.claimBatch(eq(10), anyString(), eq(30))).thenReturn(List.of(row(0)));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(2, TimeUnit.SECONDS)).thenThrow(new ExecutionException(
                new org.apache.kafka.common.errors.TimeoutException("delivery timeout")));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(future);
        when(outbox.markDeliveryUnknown(eq("evt-1"), anyString(), anyString())).thenReturn(true);

        publisher.publishBatch();

        verify(outbox).markDeliveryUnknown(eq("evt-1"), anyString(),
                org.mockito.ArgumentMatchers.contains("ExecutionException"));
        verify(outbox, never()).reschedule(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
        verify(outbox, never()).markFailed(anyString(), anyString(), anyString());
    }

    @Test
    void cancelledKafkaFutureIsUnknownNotRetryableFailure() throws Exception {
        when(outbox.claimBatch(eq(10), anyString(), eq(30))).thenReturn(List.of(row(0)));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(2, TimeUnit.SECONDS)).thenThrow(new CancellationException("cancelled"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(future);
        when(outbox.markDeliveryUnknown(eq("evt-1"), anyString(), anyString())).thenReturn(true);

        publisher.publishBatch();

        verify(outbox).markDeliveryUnknown(eq("evt-1"), anyString(),
                org.mockito.ArgumentMatchers.contains("CancellationException"));
        verify(outbox, never()).reschedule(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    void interruptionStopsTheBatchBeforeSendingLaterRows() throws Exception {
        when(outbox.claimBatch(eq(10), anyString(), eq(30)))
                .thenReturn(List.of(row("evt-1", 0), row("evt-2", 0)));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(2, TimeUnit.SECONDS)).thenThrow(new InterruptedException("shutdown"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(future);
        when(outbox.markDeliveryUnknown(eq("evt-1"), anyString(), anyString())).thenReturn(true);

        publisher.publishBatch();

        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
        verify(outbox, never()).renewLease(eq("evt-2"), anyString(), eq(30));
        assertThat(Thread.interrupted()).isTrue(); // 同时清理本测试线程的中断标记。
    }

    @Test
    void kafkaSynchronousInterruptStopsTheBatchAndIsDeliveryUnknown() {
        when(outbox.claimBatch(eq(10), anyString(), eq(30)))
                .thenReturn(List.of(row("evt-1", 0), row("evt-2", 0)));
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(
                new org.apache.kafka.common.errors.InterruptException(new InterruptedException("shutdown")));
        when(outbox.markDeliveryUnknown(eq("evt-1"), anyString(), anyString())).thenReturn(true);

        publisher.publishBatch();

        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
        verify(outbox).markDeliveryUnknown(eq("evt-1"), anyString(),
                org.mockito.ArgumentMatchers.contains("InterruptException"));
        verify(outbox, never()).renewLease(eq("evt-2"), anyString(), eq(30));
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void staleFailureDoesNotEmitFailedMetric() {
        when(outbox.claimBatch(eq(10), anyString(), eq(30))).thenReturn(List.of(row(2)));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("late result"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failed);
        when(outbox.markFailed(eq("evt-1"), anyString(), anyString())).thenReturn(false);

        publisher.publishBatch();

        verify(metrics, never()).outboxFailed(anyString());
    }

    @Test
    void expiredRowInClaimedBatchIsSkippedBeforeKafkaSend() {
        when(outbox.claimBatch(eq(10), anyString(), eq(30))).thenReturn(List.of(row(0)));
        when(outbox.renewLease(eq("evt-1"), anyString(), eq(30))).thenReturn(false);

        publisher.publishBatch();

        verify(kafka, never()).send(anyString(), anyString(), anyString());
        verify(outbox, never()).markSent(anyString(), anyString());
    }

    @Test
    void validatesTotalSendBudgetIsStrictlyShorterThanLease() {
        assertThatCode(publisher::afterPropertiesSet).doesNotThrowAnyException();

        publisher.sendTimeoutSeconds = 25;
        assertThatThrownBy(publisher::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("producerMaxBlockMs + sendTimeoutSeconds");
    }

    @Test
    void validatesProducerDeliveryTimeoutFitsApplicationWait() {
        publisher.producerDeliveryTimeoutMs = 2_001;

        assertThatThrownBy(publisher::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("producerDeliveryTimeoutMs <= sendTimeoutSeconds");
    }
}
