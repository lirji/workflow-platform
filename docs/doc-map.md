# Doc Map（由 /doc-sync 维护）

lastSyncedCommit: a9bda5d
lastSyncedAt: 2026-08-17

> 代码区域 ↔ 文档 映射 + 上次同步点。之后每次 `/doc-sync` 据 `lastSyncedCommit..HEAD` 的变更范围做**增量**同步。

## 映射

| 代码区域 / 模块 | 相关文档 | 类型 | 说明 |
|---|---|---|---|
| 全局 / 所有模块 | `README.md` | 概览 | 项目简介、架构一览、模块地图、能力清单、快速开始、文档索引 |
| 全局(内部设计) | `docs/architecture.md` | 架构 | 模块职责、可靠消息与幂等、生命周期、数据模型、鉴权、可观测、运维 REST API |
| `workflow-platform-protocol/**` | `docs/integration-guide.md`（§3/§8/附录 A）、`docs/architecture.md`（§2） | 契约 | 事件/DTO record、主题常量、JSON 样例;`ContractGoldenTest` 门禁 |
| `workflow-platform-core/**` | `docs/architecture.md`（§4–§6）、`docs/integration-guide.md`（§5 骨架） | 架构 | 幂等发起、outbox/inbox、ACK 关联、`wf_*` 数据模型、试点 BPMN |
| `workflow-platform-sdk/**` | `docs/integration-guide.md`（§4.1） | API | `WorkflowClient` 门面、自动装配、Nexus 发布 |
| `workflow-platform-server/web/**` | `docs/integration-guide.md`（§4.2 消费方接口）、`docs/architecture.md`（§9 运维 API） | API | REST 端点参考(tasks/process/definitions/admin/dlq) |
| `workflow-platform-server/security/**` | `docs/integration-guide.md`（§4.3）、`docs/architecture.md`（§7） | 架构 | Casdoor JWT 分期鉴权、groups 归一化、ADMIN 门控 |
| `workflow-platform-server/{kafka,outbox,correlation}/**` | `docs/architecture.md`（§4） | 架构 | 监听/投递/关联重试等运行时机制 |
| `workflow-platform-server/{metrics,audit}/**` + `kafka/LifecyclePublisher` | `docs/architecture.md`（§8）、`deploy/README.md`（监控与告警） | 架构 | Prometheus 指标、审计、生命周期事件、告警规则 |
| `workflow-platform-admin/**` | `docs/architecture.md`（§2/§3） | 架构 | 定义/租户管理服务(async 关) |
| `workflow-console/**` | `workflow-console/README.md` | 概览+架构 | 前端管控台:待办/轨迹/运维/设计器/SSO 登录、鉴权分期 |
| `deploy/**` + `core/db/migration/**` | `deploy/README.md`、`docs/adr/0001-flowable-version-and-schema.md` | 部署+ADR | compose 全栈、HA/扩展、schema 与迁移策略 |
| 路线图 / 缺口 / 分期 | `docs/ROADMAP.md` | 概览 | 现状盘点、缺口评估、执行线路、里程碑 |
| 新流程接入 | `docs/onboarding-new-process.md` | 指南 | 以审方为模板的新流程接入配方 |
| 架构决策 | `docs/adr/*.md` | ADR | Flowable 版本锁定与 schema 管理(0001) |
| 实施计划(历史) | `docs/plans/**/{FINAL_PLAN,DECISION_RECORD}.md` | 计划 | frontend-plan 产出的特性计划与决策记录(只增不改) |
