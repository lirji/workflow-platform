package com.lrj.workflow.core;

/**
 * 业务/并发冲突(如同 businessKey 已有活动人工待办)。上层映射为 HTTP 409。
 */
public class WorkflowConflictException extends RuntimeException {
    public WorkflowConflictException(String message) {
        super(message);
    }
}
