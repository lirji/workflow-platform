package com.lrj.workflow.core.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.workflow.core.outbox.OutboxEventRepository;
import com.lrj.workflow.protocol.event.Actor;
import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import com.lrj.workflow.protocol.event.WorkflowActionRequestedV1;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * SKU 上线审批专用 delegate。它只在 Flowable 事务内写 action.requested outbox，不直接调用权益中台。
 */
@Component("skuGoLiveActionOutboxDelegate")
public final class SkuGoLiveActionOutboxDelegate implements JavaDelegate {
    private final OutboxEventRepository outbox;
    private final ObjectMapper mapper;

    public SkuGoLiveActionOutboxDelegate(OutboxEventRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    /** 把 PASS/REJECT 映射成 SKU 专用动作；序列化失败必须抛出以回滚人工任务办理。 */
    @Override
    public void execute(DelegateExecution execution) {
        String decision = string(execution.getVariable("decision"));
        String action = switch (decision) {
            case "PASS" -> "SKU_GO_LIVE_APPROVE";
            case "REJECT" -> "SKU_GO_LIVE_REJECT";
            default -> throw new IllegalStateException("unsupported SKU go-live decision: " + decision);
        };
        String actionId = string(execution.getVariable("actionId"));
        String taskId = string(execution.getVariable("completedTaskId"));
        String definitionKey = string(execution.getVariable("processDefinitionKey"));
        Actor actor = new Actor(string(execution.getVariable("actorSub")),
                string(execution.getVariable("actorUsername")),
                string(execution.getVariable("actorDisplayName")));
        String opinion = string(execution.getVariable("opinion"));
        Map<String, Object> parameters = opinion == null ? Map.of() : Map.of("opinion", opinion);
        var request = new WorkflowActionRequestedV1(execution.getProcessInstanceId(), taskId,
                "skuGoLiveReview", definitionKey, execution.getProcessInstanceBusinessKey(),
                actionId, action, actor, parameters);
        String eventId = UUID.randomUUID().toString();
        var envelope = new EventEnvelopeV1<>(eventId, 1, WorkflowTopics.ACTION_REQUESTED, Instant.now(),
                "workflow-server", execution.getTenantId(), actionId, null, request);
        try {
            outbox.enqueue(eventId, WorkflowTopics.ACTION_REQUESTED,
                    execution.getTenantId() + '|' + definitionKey + '|'
                            + execution.getProcessInstanceBusinessKey(),
                    WorkflowTopics.ACTION_REQUESTED, mapper.writeValueAsString(envelope));
        } catch (Exception failure) {
            throw new IllegalStateException("SKU go-live action cannot be written to outbox", failure);
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
