package com.lrj.workflow.server.outbox;

import com.lrj.workflow.core.WorkflowConflictException;
import com.lrj.workflow.core.outbox.OutboxEventRepository;
import com.lrj.workflow.server.audit.WorkflowAudit;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 仅供 ADMIN 在外部核账后恢复 DELIVERY_UNKNOWN；publisher 永不自动重发未知结果。 */
@Service
public class OutboxRecoveryService {

    private final OutboxEventRepository outbox;
    private final WorkflowMetrics metrics;
    private final WorkflowAudit audit;

    public OutboxRecoveryService(OutboxEventRepository outbox, WorkflowMetrics metrics, WorkflowAudit audit) {
        this.outbox = outbox;
        this.metrics = metrics;
        this.audit = audit;
    }

    @Transactional
    public void requeueDeliveryUnknown(String tenant, String eventId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("重新投递 DELIVERY_UNKNOWN 必须填写核账原因");
        }
        String boundedReason = reason.length() <= 1_000 ? reason : reason.substring(0, 1_000);
        if (!outbox.requeueDeliveryUnknown(tenant, eventId, boundedReason)) {
            throw new WorkflowConflictException("outbox 不存在或不是 DELIVERY_UNKNOWN: " + eventId);
        }
        metrics.adminOp("outbox-requeue-unknown");
        audit.adminOp("outbox-requeue-unknown", eventId, "tenant=" + tenant + " " + boundedReason);
    }
}
