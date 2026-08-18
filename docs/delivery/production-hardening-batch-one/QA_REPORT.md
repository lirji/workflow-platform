# QA Report

## Environment Profile

- Target: 本地工作区，`main` 未提交差异。
- Version or commit: `0.1.0-SNAPSHOT`，Java 21、Maven reactor、pnpm/Vitest/Vite。
- Services and dependencies: H2 Flowable 集成测试与 loopback HTTP；未访问生产或外部测试环境。
- Test data: 测试内创建的 BPMN、任务、JWT、DLQ mock 与临时 HTTP server。
- Known environment limitations: Docker daemon 不可用；localhost:25432 PostgreSQL 当前不可达；未提供真实 IdP/Kafka/多副本环境。

## Cases

| ID | AC/Risk | Setup and steps | Expected | Actual/evidence | Verdict |
| --- | --- | --- | --- | --- | --- |
| QA-01 | AC-01 | 执行 `WorkflowProductionGuardTest` | 不安全 prod 配置拒绝，完整配置通过，非 prod 兼容 | 3/3 通过 | pass |
| QA-02 | AC-02/03 | JWT validator、identity、security chain 与前端 role tests | issuer/audience/tenant 强校验；`his_ADMIN` 不提权 | 后端 9 个相关用例与前端 role 用例通过；缺失/冲突 tenant 返回 403 | pass |
| QA-03 | AC-04 | H2 部署候选组任务，模拟 outsider/alice/bob；跑 Controller tests | 非候选拒绝、自己可原子认领、非 assignee 不可转办 | `FlowableSpikeTest#taskOperations...` 与 `TaskControllerTest` 通过 | pass |
| QA-04 | AC-05 | 服务层按错误 tenant 查询 timeline、挂起实例和定义 | 返回 empty/404，Flowable mutation 不执行 | `TenantIsolationServiceTest` 2/2、`DefinitionAdminServiceTest` 通过 | pass |
| QA-05 | AC-06 | SDK provider 动态换 token、缺 token、Noop write、loopback GET JSON | 每请求 Bearer；缺 token/误关写操作 fail-fast；JSON 可读 | `WorkflowSdkSecurityTest` 4/4 通过 | pass |
| QA-06 | AC-07 | 检查 listener `@Transactional` 与 TransactionTemplate propagation，跑回归 | inbox 与业务处理同事务；start 可安全独立提交并重入 | `MessagingTransactionContractTest` 2/2、完整 server 回归通过 | pass |
| QA-07 | AC-08 | V3 schema 与 outbox/inbox claim 仓储 PostgreSQL 测试 | lease 列存在，两个 claim SQL 可实际执行 | 测试已加入且编译；本机 Docker 不可用导致整类未执行 | blocked |
| QA-08 | AC-09 | Kafka send success/missing/already replayed/failure mock | 只有 broker ACK 后标记，失败保持 NEW | `DlqReplayServiceTest` 4/4 通过 | pass |
| QA-09 | AC-10 | `mvn -B --no-transfer-progress test` | 全 reactor 通过 | BUILD SUCCESS；60 项计入，58 pass、2 条外部 PG 条件测试 skipped；另有 5 条 Testcontainers case 未计入 | pass-with-limitation |
| QA-10 | AC-10 | `pnpm test && pnpm build` | 前端单测与生产构建成功 | 16 files、57/57；3627 modules build 成功 | pass |
| QA-11 | AC-10 | 解析 CI YAML、Compose config、shell syntax、diff whitespace | 配置可解析，无语法/空白错误 | 全部 exit 0 | pass |

## Defects And Retests

- 首次 SDK loopback GET 暴露缺 Jackson converter；改用 `spring-boot-starter-json` 后 4/4 通过。
- 首次前端全量回归发现 `authStore.test.ts` 仍期待 `his_admin` 提权；修正为 fail-closed 断言后 57/57 通过。
- review 发现 PostgreSQL claim SQL 与 ACK tenant 漏校验；修复后完成 backend 全量回归。

## Automated Regression

- Backend: full Maven reactor passed after final fixes。
- Frontend: Vitest 57/57 and Vite production build passed。
- Deployment config: Compose model、shell syntax、CI YAML parsing passed。
- 已存在的 React `act(...)`、React Router future flag、Ant Design deprecated-property warnings 不影响退出码，本批未修改对应交互组件。

## Blocked External Checks

- Testcontainers PostgreSQL 的 5 个 V1–V3/唯一约束/claim SQL 用例：Docker daemon 不可用。
- `RxReviewLoopTest` 2 个真 PostgreSQL 回环：localhost:25432 不可达。
- Docker 镜像实际 build、完整 Compose 启动、真实 Casdoor/JWKS/Kafka、双副本负载/故障注入、备份恢复：缺目标环境，未宣称通过。
- Playwright 依赖当前 live server 数据，基线已知不是 hermetic；本批无页面流程改动，未把其结果作为放行证据。

## Verdict

conditional-pass
