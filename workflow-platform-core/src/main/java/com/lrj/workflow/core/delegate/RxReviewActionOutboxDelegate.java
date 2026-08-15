package com.lrj.workflow.core.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.workflow.core.outbox.OutboxEventRepository;
import com.lrj.workflow.protocol.event.Actor;
import com.lrj.workflow.protocol.event.EventEnvelopeV1;
import com.lrj.workflow.protocol.event.WorkflowActionRequestedV1;
import com.lrj.workflow.protocol.event.WorkflowTopics;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 审方结论服务任务(BPMN {@code ${rxReviewActionOutboxDelegate}})。只做一件事:把"请求业务落实人工决定"
 * 写进 wf_outbox_event。因运行在 Flowable command 的 Spring 事务里,它与 taskService.complete 的推进原子提交,
 * 消除"任务已完成但 action 事件未发"的窗口。实际 Kafka 发布交给 OutboxPublisher。
 */
@Component("rxReviewActionOutboxDelegate")
public class RxReviewActionOutboxDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(RxReviewActionOutboxDelegate.class);

    private final OutboxEventRepository outbox;
    private final ObjectMapper mapper;

    public RxReviewActionOutboxDelegate(OutboxEventRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String tenant = execution.getTenantId();
        String bizKey = execution.getProcessInstanceBusinessKey();
        String pdKey = str(execution.getVariable("processDefinitionKey"));
        String decision = str(execution.getVariable("decision"));           // PASS / REJECT
        String actionId = str(execution.getVariable("actionId"));
        String opinion = str(execution.getVariable("opinion"));
        String taskId = str(execution.getVariable("completedTaskId"));

        Actor actor = new Actor(
                str(execution.getVariable("actorSub")),
                str(execution.getVariable("actorUsername")),
                str(execution.getVariable("actorDisplayName")));

        Map<String, Object> params = new HashMap<>();
        if (opinion != null) {
            params.put("opinion", opinion);
        }

        var req = new WorkflowActionRequestedV1(
                execution.getProcessInstanceId(), taskId, "pharmacistReview", pdKey, bizKey,
                actionId, "RX_REVIEW_" + decision, actor, params);
        var env = new EventEnvelopeV1<>(
                UUID.randomUUID().toString(), 1, WorkflowTopics.ACTION_REQUESTED, Instant.now(),
                "workflow-server", tenant, actionId, null, req);

        try {
            String json = mapper.writeValueAsString(env);
            outbox.enqueue(env.eventId(), WorkflowTopics.ACTION_REQUESTED,
                    tenant + "|" + pdKey + "|" + bizKey, WorkflowTopics.ACTION_REQUESTED, json);
            log.info("审方动作入 outbox actionId={} decision={} biz={}", actionId, decision, bizKey);
        } catch (Exception e) {
            // 抛出使 Flowable command 事务回滚(连带 task complete),保证不产生"半推进"
            throw new IllegalStateException("序列化审方动作事件失败 actionId=" + actionId, e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
