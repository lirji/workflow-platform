package com.lrj.workflow.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lrj.workflow.protocol.api.CompleteReviewRequest;
import com.lrj.workflow.protocol.api.ProcessInstanceView;
import com.lrj.workflow.protocol.api.TaskSearchResult;
import com.lrj.workflow.protocol.api.TaskView;
import com.lrj.workflow.protocol.api.TimelineEntry;
import com.lrj.workflow.protocol.event.Actor;
import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import com.lrj.workflow.protocol.event.StartProcessCommandV1;
import com.lrj.workflow.protocol.event.WorkflowActionAppliedV1;
import com.lrj.workflow.protocol.event.WorkflowActionRequestedV1;
import com.lrj.workflow.protocol.event.WorkflowActionStatus;
import com.lrj.workflow.protocol.event.WorkflowLifecycleV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约治理:钉死每个对外 Published Language record 的**顶层字段集**。
 * 跨仓库消费方(his 等)依赖这些字段名——任何增/删/改名都会让本测试失败,
 * 从而强制走契约版本化(新版本 topic / *V2)而非静默破坏。CI(mvn test)即门禁。
 */
class ContractGoldenTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Set<String> topLevelKeys(Object o) throws Exception {
        JsonNode n = mapper.readTree(mapper.writeValueAsString(o));
        Set<String> keys = new TreeSet<>();
        n.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Test
    void eventContracts() throws Exception {
        assertThat(topLevelKeys(new Actor("s", "u", "d")))
                .containsExactlyInAnyOrder("subjectId", "username", "displayName");

        assertThat(topLevelKeys(new EventEnvelopeV1<>("e", 1, "t", Instant.EPOCH, "src", "his", "corr", null, "payload")))
                .containsExactlyInAnyOrder("eventId", "contractVersion", "eventType", "occurredAt", "source",
                        "tenantId", "correlationId", "causationId", "payload");

        assertThat(topLevelKeys(new StartProcessCommandV1("d", "b", "i", "init", Map.of())))
                .containsExactlyInAnyOrder("processDefinitionKey", "businessKey", "idempotencyKey", "initiator", "variables");

        assertThat(topLevelKeys(new WorkflowActionRequestedV1("pi", "t", "tk", "d", "b", "a", "ACT",
                new Actor("s", "u", "d"), Map.of())))
                .containsExactlyInAnyOrder("processInstanceId", "taskId", "taskDefinitionKey", "processDefinitionKey",
                        "businessKey", "actionId", "action", "actor", "parameters");

        assertThat(topLevelKeys(new WorkflowActionAppliedV1("pi", "t", "d", "b", "a",
                WorkflowActionStatus.APPLIED, 1L, null, null)))
                .containsExactlyInAnyOrder("processInstanceId", "taskId", "processDefinitionKey", "businessKey",
                        "actionId", "status", "businessVersion", "errorCode", "errorMessage");

        assertThat(topLevelKeys(new WorkflowLifecycleV1("pi", "d", "b", "STARTED")))
                .containsExactlyInAnyOrder("processInstanceId", "processDefinitionKey", "businessKey", "lifecycle");
    }

    @Test
    void apiContracts() throws Exception {
        assertThat(topLevelKeys(new TaskView("t", "tk", "n", "pi", "d", "b", "his", null, List.of(), 1L)))
                .containsExactlyInAnyOrder("taskId", "taskDefinitionKey", "name", "processInstanceId",
                        "processDefinitionKey", "businessKey", "tenantId", "assignee", "candidateGroups", "createTimeEpochMs");

        assertThat(topLevelKeys(new TaskSearchResult(List.of(), 0L, 0, 20)))
                .containsExactlyInAnyOrder("items", "total", "page", "size");

        assertThat(topLevelKeys(new CompleteReviewRequest("PASS", "o", "s", "u", "d")))
                .containsExactlyInAnyOrder("decision", "opinion", "actorSub", "actorUsername", "actorDisplayName");

        assertThat(topLevelKeys(new ProcessInstanceView("pi", "his", "d", "b", "i", "COMPLETED", "ENDED", false, false)))
                .containsExactlyInAnyOrder("processInstanceId", "tenantId", "processDefinitionKey", "businessKey",
                        "idempotencyKey", "phase", "status", "running", "suspended");

        assertThat(topLevelKeys(new TimelineEntry("a", "an", "at", null, 1L, 2L)))
                .containsExactlyInAnyOrder("activityId", "activityName", "activityType", "assignee", "startEpochMs", "endEpochMs");
    }

    @Test
    void contractVersionPinned() {
        assertThat(ProtocolInfo.CONTRACT_VERSION).isEqualTo(1);
    }
}
