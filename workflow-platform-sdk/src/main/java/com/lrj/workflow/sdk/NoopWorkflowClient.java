package com.lrj.workflow.sdk;

import com.lrj.workflow.protocol.api.CompleteReviewRequest;
import com.lrj.workflow.protocol.api.TaskView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * enabled=false 时的空实现:查询返回空,办理返回 null(不伪造 actionId)。引入 SDK 即安全、可回退。
 */
public class NoopWorkflowClient implements WorkflowClient {

    private static final Logger log = LoggerFactory.getLogger(NoopWorkflowClient.class);

    @Override
    public List<TaskView> findTasks(String tenant, String definitionKey, String businessKey) {
        return List.of();
    }

    @Override
    public String completeReview(String tenant, String taskId, CompleteReviewRequest request) {
        log.debug("WorkflowClient 未启用(Noop),completeReview 跳过 taskId={}", taskId);
        return null;
    }
}
