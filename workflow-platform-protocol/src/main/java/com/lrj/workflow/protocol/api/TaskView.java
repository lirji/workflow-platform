package com.lrj.workflow.protocol.api;

import java.util.List;

/**
 * 待办任务视图(REST 返回 / SDK 消费)。不暴露 Flowable 内部类型。
 *
 * @param taskId            任务 id
 * @param taskDefinitionKey 任务定义 key(如 pharmacistReview)
 * @param name              任务名
 * @param processInstanceId 流程实例 id
 * @param processDefinitionKey 流程定义 key
 * @param businessKey       业务键
 * @param tenantId          租户
 * @param assignee          办理人(可空)
 * @param candidateGroups   候选组
 * @param createTimeEpochMs 创建时间(epoch 毫秒)
 */
public record TaskView(
        String taskId,
        String taskDefinitionKey,
        String name,
        String processInstanceId,
        String processDefinitionKey,
        String businessKey,
        String tenantId,
        String assignee,
        List<String> candidateGroups,
        Long createTimeEpochMs
) {
}
