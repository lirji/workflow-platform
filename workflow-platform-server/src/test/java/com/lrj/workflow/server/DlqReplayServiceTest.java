package com.lrj.workflow.server;

import com.lrj.workflow.core.dlq.DlqEventRepository;
import com.lrj.workflow.core.dlq.DlqRecord;
import com.lrj.workflow.server.audit.WorkflowAudit;
import com.lrj.workflow.server.dlq.DlqReplayService;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/** DlqReplayService 单元测试(mock 仓库 + KafkaTemplate)。 */
class DlqReplayServiceTest {

    private final DlqEventRepository repo = mock(DlqEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final TransactionTemplate tx = transactionTemplate();
    private final DlqReplayService svc = new DlqReplayService(repo, kafka,
            mock(WorkflowMetrics.class), mock(WorkflowAudit.class), tx);

    private static TransactionTemplate transactionTemplate() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return tx;
    }

    private static DlqRecord rec(long id, String status) {
        return rec(id, status, "workflow.command.start.v1");
    }

    private static DlqRecord rec(long id, String status, String topic) {
        return new DlqRecord(id, topic, "his|hisRxReview|90003",
                "{\"eventId\":\"e1\"}", "signed-value", "boom", status, 1L, null);
    }

    @Test
    void replay_new_republishesToOriginalTopicAndMarks() {
        when(repo.findNewForUpdate(7L)).thenReturn(Optional.of(rec(7L, "NEW")));
        when(kafka.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        boolean ok = svc.replay(7L);

        assertThat(ok).isTrue();
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("workflow.command.start.v1");
        assertThat(sent.getValue().key()).isEqualTo("his|hisRxReview|90003");
        assertThat(sent.getValue().value()).isEqualTo("{\"eventId\":\"e1\"}");
        assertThat(new String(sent.getValue().headers().lastHeader("workflow-signature-v1").value()))
                .isEqualTo("signed-value");
        verify(repo).markReplayed(7L);
    }

    @Test
    void replay_missing_returnsFalse_noSend() {
        when(repo.findNewForUpdate(9L)).thenReturn(Optional.empty());

        assertThat(svc.replay(9L)).isFalse();
        verify(kafka, never()).send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any());
        verify(repo, never()).markReplayed(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void replay_alreadyReplayed_returnsFalse() {
        when(repo.findNewForUpdate(3L)).thenReturn(Optional.empty());

        assertThat(svc.replay(3L)).isFalse();
        verify(repo, never()).markReplayed(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void replay_sendFailure_doesNotMarkReplayed() {
        when(repo.findNewForUpdate(8L)).thenReturn(Optional.of(rec(8L, "NEW")));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafka.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any())).thenReturn(failed);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.replay(8L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("保持 NEW");
        verify(repo, never()).markReplayed(8L);
    }

    @Test
    void replayRejectsUntrustedOriginalTopicWithoutSending() {
        when(repo.findNewForUpdate(10L)).thenReturn(Optional.of(rec(10L, "NEW", "attacker.topic")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.replay(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不在允许重放范围");
        verify(kafka, never()).send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any());
        verify(repo, never()).markReplayed(10L);
    }
}
