package com.lrj.workflow.sdk;

import com.lrj.workflow.protocol.api.CompleteReviewRequest;
import com.lrj.workflow.protocol.api.CompleteTaskRequest;
import com.lrj.workflow.protocol.api.ProcessInstanceView;
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

    /**
     * 办理通用人工任务(OA 请假 / 报销 / 用印等),返回 server 生成的 actionId。
     * 与 {@link #completeReview} 并存:审方保持原语义。
     *
     * <p>声明为 default 而非抽象方法 —— 既有的第三方 {@code WorkflowClient} 实现(如测试替身)
     * 不会因为 SDK 升级而编译不过;未覆写就调用会明确报错,不会静默无操作。
     */
    default String completeTask(String tenant, String taskId, CompleteTaskRequest request) {
        throw new UnsupportedOperationException("当前 WorkflowClient 实现未支持 completeTask");
    }

    /**
     * 按 businessKey 查流程实例(含是否仍在运行)。消费方用它把自己的单据与中台实例对上,
     * 以及发现"流程已结束"这个中台不会主动推的事实。
     */
    default List<ProcessInstanceView> findProcesses(String tenant, String definitionKey, String businessKey) {
        return List.of();
    }

    /** 认领任务(设办理人为 userId)。 */
    void claimTask(String tenant, String taskId, String userId);

    /** 转办任务(改办理人为 assignee)。 */
    void reassignTask(String tenant, String taskId, String assignee);
}
