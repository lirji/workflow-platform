package com.lrj.workflow.protocol.event;

/**
 * Kafka Published Language 主题常量(FINAL_PLAN §8.1)。key 一律用 {@code tenant|definition|businessKey}。
 */
public final class WorkflowTopics {

    /** 消费方→中台:幂等发起。 */
    public static final String COMMAND_START = "workflow.command.start.v1";
    /** 中台→消费方:请求业务落实人工决定。 */
    public static final String ACTION_REQUESTED = "workflow.action.requested.v1";
    /** 消费方→中台:业务落地 ACK/NACK,推进 message。 */
    public static final String ACTION_APPLIED = "workflow.action.applied.v1";
    /** 中台→观察者:生命周期通知(不参与正确性)。 */
    public static final String LIFECYCLE = "workflow.lifecycle.v1";
    /** 双侧运维:毒消息/超限失败。 */
    public static final String DLQ = "workflow.dlq.v1";

    private WorkflowTopics() {
    }
}
