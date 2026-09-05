# 候选方案对比、风险评审与裁决

## 1. 统一评分口径

每项 1-5 分，5 为最好。对“复杂度、测试难度、回滚成本”采用反向评分：越简单/越容易测试/越低成本分越高。权重：正确性 25%、改动风险 15%、复杂度 10%、可维护性 15%、扩展性 15%、测试难度 10%、回滚成本 10%。

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 加权分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 可靠事件闭环 | 5 | 3 | 2 | 4 | 5 | 2 | 3 | 3.70 |
| B 平台直耦 HIS 事件 | 3 | 3 | 4 | 2 | 2 | 3 | 3 | 2.80 |
| C REST/SDK 命令主导 | 2 | 2 | 3 | 3 | 3 | 3 | 2 | 2.50 |
| D 影子 Strangler | 4 | 5 | 2 | 3 | 4 | 2 | 5 | 3.65 |

说明：D 得分高是因为它降低迁移风险，但它不是完整的长期数据面，不能机械地“得分最高即选择”。A 是长期架构，D 是迁移护栏，两者合并才覆盖目标。

## 2. 多维对比

| 维度 | A | B | C | D |
|---|---|---|---|---|
| 平台是否依赖 HIS | 否 | 是，编译依赖 | 否 | shadow 适配依赖事件语义 |
| 发起耦合 | Kafka 异步 | Kafka 异步 | HTTP 同步 | shadow 阶段无主链耦合 |
| 业务落地确认 | 明确 ACK message | 通常缺失 | 可选 | shadow 对账，不等于 ACK |
| 双写处理 | 双侧 inbox/outbox | 容易沿用现有风险 | HTTP 补偿/重试 | enforce 仍需另选 |
| 消费方复杂度 | 中 | 低 | 中高 SDK | 初期低、双轨期高 |
| 新业务接入 | BPMN+通用 handler | 修改平台代码 | SDK+handler | 先影子再选数据面 |
| 回滚 | 按发起路由退回旧实现 | 中 | 中高 | 最佳 |

## 3. 风险评审

### 3.1 兼容性

- Flowable 7.1.0 官方源码的父 POM是 Java 17、Spring Boot 3.3.4；这只是相邻版本证据，不是 Boot 3.3.5/JDK21 实测结论。Phase 0 必须用目标 Maven/JDK 与 PostgreSQL 跑真实 smoke。
- Flowable 7.2.0 已升级到 Boot 3.5.4，可能覆盖安全/缺陷修复，但更容易与固定的 Boot 3.3.5 BOM 冲突；只作为 7.1.0 失败后的备选。
- 不选择 Flowable 8：其官方 release 已进入 Boot 4/Jackson 3 世代，与锁定基线不符。
- `flowable-spring-boot-starter-process` 是 7.x 正确 starter；7.0 release 已移除旧 relocated starter 名称。

### 3.2 事务和消息

失败场景：HIS DB 提交成功但 start event 未发，审批永远不出现；或 Kafka 已发而 DB 回滚，出现孤儿流程。A 用同库 outbox 消除窗口；B/C 未补偿时无法解决。

失败场景：Flowable task 已完成但 action event 未发，流程卡死或业务未变。A 在 `taskService.complete` 同一事务写 platform outbox，并在 BPMN 等业务 ACK。

失败场景：业务已修改并发 ACK，但 ACK 丢失。HIS local outbox 重发，workflow inbox/eventId 去重后再关联。

注意：outbox 只能保证至少一次，不能保证业务 exactly-once；业务 handler 必须以 actionId inbox 唯一键和现有状态机/唯一约束收敛。

### 3.3 并发和幂等

- 并发 start：Flowable businessKey 无唯一性。拟新增 `wf_process_link` 对 `(tenant,definition,businessKey,idempotencyKey)` 唯一；审方另约束同 encounter 最多一个 `WAITING_USER`。旧轮次处于 `WAITING_BUSINESS` 时允许不同 cycle 启动，避免驳回落地后立即重提被旧实例吞掉。
- 并发 complete：同 actionId 重试返回首次结果；不同 actionId 对已完成 task 返回 409。不能把相反 decision 的第二次请求当幂等成功。
- `tryBill`：保留乐观锁、billed 和 invoice unique；workflow 不提前标 billed，不自行发 `OrderPlaced`。
- 退费：`RefundRequest.ensurePending` 与 `@Version` 保留；建议新增 invoice 上活动退费申请唯一约束，但由于现有业务是否允许驳回后重申不明确，先作为“待业务确认”，测试先覆盖并发重复申请。
- 回执早到：平台先把流程推进到 message catch、持久化 pendingActionId，再发布 outbox；仍早到时 inbox 记录 `WAITING_CORRELATION` 并指数退避，不丢弃。

### 3.4 性能

- 热路径查询必须限定 tenant、候选主体、分页和排序；禁止 `list()` 后 JVM 全量过滤。
- 历史轨迹走 HistoryService 并限制变量白名单，避免大 JSON/敏感业务数据进入 ACT_HI_VARINST。
- outbox publisher 使用 `FOR UPDATE SKIP LOCKED` 或等价 claim，批量上限和租约；多实例避免重复扫描风暴。
- Redis 仅可缓存流程定义/短期查询，不可用作 inbox、锁或流程真相。
- authz 每次任务办理需要远程 check；列表可先 Flowable candidate query，再对当前页 bulk check。不得对全库逐项 check。

### 3.5 安全

- tenant 只从验证过的 JWT `owner` 或服务凭证绑定配置取得，拒绝 body/header 覆盖。
- taskId 查询后必须再次校验 task.tenantId、assignee/candidate、流程链接；防 IDOR。
- authz disabled 不能匿名放行；只是不调用 SpiceDB。基础 OIDC/service auth 和 Flowable candidate 规则始终执行。
- enforce 下 SpiceDB 超时/协议错误 fail-closed；shadow 只记录 `would_allow/would_deny/error`，不改变结果。
- 完成任务时 actor 以 JWT `sub` 为授权主体；显示名和 HIS 本地 ID由服务端映射，客户端字段只作展示请求不能作审计真相。
- BPMN XML 部署需禁止外部实体、脚本任务/表达式任意 Bean 调用的越权；首期白名单 UserTask、gateway、message catch 和受控 delegate。
- admin 写端 OIDC 要求 workflow-admin，读端 workflow-viewer/admin，健康端点放行；复用 auth admin 的 issuer/aud/groups 校验样式。

### 3.6 数据迁移

- 不迁移老审批数据进 ACT_* 作为可运行实例。已有 `prescription_review/refund_request` 保持历史真相。
- 切换时给“申请”记录固定 `approval_mode`/`workflow_instance_id`（拟新增列），只有新建时决定路由，运行中不切换。
- 旧在途审批数量必须在 cutover 前盘点；rollback 只影响新发起路由，已被 workflow 接管且已有业务 action 的实例不能盲目复制回旧待办。
- Flowable schema 升级应由版本化官方 SQL/受控 migration 完成；生产禁用自动 schema update。Phase 0 dev 可临时 `database-schema-update=true` 验证。

### 3.7 灰度和回滚

灰度维度固定为 `(tenantId, processDefinitionKey)`，而不是随机请求百分比，以免同一流程两套权威。模式：

1. disabled：全部旧实现。
2. shadow：旧实现权威，Flowable 镜像轨迹，不开放办理。
3. enforce-new：仅新申请走 Flowable；旧在途走旧实现。
4. enforce-all-new：观察窗满足后成为默认；仍保留紧急禁新开关。

回滚触发：start/outbox 积压超阈值、action ACK 超时、重复账单/重复退款、授权误拒/误放、Flowable DB 错误率或 P95 超阈值。回滚动作先关闭“新申请进 workflow”，不删除 ACT_*，不回滚已提交的 Flyway，不撤销已发生业务动作；待人工分类在途实例后继续或补偿。

## 4. 裁决

最终采用“A 作为长期数据面 + D 作为迁移策略”，并吸收以下优点：

- 从 B 吸收：试点适配器可复用现有 HIS 领域事件语义，但转换发生在 HIS 侧，平台不依赖 `his-api`。
- 从 C 吸收：SDK 提供显式 `WorkflowClient` 给查询/办理等需要立即反馈的场景；流程发起仍优先用 outbox/Kafka，不把同步 HTTP 作为业务事务主路径。
- 从 D 吸收：先恢复旧 E2E 基线并跑 shadow，对新申请按固定 authority 切换。

所选方案的真实弱点是代码量与运维面显著增加、用户动作到业务落地变为最终一致、首个试点周期更长。若 Phase 0/1 资源不足，可以只交付审方并把退费延后；不能用 B 的直耦捷径替代 A 的契约边界。
