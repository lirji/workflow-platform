# Workflow Platform 最终实施计划

> 状态：规划完成，未实施。本文内“拟新增”的文件、类、表和接口尚不存在。所有现状类名/方法/表均已从仓库读取；不能确认的 Flowable API/版本行为均标为“待验证”。

## 1. 背景

`his-platform` 已用 Spring StateMachine、JPA 和 Kafka 手写药师审方与退费审批，并有真机 22 项断言通过的文档记录。其业务状态与审批编排现在混在 `PrescriptionReviewService`、`RefundService` 和角色待办接口中。目标是新建独立的 Flowable 流程/审批中台，让 HIS 只保留业务规则和业务状态，中台管理 BPMN、UserTask、待办、版本和轨迹。

新项目遵循 `auth-platform` 的多模块/Starter 消费形态，但不复制它的引擎抽象：workflow core 直接注入 Flowable `RuntimeService`、`TaskService`、`HistoryService`、`RepositoryService`，仅用薄 application service 集中事务、租户和测试边界。

Flowable 官方证据：7.1.0 源码父 POM使用 Java 17 和 Spring Boot 3.3.4，且 release note 明确升级至 Boot 3.3.4；它是最接近固定 Boot 3.3.5/JDK21 的候选，但本轮没有写临时工程或执行 Maven，故兼容性仍是 Phase 0 实测门禁。[Flowable 7.1.0 POM](https://raw.githubusercontent.com/flowable/flowable-engine/flowable-7.1.0/pom.xml)、[Flowable releases](https://github.com/flowable/flowable-engine/releases)。

## 2. 目标与非目标

### 目标

- 建成 protocol/core/server/admin/sdk 五模块和 console 占位的独立平台。
- 支持租户隔离的部署、发起、待办分页、认领/办理、通过/驳回、转办、加签、实例轨迹。
- 用通用 Kafka Published Language 与持久化 inbox/outbox 连接消费方。
- 先迁审方，再迁退费；保留 HIS 的业务状态机、审计表、通知和计费防重。
- 接入 Casdoor OIDC 与 auth-platform/SpiceDB；默认关闭外部任务判权并可 disabled/shadow/enforce 灰度。
- 支持 shadow、按新申请切换、在途隔离和紧急停止新发起。

### 非目标

- 不实现引擎可插拔端口，不嵌入 HIS，不迁业务数据到中台。
- 不替换 `OrderStateMachineService`、`EncounterService.tryBill`、`Invoice.refund` 或 `RefundRequest` 领域约束。
- 不把 Redis 用作正确性存储。
- 不在本计划实施 workflow-console；仅给接口契约和目录占位，正式前端另走 `/frontend-plan`。
- 首期不做通用表单、DMN、流程市场、任意脚本任务或跨引擎迁移。

## 3. 已确认业务规则

1. DRUG 才需审方；非药医嘱直接 SUBMITTED。
2. 审方按 encounter 批量处理当前 PENDING_REVIEW；PASS→SUBMITTED，REJECT→REJECTED。
3. 驳回医嘱可逐条 resubmit/cancel；同 encounter 同一时刻最多一个活动人工审方待办。上一轮已完成人工决定但等待业务 ACK 时，可与新轮次流程短暂并存。
4. 每个真实审批动作继续写 `prescription_review`。
5. `EncounterService.tryBill` 的待处理集合 `{CREATED,PENDING_REVIEW,REJECTED}`、`Encounter.billed` 和 billing `invoice.encounter_id UNIQUE` 均不可删除或替代。
6. 退费仅从 PAID 发起；APPROVE 在 billing 本地事务中同时 approve request 和 refund invoice；REJECT 不改 invoice。
7. APPROVE 仍发 `RefundReviewedEvent(orderIds)`，outpatient 仅将仍为 EXECUTED 的医嘱幂等撤销。
8. 业务关联查询语义为 `(tenantId, processDefinitionKey, businessKey)`；幂等发起再加 `idempotencyKey`。HIS tenant 为 `his`，审方 businessKey 为 encounterId、idempotencyKey 为 review cycle id，退费 businessKey/idempotencyKey 均来自 refundRequestId。
9. 目标实例回调一律用 BPMN message，不用会广播给多个订阅者的 signal。
10. 业务办理从同步完成变为“人工决定已受理—Kafka业务落地—ACK结束流程”的最终一致；API 必须显式返回 accepted/pending，不伪装已落地。

## 4. 当前代码与调用链

### 审方

`EncounterController#submit` → `EncounterService#submitOrders`。该方法把 DRUG 转 PENDING_REVIEW、其他转 SUBMITTED，发布 `RxReviewRequestedEvent`，再调用 `tryBill`（`his-outpatient/.../EncounterService.java:85-140`）。

`PrescriptionReviewController#pass/#reject` → `PrescriptionReviewService#pass/#reject`。PASS/REJECT 批量推进 `OrderStateMachineService`、写 `PrescriptionReview`、发布 `RxReviewedEvent`；PASS 再 `tryBill`（`PrescriptionReviewService.java:72-107`）。

`EncounterService#resubmitRejectedOrder` 对单个医嘱重提并再次发 requested event（`EncounterService.java:172-184`），所以平台必须合并同 encounter 的重复活动 start。

### 退费

`RefundController#request/#approve/#reject` → `RefundService`。request 创建 REQUESTED；approve 调 `RefundRequest.approve` 与 `Invoice.refund` 后发 `RefundReviewedEvent`；reject 只改请求（`RefundService.java:39-81`）。`RefundReviewedListener` 最终调用 `cancelExecutedOrders`（`his-outpatient/.../RefundReviewedListener.java:25-31`）。

### 持久化与消息

- `AuditableEntity.version` 是 JPA `@Version`（`his-common/.../AuditableEntity.java:32-34`）。
- `encounter.billed` 由 `V2__rx_review.sql:3-4` 添加。
- `invoice.encounter_id` 在 `V1__init_billing.sql:3-16` 唯一。
- 当前 publisher 都在本地事务中调用 `KafkaTemplate.send`，没有 inbox/outbox；数据库与 Kafka 存在提交窗口。
- 四类通知由 `NotificationListeners` 落 read model（`his-notify/.../NotificationListeners.java:30-68`）。
- README/CLAUDE 只记录 22 项通过，仓库没有对应脚本。迁移前必须恢复可执行基线。

### 身份

HIS `CurrentUser.userId` 是 Long；Casdoor 路径从 JWT username 回查 `his-auth` 的 `AuthInternalApi#getByUsername` 获得本地身份。SpiceDB 规范主体却应为 JWT `sub`。任务授权用 sub；回写 `PrescriptionReview.pharmacistId` 时由服务端用可信 username 回查本地 ID，不能信任客户端自报 actor。

## 5. 候选方案与评分

评分 1-5，复杂度/测试难度/回滚成本均为反向分；详细论证见 `comparison.md`。

| 方案 | 正确性 | 风险 | 简单度 | 可维护 | 扩展 | 易测试 | 易回滚 | 加权 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 可靠事件闭环 | 5 | 3 | 2 | 4 | 5 | 2 | 3 | 3.70 |
| B 平台直耦 HIS DTO | 3 | 3 | 4 | 2 | 2 | 3 | 3 | 2.80 |
| C REST/SDK 命令主导 | 2 | 2 | 3 | 3 | 3 | 3 | 2 | 2.50 |
| D 影子 Strangler | 4 | 5 | 2 | 3 | 4 | 2 | 5 | 3.65 |

不机械选单一方案：采用 A 的长期数据面 + D 的迁移护栏；从 C 只吸收查询/办理用显式 `WorkflowClient`，不让业务发起依赖同步 HTTP；从 B 只复用 HIS 既有领域事件语义，转换留在 HIS adapter，平台不依赖 `his-api`。

已知弱点：系统新增两侧 inbox/outbox、DLQ、对账和最终一致延迟，代码/运维成本最高。缓解方式是先只交付审方、稳定一个观察窗再上退费，而不是牺牲 Published Language 或 ACK。

## 6. 最终架构

```text
HIS local TX
  ├─ business state
  └─ his workflow outbox
          │ workflow.command.start.v1
          ▼
workflow-server: Kafka inbox ──same PG TX──> Flowable start + wf_process_link
          │
          ▼
      UserTask <── REST/OIDC ── workflow-console or SDK
          │ complete + platform outbox in same PG TX
          ▼
 workflow.action.requested.v1
          │
          ▼
HIS inbox ──same local TX──> existing domain state machine + domain event + ACK outbox
          │ workflow.action.applied.v1
          ▼
workflow inbox -> exact message correlation -> process end/history
```

### 6.1 模块与依赖

```text
workflow-platform-protocol  (纯 Java/Jackson annotations only)
       ▲                 ▲
       │                 └─ workflow-platform-sdk (Spring Starter/HTTP)
workflow-platform-core (Flowable API + JDBC application services)
       ▲            ▲
       │            └─ workflow-platform-admin :8301
       └─ workflow-platform-server :8300

workflow-console :5373/:8302  [只占位，需独立 frontend-plan]
```

- core 直接依赖 Flowable engine API，禁止新增 `WorkflowEngine` 接口。
- server/admin 各自嵌入同版本 process engine、连接同一 workflow PG；仅 server 开 async executor，admin 关闭，待 Phase 0 验证配置名。
- sdk 不暴露 Flowable Task/Process 类型。
- Redis 26379 只预留缓存/限流，首期正确性逻辑不依赖它。

### 6.2 tenant 与数据边界

- 所有 deploy/start/query/task/history 调用必须带 tenantId；start 使用 `processDefinitionTenantId` 对应的 Flowable API（具体方法签名 Phase 0 编译验证）。
- tenant 从 OIDC `owner` 或服务凭证绑定得到；REST body 的 tenant 只能做一致性校验，不能覆盖认证值。
- 流程变量白名单：IDs、流程控制枚举、金额快照、actor snapshot、actionId；不存完整患者/病历/发票对象。
- businessKey 可重复用于审方历史轮次；link 按四元组 `(tenant,definition,businessKey,idempotencyKey)` 唯一。同 encounter 最多一个 `WAITING_USER`，但旧轮次已完成用户任务、处于 `WAITING_BUSINESS` 时允许不同 cycle 的新实例，避免重提事件被吞。

## 7. BPMN 设计

### 7.1 `his-rx-review-v1.bpmn20.xml`（拟新增）

```text
start(requested)
 -> pharmacistReview UserTask(candidate group PHARMACIST)
 -> decision gateway
    PASS   -> prepareAction(service task: only writes outbox data)
    REJECT -> validate opinion -> prepareAction
 -> catch message hisRxReviewAppliedV1
 -> applied? gateway
    yes -> end
    no  -> incident/manual repair (never auto-pass)
```

变量：`encounterId`、`patientId`、`deptCode`、`doctorId`、`reviewRound`（若 HIS 能提供）、`decision`、`opinion`、`actorSub`、`actorUsername`、`actionId`。页面详情不从变量取全量医嘱，后续 console 通过 HIS detail API 查询。

驳回流程在业务 ACK 后结束；医生 resubmit 先创建/复用新的 `rx_review_cycle`，以 cycle id 为 idempotencyKey 发新 start。相同 cycle 重复返回原实例；旧 cycle 若只是在等 ACK，不阻止新 cycle 建 UserTask。数据库仍保证同 encounter 同时最多一个 `WAITING_USER`。

### 7.2 `his-refund-approval-v1.bpmn20.xml`（拟新增）

```text
start(requested)
 -> refundApproval UserTask(candidate group ADMIN)
 -> decision gateway(APPROVE/REJECT; REJECT opinion required)
 -> prepareAction
 -> catch message hisRefundAppliedV1
 -> success end / failure incident
```

变量：`refundRequestId`、`invoiceId`、`encounterId`、`amount`（展示快照，不作为退款金额真相）、`reason`、`applicantUserId`、`actionId`、actor snapshot。

timer 只做提醒/升级，不自动通过、拒绝或退款。

### 7.3 转办与加签

- 转办 v1：`TaskApplicationService#transferTask` 校验当前办理权和目标主体后调用 Flowable TaskService 的 assignee/candidate API，写审计，并触发 authz 关系重建。
- 加签 v1 仅支持“当前模型明确标为 multi-instance 的 UserTask 增加一个办理人”；不对任意单人任务动态改 BPMN。Flowable 7.1 的具体 multi-instance execution API 与签名必须在 Phase 0 spike 编译验证，验证失败则该 endpoint 返回明确 `FEATURE_NOT_SUPPORTED`，不能用独立无 execution 的临时 task 冒充流程任务。
- 会签完成条件和串/并行模式写在 BPMN；API 不允许调用方临时改完成表达式。

## 8. Kafka Published Language

### 8.1 Topic

| Topic | 方向 | key | 作用 |
|---|---|---|---|
| `workflow.command.start.v1` | consumer→platform | `tenant|definition|businessKey` | 幂等发起 |
| `workflow.action.requested.v1` | platform→consumer | 同上 | 请求业务系统落实人工决定 |
| `workflow.action.applied.v1` | consumer→platform | 同上 | 业务落地 ACK/NACK，推进 message |
| `workflow.lifecycle.v1` | platform→observers | 同上 | 可选生命周期通知，不参与正确性 |
| `workflow.dlq.v1` | 双侧运维 | 原 key | 毒消息/超限失败 |

现有 HIS `his.rx.review.requested/reviewed`、`his.refund.requested/reviewed` 在兼容窗口继续服务 notify；workflow 平台不直接依赖这些 DTO。

### 8.2 DTO（拟新增 protocol records）

`EventEnvelopeV1<T>`：`eventId UUID`、`contractVersion=1`、`eventType`、`occurredAt`、`source`、`tenantId`、`correlationId`、`causationId?`、`payload`。

`StartProcessCommandV1`：`processDefinitionKey`、`businessKey`、`idempotencyKey`、`initiator`、`variables`。variables 只允许 JSON scalar/受控 list/map，拒绝 Java serialized object。

`WorkflowActionRequestedV1`：`processInstanceId`、`taskId`、`taskDefinitionKey`、`processDefinitionKey`、`businessKey`、`actionId`、`action`、`actor{subjectId,username,displayName}`、`parameters`。

`WorkflowActionAppliedV1`：上述关联字段 + `actionId`、`status=APPLIED|REJECTED_BY_BUSINESS|FAILED_RETRYABLE|FAILED_FINAL`、`businessVersion?`、`errorCode?`、`errorMessage?`。

消费者必须先按 eventId inbox 去重；业务 action 再按 actionId 去重。ACK correlation 顺序：校验 instance → tenant/definition/businessKey → message subscription → pendingActionId。0 个订阅进入 `WAITING_CORRELATION` 重试；多个订阅是 P0 一致性故障。

## 9. REST 与 SDK 契约

拟定 `/api/v1`：

| Method | Path | 语义 |
|---|---|---|
| POST | `/process-instances` | 同步显式发起；仅非事务或已具备补偿场景，返回 instance/link |
| GET | `/process-instances/{id}` | tenant 内实例摘要 |
| GET | `/process-instances/{id}/timeline` | 历史活动/任务/意见，变量白名单 |
| GET | `/tasks?state=&candidate=&page=&size=` | 当前用户待办分页；主体取 token |
| POST | `/tasks/{id}/claim` | 认领 |
| POST | `/tasks/{id}/complete` | decision/opinion/idempotencyKey，返回 202 ACCEPTED/PENDING_BUSINESS |
| POST | `/tasks/{id}/transfer` | 转办 |
| POST | `/tasks/{id}/add-signers` | 仅支持已验证的 multi-instance 模型 |

Admin `/admin/v1`：definitions deploy/validate/list/activate/suspend、deployment diff/metadata、tenant config、audit/incident/replay。删除 deployment 默认不提供；如后续增加，必须无运行实例且显式二次确认。

SDK v1 提供：

- `WorkflowClient.startProcess(StartProcessRequest)`（显式同步场景）。
- `WorkflowClient.listMyTasks(TaskQuery)`、`completeTask`、`transferTask`、`addSigners`、`getTimeline`。
- `WorkflowEventPublisher`/outbox payload builder，供消费方可靠发起。
- `WorkflowSdkAutoConfiguration`：`workflow.client.enabled=false` 为默认；disabled 注册 `NoopWorkflowClient`。Noop start 返回 `NOT_STARTED_DISABLED`，不能伪造实例 ID。
- 首期不提供 `@StartProcess`：注解无法自动解决任意业务数据库与远程 HTTP/Kafka 的原子性。待有通用 outbox SPI 后再评估。

错误语义：400 校验、401 未认证、403 无权限、404 tenant 内不可见、409 状态/并发冲突、422 BPMN/业务参数不可处理、503 依赖失败。重复同 idempotencyKey 返回原结果，不使用 500。

## 10. 鉴权接入

### 10.1 身份和基础安全

- admin 和用户 task API 都校验 Casdoor JWT 的 JWKS、iss、aud、timestamp；配置 64KB request header。
- `/actuator/health|info` 放行；admin 写端需 `workflow-admin`，读端 `workflow-viewer|workflow-admin`。
- 服务 start/event replay 用独立 service credential，不能用匿名 endpoint。
- `workflow.authz.enabled=false` 只跳过 SpiceDB check；OIDC/service auth、tenant 和 Flowable candidate/assignee 校验始终存在。

### 10.2 `workflow.zed`（拟新增，语义待 fixture 验证）

若复用 8543 已有 `user/group/organization` 定义，文件只增加：

```zed
definition workflow_task {
  relation tenant: organization
  relation candidate: user | group#member
  relation assignee: user
  relation delegate: user
  relation administrator: user | group#member
  permission work = assignee + candidate + delegate + administrator
  permission transfer = assignee + administrator
  permission add_signer = assignee + administrator
}
```

resource id 为 `<tenantId>_<taskId>`，主体为 Casdoor `sub`。BPMN 中的逻辑候选组（如 `PHARMACIST`、`ADMIN`）必须经 `wf_tenant_config` 显式映射到 Casdoor/SpiceDB group object id（如 `<org>_<group>`）；Phase 3 provision/fixture 要同时验证 JWT groups、Flowable candidate 和 SpiceDB `group#member` 三者对齐，禁止按字符串碰巧相等。task create/assign/transfer/add-sign/complete 通过平台 outbox 同步关系；`wf_task_authz_sync.state=PENDING` 时 enforce fail-closed，READY 才可办。任务完成后即使 tuple 删除延迟，server 先查不到活动 task，仍不能办理。

authz 三态：`enabled=false`=disabled；`enabled=true, mode=shadow` 仅记录；`enabled=true, mode=enforce` 作为最终门禁。远程异常在 enforce 返回 503 且不 complete。

若 Phase 3 决定遵循 auth 指南“每项目独立 SpiceDB”，需先补端口/实例/独立 auth server ADR；在此之前按用户端口约束复用 8200/8543，且所有 schema 写脚本必须合并 `knowledge+his+workflow`，禁止单写 `workflow.zed`。

## 11. 数据库变更

### 11.1 workflow PostgreSQL 25432

Flowable `ACT_*`：Phase 0 dev 可自动创建；锁版后把对应版本官方 PostgreSQL DDL 固化到 `deploy/postgres/flowable-7.1.0/`，生产 `database-schema-update=false`。不手写猜测 ACT 表。

平台拟新增表：

- `wf_process_link`：tenant、definition key、business key、idempotency key、process instance id、phase(`WAITING_USER/WAITING_BUSINESS/...`)、status、version、timestamps；四元组唯一，同审方 businessKey 的 `WAITING_USER` 另设 partial unique，`WAITING_BUSINESS` 不阻塞新 cycle。
- `wf_inbox_event`：eventId PK、topic/partition/offset、type、payload hash、status、attempt、nextRetryAt、error、timestamps。
- `wf_outbox_event`：eventId PK、topic/key/type、payload JSONB、status、attempt、lease、availableAt、timestamps；`status+available_at` 索引。
- `wf_task_authz_sync`：taskId PK、tenant/resource、desired version、sync state、last error、timestamps。
- `wf_deployment_audit`：deployment/definition/version/tenant、BPMN hash、operator sub、action、time。
- `wf_tenant_config`：tenant PK、enabled definitions、authz mode、逻辑候选组→Casdoor/SpiceDB group ID 映射、retention/config version。

所有自有表用 Flyway；server/admin 同库启动前验证 Flyway lock。表字段精确类型在实施时由 migration + Testcontainers 验证。

### 11.2 HIS

- outpatient 新 migration：`rx_review_cycle`（cycle id、encounter、cycleNo、authority mode、workflow instance、status、version、审计列），并对同 encounter 活动 cycle 建唯一约束；另建 local `workflow_inbox_event/workflow_outbox_event`。
- billing 新 migration：给 `refund_request` 增 `approval_mode`、`workflow_instance_id`、`workflow_status`；另建 local inbox/outbox。
- notify 如在本轮解决重复通知：新增 `source_event_id` unique；否则记录为既有风险，不作为 cutover 阻塞，但 E2E 必须允许/检测重复。
- 不改 V1/V2，不删除或放宽 billed/invoice unique。

数据迁移只给既有未决请求标 `approval_mode=LEGACY`；不创建伪 Flowable 运行实例。具体 backfill SQL 先 dry-run 统计，再在 migration 写确定性更新。

## 12. 配置与端口

```yaml
server: 8300
admin: 8301
console-dev: 5373
console-prod: 8302
postgres: 25432
redis: 26379
auth-platform-server: 8200
casdoor: 8000
spicedb-http: 8543
```

拟定配置：`workflow.client.*`、`workflow.kafka.*`、`workflow.outbox.*`、`workflow.authz.enabled=false`、`workflow.authz.mode=shadow`、`workflow.security.*`、`flowable.*`、`spring.datasource.*`。HIS：`his.workflow.mode=disabled|shadow|enforce`、tenant、topic、outbox batch/retry、workflow base URL/token。

Compose 必须 `name: workflow-platform`、端口全变量化、容器/卷唯一；操作手册要求起前 `docker compose -p workflow-platform down --remove-orphans`，随后用 `lsof`/Docker inspect 检查 8300/8301/8302/5373/25432/26379，防 docker-proxy 残留。

## 13. 精确文件/类/方法改动清单

### 13.1 新建 `/Users/liruijun/personal/LLM/workflow-platform`

#### 根与部署

- `pom.xml`：Boot 3.3.5、Java 21、Flowable 版本属性和五模块。
- `.env.example`、`docker-compose.yml`、`Dockerfile`。
- `deploy/bpmn/his-rx-review-v1.bpmn20.xml`、`his-refund-approval-v1.bpmn20.xml`。
- `deploy/postgres/`：PG init、锁版后的官方 Flowable DDL。
- `deploy/scripts/{compose-preflight,phase0-smoke,server-smoke,authz-fixture,his-pilot-e2e}.sh`。
- `docs/adr/`：版本、事件关联、auth 拓扑、灰度 ADR。

#### protocol

- `.../protocol/event/EventEnvelopeV1.java`
- `.../protocol/event/StartProcessCommandV1.java`
- `.../protocol/event/WorkflowActionRequestedV1.java`
- `.../protocol/event/WorkflowActionAppliedV1.java`
- `.../protocol/event/WorkflowTopics.java`
- `.../protocol/api/StartProcessRequest.java`、`ProcessInstanceView.java`、`TaskView.java`、`TaskQuery.java`、`CompleteTaskRequest.java`、`TransferTaskRequest.java`、`AddSignersRequest.java`、`TimelineView.java`、`WorkflowError.java`。

#### core

- `ProcessApplicationService#start/#get/#timeline`：直接调用 Runtime/History API。
- `TaskApplicationService#listMyTasks/#claim/#complete/#transfer/#addSigners`：直接调用 Task/Runtime API；统一 tenant/幂等/事务。
- `MessageCorrelationService#correlateActionApplied`：精确找 execution 并 `messageEventReceived`。
- `ProcessLinkRepository`、`InboxEventRepository`、`OutboxEventRepository`、`TaskAuthzSyncRepository`：JdbcTemplate，不抽象 Flowable 引擎。
- `TaskAuthorizationService`、`DisabledTaskAuthorizationService`、`SpiceDbTaskAuthorizationService`：这是业务授权策略，不是 WorkflowEngine 端口。
- `src/main/resources/db/migration/V1__workflow_platform_metadata.sql`。

#### server

- `WorkflowPlatformServerApplication`。
- `ProcessController`、`TaskController`，方法与第 9 节 REST 对应。
- `WorkflowStartListener#onStart`、`WorkflowActionAppliedListener#onApplied`。
- `OutboxPublisher#publishBatch`、`CorrelationRetryJob#retry`、`AuthzRelationSyncJob#sync`。
- `RuntimeSecurityConfig`、`WorkflowSecurityProperties`、`WorkflowAuthzProperties`、`KafkaConfig`、`FlowableRuntimeConfig`。
- 对应 unit/integration tests。

#### admin

- `WorkflowPlatformAdminApplication`。
- `DefinitionAdminController#validate/#deploy/#list/#suspend/#activate`。
- `DefinitionAdminService` 直接用 RepositoryService；部署时记录 BPMN hash/audit。
- `AdminSecurityConfig` 复用 auth admin 的 JWT 校验语义。
- `IncidentAdminController#list/#retry`、`TenantConfigController`。

#### sdk

- `WorkflowClient`、`RemoteWorkflowClient`、`NoopWorkflowClient`。
- `WorkflowClientProperties`（enabled 默认 false、server URL 8300、超时、service token/Bearer relay）。
- `WorkflowSdkAutoConfiguration` 与 `AutoConfiguration.imports`。
- `WorkflowEventFactory`（只构造契约，不直接假定业务事务）。

#### console

- 只保留 `workflow-console/README.md`，列明待 `/frontend-plan` 的 bpmn-js、待办、轨迹需求和上述 API；不生成页面代码。

### 13.2 修改 his-platform

- 根 `pom.xml`：管理 workflow protocol/sdk 版本。
- `his-outpatient/pom.xml`、`his-billing/pom.xml`：引 protocol/sdk 与需要的 JDBC/Kafka 测试依赖。
- 两服务 `application.yml`：workflow mode/topics/outbox/JSON 契约配置。
- `EncounterService#submitOrders/#resubmitRejectedOrder`：在原业务事务创建/复用 `RxReviewCycle` 并写 start outbox；`tryBill` 逻辑不改。
- `PrescriptionReviewService#pass/#reject`：保留 legacy 入口；抽取 `applyWorkflowDecision` 供 action listener，仍调用原 state machine、review repository、publisher、tryBill。
- `PrescriptionReviewController#pending/#pass/#reject`：通过 `ApprovalModeRouter` 选择 legacy 或 workflow adapter；不直接复制业务逻辑。
- 新增 `RxReviewCycle`/repository、`RxReviewWorkflowActionListener`、`RxReviewWorkflowOutboxPublisher`、`WorkflowActorResolver`。
- outpatient 新 Flyway V3（具体序号以实施时现有最新 migration 再确认）创建 cycle/inbox/outbox。
- `RefundService#request/#approve/#reject`：request 写 start outbox；legacy 方法保留；新增 `applyWorkflowDecision`，approve/reject 的领域顺序不变。
- `RefundController#pending/#approve/#reject`：经 router；enforce 返回 accepted/pending 语义，前端适配留给 `/frontend-plan`。
- 新增 `RefundWorkflowActionListener`、billing inbox/outbox publisher；billing 新 Flyway V3 增链接列和事件表。
- `OrderEventPublisher#publishRxReviewed`、`RefundEventPublisher#publishReviewed`：workflow action 路径改为同事务 outbox，legacy 路径在兼容期保留；禁止一次动作双发。
- `his-notify/NotificationListeners` 仅在决定 sourceEventId 去重时修改；角色待办在 enforce 后标为通知兼容，不再作为权威办理列表。
- 新增 `scripts/step6_approval_e2e.sh`（名称可在恢复旧资料后调整），先对 legacy 22 项基线，再支持 workflow mode。
- README/CLAUDE/能力清单同步真实状态和命令。

### 13.3 修改 auth-platform

- 新增 `auth-platform-core/src/main/resources/schemas/workflow.zed`。
- 新增 `deploy/workflow-authz-fixture.sh`，默认 dry-run、`APPLY=1` 写入并强一致 allow/deny 校验。
- 若复用 8543，修改 `deploy/spicedb-smoke.sh`、`deploy/his-smoke.sh` 的全量 schema 合并为 knowledge+his+workflow。
- 更新平台能力与新项目接入文档，记录 workflow task resource、Casdoor sub、关系同步和 authz 三态。

实施前必须先复查 Git 状态；当前 `his-security/authz/**`、gateway filter、`EncounterController` 等有用户未提交改动，任何重叠文件需基于现状合并。

## 14. 分阶段实施与依赖

### Phase 0：版本与基建 Spike（依赖：无）

1. 创建多模块骨架、Compose PG/Redis、server/admin 最小启动。
2. 按指定 JAVA_HOME/Maven PATH 实测 Flowable 7.1.0。
3. 最简 BPMN deploy/start/complete/history、PG restart、双实例并发、v1/v2 部署。
4. 验证 multi-instance 动态加签 API；不通过则按第 7.3 节收窄能力。
5. 锁定版本和官方 DDL，写 ADR。

完成标准：兼容矩阵全绿、版本/DDL hash 固定、无未解释依赖冲突；否则停止 Phase 1。

### Phase 1：数据结构与领域模型（依赖 Phase 0）

1. 定义 protocol records、topic/version/idempotency 规则。
2. 建 workflow 自有 Flyway 表、repositories 和唯一约束。
3. 建 HIS `rx_review_cycle`、refund 链接列、本地 inbox/outbox migration。
4. 建 BPMN v1 和模型 lint/tests。

完成标准：PG Testcontainers migration 从空库/升级均过；同 cycle 20 并发 start 仅一实例，不同 cycle 不被 WAITING_BUSINESS 旧实例吞掉且仅一 WAITING_USER；DTO golden tests 与 BPMN model tests 全绿。

### Phase 2：核心业务逻辑（依赖 Phase 1）

1. 实现 Process/Task/Correlation application services，直接 Flowable API。
2. 实现 platform inbox/outbox、早到 ACK、DLQ/重放。
3. 在 HIS 抽取 workflow action handler，但保持原 service 业务规则、计费防重和 legacy 方法。
4. 先完成审方 shadow/enforce；稳定后再做退费。

完成标准：故障注入下无丢 start/action/ACK；重复 100 次只一次业务副作用；审方计费双防重断言全绿；退费未开始前不影响 legacy。

### Phase 3：接口与适配层（依赖 Phase 2）

1. REST controllers、SDK/Noop、service credentials/OIDC。
2. HIS mode router、旧 endpoint 兼容适配、Kafka 配置。
3. admin 部署/版本/审计/incident。
4. auth `workflow.zed`、关系同步、disabled→shadow→enforce。
5. 只写 console API/README 占位，不实现前端。

完成标准：API contract tests、tenant IDOR 矩阵、authz 三态、admin OIDC、SDK disabled 无网络请求全绿；enforce 下依赖故障不完成 task。

### Phase 4：测试与灰度迁移（依赖 Phase 3）

1. 恢复并先在 legacy 跑旧 22 项。
2. shadow 对账审方；零不明差异后只对新审方 enforce。
3. 观察窗达标后对退费重复同流程。
4. 跑并发、乱序、宕机、版本、性能、rollback 演练。

完成标准：旧 22 项在 legacy/enforce 最终态均过；异常矩阵全绿；无重复账单/退款；在途分类与回滚演练有证据。

### Phase 5：文档与最终检查（依赖 Phase 4）

1. 同步 README、ADR、OpenAPI/AsyncAPI、部署/监控/重放/回滚 runbook。
2. 对照本计划逐文件 review，检查未提交用户改动未丢失。
3. 保存 Maven、Compose、E2E、数据库对账输出；再做独立 diff review。

完成标准：文档命令可复跑，实际文件/接口与契约一致，无“已验证”但无输出证据的声明；console 仍明确等待 `/frontend-plan`。

## 15. 测试方案

完整方案见 `test-plan.md`。强制门禁：

- Flowable 版本/PG/JDK21 真实 smoke。
- protocol golden、core 单元、PG/Kafka Testcontainers。
- 20 并发 start、并发相反 decision、100 次 duplicate。
- commit 前后 kill、Kafka rebalance、ACK 早到、毒消息/DLQ/replay。
- tenant 隔离、OIDC 401、角色/SpiceDB 403、authz failure 503。
- BPMN v1/v2 在途兼容和 add-sign 能力边界。
- legacy 22 项恢复、disabled/shadow/enforce 回归。
- 对账 SQL/管理 API验证：无同 idempotencyKey 重复实例、无同 encounter 重复 WAITING_USER、无长期 PROCESSING、无流程完成但业务仍待审。

## 16. 监控、灰度与回滚

### 指标

- API latency/error；Flowable active task/process、job failure、optimistic locking。
- inbox/outbox READY/PROCESSING/FAILED 数、oldest age、retry/DLQ。
- start duplicate、correlation zero/multiple、ACK latency。
- HIS workflow action applied/duplicate/final-failed；`tryBill` call、OrderPlaced、invoice unique conflict；refund applied count。
- authz allow/deny/error/latency、task authz sync pending age。
- 每 tenant/definition 的 legacy/shadow/enforce 数和对账 mismatch。

日志必须带 `traceId,eventId,actionId,tenantId,processDefinitionKey,businessKey,processInstanceId,taskId`，医疗/退款原因等敏感字段不进普通日志。

### 灰度

按 `(tenant, definition)`：disabled → shadow → enforce-new。每个申请在创建时固定 authority；旧在途永不半途换轨。建议先审方，至少完成既定观察窗和故障演练，再启退费。

### 回滚

1. 立即关闭该 definition 的新 workflow 发起，新的申请回 legacy。
2. 不删除 Flowable 数据、不回滚 Flyway、不撤销已发生业务动作。
3. 对 workflow 在途按“未人工决定/已决定待业务 ACK/业务已落地待 ACK”分类。
4. 第一类可人工取消并重建 legacy；第二类优先重放 action；第三类重放 ACK。禁止生成第二次退款/计费。
5. 修复后可继续原实例或在明确补偿后终止；所有人工动作写 deployment/incident audit。

## 17. 风险清单

| 风险 | 失败场景 | 缓解/门禁 |
|---|---|---|
| Flowable 不兼容 | 启动成功但并发/PG/版本升级失败 | Phase 0 完整矩阵后锁版 |
| 事务双写 | DB 成功消息丢/反之 | 双侧同库 inbox/outbox |
| 重复/漏实例 | 同 cycle 两实例，或旧轮次等 ACK 时吞掉新重提 | 四元幂等唯一 + WAITING_USER partial unique + cycle 并发测试 |
| 并发办理 | PASS/REJECT 同时到 | Flowable锁 + actionId + 409 |
| 提前流程完成 | HIS 状态修改失败 | BPMN 等业务 ACK |
| 早到 ACK | 找不到 message subscription | 先订阅后发布 + WAITING_CORRELATION |
| 重复计费/退款 | publish 后本地 rollback | 保留 billed/unique/state/@Version，handler 幂等 |
| 身份错配 | 数值 uid 当 Casdoor sub | authz 只用 sub；业务审计 server-side username crosswalk |
| 授权同步窗口 | task 可见但 tuple 未就绪 | PENDING fail-closed，READY 才办 |
| schema 覆盖 | 单写 workflow.zed 删除旧定义 | 全量合并脚本或独立实例 ADR |
| 在途迁移 | 老申请被两套系统同时办 | authority 固定、只切新申请 |
| 22 项不可复跑 | 文档声称通过但无脚本 | 迁移前恢复并在 legacy 跑基线 |
| docker-proxy 残留 | 端口被旧容器占用 | 独立 project name、down/orphans、preflight |

## 18. 最终验收清单

- [ ] 未修改既有 V1/V2，计费双防重仍在。
- [ ] Flowable 版本在 Boot 3.3.5/JDK21/PG16 实测锁定。
- [ ] 所有 Flowable 调用均 tenant-scoped，无 `WorkflowEngine` 抽象。
- [ ] 同四元组并发发起仅一个实例；不同审方 cycle 不丢，且同 encounter 最多一个 WAITING_USER。
- [ ] UserTask PASS/REJECT/transfer/add-sign（或明确不支持边界）有测试。
- [ ] action 在业务 ACK 前流程不结束。
- [ ] 双侧 inbox/outbox 可恢复、重放、DLQ、监控。
- [ ] 审方业务状态、review 留痕、通知、计费最终行为等价。
- [ ] 退费只处理一次，驳回不退款，通过最终撤销医嘱。
- [ ] authz 默认关；disabled 不匿名，shadow 有指标，enforce fail-closed。
- [ ] Casdoor sub、tenant owner、HIS local uid 映射清楚且不可伪造。
- [ ] admin OIDC issuer/aud/groups 与 64KB header 验证通过。
- [ ] 旧 22 项脚本已恢复并在 legacy/enforce 各跑通。
- [ ] 并发、重复、乱序、宕机、版本和 rollback 演练全绿。
- [ ] 监控/对账/runbook 完整，文档声明都有真实输出证据。
- [ ] workflow-console 只留契约占位，后续实现已明确需 `/frontend-plan`。

## 19. 二次资深架构审查记录

已对本文做一轮新鲜的对抗性复核，并修正以下容易自相矛盾之处：

1. 否定“businessKey 永久唯一”的假设；允许同 encounter 多轮历史审方，并在后续复核进一步细化为 cycle/idempotencyKey 与 phase 约束。
2. 明确流程不能在发 action 后直接结束，必须等待业务 ACK。
3. 明确 authz disabled 不等于跳过身份/candidate 校验。
4. 发现 Casdoor sub 与 HIS Long uid 不同，补充可信 username crosswalk。
5. 发现 22 项脚本并不存在，改为 Phase 2/4 前置恢复，而非声称可直接复用。
6. 发现 auth schema/write 全量替换与“独立实例”文档冲突，给出试点默认拓扑和 Phase 3 ADR 门禁。
7. 收窄“任意加签”为经过 Phase 0 API 验证的 multi-instance 任务，避免虚构 Flowable 能力。
8. 将 Redis 从锁/幂等设计中移除，正确性统一落 PG。
9. 明确旧 HTTP 审批接口在异步 enforce 下不能继续宣称业务已同步落地，前端适配另走计划。
10. 把退费排在审方稳定之后，避免一次同时迁移医疗计费与资金状态两条高风险链路。
11. 修正“活动三元组永久唯一”的竞态：引入 cycle/idempotencyKey 与 WAITING_USER/WAITING_BUSINESS phase，确保重提既不重复也不被旧 ACK 等待实例吞掉。
12. 补充逻辑候选组到 Casdoor/SpiceDB group object 的显式映射，避免本地角色名与统一身份组名表面相同、实际授权空转。

复核后没有把任何“待验证”项改写成既成事实；实施 Agent 必须在每阶段完成标准满足后才进入下一阶段。
