-- 多副本 correlation retry 领取租约。任务处理进程崩溃后，租约到期可由其它副本接管。
ALTER TABLE wf_inbox_event ADD COLUMN lease_owner VARCHAR(64);
ALTER TABLE wf_inbox_event ADD COLUMN lease_until TIMESTAMPTZ;

CREATE INDEX idx_wf_inbox_retry_lease
    ON wf_inbox_event (status, next_retry_at, lease_until);
