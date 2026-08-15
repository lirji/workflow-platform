package com.lrj.workflow.core.link;

/**
 * wf_process_link 行:一次幂等发起 ↔ Flowable 实例的绑定。
 */
public record ProcessLink(
        long id,
        String tenantId,
        String processDefinitionKey,
        String businessKey,
        String idempotencyKey,
        String processInstanceId,
        ProcessPhase phase,
        String status,
        long version
) {
}
