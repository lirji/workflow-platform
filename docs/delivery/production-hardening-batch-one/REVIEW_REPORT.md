# Code Review Report

## Scope And Diff Base

- Diff base: 当前 `main` 的 `HEAD` 对工作区完整差异；未创建 commit、未 push、未部署。
- Scope: 第一批生产加固的 core/server/sdk/console auth、配置、迁移、测试、文档与 CI。
- 工作区在本批开始前已有 README、deploy 与 console 镜像/nginx 改动；审查时保留这些用户改动，不作回滚。

## Confirmed Findings

| Severity | Finding | Failure scenario | Evidence | Resolution |
| --- | --- | --- | --- | --- |
| High | outbox/inbox 领取 SQL 的锁定与 LIMIT 顺序不可用于目标 PostgreSQL | publisher 或 correlation retry 首次领取即 SQL 失败，积压无法消费 | `workflow-platform-core/.../OutboxEventRepository.java:37`、`InboxEventRepository.java:64` | 两处改为 `ORDER BY ... LIMIT ? FOR UPDATE SKIP LOCKED`，并在 PostgreSQL 迁移测试加入真实仓储调用 |
| High | ACK 关联只信任 `processInstanceId`/`actionId`，未验证 envelope tenant 与业务元数据 | 被错误路由或伪造的跨租户 ACK 可能推进另一租户实例 | `workflow-platform-core/.../MessageCorrelationService.java:39` | 关联前强制按 tenant 查 link，并核对 definition/businessKey；listener 与 retry 均传入 envelope tenant |
| Medium | 普通候选人认领使用 `setAssignee`，并发时可覆盖先认领者 | 两个候选人同时认领，后提交者覆盖办理人 | `workflow-platform-core/.../TaskApplicationService.java:86` | 普通用户改用 Flowable 原子 `claim`；已认领给自己保持幂等 |
| Medium | core/admin 存在不带访问上下文或 tenant 的公开重载 | 后续调用方可能绕过 Controller 的授权边界 | `TaskApplicationService`、`AdminOpsService`、`DefinitionAdminService`、`ProcessQueryService` | 移除旁路重载，只保留显式 tenant/`TaskAccessContext` API；dev 由 resolver 显式传 disabled context |
| Medium | SDK 独立引入时只有 `spring-web`，没有 JSON message converter | Bearer 已附带但 `findTasks` 无法反序列化 JSON | `workflow-platform-sdk/pom.xml:23` | 改为 `spring-boot-starter-json`，真实 loopback HTTP 测试同时验证 Bearer 与 JSON |

## Rejected Suspicions

| Suspicion | Why rejected | Evidence |
| --- | --- | --- |
| listener 外层事务 + 发起 `REQUIRES_NEW` 会制造重复流程 | inbox 回滚后的 Kafka 重投会按四元组唯一键命中已提交实例，不再新建 | `ProcessApplicationService` 幂等路径、`MessagingTransactionContractTest`、既有并发发起测试 |
| DLQ broker 失败后仍可能标成 `REPLAYED` | 行锁、send future `.get(10s)` 与状态更新位于同一事务；异常在更新前抛出并触发回滚 | `DlqReplayService.java:46`、`DlqReplayServiceTest#replay_sendFailure_doesNotMarkReplayed` |
| JWT 模式省略 tenant Header 会绕过租户校验 | Header 仅为可选传输方式；配置 tenant claim 后 claim 缺失或 Header 冲突均 403 | `WorkflowIdentityResolver` 与 `WorkflowSecurityChainTest` |

## Checks Rerun After Fixes

- `mvn -B --no-transfer-progress test`：BUILD SUCCESS；protocol 6、SDK 4、server 50（其中外部 PG 条件测试 2 skipped）。
- `pnpm test && pnpm build`：57/57 通过，Vite production build 成功。
- Compose config、CI YAML 解析、全部 deploy shell `bash -n`、`git diff --check`：通过。

## Residual Risks

- 本机 Docker daemon 不可用，5 个 Testcontainers PostgreSQL 迁移/仓储用例未在本机运行；GitHub CI 的 backend job 会在 Docker runner 上执行。
- `preferred_username` 必须与 Flowable assignee/userId 对齐；正式接 IdP 前需用真实 token 验证映射。
- Kafka DLQ 表本批仍是全局 ADMIN 域，没有 tenant 字段；这是已批准的 non-goal。
- 任务列表目前在租户结果上做服务端内存授权过滤，数据隔离正确但大租户下需后续下推查询与压测。

## Verdict

conditional-pass
