package com.lrj.workflow.core.dlq;

/**
 * 死信事件视图(排查/重放用)。时间以 epoch 毫秒暴露,与其它 REST 视图一致。
 *
 * @param id                主键
 * @param originalTopic     原始 topic(重放投回此 topic)
 * @param msgKey            原始消息 key
 * @param payload           原始消息体(JSON envelope)
 * @param errorMessage      进 DLQ 的异常信息
 * @param status            NEW / REPLAYED
 * @param failedAtEpochMs   入 DLQ 时间
 * @param replayedAtEpochMs 重放时间,可空
 */
public record DlqRecord(
        long id,
        String originalTopic,
        String msgKey,
        String payload,
        String errorMessage,
        String status,
        Long failedAtEpochMs,
        Long replayedAtEpochMs
) {
}
