-- 第二批正确性加固：保留 outbox 最后一次发送错误，供 FAILED 告警后的运维诊断。
ALTER TABLE wf_outbox_event
    ADD COLUMN last_error TEXT;

-- 修正历史漂移，并让后续迁移后的读模型立即与 phase 语义一致。
UPDATE wf_process_link
SET status = CASE
                 WHEN phase IN ('COMPLETED', 'CANCELLED') THEN 'ENDED'
                 WHEN phase = 'INCIDENT' THEN 'ERROR'
                 ELSE 'ACTIVE'
             END,
    updated_at = now()
WHERE status IS DISTINCT FROM CASE
                                  WHEN phase IN ('COMPLETED', 'CANCELLED') THEN 'ENDED'
                                  WHEN phase = 'INCIDENT' THEN 'ERROR'
                                  ELSE 'ACTIVE'
                              END;
