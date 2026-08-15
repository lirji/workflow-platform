package com.lrj.workflow.server.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 业务指标(Micrometer → /actuator/prometheus)。计数器名点分,Prometheus 侧转下划线 + _total 后缀
 * (如 workflow_review_completed_total)。埋点在 server 层 listener/controller/service,不侵入 core。
 */
@Component
public class WorkflowMetrics {

    private final MeterRegistry reg;

    public WorkflowMetrics(MeterRegistry reg) {
        this.reg = reg;
    }

    public void processStarted(String tenant, String definitionKey) {
        reg.counter("workflow.process.started", "tenant", nz(tenant), "definition", nz(definitionKey)).increment();
    }

    public void reviewCompleted(String tenant, String decision) {
        reg.counter("workflow.review.completed", "tenant", nz(tenant), "decision", nz(decision)).increment();
    }

    /** 业务落地回执落地(关联成功)。status = APPLIED / REJECTED_BY_BUSINESS / FAILED_*。 */
    public void actionApplied(String status) {
        reg.counter("workflow.action.applied", "status", nz(status)).increment();
    }

    /** 消息关联结果:CORRELATED / WAITING_SUBSCRIPTION / INSTANCE_GONE / ACTION_MISMATCH。 */
    public void correlationOutcome(String outcome) {
        reg.counter("workflow.correlation.outcome", "outcome", nz(outcome)).increment();
    }

    public void dlqLanded(String topic) {
        reg.counter("workflow.dlq.landed", "topic", nz(topic)).increment();
    }

    public void dlqReplayed() {
        reg.counter("workflow.dlq.replayed").increment();
    }

    public void deadLetterRetried() {
        reg.counter("workflow.deadletter.retried").increment();
    }

    public void adminOp(String op) {
        reg.counter("workflow.admin.op", "op", nz(op)).increment();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
