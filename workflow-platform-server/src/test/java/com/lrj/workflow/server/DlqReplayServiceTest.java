package com.lrj.workflow.server;

import com.lrj.workflow.core.dlq.DlqEventRepository;
import com.lrj.workflow.core.dlq.DlqRecord;
import com.lrj.workflow.server.dlq.DlqReplayService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DlqReplayService 单元测试(mock 仓库 + KafkaTemplate)。 */
class DlqReplayServiceTest {

    private final DlqEventRepository repo = mock(DlqEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final DlqReplayService svc = new DlqReplayService(repo, kafka);

    private static DlqRecord rec(long id, String status) {
        return new DlqRecord(id, "workflow.command.start.v1", "his|hisRxReview|90003",
                "{\"eventId\":\"e1\"}", "boom", status, 1L, null);
    }

    @Test
    void replay_new_republishesToOriginalTopicAndMarks() {
        when(repo.find(7L)).thenReturn(Optional.of(rec(7L, "NEW")));

        boolean ok = svc.replay(7L);

        assertThat(ok).isTrue();
        verify(kafka).send("workflow.command.start.v1", "his|hisRxReview|90003", "{\"eventId\":\"e1\"}");
        verify(repo).markReplayed(7L);
    }

    @Test
    void replay_missing_returnsFalse_noSend() {
        when(repo.find(9L)).thenReturn(Optional.empty());

        assertThat(svc.replay(9L)).isFalse();
        verify(kafka, never()).send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(repo, never()).markReplayed(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void replay_alreadyReplayed_returnsFalse() {
        when(repo.find(3L)).thenReturn(Optional.of(rec(3L, "REPLAYED")));

        assertThat(svc.replay(3L)).isFalse();
        verify(repo, never()).markReplayed(org.mockito.ArgumentMatchers.anyLong());
    }
}
