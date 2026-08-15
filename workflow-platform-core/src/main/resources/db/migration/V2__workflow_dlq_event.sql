-- 死信事件 DLQ:Kafka 监听重试超限后由 DeadLetterPublishingRecoverer 投到 workflow.dlq.v1,
-- 消费方(WorkflowDlqListener)落库供人工排查与重放,避免毒消息无限重投阻塞分区。
CREATE TABLE wf_dlq_event (
    id             BIGSERIAL PRIMARY KEY,
    original_topic VARCHAR(128) NOT NULL,          -- 原始 topic(来自 DLT header),重放时投回此 topic
    msg_key        VARCHAR(256),                    -- 原始消息 key(tenant|definition|businessKey)
    payload        TEXT         NOT NULL,           -- 原始消息体(JSON envelope)
    error_message  TEXT,                            -- 触发进 DLQ 的异常信息
    status         VARCHAR(32)  NOT NULL DEFAULT 'NEW', -- NEW / REPLAYED
    failed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    replayed_at    TIMESTAMPTZ
);
CREATE INDEX idx_wf_dlq_status ON wf_dlq_event (status, failed_at);
