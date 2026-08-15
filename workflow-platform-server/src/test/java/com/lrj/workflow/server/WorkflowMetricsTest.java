package com.lrj.workflow.server;

import com.lrj.workflow.server.metrics.WorkflowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** WorkflowMetrics 单元测试(SimpleMeterRegistry):计数器按名+tag 累加,空 tag → unknown。 */
class WorkflowMetricsTest {

    private final SimpleMeterRegistry reg = new SimpleMeterRegistry();
    private final WorkflowMetrics m = new WorkflowMetrics(reg);

    @Test
    void countersIncrementByNameAndTag() {
        m.reviewCompleted("his", "PASS");
        m.reviewCompleted("his", "PASS");
        m.reviewCompleted("his", "REJECT");
        assertThat(reg.counter("workflow.review.completed", "tenant", "his", "decision", "PASS").count()).isEqualTo(2.0);
        assertThat(reg.counter("workflow.review.completed", "tenant", "his", "decision", "REJECT").count()).isEqualTo(1.0);

        m.correlationOutcome("CORRELATED");
        assertThat(reg.counter("workflow.correlation.outcome", "outcome", "CORRELATED").count()).isEqualTo(1.0);

        m.dlqReplayed();
        m.dlqReplayed();
        assertThat(reg.counter("workflow.dlq.replayed").count()).isEqualTo(2.0);
    }

    @Test
    void nullTagBecomesUnknown() {
        m.adminOp(null);
        assertThat(reg.counter("workflow.admin.op", "op", "unknown").count()).isEqualTo(1.0);
    }
}
