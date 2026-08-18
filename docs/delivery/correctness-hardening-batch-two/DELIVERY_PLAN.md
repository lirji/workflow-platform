# Correctness Hardening Batch Two Delivery Plan

## Requirement

在第一批生产安全加固之后，关闭当前最危险的正确性缺口：测试不得接触共享开发库；outbox 的租约拥有者必须通过 CAS 完成发送结果写回并具备有界发送/失败终态；Kafka 入站信封必须做契约校验并绑定可信 source/tenant；流程阶段与状态必须一致；流程轨迹必须选择最新实例并展示该实例实际运行的 BPMN 版本。

## Repository Evidence

- `RxReviewLoopTest` 探测并连接固定的 `localhost:25432/workflow`，前后清理全部部署和平台表，已证明会破坏正在运行的本地 Compose 数据。
- `OutboxEventRepository.markSent/reschedule` 仅按 `event_id` 更新；租约过期被其它实例接管后，旧 publisher 仍可覆盖新结果。
- `OutboxPublisher` 使用无超时的 `KafkaTemplate.send(...).get()`，且失败永久重试，没有 `FAILED` 终态。
- Kafka listener 只反序列化，不验证 `contractVersion/eventType/必填字段`，并直接信任 envelope 的 `source/tenantId`。
- `ProcessLinkRepository.updatePhase` 的乐观锁失败被调用方忽略，`status` 初始化为 `ACTIVE` 后不会随终态变化。
- 流程实例查询按最新在前返回，轨迹页却取最后一条；定义 XML 接口总取最新部署版本，老实例会显示错误流程图。

## Feasibility

- Verdict: go
- Constraints: 保持模块化单体和现有 v1 REST/event 路径；开发环境仍可关闭 Kafka HMAC/source/tenant 信任校验；不引入 XA；不重写统一任务中心或 Saga。
- Dependencies: PostgreSQL/Flyway、Spring Kafka、Flowable History/RepositoryService、Testcontainers、现有 React Query/Vitest。
- Risks and mitigations:
  - Testcontainers 在无 Docker 环境不可运行：使用 `disabledWithoutDocker` 明确跳过；Docker Engine 29 所需 API 固定为 1.44；测试代码增加独占数据库硬断言，永久禁止固定共享端口。
  - 严格 Kafka 校验会拒绝历史脏消息：错误进入现有 Kafka retry/DLQ；开发默认仅做结构校验，生产 guard 强制 source/tenant allowlist 和 per-source HMAC 密钥。
  - outbox 明确失败达到最大次数后不再自动发送：落 `FAILED`；客户端超时/取消/中断无法证明 broker 未接收，落 `DELIVERY_UNKNOWN` 且禁止自动重发；核账确认未送达后才可由 ADMIN 带原因以同 eventId 恢复；两者保留最后错误并产生指标/告警。
  - 并发状态更新可能发生真实冲突：有限次数重读 CAS；若仍冲突则抛异常回滚当前事务，避免 Flowable 已推进但平台状态未更新。

## Product Design

- Actors and goals:
  - 开发者执行全量测试时，不会触碰本地/共享工作流数据。
  - 运维可确定 outbox 不会因陈旧租约写回而重新投递，永久失败可观察。
  - 平台只处理契约正确且 source 获准代表目标 tenant 的 Kafka 消息。
  - 业务/运维人员看到的流程状态和轨迹与实际 Flowable 实例一致。
- Scope: server/core/console 的上述正确性修复、迁移、配置、指标、测试、文档与 CI 门禁。
- Out of scope: inbox payload hash 冲突治理、Kafka broker SASL/TLS/ACL 实施、outbox 人工重放 UI、通用动作账本、任务中心性能重构、HIS 硬编码泛化、Saga。
- Business rules:
  - 只有当前 `PROCESSING + lease_owner + lease 未过期` 才能写回 outbox；每行发送前独立续租。
  - 单次 ACK 等待必须早于 lease 超时结束；明确失败才退避重试，累计达到上限进入 `FAILED`；ACK 等待超时进入 `DELIVERY_UNKNOWN`，不自动重发。
  - Kafka `contractVersion`、`eventType`、信封和 payload 必填字段不匹配即拒绝处理。
  - 信任校验开启时，只有原始 JSON HMAC 有效且显式 `source=tenant` 绑定匹配的消息可进入 inbox；`prod` 必须开启并为每个 source 配置至少 32 字节密钥。
  - `COMPLETED/CANCELLED` 对应 `ENDED`，`INCIDENT` 对应 `ERROR`，其余阶段对应 `ACTIVE`。
  - 轨迹页使用后端返回的第一条（最新）实例，并按该实例的 `processDefinitionId` 读取 XML。

## Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-11 | `RxReviewLoopTest` 仅使用 Testcontainers 随机 PostgreSQL；代码中不再出现固定 `localhost:25432/workflow`，清理前验证独占 test DB | P0 | test source guard + isolated integration test |
| AC-12 | 过期 owner 不能 mark sent/reschedule/fail；新 owner 的 `SENT` 不会被旧 owner 改回 `READY` | P0 | PostgreSQL repository integration tests |
| AC-13 | Kafka send 有界超时；明确失败达到最大次数写 `FAILED`，timeout/cancel/interruption 写 `DELIVERY_UNKNOWN` 且不自动重发；核账确认未送达后可带原因以同 eventId 人工恢复；保留错误并产生 metric/alert | P0 | publisher/recovery unit + PostgreSQL tests + migration/alert checks |
| AC-14 | 两个 listener 在 inbox 前拒绝错误版本、事件类型、空必填字段、无效 HMAC 和不获准的 source/tenant；生产漏配信任边界启动失败 | P0 | codec/validator/listener/production guard tests |
| AC-15 | 阶段写入同步更新 status，CAS 失败会重读重试且不会静默丢失；终态不被普通推进覆盖 | P0 | repository/service tests + loop integration |
| AC-16 | 轨迹默认选择最新实例；带实例 ID 的 XML 查询做租户/key 校验并返回实例对应 definition，而非最新版本 | P0 | controller/service/frontend tests |
| AC-17 | Maven、Vitest/build、Compose config、Docker 全栈重建与健康检查通过 | P0 | final QA evidence |

## UI/UX Design

- Applicability: 仅修正已有轨迹页的数据选择，不新增页面或交互。
- Loading/error/empty behavior: 沿用现有 React Query、`PageSkeleton/ErrorState/EmptyState`；实例查询完成后再按实例 ID 拉取正确 XML。
- Accessibility/responsiveness: 现有布局不变。

## Technical Solution

- Chosen approach:
  - 测试使用静态 Testcontainers PostgreSQL + `@DynamicPropertySource`，并在清理前检查数据库名与共享端口。
  - outbox repository 所有写回使用 `event_id + status=PROCESSING + lease_owner + lease_until>=now()` CAS；V4 增加 `last_error`；publisher 逐行续租，用 `get(timeout)` 区分明确失败与结果未知，并在启动时校验 delivery timeout≤应用等待、max.block+等待<lease。
  - `EnvelopeCodec` 按 expected event type 做 v1 结构/载荷校验；`KafkaEnvelopeTrustValidator` 对精确原始 JSON 验证 per-source HMAC 后再检查 source/tenant bindings；生产 guard 强制开启并校验密钥。DLQ 保存并原样重放入口签名，不替外部 source 重新签名。
  - `ProcessLinkRepository` 统一 phase→status SQL，新增有限重试的 `ProcessPhaseTransitionService`；任务完成与消息关联复用它。
  - `DefinitionController` 可接收 `processInstanceId`，先按 tenant/link/history 定位 `processDefinitionId`；前端先取最新实例再以实例 ID 请求 XML。
- Alternatives rejected:
  - 延长 outbox lease：只能降低概率，不能消除陈旧写。
  - Kafka/DB XA：复杂度高且不解决 producer 身份治理。
  - 只把 definition version 返回前端再拼查询：仍要求前端承担租户与实例真实性校验。
  - 后台 reconciliation 掩盖状态 CAS 丢失：可作为后续防线，本批先保证同步事务不静默失败。
- Security boundary: per-source HMAC 认证 envelope 的 source 声明，source/tenant allowlist 再做授权；生产 Kafka 仍必须配置 SASL/mTLS、TLS 与 producer topic ACL，缩小密钥与 topic 的暴露面。
- Compatibility: 开发默认 `workflow.kafka-trust.enabled=false`；v1 topic/REST 路径不变；definition XML 的实例参数为可选，不影响设计器读取最新版本。

## Implementation Sequence

1. AC-11：隔离危险集成测试并加入静态守卫。
2. AC-12–13：outbox V4/CAS/超时/失败终态/指标告警。
3. AC-14：Kafka 契约校验、source/tenant 信任边界与生产 guard。
4. AC-15：状态映射与可靠阶段转移。
5. AC-16：实例版本正确的定义 XML 与前端 newest 修复。
6. AC-17：独立 review、QA、文档/CI 同步及 Docker 重部署。

## Verification Plan

| AC/Risk | Test level | Case or command | Required evidence |
| --- | --- | --- | --- |
| AC-11–12 | PostgreSQL integration/static | Testcontainers tests + `rg` guard | no shared URL; stale owner updates return false |
| AC-13 | unit/integration | `OutboxPublisherTest` + migration test | timeout/retry/FAILED branches and last error |
| AC-14 | unit/listener/config | codec/trust/guard tests | invalid or unauthorized envelope rejected before inbox |
| AC-15 | unit/integration | transition and full review loop | phase/status converge or transaction fails visibly |
| AC-16 | web/frontend | controller tests + Vitest | exact definition id and newest selection |
| AC-17 | build/runtime | Maven, pnpm test/build, Compose rebuild/health | clean exit, test counts, six healthy services |

## Documentation And CI Plan

同步 README、架构、接入指南、部署说明和路线图；CI 明确 Testcontainers 隔离，增加禁止测试连接固定共享库的文本门禁，并断言两套 PostgreSQL suite 的 Surefire `tests>0/skipped=0`；保留现有 Maven/frontend/Compose 门禁。

## Rollout And Rollback

- Rollout: 禁止新旧 writer 混跑。先停止/排空全部 server 写入，预检 `wf_process_link` 漂移行数和表大小并为 V4 的回填预留维护窗口，再依次应用 V4/V5、发布全部 server。生产发布前配置 Kafka 绑定与 per-source HMAC 密钥，并确保 producer `max.block.ms` + 应用 send timeout 严格小于 lease、`delivery.timeout.ms` 不大于应用等待预算；观察 outbox `FAILED`、`DELIVERY_UNKNOWN` 与 DLQ。
- Rollback: V4/V5 的 nullable 加列及 TEXT 扩容保持 SQL 兼容，但旧版本不会维护 phase/status 一致性，回滚仅用于紧急恢复且会暂时失去 AC-15；不得与新版本 writer 混跑。回滚后保留迁移列；恢复新版本前停止旧 writer，执行 `deploy/sql/reconcile-process-link-status.sql` 修复回滚期间新增的漂移，再一次性恢复全部新 writer。若 Kafka 绑定/密钥误配，修正配置后重启，不在生产关闭 guard。

## Approval

- Status: approved
- Evidence: 用户在评估与 P0 路线后回复“可以继续推进流程”。
- Approved scope: 第二批 P0 正确性加固；不扩展到任务中心、通用流程域或 Saga。
