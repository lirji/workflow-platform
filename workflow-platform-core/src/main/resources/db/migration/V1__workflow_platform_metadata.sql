-- 流程/审批中台自有元数据表(与 Flowable 自建的 ACT_* 分离,由 Flyway 管理)。
-- FINAL_PLAN §11.1。所有表面向多租户,业务数据仍留在业务系统,中台只存流程状态 + businessKey 关联。

-- 流程实例链接:一次幂等发起对应一条;绑定业务键与 Flowable 实例。
CREATE TABLE wf_process_link (
    id                     BIGSERIAL PRIMARY KEY,
    tenant_id              VARCHAR(64)  NOT NULL,
    process_definition_key VARCHAR(128) NOT NULL,
    business_key           VARCHAR(128) NOT NULL,
    idempotency_key        VARCHAR(128) NOT NULL,
    process_instance_id    VARCHAR(64)  NOT NULL,
    phase                  VARCHAR(32)  NOT NULL,          -- WAITING_USER / WAITING_BUSINESS / COMPLETED / CANCELLED / INCIDENT
    status                 VARCHAR(32)  NOT NULL,          -- ACTIVE / ENDED / ERROR
    version                BIGINT       NOT NULL DEFAULT 0, -- 乐观锁
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 幂等发起:四元组唯一。同 idempotencyKey 重复发起返回原实例。
ALTER TABLE wf_process_link
    ADD CONSTRAINT uk_wf_link_idem UNIQUE (tenant_id, process_definition_key, business_key, idempotency_key);
-- 同 businessKey 最多一个"等人工"实例;WAITING_BUSINESS(已决定等业务 ACK)不受此约束,
-- 从而驳回落地后医生重提可建新 cycle 而不被旧实例吞掉(FINAL_PLAN §6.2)。
CREATE UNIQUE INDEX uk_wf_link_waiting_user
    ON wf_process_link (tenant_id, process_definition_key, business_key)
    WHERE phase = 'WAITING_USER';
CREATE INDEX idx_wf_link_instance ON wf_process_link (process_instance_id);

-- 入站事件 inbox:按 eventId 去重(至少一次投递收敛为幂等)。
CREATE TABLE wf_inbox_event (
    event_id      VARCHAR(64) PRIMARY KEY,
    topic         VARCHAR(128) NOT NULL,
    partition_no  INT,
    offset_no     BIGINT,
    event_type    VARCHAR(128),
    payload_hash  VARCHAR(128),
    status        VARCHAR(32)  NOT NULL DEFAULT 'RECEIVED', -- RECEIVED / PROCESSING / DONE / WAITING_CORRELATION / FAILED
    attempt       INT          NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    error         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_wf_inbox_status ON wf_inbox_event (status, next_retry_at);

-- 出站事件 outbox:与业务/流程写在同一 PG 事务,后台 claim 后发 Kafka(至少一次)。
CREATE TABLE wf_outbox_event (
    event_id     VARCHAR(64) PRIMARY KEY,
    topic        VARCHAR(128) NOT NULL,
    msg_key      VARCHAR(256),
    event_type   VARCHAR(128),
    payload      JSONB        NOT NULL,
    status       VARCHAR(32)  NOT NULL DEFAULT 'READY',    -- READY / PROCESSING / SENT / FAILED
    attempt      INT          NOT NULL DEFAULT 0,
    lease_owner  VARCHAR(64),
    lease_until  TIMESTAMPTZ,
    available_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_wf_outbox_claim ON wf_outbox_event (status, available_at);

-- 任务授权关系同步态:PENDING 时 enforce fail-closed,READY 才可办(FINAL_PLAN §10.2)。
CREATE TABLE wf_task_authz_sync (
    task_id         VARCHAR(64) PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    resource        VARCHAR(256) NOT NULL,
    desired_version BIGINT       NOT NULL DEFAULT 0,
    sync_state      VARCHAR(32)  NOT NULL DEFAULT 'PENDING', -- PENDING / READY / FAILED
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 流程定义部署审计。
CREATE TABLE wf_deployment_audit (
    id                         BIGSERIAL PRIMARY KEY,
    deployment_id              VARCHAR(64),
    process_definition_key     VARCHAR(128),
    process_definition_version INT,
    tenant_id                  VARCHAR(64) NOT NULL,
    bpmn_hash                  VARCHAR(128),
    operator_sub               VARCHAR(128),
    action                     VARCHAR(32) NOT NULL,        -- DEPLOY / SUSPEND / ACTIVATE
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_wf_depaudit_key ON wf_deployment_audit (tenant_id, process_definition_key);

-- 租户配置:启用的定义、authz 三态、逻辑候选组→Casdoor/SpiceDB group 映射。
CREATE TABLE wf_tenant_config (
    tenant_id               VARCHAR(64) PRIMARY KEY,
    enabled_definitions     TEXT,
    authz_mode              VARCHAR(16) NOT NULL DEFAULT 'shadow', -- disabled / shadow / enforce
    candidate_group_mapping JSONB,
    retention_days          INT,
    config_version          BIGINT      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
