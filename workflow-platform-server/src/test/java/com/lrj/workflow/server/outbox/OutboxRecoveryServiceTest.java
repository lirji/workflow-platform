package com.lrj.workflow.server.outbox;

import com.lrj.workflow.core.WorkflowConflictException;
import com.lrj.workflow.core.outbox.OutboxEventRepository;
import com.lrj.workflow.server.audit.WorkflowAudit;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRecoveryServiceTest {

    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final WorkflowMetrics metrics = mock(WorkflowMetrics.class);
    private final WorkflowAudit audit = mock(WorkflowAudit.class);
    private final OutboxRecoveryService service = new OutboxRecoveryService(outbox, metrics, audit);

    @Test
    void requeuesOnlyAfterExplicitReasonAndAudits() {
        when(outbox.requeueDeliveryUnknown("his", "evt-1", "target inbox confirmed absent")).thenReturn(true);

        service.requeueDeliveryUnknown("his", "evt-1", "target inbox confirmed absent");

        verify(metrics).adminOp("outbox-requeue-unknown");
        verify(audit).adminOp("outbox-requeue-unknown", "evt-1",
                "tenant=his target inbox confirmed absent");
    }

    @Test
    void blankReasonIsRejectedBeforeRepositoryMutation() {
        assertThatThrownBy(() -> service.requeueDeliveryUnknown("his", "evt-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须填写核账原因");
        verify(outbox, never()).requeueDeliveryUnknown(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void nonUnknownStateConflictsAndDoesNotEmitAudit() {
        when(outbox.requeueDeliveryUnknown("his", "evt-1", "checked")).thenReturn(false);

        assertThatThrownBy(() -> service.requeueDeliveryUnknown("his", "evt-1", "checked"))
                .isInstanceOf(WorkflowConflictException.class);
        verify(metrics, never()).adminOp(org.mockito.ArgumentMatchers.anyString());
        verify(audit, never()).adminOp(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
