package com.lrj.workflow.protocol.event;

import java.util.Map;

/**
 * 消费方 → 中台:幂等发起一个流程实例(topic {@link WorkflowTopics#COMMAND_START})。
 *
 * <p>幂等语义:中台按 {@code (tenantId, processDefinitionKey, businessKey, idempotencyKey)} 四元组去重,
 * 同 idempotencyKey 重复发起返回原实例。审方场景 businessKey=encounterId、idempotencyKey=review cycle id。
 *
 * <p>{@code variables} 只允许 JSON scalar / 受控 list / map,拒绝 Java 序列化对象;且遵守流程变量白名单
 * (IDs、控制枚举、金额快照、actor 快照、actionId),不放完整患者/病历/发票对象。
 *
 * @param processDefinitionKey 流程定义 key(如 {@code hisRxReview})
 * @param businessKey          业务键(审方=encounterId)
 * @param idempotencyKey       幂等键(审方=review cycle id)
 * @param initiator            发起人主体(Casdoor sub 或服务标识)
 * @param variables            初始流程变量(白名单)
 */
public record StartProcessCommandV1(
        String processDefinitionKey,
        String businessKey,
        String idempotencyKey,
        String initiator,
        Map<String, Object> variables
) {
}
