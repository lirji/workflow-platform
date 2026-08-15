package com.lrj.workflow.server.admin;

/** 流程定义视图(运维/部署管理用)。 */
public record ProcessDefinitionView(
        String id,
        String key,
        String name,
        int version,
        String tenantId,
        boolean suspended,
        String deploymentId
) {
}
