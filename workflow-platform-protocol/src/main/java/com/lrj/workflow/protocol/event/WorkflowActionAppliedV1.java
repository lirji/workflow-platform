package com.lrj.workflow.protocol.event;

/**
 * 消费方 → 中台:业务落地 ACK/NACK,推进流程的 message catch(topic {@link WorkflowTopics#ACTION_APPLIED})。
 *
 * <p>中台按关联顺序推进:校验 instance → tenant/definition/businessKey → message subscription → pendingActionId。
 * 0 个订阅进入 WAITING_CORRELATION 重试(回执早到);多个订阅是 P0 一致性故障。
 *
 * @param processInstanceId 流程实例 id
 * @param taskId            关联任务 id,业务落地时任务可能已结束故可空
 * @param processDefinitionKey 流程定义 key
 * @param businessKey       业务键
 * @param actionId          对应 {@link WorkflowActionRequestedV1#actionId}
 * @param status            落地结果
 * @param businessVersion   业务实体落地后版本,可空
 * @param errorCode         失败码,可空
 * @param errorMessage      失败信息,可空
 */
public record WorkflowActionAppliedV1(
        String processInstanceId,
        String taskId,
        String processDefinitionKey,
        String businessKey,
        String actionId,
        WorkflowActionStatus status,
        Long businessVersion,
        String errorCode,
        String errorMessage
) {
}
