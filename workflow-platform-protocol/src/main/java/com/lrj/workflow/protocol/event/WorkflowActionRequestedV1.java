package com.lrj.workflow.protocol.event;

import java.util.Map;

/**
 * 中台 → 消费方:请求业务系统落实一次人工决定(topic {@link WorkflowTopics#ACTION_REQUESTED})。
 *
 * <p>消费方按 {@code actionId} 做业务级幂等(在 EventEnvelope.eventId 去重之上再收敛一层),
 * 保证同一动作只产生一次业务副作用。
 *
 * @param processInstanceId 流程实例 id
 * @param taskId            触发该动作的任务 id
 * @param taskDefinitionKey 任务定义 key(如 {@code pharmacistReview})
 * @param processDefinitionKey 流程定义 key
 * @param businessKey       业务键(审方=encounterId)
 * @param actionId          动作幂等 id(业务侧去重键)
 * @param action            动作类型(如 {@code RX_REVIEW_PASS} / {@code RX_REVIEW_REJECT})
 * @param actor             办理人快照
 * @param parameters        动作参数(如审方意见 opinion)
 */
public record WorkflowActionRequestedV1(
        String processInstanceId,
        String taskId,
        String taskDefinitionKey,
        String processDefinitionKey,
        String businessKey,
        String actionId,
        String action,
        Actor actor,
        Map<String, Object> parameters
) {
}
