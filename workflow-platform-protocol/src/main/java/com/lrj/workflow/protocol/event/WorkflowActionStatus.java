package com.lrj.workflow.protocol.event;

/**
 * 业务系统对一次人工决定的落地结果(回给中台的 ACK 语义)。
 */
public enum WorkflowActionStatus {
    /** 业务已成功落地。 */
    APPLIED,
    /** 业务侧规则拒绝(非重试)。 */
    REJECTED_BY_BUSINESS,
    /** 可重试失败(消费方 outbox 重发)。 */
    FAILED_RETRYABLE,
    /** 终态失败(进 incident/人工处置,流程不自动通过)。 */
    FAILED_FINAL
}
