# 方案 B：平台直接理解 HIS 事件

## 架构

平台仍集中嵌入 Flowable，但直接依赖 `his-api`，消费现有 `RxReviewRequestedEvent/RefundRequestedEvent`，并直接发布 `RxReviewedEvent/RefundReviewedEvent`。BPMN 中的 JavaDelegate 组装 HIS DTO；HIS 仅新增结果 listener。

## 模块职责

通用模块形态不变，但 server/core 增加 `his` adapter 包并依赖 `his-api`。SDK 只覆盖 REST；Kafka 契约沿用 HIS。

## 核心流程

现有 HIS requested event 启动 BPMN；UserTask 完成后平台直接发已有 reviewed event；HIS listener 更新状态，流程可直接结束或等新增 ack。

## 改动范围

HIS 发起端最小，平台改动中等。若不加 ack，业务状态失败时流程已完成；若加 ack，又会逐渐演化成方案 A。

## 扩展性

低到中。每接一个业务线，平台都要编译依赖其 DTO、受其 trusted packages 和版本节奏影响，破坏中台 Published Language 边界。

## 实施成本

初期最低，适合验证 Flowable/BPMN，但不适合作为正式中台内核。

## 已知弱点

- 平台与 HIS 强耦合，形成反向依赖和发布锁步。
- 现有 requested DTO 没有 eventId、process key、actionId，无法严谨幂等和关联。
- 直接复用 `RxReviewedEvent` 缺 processInstanceId，不能可靠推进 message catch。
- 容易把“试点捷径”固化成平台契约。
