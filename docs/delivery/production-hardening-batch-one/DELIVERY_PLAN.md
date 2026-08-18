# Production Hardening Batch One Delivery Plan

## Requirement

将现有工作流平台的第一批生产阻断项闭环：强制生产安全配置、可信租户与任务授权、SDK Bearer Token、inbox 崩溃一致性、HA 关联重试、可靠 DLQ 重放，并建立可重复执行的回归与 CI 门禁。

## Repository Evidence

- `WorkflowIdentityResolver` 在 JWT tenant claim 为空时信任 `X-Workflow-Tenant`。
- `TaskApplicationService` 仅校验任务租户，未校验办理人、候选用户或候选组。
- `ProcessController` 的按 ID 查询与轨迹查询没有租户条件。
- Kafka listener 的 inbox claim 与业务事务分离；崩溃后记录可停在 `PROCESSING`。
- `DlqReplayService` 在异步 Kafka send 完成前标记 `REPLAYED`。
- SDK 不支持 Bearer Token；生产镜像与仓库没有 CI 门禁。

## Feasibility

- Verdict: go
- Constraints: 保持默认开发/shadow 免鉴权兼容；不修改用户现有 console/compose 未提交改动的语义；不部署、不写真实密钥。
- Dependencies: Spring Security/JWT、Flowable TaskService、Spring Kafka、PostgreSQL/Flyway、现有 Maven/Vitest 测试。
- Risks and mitigations:
  - 授权收紧可能暴露旧调用方依赖明文租户或任意 `userId`：仅在鉴权启用时强制，开发模式保持兼容。
  - inbox 外层事务与发起流程并发幂等冲突：发起事务改为 `REQUIRES_NEW`，外层 inbox 事务回滚后允许 Kafka 重投并命中幂等实例。
  - DLQ 等待 broker ACK 会增加管理请求时延：设置有界超时，失败回滚为 `NEW`。

## Product Design

- Actors and goals: 普通审批人只能查看/操作自己有权的任务；租户管理员只能管理 JWT 租户内资源；服务调用方可安全携带访问令牌；运维可确认重放结果真实可靠。
- Scope: server/core/sdk 安全与消息可靠性、生产 profile/guard、测试、文档、CI。
- Out of scope: 新 UI、DLQ 表租户字段改造、Kubernetes/容灾、外部 IdP 或 Kafka 集群部署、数据留存任务。
- Business rules:
  - 鉴权开启且配置 tenant claim 时，租户只能来自 JWT，缺失或与可选 Header 冲突即拒绝。
  - 非管理员：未认领任务需命中候选用户/组；已认领任务只允许当前 assignee 操作；只能为自己认领。
  - `ADMIN` 必须是精确组名，不能由任意 `*_ADMIN` 自动提升。
  - 生产 profile 缺少 issuer/audience/tenant claim、仍关闭安全、使用默认 DB 密码、启用 schema 自动升级或试点自动部署时拒绝启动。

## Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-01 | `prod` profile 对不安全或不完整配置 fail-fast，完整配置可通过 guard | P0 | production guard unit tests |
| AC-02 | JWT 校验 issuer + audience；tenant claim 缺失/冲突拒绝；Header 不能越租户 | P0 | security/identity tests |
| AC-03 | `foo_ADMIN` 不再获得 `ADMIN`；精确角色仍可用 | P0 | role mapping tests |
| AC-04 | 鉴权开启时任务列表和完成/认领/转办/委派/撤回均执行服务端授权 | P0 | TaskApplicationService + controller tests |
| AC-05 | 流程实例、轨迹、管理操作和定义变更按有效租户校验 | P0 | query/admin/controller tests |
| AC-06 | SDK 可逐请求注入 Bearer Token，缺 token 可 fail-fast；Noop 写操作可配置 fail-fast | P0 | SDK unit tests |
| AC-07 | inbox claim、业务处理、完成状态在监听器事务内；发起事务可安全重入 | P0 | transaction configuration/listener tests + integration regression |
| AC-08 | 多副本 correlation retry 通过 DB lease 互斥领取 | P0 | repository migration/query tests |
| AC-09 | DLQ 仅在 Kafka ACK 成功后标记重放，发送失败保持 `NEW` | P0 | DLQ service tests |
| AC-10 | 后端、前端、Compose 与 CI 底层命令可重复通过 | P0 | full local verification |

## UI/UX Design

- Applicability: Not applicable；不新增或重排页面。
- Existing behavior: 后端 401/403/404 继续由现有 Axios/React Query 错误态呈现。

## Technical Solution

- Chosen approach: 保持模块化单体；在 core 引入框架无关的 `TaskAccessContext` 和拒绝异常，由 server 从 Spring Security 上下文构造；使用 Spring DB 事务 + `REQUIRES_NEW` 发起事务；correlation retry 增加 inbox lease；DLQ 在 DB 锁内等待 Kafka ACK。
- Alternatives rejected:
  - 立即拆 control/data plane：超出第一批且不能消除当前授权与消息窗口。
  - 仅靠前端候选组过滤/API Gateway 租户头：无法形成服务端安全边界。
  - Kafka/DB XA：复杂度和运维成本过高，现有 inbox/outbox 幂等模式足够。
- Modules and file map: `workflow-platform-core` task/query/inbox/transaction/migration；`workflow-platform-server` security/controllers/listeners/admin/DLQ/config/tests；`workflow-platform-sdk` token provider/properties/client/tests；deploy/docs/CI。
- Contracts and data: REST 路径保持不变；按 ID/管理变更请求新增可选租户 Header（生产由 JWT 决定）；inbox 新增 lease 字段；SDK 新增可选 token provider SPI。
- Security and reliability: fail closed、精确权限、租户资源核验、Kafka ACK + DB rollback、lease + SKIP LOCKED。
- Observability: 保留现有指标/审计；授权失败使用统一 403，不记录 token。
- Compatibility and migration: dev/shadow `security.enabled=false` 保持原行为；Flyway 新增向前迁移；生产必须显式启用 `prod` profile 与安全变量。

## Implementation Sequence

1. 交付计划与生产 guard/JWT/tenant 基础，覆盖 AC-01–03。
2. 任务与实例/管理资源授权，覆盖 AC-04–05。
3. SDK token/noop fail-fast，覆盖 AC-06。
4. inbox/correlation/DLQ 可靠性，覆盖 AC-07–09。
5. 全量回归、审查修复、文档和 CI，覆盖 AC-10。

## Verification Plan

| AC/Risk | Test level | Case or command | Required evidence |
| --- | --- | --- | --- |
| AC-01–03 | unit/web slice | targeted Maven tests | invalid prod config rejected; exact roles and tenant claims enforced |
| AC-04–05 | unit/web/integration | task/query/admin tests | cross-tenant and unauthorized operations rejected |
| AC-06 | unit | SDK tests | token attached; missing token/noop writes fail as configured |
| AC-07–09 | unit/integration | listener/DB/DLQ tests | rollback, lease and ACK ordering proven |
| AC-10 | build/QA | `mvn -B test`, `pnpm test`, `pnpm build`, Compose config | clean exit and exact counts |

## Documentation Plan

同步 README、架构、接入、部署与路线图；明确生产 profile、JWT claims、精确角色、SDK token provider、消息事务和剩余风险。

## CI Plan

仓库 remote 为 GitHub，新增最小 GitHub Actions：JDK 21 Maven tests、Node 20/pnpm 9 前端 tests/build、Compose config。只使用无密钥本地门禁，不部署。

## Rollout And Rollback

- Rollout: 先在 shadow 以安全开启但授权审计观察，确认 IdP 提供 issuer/audience/tenant/groups；再启用 `prod` profile；先单副本验证，再双副本故障注入。
- Rollback: 应用可回滚到旧版本；新增 inbox 列为向后兼容，不回滚数据库迁移；紧急情况下只能在隔离测试环境关闭安全，生产 guard 不允许降级。

## Assumptions And Open Decisions

- JWT `preferred_username` 与 Flowable assignee/userId 对齐；无该 claim 时回退 `sub`。
- IdP 可签发精确 `ADMIN`/业务候选组以及 tenant claim。
- 本批不把 DLQ schema 改为 tenant-scoped；DLQ 仍由全局 ADMIN 管理，记录为后续风险。

## Approval

- Status: approved
- Approved scope: 第一批生产加固（安全/租户授权、inbox/DLQ 可靠性、生产配置门禁及回归）。
- Evidence: 用户消息“做第一批吧”。
