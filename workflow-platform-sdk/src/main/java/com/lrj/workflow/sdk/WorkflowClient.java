package com.lrj.workflow.sdk;

import com.lrj.workflow.protocol.api.CompleteReviewRequest;
import com.lrj.workflow.protocol.api.TaskView;

import java.util.List;

/**
 * 消费方接入中台的最小 SDK 门面。发起流程走消费方自己的 outbox→Kafka(保证与业务同事务),
 * 故 SDK 只提供需要即时反馈的查询/办理。enabled 默认 false 时注入 {@link NoopWorkflowClient}。
 */
public interface WorkflowClient {

    /** 按租户 +(可选)流程定义 key + businessKey 查活动待办。 */
    List<TaskView> findTasks(String tenant, String definitionKey, String businessKey);

    /** 办理审方(通过/驳回),返回 server 生成的 actionId;不可用时返回 null(Noop)。 */
    String completeReview(String tenant, String taskId, CompleteReviewRequest request);

    /** 认领任务(设办理人为 userId)。 */
    void claimTask(String tenant, String taskId, String userId);

    /** 转办任务(改办理人为 assignee)。 */
    void reassignTask(String tenant, String taskId, String assignee);
}
