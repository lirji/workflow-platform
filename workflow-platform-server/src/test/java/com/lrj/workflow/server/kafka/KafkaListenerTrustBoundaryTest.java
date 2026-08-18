package com.lrj.workflow.server.kafka;

import com.lrj.workflow.core.correlation.MessageCorrelationService;
import com.lrj.workflow.core.dlq.DlqEventRepository;
import com.lrj.workflow.core.inbox.InboxEventRepository;
import com.lrj.workflow.core.process.ProcessApplicationService;
import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import com.lrj.workflow.protocol.event.StartProcessCommandV1;
import com.lrj.workflow.protocol.event.WorkflowActionAppliedV1;
import com.lrj.workflow.protocol.event.WorkflowActionStatus;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.annotation.KafkaListener;

class KafkaListenerTrustBoundaryTest {

    private final EnvelopeCodec codec = mock(EnvelopeCodec.class);
    private final InboxEventRepository inbox = mock(InboxEventRepository.class);
    private final WorkflowMetrics metrics = mock(WorkflowMetrics.class);
    private final LifecyclePublisher lifecycle = mock(LifecyclePublisher.class);
    private final KafkaEnvelopeTrustValidator trust = mock(KafkaEnvelopeTrustValidator.class);

    @Test
    void startRejectsUntrustedEnvelopeBeforeInboxClaim() {
        EventEnvelopeV1<StartProcessCommandV1> envelope = new EventEnvelopeV1<>(
                "evt-start", 1, WorkflowTopics.COMMAND_START, Instant.now(), "spoofed", "his", "corr", null,
                new StartProcessCommandV1("hisRxReview", "enc", "cycle", "doctor", Map.of()));
        when(codec.parse("json", StartProcessCommandV1.class, WorkflowTopics.COMMAND_START)).thenReturn(envelope);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(WorkflowTopics.COMMAND_START, 0, 0, "key", "json");
        doThrow(new IllegalArgumentException("untrusted")).when(trust).validate(envelope, "json", null);
        WorkflowStartListener listener = new WorkflowStartListener(codec, inbox,
                mock(ProcessApplicationService.class), metrics, lifecycle, trust);

        assertThatThrownBy(() -> listener.onStart(record)).hasMessageContaining("untrusted");
        verify(inbox, never()).tryClaim(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void appliedRejectsUntrustedEnvelopeBeforeInboxClaim() {
        EventEnvelopeV1<WorkflowActionAppliedV1> envelope = new EventEnvelopeV1<>(
                "evt-applied", 1, WorkflowTopics.ACTION_APPLIED, Instant.now(), "spoofed", "his", "corr", null,
                new WorkflowActionAppliedV1("pi", null, "hisRxReview", "enc", "action",
                        WorkflowActionStatus.APPLIED, 1L, null, null));
        when(codec.parse("json", WorkflowActionAppliedV1.class, WorkflowTopics.ACTION_APPLIED)).thenReturn(envelope);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(WorkflowTopics.ACTION_APPLIED, 0, 0, "key", "json");
        doThrow(new IllegalArgumentException("untrusted")).when(trust).validate(envelope, "json", null);
        WorkflowActionAppliedListener listener = new WorkflowActionAppliedListener(codec, inbox,
                mock(MessageCorrelationService.class), metrics, lifecycle, trust);

        assertThatThrownBy(() -> listener.onApplied(record)).hasMessageContaining("untrusted");
        verify(inbox, never()).tryClaim(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void dlqLandingPreservesOriginalHmacForAuthenticatedReplay() {
        DlqEventRepository dlq = mock(DlqEventRepository.class);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                WorkflowTopics.DLQ, 0, 0, "key", "json");
        String signature = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        record.headers().add(KafkaEnvelopeTrustValidator.SIGNATURE_HEADER,
                signature.getBytes(StandardCharsets.UTF_8));
        WorkflowDlqListener listener = new WorkflowDlqListener(dlq, metrics);

        listener.onDlq(record);

        verify(dlq).save(WorkflowTopics.DLQ, "key", "json", signature, null);
    }

    @Test
    void malformedDlqHeadersAndOversizedKeyCannotBreakLanding() {
        DlqEventRepository dlq = mock(DlqEventRepository.class);
        String oversizedKey = "k".repeat(1_000);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                WorkflowTopics.DLQ, 0, 0, oversizedKey, null);
        record.headers().add(new RecordHeader(KafkaEnvelopeTrustValidator.SIGNATURE_HEADER, (byte[]) null));
        record.headers().add(KafkaEnvelopeTrustValidator.SIGNATURE_HEADER,
                "x".repeat(1_000).getBytes(StandardCharsets.UTF_8));
        WorkflowDlqListener listener = new WorkflowDlqListener(dlq, metrics);

        listener.onDlq(record);

        verify(dlq).save(WorkflowTopics.DLQ, oversizedKey, "", null, null);
    }

    @Test
    void dlqUsesStoppingContainerInsteadOfRepublishingToItself() throws Exception {
        KafkaListener listener = WorkflowDlqListener.class
                .getMethod("onDlq", ConsumerRecord.class)
                .getAnnotation(KafkaListener.class);

        assertThat(listener.containerFactory()).isEqualTo("workflowDlqKafkaListenerContainerFactory");
    }
}
