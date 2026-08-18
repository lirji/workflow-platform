-- HMAC 开启后 DLQ 重放必须携带原始签名；只保存原签名，平台绝不替外部 source 重新签名。
-- 独立于 V4，保持已经生成过验收证据的迁移 checksum 不变。
ALTER TABLE wf_dlq_event
    ADD COLUMN signature VARCHAR(128);

-- key 与 DLT original-topic 都来自外部 record/header；TEXT 避免超长毒消息让 DLQ 自身无法落库。
ALTER TABLE wf_dlq_event
    ALTER COLUMN msg_key TYPE TEXT,
    ALTER COLUMN original_topic TYPE TEXT;
