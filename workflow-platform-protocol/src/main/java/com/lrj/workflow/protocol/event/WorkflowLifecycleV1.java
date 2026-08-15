package com.lrj.workflow.protocol.event;

/**
 * 中台 → 观察者:流程生命周期通知(topic {@link WorkflowTopics#LIFECYCLE})。
 * 不参与正确性,best-effort 投递,仅供观察/看板/审计消费。租户在 {@link EventEnvelopeV1#tenantId}。
 *
 * @param processInstanceId    流程实例 id
 * @param processDefinitionKey 流程定义 key
 * @param businessKey          业务键
 * @param lifecycle            生命周期事件:STARTED / COMPLETED / INCIDENT / CANCELLED
 */
public record WorkflowLifecycleV1(
        String processInstanceId,
        String processDefinitionKey,
        String businessKey,
        String lifecycle
) {
}
