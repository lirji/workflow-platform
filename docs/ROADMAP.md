# workflow-platform 生产化路线图

> 现状评估 + 分期执行线路。目标:从"审方试点 shadow 跑通"演进到"企业级生产可用的通用审批中台"。
> 评估基于代码实况(截至 2026-08-15,commit `90ba313`)。

## 1. 现状盘点(已实现骨架)

架构模式 = **中台编排(Flowable)+ 消费方 outbox 发起 + 中台请求业务落实人工决定 + 消费方回执**(跨系统最终一致)。

- 可靠消息:事务 outbox(`OutboxPublisher` @Scheduled 轮询 + 批量 claim/租约)+ inbox 幂等(`eventId`)+ 消息关联(`MessageCorrelationService` + `CorrelationRetryJob`)
- 流程生命周期:`ProcessLink` + `ProcessPhase`(WAITING_USER/WAITING_BUSINESS/COMPLETED/INCIDENT/CANCELLED)
- REST + SDK:待办查询/办理/实例/轨迹/定义 XML;`BpmnAutoDeployer` 启动自动部署
- 多租户管道:tenant 贯穿(46 处),来源为明文头
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
| **流程建模** | 无设计器(下一轮 Option B) |
| **数据/合规** | 无归档/留存;变量白名单为约定非强制;无 PII 脱敏 |

## 3. 业务能力图(现有架构能支撑什么)

**甜点区** = 关键业务动作需人工审批/复核,且决定需异步、可靠、幂等地落实回业务系统。

- ✅ **直接能接**(各自建 BPMN + 消费方适配器):审方(已做)/退费审批/贵重药品审批/危急值确认;大额交易/放款审批、风控研判、对账差异处置;退款退货、大额券发放、采购报销;权限授予审批(与 auth-platform 同构)、变更管理 CAB。
- ⚠️ **补能力后可接**:合同会签(补会签)、带 SLA 的审批(补定时器)、需认领/转办的协作任务(补任务操作 SDK)。
- ❌ **不适合**:纯自动化高吞吐编排;强实时(本架构秒级最终一致);复杂人工协同工作流。

## 4. 执行线路(分期)

> 原则:每期可独立交付且可回滚;不破坏现有 shadow 联调(明文头路径保留,新能力用开关渐进启用)。

### 阶段一 · 生产化基线(P0)—— 当前进行
| 序 | 工作项 | 关键设计 | 验收 |
|---|---|---|---|
| 1.1 ✅ | **服务端 Casdoor JWT 鉴权**(已完成) | OAuth2 Resource Server 校验 Casdoor JWT;开关 `workflow.security.enabled`(默认 false 保 shadow 联调);启用时 tenant/actor **从 JWT 派生**覆盖明文头/请求体;groups claim 归一化为权限 | ✅ 关=12 测试全绿;开=无 token 401、带 JWT 200、actor 由 JWT 派生覆盖伪造请求体 |
| 1.2 ✅ | **DLQ 消费 + 重放**(已完成) | DefaultErrorHandler 重试超限 → recoverer 投 `workflow.dlq.v1`;DlqListener 落库 `wf_dlq_event`;DlqReplayService + `/api/v1/dlq`(列/重放/批量重放),重放投回原 topic 由原监听幂等消费 | ✅ 单元/切片测试全绿;V2 迁移在真库应用通过。DLQ 路由本身走 compose 冒烟(与既有 Kafka 测试策略一致) |
| 1.3 | **后端 Dockerfile + compose 补全** | server/admin 镜像;compose 补 kafka/nacos(或文档化外部依赖) | `docker compose up` 起全栈 |
| 1.4 | **DB 迁移版本化** | 自有表(outbox/inbox/process_link)+ Flowable schema 迁移策略 | 干净库一键建表 |

### 阶段二 · 运维闭环(P1)
- 2.1 admin 运维面板:实例查询/挂起/终止/重试、**incident 处置**、DLQ 重放
- 2.2 可观测性:消费 `lifecycle.v1` + Micrometer/Prometheus 指标 + 结构化审计日志 + 关键告警
- 2.3 HA:outbox 多副本压测、Flowable 异步执行器调优、多副本部署验证
- 2.4 契约治理:跨仓库契约 CI 测试(golden 共享)

### 阶段三 · 多流程扩展(业务验证)
- 3.1 接 2~3 个新审批场景(如退费、权限授予),量化"新流程 onboarding"成本
- 3.2 沉淀流程模板 + 消费方适配脚手架

### 阶段四 · 流程能力增强(P2)
- 4.1 任务操作 SDK:认领/转办/加签/会签/撤回
- 4.2 SLA/超时/定时器升级 + 边界事件
- 4.3 流程设计器(Option B)

## 5. 里程碑与顺序依赖

```
阶段一(P0 基线) ──► 阶段二(运维闭环) ──► 阶段四(能力增强)
       │                                        ▲
       └────────► 阶段三(多流程验证) ───────────┘
```
- 1.1 鉴权是其余一切的前置(生产不可无鉴权)。
- 阶段三可在阶段一完成后并行启动(用真实业务压出阶段二/四的优先级)。

---

**当前执行位置**:阶段一 · 1.1 ✅ + 1.2 ✅ 已完成 → 下一步 1.3 后端 Dockerfile + compose 补全。

> 1.2 落地说明:`workflow.dlq.max-attempts`(默认 3)/`backoff-ms`。超限入 `workflow.dlq.v1` → `wf_dlq_event` 落库;
> 运维 REST `GET /api/v1/dlq`、`POST /api/v1/dlq/{id}/replay`、`POST /api/v1/dlq/replay-all`。DLQ 面板留 P1(2.1)。

> 1.1 落地说明:`workflow.security.enabled` 开关(默认 false)。启用需配 `WORKFLOW_OIDC_JWKS`(或 `WORKFLOW_OIDC_ISSUER`);
> 租户派生需配 `WORKFLOW_TENANT_CLAIM`(未配则仍取 `X-Workflow-Tenant` 头,不臆造 Casdoor 租户映射)。
> 与前端 `VITE_AUTH_ENABLED` 分期对齐。
