# Delivery Report

## Outcome

第一批企业生产加固已实现并达到 CI-ready：生产配置 fail-fast、JWT/租户/任务授权、SDK 服务令牌、消息事务与 HA lease、可靠 DLQ 重放、生产文档和 GitHub Actions 门禁已闭环。本地 QA 为 conditional-pass，条件仅是需要 Docker/真实依赖的外部集成验证。

## Requirement Coverage

| AC | Implementation evidence | Verification evidence | Status |
| --- | --- | --- | --- |
| AC-01 | `application-prod.yml`、`WorkflowProductionGuard` | guard tests 3/3 | complete |
| AC-02 | issuer/audience validator、tenant claim authoritative resolver | validator/identity/security chain tests | complete |
| AC-03 | 后端/前端仅路径末段大写，不截断下划线 | backend + frontend normalization tests | complete |
| AC-04 | `TaskAccessContext`、candidate/assignee rules、原子 claim | Flowable task auth + Controller tests | complete |
| AC-05 | instance/timeline/admin/definition/ACK tenant validation | tenant isolation service、definition integration、controller tests | complete |
| AC-06 | `WorkflowBearerTokenProvider`、require auth、Noop fail-fast、JSON starter | SDK tests 4/4（含真实 loopback HTTP） | complete |
| AC-07 | listener 外层事务、start `REQUIRES_NEW` | transaction contract + full regression | complete |
| AC-08 | inbox V3 lease、leased `SKIP LOCKED` claim | PostgreSQL case 已加入；本机 Docker blocked，CI 待执行 | complete / external verification pending |
| AC-09 | DB 行锁内等待 Kafka ACK 后更新状态 | DLQ tests 4/4 | complete |
| AC-10 | full local commands + GitHub Actions | Maven、Vitest/build、Compose/YAML/shell checks | complete |

## Changed Files

- Core: task authorization、tenant-scoped query/correlation、inbox/outbox lease SQL、DLQ locking、V3 migration 与 PostgreSQL tests。
- Server: prod guard/JWT/identity、租户受控 controllers/services、transactional listeners、correlation retry、DLQ replay 与安全/租户测试。
- SDK: token provider/properties/interceptor、Noop fail-fast、JSON runtime dependency 与 loopback tests。
- Console: 精确角色归一化及 fail-closed tests；没有页面结构或交互变更。
- Delivery/ops: `.github/workflows/ci.yml`、Compose env、README/architecture/integration/deploy/roadmap 与本目录交付 artifacts。

## Build And Test Results

- `mvn -B --no-transfer-progress test`: success；protocol 6 + SDK 4 + server 50，合计 60（58 pass、2 external-PG skipped）；Testcontainers 类因 Docker 不可用未计入。
- `pnpm test`: 16 files、57/57 pass。
- `pnpm build`: success，3627 modules transformed。
- Compose config、CI YAML parse、deploy shell syntax、`git diff --check`: success。

## Code Review And QA Verdicts

- Code review: conditional-pass；无未解决 critical/high finding。
- QA: conditional-pass；阻塞项仅为当前环境没有 Docker/PG/IdP/Kafka/多副本目标。

## Documentation Changes

- 更新生产 profile 必填配置、JWT tenant/角色语义、Prometheus 权限、SDK token provider。
- 更新 inbox 事务/lease、DLQ ACK 语义、V3 migration、真实剩余投产验证，不再宣称“全部生产化完成”。

## CI Changes And Validation

- 新增 GitHub Actions 三个 job：JDK 21 Maven reactor、Node 20/pnpm 9 frontend tests/build、Compose 与 shell validation。
- 权限仅 `contents: read`，checkout 不持久化凭据，无部署、无 secret、无外部状态修改。
- 本地已执行所有底层命令；远程 runner 尚未触发（未 commit/push）。

## Deviations From Plan

- review 中把已有 outbox claim SQL 一并修正，否则新增 CI PostgreSQL 测试会暴露生产故障；属于同一 HA 正确性范围。
- SDK loopback 测试发现缺 JSON converter，补入 `spring-boot-starter-json`，保证认证后的查询实际可用。
- ACK 增加 envelope tenant/definition/businessKey 校验，补齐协议注释早已声明但代码缺失的隔离规则。
- UI/UX 仍为 Not applicable；仅更新前端角色解析与测试。

## Rollout, Monitoring, And Rollback

1. 在 shadow 接真实 IdP token，核对 issuer/audience/tenant/groups 与 `preferred_username` 映射。
2. 备份数据库后先执行 Flyway V3，再以 `prod` profile 单副本启动；观察 401/403、inbox lease、DLQ 与 outbox 积压指标。
3. 扩到双副本，做 kill/restart、Kafka/PG 短暂故障和 lease 接管验证，再做容量压测。
4. 回滚应用时保留 V3 两个 nullable 列（向后兼容），不做数据库降级；生产 guard 不允许靠关闭安全降级。

## Remaining Risks Or External Actions

- 远程 CI 首跑必须确认 Testcontainers 的 5 个 PostgreSQL case 全绿。
- 投产前完成真实 Casdoor/Kafka、双副本故障、容量、备份恢复/RPO-RTO、密钥轮换和告警值班演练。
- 后续批次处理全局 DLQ tenant 模型、任务查询下推/性能、分布式 tracing 与数据留存。
