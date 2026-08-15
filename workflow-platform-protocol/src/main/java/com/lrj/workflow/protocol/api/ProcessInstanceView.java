package com.lrj.workflow.protocol.api;

/**
 * 流程实例视图(只读)。来源 wf_process_link;phase 表达中台侧最终一致阶段(WAITING_USER/WAITING_BUSINESS/COMPLETED/INCIDENT/CANCELLED)。
 */
public record ProcessInstanceView(
        String processInstanceId,
        String tenantId,
        String processDefinitionKey,
        String businessKey,
        String idempotencyKey,
        String phase,
        String status,
        boolean running
) {
}
