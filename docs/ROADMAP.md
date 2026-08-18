# workflow-platform 生产化路线图

> 现状评估 + 分期执行线路。目标:从"审方试点 shadow 跑通"演进到"企业级生产可用的通用审批中台"。
> 评估基于代码实况(截至 2026-08-15,commit `90ba313`)。

## 1. 现状盘点(已实现骨架)

架构模式 = **中台编排(Flowable)+ 消费方 outbox 发起 + 中台请求业务落实人工决定 + 消费方回执**(跨系统最终一致)。

- 可靠消息:事务 outbox(`OutboxPublisher` @Scheduled 轮询 + 批量 claim/租约)+ inbox 幂等(`eventId`)+ 消息关联(`MessageCorrelationService` + `CorrelationRetryJob`)
- 流程生命周期:`ProcessLink` + `ProcessPhase`(WAITING_USER/WAITING_BUSINESS/COMPLETED/INCIDENT/CANCELLED)
- REST + SDK:待办查询/办理/实例/轨迹/定义 XML;`BpmnAutoDeployer` 启动自动部署
- 多租户管道:tenant 贯穿；dev 来源为明文头，生产由 JWT tenant claim 决定并校验资源归属
- 一个流程:审方 `hisRxReview`;前端 workflow-console(待办中心 + 轨迹)
- 测试:协议 golden / Flowable spike / BPMN 模型 / 审方回环集成 / Controller Web

## 2. 缺口评估(按优先级)

### P0 — 上生产前必须补
| 缺口 | 现状 | 影响 |
|---|---|---|
| **服务端鉴权** | 无 Spring Security / JWT 校验;租户+办理人靠明文头/请求体 | 可冒充任意租户/办理人。**安全红线** |
| **DLQ 处理** | `WorkflowTopics.DLQ` 仅常量,无生产/消费 | 毒消息/超限失败无兜底、无重放 |
| **后端部署件** | 仅 console 有 Dockerfile | 后端无镜像/编排;compose 仅 postgres+redis |
| **DB 迁移** | 未见 Flyway/Liquibase | Flowable schema + 自有表迁移需版本化 |

### P1 — 企业级运维/治理
| 缺口 | 现状 |
|---|---|
| **admin 空壳** | 仅 Application + yml;缺运行时部署/版本管理、实例运维、incident 处置、DLQ 重放 |
| **可观测性** | `lifecycle.v1` 仅常量未消费;无 metrics/tracing/审计/告警 |
| **HA/伸缩** | outbox 单机轮询(有 owner+lease 待压测);Flowable 异步执行器未调优;无多副本验证 |
| **契约治理** | 无 schema registry;跨仓库契约无 CI 兼容性测试 |

### P2 — 流程能力广度
| 缺口 | 现状 |
|---|---|
| **任务操作** | SDK 仅 findTasks/completeReview;无认领/转办/加签/会签/撤回 |
| **高级流程** | 无超时/定时器升级、SLA、边界事件、动态候选人、多级审批、表单 |
| **流程建模** | ✅ 已补:`/designer` 可视化 Modeler + 属性面板 + 部署(4.4) |
| **数据/合规** | 无归档/留存;变量白名单为约定非强制;无 PII 脱敏 |

## 3. 业务能力图(现有架构能支撑什么)

**甜点区** = 关键业务动作需人工审批/复核,且决定需异步、可靠、幂等地落实回业务系统。

- ✅ **直接能接**(各自建 BPMN + 消费方适配器):审方(已做)/退费审批/贵重药品审批/危急值确认;大额交易/放款审批、风控研判、对账差异处置;退款退货、大额券发放、采购报销;权限授予审批(与 auth-platform 同构)、变更管理 CAB。
- ⚠️ **补能力后可接**:合同会签(补会签)、带 SLA 的审批(补定时器)、需认领/转办的协作任务(补任务操作 SDK)。
- ❌ **不适合**:纯自动化高吞吐编排;强实时(本架构秒级最终一致);复杂人工协同工作流。

## 4. 执行线路(分期)

> 原则:每期可独立交付且可回滚;不破坏现有 shadow 联调(明文头路径保留,新能力用开关渐进启用)。

### 阶段一 · 生产化基线(P0)—— ✅ 全部完成
| 序 | 工作项 | 关键设计 | 验收 |
|---|---|---|---|
| 1.1 ✅ | **服务端 JWT 鉴权**(已完成并加固) | OAuth2 Resource Server 校验 issuer/audience/时效；prod fail-fast；tenant claim fail-closed；精确角色与服务端任务授权 | ✅ 无 token 401、租户缺失/冲突 403、角色防提升、actor 防伪造、任务候选/assignee 回归 |
| 1.2 ✅ | **DLQ 消费 + 重放**(已完成) | DefaultErrorHandler 重试超限 → recoverer 投 `workflow.dlq.v1`;DlqListener 落库 `wf_dlq_event`;DlqReplayService + `/api/v1/dlq`(列/重放/批量重放),重放投回原 topic 由原监听幂等消费 | ✅ 单元/切片测试全绿;V2 迁移在真库应用通过。DLQ 路由本身走 compose 冒烟(与既有 Kafka 测试策略一致) |
| 1.3 ✅ | **后端 Dockerfile + compose 补全**(已完成) | `deploy/Dockerfile` 多阶段(build 全 reactor → server/admin 双 target);compose 补 Kafka(KRaft 单节点)+ server(8300)+ admin(8301),healthcheck/depends_on 编排;端口全变量化 | ✅ `docker compose config` 通过 + `docker build server/admin` 全 reactor 在容器内构建成功。`up` 起栈步骤见 `deploy/README.md`(未在本机跑以免抢占运行中的 shadow :8300) |
| 1.4 ✅ | **DB 迁移版本化**(已完成) | `wf_*` 由 Flyway(V1–V5，含 inbox retry lease、outbox last_error/状态回填、DLQ 签名与外部字段扩容)；Flowable ACT_* 由 `WORKFLOW_FLOWABLE_SCHEMA_UPDATE` 开关(dev=true 引擎自建 / 生产=false 用固化官方 DDL)；compose 把 DDL 挂 initdb 实现干净库一键建表 | ✅ PostgreSQL 迁移测试覆盖 7 张表、V3→V5 升级、唯一约束、状态映射及 outbox/inbox 领取 SQL；compose config 门禁 |

### 阶段二 · 运维闭环(P1)—— 进行中
- 2.1 admin 运维面板 ✅(后端 + 前端)
  - **后端 ✅**:`/api/v1/admin` 实例查询/incident/挂起/恢复/终止、死信作业列/重试;`/api/v1/admin/**`+`/api/v1/dlq/**` 需 ADMIN。companion:`ProcessInstanceView.suspended` + Flowable NotFound→404。
  - **前端 ✅**(经 frontend-plan `docs/plans/workflow-console-ops-0815-1938/`):`/ops` 单页 Tabs(实例运维含 phase/INCIDENT 筛选 + 挂起/恢复/终止[reason 必填]/看轨迹;死信作业列/重试;DLQ 列/单条+批量重放),ADMIN 门控(dev 逃生门)、诚实异步文案、爆发式刷新。28 Vitest + Playwright 冒烟 + 真机门控/结构验证。**注**:数据需 :8300 部署含 admin 端点的新代码(当前 shadow 实例为旧代码)。
- 2.2 可观测性/审计 —— **指标 + 审计 ✅**
  - **Prometheus 指标 ✅**:micrometer-registry-prometheus + `WorkflowMetrics`(process.started/review.completed/action.applied/correlation.outcome/dlq.landed/dlq.replayed/deadletter.retried/admin.op),埋点在 server 层 listener/controller/service(不侵入 core);`/actuator/prometheus` 暴露。
  - **结构化审计 ✅**:`WorkflowAudit`(独立 logger WORKFLOW_AUDIT)记审方完成/运维干预/DLQ 重放。
  - **待做**:lifecycle.v1 事件生产(供外部观察者)、分布式追踪、关键告警规则。
- 2.4 契约治理 ✅:`ContractGoldenTest`(protocol)钉死所有对外 record(events + API DTOs)顶层字段集,改字段即 `mvn test` 失败→强制版本化;CI 即跨仓库契约门禁。接入指南 §7 已记。
- 2.3 HA ✅(代码+文档;真机集群压测需负载环境):outbox 已 `FOR UPDATE SKIP LOCKED`+租约、inbox eventId 去重、Flowable 作业 ACT_RU_JOB 锁、发起四元组唯一——多副本天然安全;Flowable 异步执行器线程池外部化可调(`WORKFLOW_ASYNC_*`);deploy/README 记 HA 机制/扩展方式/压测方案。
- 2.2 可观测性:消费 `lifecycle.v1` + Micrometer/Prometheus 指标 + 结构化审计日志 + 关键告警
- 2.3 HA:outbox 多副本压测、Flowable 异步执行器调优、多副本部署验证
- 2.4 契约治理:跨仓库契约 CI 测试(golden 共享)

### 阶段三 · 多流程扩展(业务验证)—— 平台侧就绪 ✅
- 3.1/3.2 ✅(平台侧):onboarding 配方 `docs/onboarding-new-process.md`(以审方为模板,含中台/消费方/前端步骤 + checklist + 成本);中台侧新流程 = 部署一个 BPMN(运维面板/REST/classpath),可靠消息/幂等/鉴权/指标/审计/运维/DLQ 全复用。**live 新场景**(退费/权限授予的真实端到端)需消费方 repo 的发起+落地适配 + 前端待办中心 definitionKey 参数化(backlog 小改)——落在消费方仓库,不在本仓库。

### 阶段四 · 流程能力增强(P2)—— 基本完成
- 4.1 任务操作 ✅(认领/转办/委派/撤回):core + REST `/tasks/{id}/{claim,reassign,delegate,unclaim}` + SDK + 审计。**加签/会签**需 multi-instance BPMN(审方无 MI),留待带 MI 流程模板。
- 4.2 SLA/超时 ✅:非阻塞边界定时器超时升级能力经 `TimerEscalationSpikeTest` 坐实(Flowable 7.1)。
- 4.3 流程定义管理 ✅(Option-B 后端 + lite 前端):`/api/v1/admin/definitions`(部署/列表/挂起/恢复 + 审计)+ 运维面板「流程定义」Tab(粘贴 XML 部署)。
- 4.4 可视化流程设计器 ✅(`/designer`,Scope C):bpmn-js Modeler 拖拽建模 + `bpmn-js-properties-panel` 完整属性面板(vanilla + 自定义 Flowable provider:candidateGroups/assignee/delegateExpression + 内联 moddle)+ 部署前校验 + 复用 deploy 端点(零后端改动)。走完 frontend-plan(`docs/plans/bpmn-designer-0815-2120/`)。**诚实边界**:可视化编辑+部署工具,产物无法从 console 独立跑实例(发起归消费方 Kafka `StartProcessCommandV1`、完成走审方端点、condition 仅 `decision` 变量);带 outbox/ACK 的流程仍走克隆 XML 路径。

## 5. 里程碑与顺序依赖

```
阶段一(P0 基线) ──► 阶段二(运维闭环) ──► 阶段四(能力增强)
       │                                        ▲
       └────────► 阶段三(多流程验证) ───────────┘
```
- 1.1 鉴权是其余一切的前置(生产不可无鉴权)。
- 阶段三可在阶段一完成后并行启动(用真实业务压出阶段二/四的优先级)。

---

**当前执行位置**:功能路线图与第二批 P0 正确性加固已落地：除第一批安全能力外，集成测试改为独占 Testcontainers；outbox 加 lease fencing CAS/有界发送/FAILED 与 DELIVERY_UNKNOWN；Kafka 加 v1 契约、per-source HMAC 和 source→tenant 边界；phase/status 同步；轨迹按最新实例及实例实际 BPMN 版本展示。

**剩余(非本仓库可完结)**:
- ~~**可视化流程设计器**(完整拖拽 Modeler)~~ ✅ 已交付(4.4,`/designer`)。
- **分布式追踪**:留作配置接入——生产接 OTLP 后端时加 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` + `management.tracing.sampling`,Spring Boot observation 自动传播 HTTP/Kafka trace(不预埋以免无后端时空跑)。
- **告警**:规则已给(`deploy/prometheus/alerts.yml`),接入需生产 Prometheus/Alertmanager。
- **live 多流程场景 + 加签/会签**:需消费方 repo 适配 / 带 MI 的新流程模板。
- **投产环境验证**:多副本容量压测、Kafka/PG 故障与恢复演练、备份恢复/RPO-RTO 验证、密钥轮换、外部 SLO/值班告警闭环。

> P0 落地摘要:服务端 Casdoor JWT 鉴权(开关渐进)、DLQ 兜底+重放、后端镜像+compose 全栈、Flowable/wf_* 迁移版本化与干净库一键建表。全栈 `docker compose up` 未在本机执行(避免与运行中的 shadow :8300 抢端口),已过 config/build/迁移冒烟验证。详见 `deploy/README.md`。

> 1.2 落地说明:`workflow.dlq.max-attempts`(默认 3)/`backoff-ms`。超限入 `workflow.dlq.v1` → `wf_dlq_event` 落库;
> 运维 REST `GET /api/v1/dlq`、`POST /api/v1/dlq/{id}/replay`、`POST /api/v1/dlq/replay-all`。DLQ 面板留 P1(2.1)。

> 1.1 落地说明:`workflow.security.enabled` 开关(默认 false)。生产 `prod` profile 要求同时配置 issuer、audience 与 tenant claim；
> tenant claim 为可信来源，缺失或请求头不一致时 fail-closed。组权限精确匹配，不再按下划线截断。
> 与前端 `VITE_AUTH_ENABLED` 分期对齐。
