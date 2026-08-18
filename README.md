# workflow-platform · 统一流程/审批中台

> 内部统一的**流程/审批中台**:以 Flowable(BPMN)承载流程编排,消费方业务系统通过 **SDK / Kafka 契约**接入,不感知引擎内部类型。
> 架构模式 = **中台编排(Flowable)+ 消费方 outbox 发起 + 中台请求业务落实人工决定 + 消费方回执**(跨系统最终一致)。

- **技术栈**:Java 21 · Spring Boot 3.3.5 · Flowable 7.1.0(BOM 统一)· PostgreSQL · Kafka · Redis · React 18 + Vite(前端)
- **坐标**:`com.lrj.workflow:workflow-platform:0.1.0-SNAPSHOT`(多模块 Maven reactor)
- **状态**:功能基线与第一批生产加固已落地；正式投产前仍需在目标环境完成多副本压测、故障演练、备份恢复与外部监控接入。详见 [`docs/ROADMAP.md`](docs/ROADMAP.md)

---

## 1. 这是什么 / 解决什么

关键业务动作需要**人工审批/复核**,且该决定要**异步、可靠、幂等**地落实回业务系统时,用本中台。首个试点是 HIS **审方**(`hisRxReview`):门诊处方由药师复核,通过/驳回的决定经 Kafka 最终一致地回到 his 业务系统。

同一套骨架可复用于:退费审批、贵重药品审批、危急值确认、放款/风控审批、退款退货、权限授予审批、变更管理 CAB 等——每个新场景 = 部署一份 BPMN + 消费方写发起/落地适配器(可靠消息/幂等/鉴权/指标/审计/运维/DLQ 全复用)。新流程接入配方见 [`docs/onboarding-new-process.md`](docs/onboarding-new-process.md)。

**不适合**:纯自动化高吞吐编排、强实时(本架构秒级最终一致)、复杂人工协同工作流。

## 2. 架构一览

```
消费方业务系统(如 his)                      workflow-platform
┌───────────────────┐   command.start.v1   ┌──────────────────────────────────┐
│  业务写库 + outbox │ ───────Kafka───────► │ server :8300(运行时)             │
│                   │                       │  ├ Kafka 监听:start/applied/dlq  │
│  inbox + 业务副作用│ ◄──action.requested── │  ├ Flowable 引擎 + async executor │
│                   │                       │  ├ OutboxPublisher(@Scheduled)   │
│  outbox(ACK)      │ ──action.applied.v1─► │  ├ REST:tasks/process/definitions│
└───────────────────┘                       │  └ REST:admin/dlq(ADMIN 门控)   │
                                             ├──────────────────────────────────┤
待办人 ─ REST/SDK ─► tasks/办理(202 最终一致)│ admin :8301(定义/租户管理,不跑作业)│
运维/药师 ─► workflow-console(待办/轨迹/运维/设计器)                            │
                                             │  PostgreSQL(ACT_* + wf_*)· Kafka │
                                             └──────────────────────────────────┘
```

- **发起 / 落地回执走 Kafka**(与业务写库同事务、最终一致):`workflow.command.start.v1`、`workflow.action.applied.v1`。
- **查询 / 办理走 REST/SDK**(需即时反馈):查待办、办理(通过/驳回)、查实例/轨迹。
- **办理是"已受理"而非"已完成"**:`complete-review` 恒返回 **202 `PENDING_BUSINESS`**,业务落地经 Kafka 异步最终一致,UI/调用方不得呈现为"已完成"。

完整内部设计(可靠消息、幂等、生命周期、数据模型、安全、可观测、运维 API)见 **[`docs/architecture.md`](docs/architecture.md)**。

## 3. 模块地图

| 模块 | 产物 | 职责 | 关键类 / 资源 |
|---|---|---|---|
| **workflow-platform-protocol** | jar(对外) | Published Language:事件契约 record + REST DTO + 主题常量;`CONTRACT_VERSION=1` | `event.EventEnvelopeV1` / `StartProcessCommandV1` / `WorkflowActionRequestedV1` / `WorkflowActionAppliedV1` / `WorkflowTopics` / `api.*View` |
| **workflow-platform-core** | jar | 领域/应用服务 + 数据访问 + Flyway 迁移 + 试点 BPMN | `ProcessApplicationService`(幂等发起)、`TaskApplicationService`、`ProcessQueryService`、`MessageCorrelationService`(ACK 关联)、`outbox/inbox/dlq/link` 仓储、`db/migration/V*.sql`、`bpmn/his-rx-review-v1.bpmn20.xml` |
| **workflow-platform-sdk** | jar(对外) | 消费方接入门面(Spring Boot Starter);默认 `enabled=false` 注入 Noop | `WorkflowClient`(findTasks/completeReview/claimTask/reassignTask)、`RemoteWorkflowClient`、`WorkflowSdkAutoConfiguration` |
| **workflow-platform-server** | 可执行 jar / 镜像(**:8300**) | **运行时服务**:Flowable 引擎 + REST + Kafka 监听 + outbox 投递 + 安全 + 指标/审计 | `web/*Controller`、`kafka/Workflow*Listener`、`outbox/OutboxPublisher`、`correlation/CorrelationRetryJob`、`security/SecurityConfig`、`metrics/WorkflowMetrics`、`BpmnAutoDeployer` |
| **workflow-platform-admin** | 可执行 jar / 镜像(**:8301**) | 定义/租户管理服务;`async-executor` 关闭(不跑运行时作业),与 server 同库 | `WorkflowPlatformAdminApplication`(扫 `com.lrj.workflow` 复用 core) |
| **workflow-console** | 静态站(nginx **:8302** / dev :5373) | 前端管控台:待办中心 / 流程轨迹 / 运维面板 / 可视化设计器 / SSO 登录 | 见 [`workflow-console/README.md`](workflow-console/README.md) |
| **deploy** | — | 全栈 compose、后端多阶段 Dockerfile、迁移/冒烟脚本、Prometheus 告警规则 | 见 [`deploy/README.md`](deploy/README.md) |

> **注意**:运维/定义部署的**后端接口目前落在 `server` 模块**(`/api/v1/admin/**`),`admin` 模块目前是承载定义/租户管理的独立进程骨架(async executor 关闭)。找运维接口实现请到 `workflow-platform-server`,不是 `admin`。

## 4. 能力清单

| 能力 | 现状 | 入口 |
|---|---|---|
| 幂等发起(四元组唯一 + 并发收敛) | ✅ | Kafka `command.start.v1` → `ProcessApplicationService` |
| 人工待办查询 / 办理(202 最终一致) | ✅ | REST `/api/v1/tasks`、`/tasks/{id}/complete-review` |
| 任务操作:认领 / 转办 / 委派 / 撤回 | ✅ | REST `/tasks/{id}/{claim,reassign,delegate,unclaim}`(加签/会签需带 MI 的流程) |
| 业务落地 ACK 关联并推进(早到重试不丢) | ✅ | Kafka `action.applied.v1` → `MessageCorrelationService` + `CorrelationRetryJob` |
| 死信兜底 + 重放(毒消息/超限失败) | ✅ | `workflow.dlq.v1` → `wf_dlq_event`;REST `/api/v1/dlq`(列/单条/批量重放) |
| 运维:实例挂起/恢复/终止、incident、死信作业重试 | ✅ | REST `/api/v1/admin/**`;前端 `/ops` |
| 流程定义部署 / 版本管理(粘贴 XML) | ✅ | REST `/api/v1/admin/definitions`;前端 `/ops`「流程定义」Tab |
| 可视化流程设计器(bpmn-js 拖拽 + 部署) | ✅ | 前端 `/designer`(复用 deploy 端点) |
| SLA / 超时升级(非阻塞边界定时器) | ✅ | 能力经 `TimerEscalationSpikeTest` 坐实(需带定时器的 BPMN) |
| 可观测性:Prometheus 指标 / 结构化审计 / 生命周期事件 | ✅ | `/actuator/prometheus`、`WORKFLOW_AUDIT` logger、`workflow.lifecycle.v1` |
| 鉴权与租户隔离:JWT issuer/audience/tenant 校验、精确角色、任务服务端授权、prod fail-fast | ✅ 第一批加固 | `workflow.security.*`;详见 [接入指南 §4.3](docs/integration-guide.md) |
| Kafka 入站契约/source→tenant 边界 | ✅ 第二批加固 | v1 信封/payload 校验 + 每 source HMAC + tenant allowlist；生产仍需 broker SASL/TLS/ACL |
| HA / 水平扩展(outbox SKIP LOCKED + fencing CAS + 有界重试、inbox 去重) | ✅ 代码就绪(集群压测需负载环境) | 见 [`deploy/README.md`](deploy/README.md) |
| 轨迹版本正确性 | ✅ | 默认选最新实例；老实例按实际 `processDefinitionId` 渲染 BPMN |
| 契约门禁(跨仓库兼容性) | ✅ | `ContractGoldenTest`(protocol);改对外字段即 `mvn test` 失败 |
| DB 迁移版本化 + 干净库一键建表 | ✅ | Flyway(`wf_*`)+ 固化 Flowable DDL(`ACT_*`);见 [ADR 0001](docs/adr/0001-flowable-version-and-schema.md) |

## 5. 快速开始

### 5.1 本地开发(单机,可关鉴权直连联调)

```bash
# 前置:PostgreSQL(库 workflow)、Kafka、Redis 可用;JAVA_HOME 指向 21
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -q -DskipTests install                      # 构建全 reactor,装本地仓库(供 sdk/protocol 复用)

# 运行时服务 :8300(dev 默认 workflow.security.enabled=false,走明文 X-Workflow-Tenant 头)
mvn -pl workflow-platform-server spring-boot:run
# (可选)定义/租户管理服务 :8301
mvn -pl workflow-platform-admin spring-boot:run

# 前端管控台(dev :5373,/api 反代到 :8300)
cd workflow-console && pnpm install && cp .env.example .env.local && pnpm dev
```

启动后:待办中心 `/tasks`、流程轨迹 `/process/hisRxReview`、运维面板 `/ops`、设计器 `/designer`。
需要 tenant=`his` 的审方待办才有数据;`server` 启动会自动部署试点 BPMN `hisRxReview`(`workflow.pilot.auto-deploy=false` 可关)。

### 5.2 一键全栈(Docker Compose)

```bash
cd deploy && cp .env.example .env
docker compose -p workflow-platform up -d --build      # PostgreSQL + Redis + Kafka + server + admin + console
curl -s localhost:8300/actuator/health                  # {"status":"UP"}
```

端口/开关/迁移/HA/监控细节见 **[`deploy/README.md`](deploy/README.md)**。前端入口为 `http://localhost:8302/login`。

## 6. 测试与门禁

```bash
mvn -q test                          # 后端:协议 golden / Flowable spike / 审方回环集成 / Controller Web / 迁移冒烟
cd workflow-console && pnpm test     # 前端:Vitest 组件/hook;pnpm e2e 为 Playwright 冒烟
```

- **契约门禁** `ContractGoldenTest`(protocol):钉死每个对外 record 的顶层字段集;任何增/删/改名都会失败,强制显式版本化——CI 即跨仓库契约守门。
- **数据库安全门禁**:审方回环测试只使用 Testcontainers 随机 PostgreSQL，清理前还会验证专用 test 数据库/用户；不会探测或清理本地 Compose 库。Docker 不可用时相关真 PG 测试明确跳过。
- **持续集成** `.github/workflows/ci.yml`:后端全 reactor 测试、前端单测与生产构建、Compose 模型及部署脚本语法校验。

## 7. 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | **内部架构 / 能力参考**:模块职责、可靠消息与幂等、生命周期、数据模型、鉴权、可观测、运维 REST API |
| [`docs/integration-guide.md`](docs/integration-guide.md) | **消费方接入指南**:Kafka 事件契约、SDK/REST、发起/落地代码骨架、JSON 样例、Nexus 发布 |
| [`docs/onboarding-new-process.md`](docs/onboarding-new-process.md) | 新流程接入配方(以审方为模板,含中台/消费方/前端步骤 + checklist + 成本) |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | 生产化路线图:现状盘点、缺口评估、分期执行、里程碑 |
| [`docs/adr/0001-flowable-version-and-schema.md`](docs/adr/0001-flowable-version-and-schema.md) | ADR:Flowable 版本锁定与 schema 管理策略 |
| [`workflow-console/README.md`](workflow-console/README.md) | 前端管控台:待办/轨迹/运维/设计器/SSO 登录、鉴权分期 |
| [`deploy/README.md`](deploy/README.md) | 部署:compose 全栈、HA/扩展、监控告警、schema 与迁移 |
| [`docs/doc-map.md`](docs/doc-map.md) | 代码区域 ↔ 文档 映射 + 同步点(由 `/doc-sync` 维护) |
