# 方案 C：REST/SDK 命令主导，Kafka 仅回调

## 架构

HIS 在提交/申请事务后同步调用 `WorkflowClient.startProcess`；人工办理也通过 SDK 同步调 server。平台完成任务后以 Kafka 发动作，HIS消费。平台可等业务 ack，也可直接结束。

## 模块职责

SDK 较重：提供重试、service token、Bearer 透传、按 businessKey 找任务并完成等能力。平台无需 start Kafka ingress，仍需 action outbox。

## 核心流程

`HIS HTTP -> workflow start -> UserTask -> workflow Kafka action -> HIS`。如果 HTTP 失败，HIS 要重试；若业务事务回滚而 HTTP 已成功，会产生孤儿流程。

## 改动范围

平台中等、HIS 中等。无需 HIS start outbox，但必须增加本地“待补偿调用”或 transaction synchronization，否则一致性弱于方案 A。

## 扩展性

中等。交互直观、可立即返回实例 ID，但同步耦合平台可用性；高峰时业务请求线程受平台延迟影响。

## 实施成本

中等。Demo 最快，但要达到可靠生产级最终仍需补命令表/重试器，成本接近 outbox。

## 已知弱点

- 本地业务提交与远程流程发起无法原子化。
- workflow 故障可能阻塞 HIS 主请求，违背事件驱动解耦方向。
- SDK 过重，消费方不再是“极小接入”。
- 对用户锁定的“Kafka Saga 一致”利用不足。
