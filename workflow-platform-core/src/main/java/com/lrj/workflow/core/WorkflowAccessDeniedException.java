package com.lrj.workflow.core;

/**
 * 工作流资源访问被拒绝。保持在 core 层，避免领域/应用服务依赖 Spring Security。
 */
public class WorkflowAccessDeniedException extends RuntimeException {

    public WorkflowAccessDeniedException(String message) {
        super(message);
    }
}
