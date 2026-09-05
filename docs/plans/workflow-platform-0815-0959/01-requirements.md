# 流程/审批中台需求分析

## 1. 任务定义

新建独立 `workflow-platform`，技术基线锁定 Spring Boot 3.3.5、JDK 21、Flowable 7.x。项目形态与 `auth-platform` 同构：平台端提供运行时、管理端、协议模块和 Spring Boot Starter SDK，消费方只保留薄适配。`his-platform` 的药师审方与退费审批是首个 BPMN 试点。

本轮只产出可执行规划，不创建项目、不改业务代码。本文中的新类、表、接口均标记为“拟新增”，不是对现状的陈述。

## 2. 已锁定且不得推翻的 ADR

1. 引擎集中部署在新项目中；HIS 是消费方，不把 Flowable 嵌入 HIS。
2. 业务代码直接使用 Flowable API 的薄应用服务，不设计 `WorkflowEngine` 可插拔端口。
3. 跨系统业务动作以 Kafka 事件为主；UserTask 承载人工待办；只对真正短小的同进程动作使用 ServiceTask。
4. 身份复用 Casdoor，任务办理授权复用 auth-platform/SpiceDB；`workflow.authz.enabled` 默认 `false`，渐进启用。
5. 平台端口为 server 8300、admin 8301、console 5373/8302；独立 PostgreSQL 25432、Redis 26379。
6. Flowable `ACT_*` 与平台运行元数据只落 workflow 独立库；HIS 业务数据不复制到中台，只保存流程变量的必要快照和业务关联键。
7. Flowable tenantId 隔离 `his`、`auth`、`risk` 等业务线。
8. 审方迁移不得削弱 `Encounter.billed` 与 billing `invoice.encounter_id UNIQUE` 的双重计费防重。
9. workflow-console 的实现必须另走 `/frontend-plan`；本计划只定义后端契约、模块边界和占位。

## 3. 目标

- 建成能部署版本化 BPMN、发起/查询/办理任务、查询轨迹、转办与加签的通用中台。
- 以稳定 Published Language 隔离 Flowable 内部模型与消费方。
- 用可持久化 inbox/outbox 使“业务事务—Kafka—流程事务”达到至少一次投递下的最终一致和可重放。
- 将 HIS 的审批编排权逐步迁到 BPMN，业务状态、金额、订单与发票仍由原服务负责。
- 迁移后审方与退费的最终业务行为与既有记录一致，并增加并发、重复、乱序、宕机恢复验证。
- authz 关闭时保留平台基础身份/租户边界和 Flowable 候选人约束；开启后由 SpiceDB 对具体任务做最终授权。
- 形成可灰度、可影子比对、可回退至手写审批的双轨迁移路径。

## 4. 非目标

- 不替换 HIS 医嘱状态机；`OrderStateMachineService` 仍负责医嘱状态合法性。
- 不把 `Encounter`、`Order`、`Invoice`、`RefundRequest` 等业务聚合搬进 workflow 数据库。
- 不重写计费、缴费、退款或通知中心；BPMN 只接管“审批编排和待办”。
- 不承诺通用低代码表单、组织架构主数据、规则引擎、流程仿真或任意引擎切换。
- 不在本轮设计 workflow-console 的页面、交互和视觉稿。
- 不把 Redis 作为正确性依赖；流程真相以 PostgreSQL/Flowable 和持久化事件表为准。
- 不把 Kafka signal 广播直接映射为流程推进；有目标实例的回调必须使用 BPMN message correlation。

## 5. 已确认业务规则

### 5.1 药师审方

- `EncounterService.submitOrders` 只把 `DRUG` 医嘱送审，其他医嘱进入 `SUBMITTED`。
- 药嘱待审期间保持 `PENDING_REVIEW`；通过后为 `SUBMITTED`，驳回后为 `REJECTED`。
- 药师按 encounter 批量处理当前全部 `PENDING_REVIEW` 药嘱。
- 驳回后医生可对具体医嘱重新送审或作废；同一 encounter 同一时刻不能出现多个有效审方待办。
- 每次审批动作仍写 `prescription_review`，保留结果、意见、办理人和时间。
- `tryBill` 仅在不存在 `CREATED/PENDING_REVIEW/REJECTED` 且存在 `SUBMITTED` 时发单；迁移不能改变此守卫。
- `encounter.billed` 和发票 `encounter_id` 唯一约束必须继续存在；BPMN 的去重不能替代两者。
- 审方结果仍通知开方医生；通知是消息读模型，不是审批真相。

### 5.2 退费审批

- 仅 `PAID` 发票可申请退费。
- `RefundRequest` 仍由 billing 持有，状态维持 `REQUESTED -> APPROVED|REJECTED`。
- 通过必须在 billing 本地事务中同时更新申请和 `Invoice.refund()`；驳回不得改变发票。
- 通过后 `RefundReviewedEvent.orderIds` 驱动 outpatient 把仍为 `EXECUTED` 的医嘱幂等改为 `CANCELLED`。
- 同一退费申请只能成功办理一次；重复任务完成、重复 Kafka 消息和并发审批必须收敛到同一结果。
- 结果仍通知申请人。

### 5.3 流程与关联

- 业务关联采用三元组 `(tenantId, processDefinitionKey, businessKey)`；不能仅以裸 `encounterId` 查询。
- HIS 试点 `tenantId=his`。审方 `businessKey=encounterId`，退费 `businessKey=refundRequestId`；跨流程通过 definition key 消歧。
- Kafka 分区键使用上述三元组的稳定拼接，确保同一业务实例内有序。
- 所有命令/回调必须有全局 `eventId`；业务动作另有 `actionId/causationId`。
- 平台收到业务回执时优先按 `processInstanceId + actionId + messageName` 找唯一 execution，并交叉校验 tenant、definition、businessKey；不允许按 signal 广播。

## 6. 边界条件和易遗漏点

1. Flowable 本身不保证 businessKey 唯一，必须以平台链接表/唯一约束阻止并发重复发起。
2. 现有 `KafkaTemplate.send` 在数据库事务中调用，但不是数据库/Kafka 原子提交；迁移路径必须引入局部 inbox/outbox，不能假设“方法有 `@Transactional` 就不丢消息”。
3. Flowable task complete 与 Kafka publish 也有同样双写问题，平台 outbox 必须与引擎使用同一 DataSource/事务管理器。
4. 业务回执可能早于 BPMN 到达 message catch；outbox 发送前要先让流程进入订阅点，或将回执暂存后重试关联。
5. 用户双击、两个药师/管理员同时办理会触发 Flowable optimistic locking/任务已完成异常；API 应把同 actionId 重试映射为幂等成功，不同决定映射为 409。
6. 任务转办/加签会改变候选人，SpiceDB 关系同步存在时间窗；同步未 READY 时必须 fail-closed，而不是回退允许。
7. authz 关闭仅跳过 SpiceDB，不等于匿名开放。server/admin 仍需服务凭证或 OIDC 身份边界，且必须校验 tenant 与 Flowable candidate/assignee。
8. Casdoor `sub` 是 SpiceDB 主体；HIS 当前业务审计使用数值 `userId`。需通过已存在的 username→`UserIdentityDto` 内部查询做映射，禁止把客户端自报的数值 ID 当可信身份。
9. `his-notify` 当前把“角色待办”和“结果通知”都落 `notification`。中台上线后，权威待办来自 Flowable；旧 `RX_REVIEW_TODO/REFUND_TODO` 只能在兼容窗口保留，不能形成第二个可办理入口。
10. 老实例不能安全“半途搬入”新 BPMN。灰度切换点以新申请为边界；切换前的在途实例继续由手写流完成。
11. BPMN 版本部署后，新实例用新版本，既有实例固定旧版本；删除部署前必须证明无运行实例和保留审计要求已满足。

## 7. 歧义与开工前决议

| 事项 | 当前证据 | 计划中的默认决定 | 状态 |
|---|---|---|---|
| Flowable 版本 | 官方 7.1.0 基于 Boot 3.3.4、JDK17；最接近目标 Boot 3.3.5/JDK21 | Phase 0 实测 7.1.0，只有编译、启动、PG、BPMN、并发测试全过才锁版；失败再评估 7.2.0，不直接上 Flowable 8 | 待验证 |
| auth SpiceDB 拓扑 | 用户端口表未给 workflow 独立 SpiceDB；auth 指南又主张每项目独立实例 | 试点按用户给定端口复用 auth server 8200/SpiceDB 8543，并将 `workflow.zed` 纳入全量合并部署；生产隔离是否另开实例在 Phase 3 ADR 再确认 | 待确认但不阻塞 Phase 0-2 |
| 22 项 E2E | README/CLAUDE 记录通过，但仓库没有脚本 | Phase 2 前先重建为检入脚本并在旧实现上跑出基线，再用于新实现 | 必做 |
| 试点顺序 | 用户允许只先做审方 | 先审方灰度并稳定一个观察窗，再迁退费；退费涉及资金状态与跨服务撤销，风险更高 | 建议采用 |
| 旧审批 HTTP API | HIS 前端仍调用旧端点 | 开关关闭走旧逻辑；shadow 不改变响应；enforce 时旧端点变为中台适配入口并呈现最终一致，前端改造另走 `/frontend-plan` | 待前端计划确认 |

## 8. 验收标准

- 新平台五个后端模块和 console 占位结构可独立构建，JDK21 下运行。
- PostgreSQL 25432 可创建/升级 Flowable 与平台表；Redis 26379 不参与正确性判定。
- 最简 BPMN 可部署、启动、完成 UserTask、查看历史。
- 同一 `(tenant, definition, businessKey, idempotencyKey)` 并发发起 20 次只产生一个实例；审方同 encounter 同时最多一个 `WAITING_USER` 待办，但旧轮次已完成人工决定、仍在 `WAITING_BUSINESS` 时，不得吞掉不同 cycle 的新一轮 start。
- 重复/乱序 action 与 ack 不造成重复业务副作用，不产生悬空待办。
- 审方迁移后，未通过前没有发票；通过或驳回后作废阻塞项才按原守卫计费；全链路仍只产生一张发票。
- 退费通过只退款一次并最终撤销对应已执行医嘱；驳回不退款。
- authz disabled、shadow、enforce 三态均有自动化测试；enforce 下无权者 403、依赖故障 fail-closed。
- 老流程在途实例可继续完成；新流程可按租户/流程灰度切换和一键回退发起路由。
- 所有新增公共 REST/Kafka 契约有版本、幂等键、错误语义和契约测试。
