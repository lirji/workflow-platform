package com.lrj.workflow.protocol.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Published Language golden 测试:round-trip 保真 + 关键字段名钉死(消费方跨语言依赖这些字段名)。
 */
class ProtocolGoldenTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void startCommandRoundTrips() throws Exception {
        var cmd = new StartProcessCommandV1("hisRxReview", "enc-1001", "cycle-1", "his-outpatient",
                Map.of("encounterId", 1001, "reviewRound", 1));
        var env = new EventEnvelopeV1<>("evt-1", 1, "workflow.command.start.v1",
                Instant.parse("2026-08-15T02:00:00Z"), "his-outpatient", "his", "corr-1", null, cmd);

        String json = mapper.writeValueAsString(env);
        assertThat(json).contains("\"eventId\":\"evt-1\"")
                .contains("\"contractVersion\":1")
                .contains("\"tenantId\":\"his\"")
                .contains("\"businessKey\":\"enc-1001\"")
                .contains("\"idempotencyKey\":\"cycle-1\"");

        @SuppressWarnings("unchecked")
        EventEnvelopeV1<StartProcessCommandV1> back = mapper.readValue(json,
                mapper.getTypeFactory().constructParametricType(EventEnvelopeV1.class, StartProcessCommandV1.class));
        assertThat(back.payload().processDefinitionKey()).isEqualTo("hisRxReview");
        assertThat(back.payload().businessKey()).isEqualTo("enc-1001");
        assertThat(back.occurredAt()).isEqualTo(Instant.parse("2026-08-15T02:00:00Z"));
    }

    @Test
    void actionRequestedRoundTrips() throws Exception {
        var req = new WorkflowActionRequestedV1("pi-1", "task-1", "pharmacistReview", "hisRxReview",
                "enc-1001", "act-1", "RX_REVIEW_PASS",
                new Actor("sub-123", "pharma01", "药师张三"), Map.of("opinion", "同意"));
        String json = mapper.writeValueAsString(req);
        assertThat(json).contains("\"actionId\":\"act-1\"").contains("\"subjectId\":\"sub-123\"");

        var back = mapper.readValue(json, WorkflowActionRequestedV1.class);
        assertThat(back.action()).isEqualTo("RX_REVIEW_PASS");
        assertThat(back.actor().subjectId()).isEqualTo("sub-123");
        assertThat(back.actor().username()).isEqualTo("pharma01");
    }

    @Test
    void actionAppliedRoundTripsWithStatusEnum() throws Exception {
        var applied = new WorkflowActionAppliedV1("pi-1", null, "hisRxReview", "enc-1001", "act-1",
                WorkflowActionStatus.APPLIED, 7L, null, null);
        String json = mapper.writeValueAsString(applied);
        assertThat(json).contains("\"status\":\"APPLIED\"").contains("\"businessVersion\":7");

        var back = mapper.readValue(json, WorkflowActionAppliedV1.class);
        assertThat(back.status()).isEqualTo(WorkflowActionStatus.APPLIED);
        assertThat(back.taskId()).isNull();
        assertThat(back.businessVersion()).isEqualTo(7L);
    }
}
