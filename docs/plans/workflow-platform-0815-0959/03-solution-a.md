# 方案 A：可靠事件编排中台（Inbox/Outbox + 业务回执）

## 架构

平台 server 嵌入 Flowable，Kafka ingress 接收通用 start/ack 事件，运行时 API 处理 UserTask。任务完成后不直接修改 HIS，而是在同一数据库事务写 `wf_outbox_event`，发布通用 `WorkflowActionRequestedV1`。HIS 适配器在本地事务内执行业务状态机并写本地 outbox，同时发业务领域结果和 `WorkflowActionAppliedV1`。平台 inbox 消费回执，以 BPMN message 精确关联并结束流程。

## 模块职责

- protocol：REST DTO、Kafka envelope、错误码和版本字段；无 Flowable 类型。
- core：直接依赖 `RuntimeService/TaskService/HistoryService/RepositoryService` 的薄应用服务；流程链接、inbox/outbox、关联和 task authz 协调。
- server：8300 REST、Kafka listener/publisher、运行时安全、唯一租户边界。
- admin：8301 部署/版本/审计/租户配置，OIDC resource server。
- sdk：`WorkflowClient`、自动配置、disabled Noop、事件发布/消费辅助。
- console：仅占位，后续按后端契约实现。

## 核心流程

`HIS local transaction -> HIS outbox -> workflow.command.start.v1 -> workflow inbox + start BPMN -> UserTask -> workflow outbox -> workflow.action.requested.v1 -> HIS inbox + domain state/outbox -> workflow.action.applied.v1 -> message correlation -> end`。

## 改动范围

最大。两个项目都新增 inbox/outbox；HIS 审批 service 要拆成“发起适配”和“处理 workflow action”；旧 Controller 受开关路由。

## 扩展性

高。平台不依赖 HIS DTO，新业务只需 BPMN、事件映射和消费方 handler。业务 ACK 使轨迹能区分“人工已决定”和“业务已落地”。

## 实施成本

高，约束和测试最多；但一次解决双写、重复、乱序和宕机恢复。适合最终形态。

## 已知弱点

- 事件链变长，最终一致带来 UI 延迟。
- 本地 outbox 需要调度、积压监控和清理策略。
- 回执早到、毒消息、关联失败需要专门状态与人工重放工具。
- 对当前作品集规模偏重，但审批涉及资金和计费，可靠性收益足以抵消复杂度。
