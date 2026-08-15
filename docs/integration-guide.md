# 工作流中台接入指南

> 面向**消费方业务系统**（如 his-platform）接入 `workflow-platform` 流程/审批中台。
> 契约主版本 `CONTRACT_VERSION = 1`（见 `workflow-platform-protocol` 的 `ProtocolInfo`）。
> 本文所有契约字段以 `workflow-platform-protocol` 的 record 为唯一真值源。

## 1. 设计原则

- **中台 + SDK 同构**：与 auth-platform 一致——中台承载流程编排（Flowable），消费方通过 SDK / Kafka 接入，不感知 Flowable 内部类型。
- **发起与落地走 Kafka（事务性、最终一致）**：发起流程、业务落地回执**必须**走消费方自己的 **outbox → Kafka**，与业务写库同事务；**不走 SDK**。
- **查询与办理走 SDK / REST（需即时反馈）**：查待办、办理（通过/驳回）需要同步结果，走 SDK 或 REST。
- **办理是"已受理"而非"已完成"**：办理返回 `202 PENDING_BUSINESS`，人工决定已受理，业务落地经 Kafka 异步最终一致，UI/调用方**不得**呈现为"已完成"。
- **三层幂等**：`EventEnvelopeV1.eventId`（收件箱去重）→ 业务 `actionId`（副作用去重）→ 发起四元组 `(tenantId, processDefinitionKey, businessKey, idempotencyKey)`。
- **租户显式传递**：REST 带 `X-Workflow-Tenant` 头；事件带 `EventEnvelopeV1.tenantId`。

## 2. 接入平面总览

| 平面 | 通道 | 用途 | 走 SDK? |
|---|---|---|---|
| ① 异步事件 | Kafka | **发起流程** + **业务落地回执** | ❌ 走消费方 outbox |
| ② 同步接口 | REST(:8300) / SDK | 查待办、办理(通过/驳回)、查实例/轨迹/定义 XML | ✅ 可选 SDK |
| ③ 待办 UI | workflow-console | 人工待办中心 + 流程轨迹（可选，或自建） | — |

### 端到端时序（审方为例）

```mermaid
sequenceDiagram
    participant B as 消费方业务(his)
    participant OB as 消费方 outbox
    participant K as Kafka
    participant W as 中台(:8300)
    participant U as 待办人(console/SDK)

    B->>OB: 业务事务内写 outbox(StartProcessCommand)
    OB->>K: workflow.command.start.v1
    K->>W: 消费(inbox 去重 + 四元组幂等)
    W->>W: 启动流程实例,停在人工任务
    U->>W: GET /tasks 查待办
    U->>W: POST /complete-review (202 PENDING_BUSINESS)
    W->>K: workflow.action.requested.v1
    K->>B: 消费(按 actionId 幂等做业务副作用)
    B->>OB: 事务内写 outbox(ActionApplied ACK)
    OB->>K: workflow.action.applied.v1
    K->>W: 关联回流程 message,推进至落地/incident
```

## 3. 平面①：Kafka 事件契约

主题常量见 `WorkflowTopics`。事件统一序列化为 **JSON 字符串**，外层包 `EventEnvelopeV1<T>` 信封；消息 **key 一律 `tenant|definition|businessKey`**。

| Topic | 方向 | 载荷 record |
|---|---|---|
| `workflow.command.start.v1` | 消费方 → 中台 | `StartProcessCommandV1` |
| `workflow.action.requested.v1` | 中台 → 消费方 | `WorkflowActionRequestedV1` |
| `workflow.action.applied.v1` | 消费方 → 中台 | `WorkflowActionAppliedV1` |
| `workflow.lifecycle.v1` | 中台 → 观察者 | 生命周期通知（不参与正确性） |
| `workflow.dlq.v1` | 双侧运维 | 毒消息 / 超限失败 |

### 信封 `EventEnvelopeV1<T>`

```
eventId(UUID,inbox 去重键) · contractVersion(固定 1) · eventType · occurredAt
· source(来源系统标识) · tenantId · correlationId · causationId(上游 eventId,可空) · payload
```

### ① 发起 `StartProcessCommandV1`（消费方 → 中台）

```
processDefinitionKey  流程定义 key(如 hisRxReview)
businessKey           业务键(审方=encounterId)
idempotencyKey        幂等键(审方=review cycle id)
initiator             发起人主体(Casdoor sub 或服务标识)
variables             初始流程变量(仅 JSON scalar/受控 list/map,白名单:IDs/枚举/金额快照/actor 快照;禁整对象)
```
中台按 `(tenantId, processDefinitionKey, businessKey, idempotencyKey)` 四元组去重，重复发起返回原实例；至少一次投递安全。

### ② 请求落地 `WorkflowActionRequestedV1`（中台 → 消费方）

```
processInstanceId · taskId · taskDefinitionKey(如 pharmacistReview) · processDefinitionKey
· businessKey · actionId(业务侧幂等键) · action(如 RX_REVIEW_PASS/RX_REVIEW_REJECT)
· actor(办理人快照) · parameters(如审方意见 opinion)
```
消费方**按 `actionId` 幂等**做业务副作用（在 `eventId` 收件箱去重之上再收敛一层）。

### ③ 落地回执 `WorkflowActionAppliedV1`（消费方 → 中台）

```
processInstanceId · taskId(落地时任务可能已结束,可空) · processDefinitionKey · businessKey
· actionId(对应 requested 的 actionId) · status · businessVersion(可空) · errorCode/errorMessage(可空)
```

`status`（`WorkflowActionStatus`）语义：

| 值 | 含义 | 流程走向 |
|---|---|---|
| `APPLIED` | 业务已成功落地 | 推进至完成 |
| `REJECTED_BY_BUSINESS` | 业务规则拒绝（非重试） | 走人工处置分支 |
| `FAILED_RETRYABLE` | 可重试失败 | 消费方 outbox 重发 |
| `FAILED_FINAL` | 终态失败 | 进 incident / 人工处置，流程不自动通过 |

### `Actor` 快照

```
subjectId   Casdoor sub(授权主体,唯一授权真相)
username    登录名(业务侧回查本地数值 id 用)
displayName 展示名,可空
```

## 4. 平面②：SDK / REST

### 4.1 引入 SDK（Spring Boot Starter）

```xml
<dependency>
  <groupId>com.lrj.workflow</groupId>
  <artifactId>workflow-platform-sdk</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```
> SDK 已传递依赖 `workflow-platform-protocol`，消费方可直接复用其 record 做事件序列化。

```yaml
workflow:
  client:
    enabled: true                       # 默认 false → 注入 NoopWorkflowClient(引入即安全,不开不走远程)
    base-url: http://workflow-server:8300
    connect-timeout-ms: 2000
    read-timeout-ms: 5000
```
自动装配（`WorkflowSdkAutoConfiguration`）后注入 `WorkflowClient`：

```java
public interface WorkflowClient {
    List<TaskView> findTasks(String tenant, String definitionKey, String businessKey);
    String completeReview(String tenant, String taskId, CompleteReviewRequest request); // 返回 actionId
}
```
> 发起故意**不在** SDK 里——发起必须与业务写库同事务，走 outbox（见 §5）。

### 4.2 直接调 REST（不引 SDK）

所有请求带头 `X-Workflow-Tenant: <租户>`（如 `his`）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/tasks?definitionKey=&businessKey=` | 查活动待办 → `TaskView[]` |
| GET | `/api/v1/tasks/search?definitionKey=&businessKey=&candidateGroup=&page=&size=` | 候选组过滤 + 分页 → `TaskSearchResult` |
| POST | `/api/v1/tasks/{taskId}/complete-review` | 办理，body `CompleteReviewRequest` → **202** `{actionId, status:"PENDING_BUSINESS"}`；冲突 409 |
| GET | `/api/v1/process-instances?definitionKey=&businessKey=` | 查实例（含最终一致 `phase`）→ `ProcessInstanceView[]` |
| GET | `/api/v1/process-instances/{id}/timeline` | 历史轨迹 → `TimelineEntry[]` |
| GET | `/api/v1/definitions/{key}/xml` | 最新版 BPMN XML（含 DI，供 bpmn-js 渲染） |

`phase`（`ProcessInstanceView.phase`）：`WAITING_USER` / `WAITING_BUSINESS` / `COMPLETED` / `INCIDENT` / `CANCELLED`。

## 5. 消费方代码骨架（示意）

> 以下为**示意骨架**，字段以 protocol record 为准；消费方复用 `workflow-platform-protocol` 的 record + Jackson 即可。

### 5.1 发起（业务事务内写 outbox）

```java
// 在你的业务写库同一事务里,把 EventEnvelopeV1<StartProcessCommandV1> 序列化后写入你自己的 outbox 表。
var cmd = new StartProcessCommandV1(
        "hisRxReview",                 // processDefinitionKey
        encounterId,                   // businessKey
        reviewCycleId,                 // idempotencyKey
        currentUserSub,                // initiator
        Map.of("encounterId", encounterId, "amount", amountSnapshot)); // 白名单变量
var env = new EventEnvelopeV1<>(
        UUID.randomUUID().toString(), 1, "workflow.command.start.v1",
        Instant.now(), "his-outpatient", tenant, correlationId, null, cmd);
outbox.save(topic("workflow.command.start.v1"),
            key(tenant, "hisRxReview", encounterId), json(env)); // 你的 outbox 轮询器再投 Kafka
```

### 5.2 落地（消费 requested → 做业务 → 回 applied）

```java
@KafkaListener(topics = "workflow.action.requested.v1", groupId = "his-outpatient") // 用你自己的 group
public void onActionRequested(String message) {
    EventEnvelopeV1<WorkflowActionRequestedV1> env = codec.parse(message, WorkflowActionRequestedV1.class);
    if (!inbox.tryClaim(env.eventId())) return;         // ① eventId 收件箱去重
    var req = env.payload();
    // ② 按 actionId 幂等做业务副作用(例:按 action=RX_REVIEW_PASS 放行发药 / REJECT 退回医生)
    WorkflowActionStatus status = applyBusiness(req.actionId(), req.action(), req.parameters());
    // ③ 事务内写 outbox 回执
    var applied = new WorkflowActionAppliedV1(
            req.processInstanceId(), req.taskId(), req.processDefinitionKey(), req.businessKey(),
            req.actionId(), status, businessVersion, null, null);
    var out = new EventEnvelopeV1<>(UUID.randomUUID().toString(), 1, "workflow.action.applied.v1",
            Instant.now(), "his-outpatient", env.tenantId(), env.correlationId(), env.eventId(), applied);
    outbox.save("workflow.action.applied.v1", key(env.tenantId(), req.processDefinitionKey(), req.businessKey()), json(out));
    inbox.markDone(env.eventId());
}
```

## 6. 接入 checklist

**中台侧（一次性）**
- [ ] 为该业务设计并部署一份 BPMN 流程定义（如 `hisRxReview`），含 BPMNDI 图形段（供 console 渲染）。
- [ ] 约定 `action` 类型语义（如 `RX_REVIEW_PASS` / `RX_REVIEW_REJECT`）与候选组命名（大写无前缀，如 `PHARMACIST`）。
- [ ] 约定 `variables` 白名单。

**消费方侧**
- [ ] 引 `workflow-platform-sdk` 依赖（查询/办理即时反馈用；不需要可只引 `workflow-platform-protocol`）。
- [ ] **发起**：业务事务内写 outbox → `workflow.command.start.v1`（四元组幂等）。
- [ ] **落地**：消费 `workflow.action.requested.v1`（自有 group）→ 按 `actionId` 幂等做副作用 → 事务内写 outbox → `workflow.action.applied.v1`。
- [ ] 实现 inbox（按 `eventId` 去重）+ outbox（至少一次投递 + 重发）。
- [ ] 所有 REST 调用带 `X-Workflow-Tenant`；配 `workflow.client.*`（若用 SDK）。
- [ ] 待办 UI：接 workflow-console，或自建接 REST。

## 7. 参考实现与约束

- **参考实现**：`his-platform` 审方场景（消费方适配器），是本中台的首个试点接入方。
- **契约演进**：`EventEnvelopeV1.contractVersion` 固定 `1`；破坏性变更走新版本 topic（`*.v2`），并行灰度。
- **现状约束**：
  - SDK/protocol 为 `0.1.0-SNAPSHOT`，**未发布到公共仓库**（构建产物在本地 maven 仓库 `/Users/liruijun/personal/repository`）；外部项目引依赖前需能访问该仓库或内网 Nexus。
  - Kafka topic/序列化/inbox-outbox 需消费方自行落实（中台侧参考 `workflow-platform-server` 的 `WorkflowStartListener` / `WorkflowActionAppliedListener` 与 `EnvelopeCodec`）。

## 8. 契约 record 速查

| 用途 | Record（`com.lrj.workflow.protocol.*`） |
|---|---|
| 事件信封 | `event.EventEnvelopeV1<T>` |
| 发起 | `event.StartProcessCommandV1` |
| 请求落地 | `event.WorkflowActionRequestedV1` |
| 落地回执 | `event.WorkflowActionAppliedV1` + `event.WorkflowActionStatus` |
| 办理人 | `event.Actor` |
| 主题常量 | `event.WorkflowTopics` |
| 待办视图 | `api.TaskView` / `api.TaskSearchResult` |
| 办理请求 | `api.CompleteReviewRequest` |
| 实例视图 | `api.ProcessInstanceView` |
| 轨迹条目 | `api.TimelineEntry` |
