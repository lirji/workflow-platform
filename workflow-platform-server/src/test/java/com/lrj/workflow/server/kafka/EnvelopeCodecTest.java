package com.lrj.workflow.server.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import com.lrj.workflow.protocol.event.StartProcessCommandV1;
import com.lrj.workflow.protocol.event.WorkflowActionAppliedV1;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeCodecTest {

    private final EnvelopeCodec codec = new EnvelopeCodec(new ObjectMapper().findAndRegisterModules());

    private EventEnvelopeV1<StartProcessCommandV1> validStart() {
        return new EventEnvelopeV1<>("evt-1", 1, WorkflowTopics.COMMAND_START, Instant.now(),
                "his-outpatient", "his", "corr-1", null,
                new StartProcessCommandV1("hisRxReview", "enc-1", "cycle-1", "doctor-1", Map.of()));
    }

    @Test
    void parsesAndValidatesExpectedV1StartEnvelope() {
        String json = codec.toJson(validStart());

        var parsed = codec.parse(json, StartProcessCommandV1.class, WorkflowTopics.COMMAND_START);

        assertThat(parsed.tenantId()).isEqualTo("his");
        assertThat(parsed.payload().businessKey()).isEqualTo("enc-1");
    }

    @Test
    void rejectsWrongContractVersionAndEventType() {
        var wrongVersion = new EventEnvelopeV1<>("evt-1", 2, WorkflowTopics.COMMAND_START, Instant.now(),
                "his-outpatient", "his", "corr-1", null, validStart().payload());
        assertThatThrownBy(() -> codec.parse(codec.toJson(wrongVersion), StartProcessCommandV1.class,
                WorkflowTopics.COMMAND_START)).hasMessageContaining("contractVersion 不支持");

        assertThatThrownBy(() -> codec.parse(codec.toJson(validStart()), StartProcessCommandV1.class,
                WorkflowTopics.ACTION_APPLIED)).hasMessageContaining("eventType 不匹配");
    }

    @Test
    void rejectsMissingEnvelopeAndPayloadRequiredFields() {
        var noSource = new EventEnvelopeV1<>("evt-1", 1, WorkflowTopics.COMMAND_START, Instant.now(),
                " ", "his", "corr-1", null, validStart().payload());
        assertThatThrownBy(() -> codec.parse(codec.toJson(noSource), StartProcessCommandV1.class,
                WorkflowTopics.COMMAND_START)).hasMessageContaining("source 不能为空");

        var badPayload = new EventEnvelopeV1<>("evt-1", 1, WorkflowTopics.COMMAND_START, Instant.now(),
                "his-outpatient", "his", "corr-1", null,
                new StartProcessCommandV1("hisRxReview", "", "cycle-1", "doctor-1", null));
        assertThatThrownBy(() -> codec.parse(codec.toJson(badPayload), StartProcessCommandV1.class,
                WorkflowTopics.COMMAND_START)).hasMessageContaining("payload.businessKey 不能为空");
    }

    @Test
    void rejectsAppliedPayloadWithoutRequiredStatus() {
        var envelope = new EventEnvelopeV1<>("evt-2", 1, WorkflowTopics.ACTION_APPLIED, Instant.now(),
                "his-outpatient", "his", "corr-2", null,
                new WorkflowActionAppliedV1("pi-1", null, "hisRxReview", "enc-1", "action-1",
                        null, 1L, null, null));

        assertThatThrownBy(() -> codec.parse(codec.toJson(envelope), WorkflowActionAppliedV1.class,
                WorkflowTopics.ACTION_APPLIED)).hasMessageContaining("payload.status 不能为空");
    }
}
