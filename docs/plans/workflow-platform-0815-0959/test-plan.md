# 测试方案与验收标准

## 1. 测试原则

- 先在旧实现上恢复可重复执行的 22 项基线，再改路由。
- 以 PostgreSQL + Kafka + Flowable 真实集成为主；H2 不能替代 PG 的唯一约束、锁和 JSONB 测试。
- 所有异步断言使用有上限的轮询，不用固定长 sleep。
- 每类失败测试必须验证最终状态、消息数、outbox/inbox 状态和审计轨迹，而非只看 HTTP 200。

## 2. Phase 0 兼容性矩阵

使用明确环境：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH=/Users/liruijun/personal/devUtils/apache-maven-3.9.12/bin:$PATH
mvn -version
```

对 Flowable 7.1.0 + Boot 3.3.5 执行：

1. reactor compile/test/package。
2. PG16 上首次建表和第二次启动无变更。
3. 部署最简 BPMN，tenant=his，启动实例，完成 UserTask，查历史。
4. JDK21 下序列化基本变量，不使用 Java Serializable 业务对象。
5. 两 server 实例并发完成同一 task，只一个成功。
6. admin 部署 v2 后，新实例用 v2、老实例仍 v1。
7. starter 依赖树无 Spring 3.3.5 管理版本冲突；记录 `mvn dependency:tree`。

门禁：任一项失败不得锁版；评估 7.2.0 时重跑完整矩阵，不能只看能启动。

## 3. 单元测试

### protocol/sdk

- 所有 record 的必填、长度、枚举和 contractVersion 校验。
- envelope JSON golden file：未知可选字段可忽略，缺必填/version 不支持应拒绝并进 DLQ。
- SDK disabled 时 `NoopWorkflowClient` 不发网络请求；enabled 才创建 remote client。
- HTTP 401/403/404/409/422/503 映射到稳定 SDK 异常。

### core

- process key/businessKey/tenant 规范化和非法值拒绝。
- action outcome 到 BPMN variable/message 的映射。
- 精确 correlation：0 个、1 个、多个 execution；0 进入等待重试，多个为数据一致性故障且不推进。
- 相同 eventId/actionId 幂等，不同 decision 冲突。
- actor snapshot 不接受请求伪造的 subject/tenant。

### HIS handler

- Rx PASS/REJECT 调用原 `OrderStateMachineService` 并写一次 review。
- Rx 重复 action 不重复 review、不重复 `RxReviewedEvent`、不重复计费。
- refund approve/reject 保留 `RefundRequest.ensurePending` 和发票约束。
- 本地 actor username 映射失败时不改业务态、action 留待重试/DLQ。

## 4. 组件集成测试

### PostgreSQL/Testcontainers

- `wf_process_link` 幂等约束：相同 cycle/idempotencyKey 的 20 并发 start 只有一个 processInstanceId；不同 cycle 在旧实例 `WAITING_BUSINESS` 时可启动，但同 encounter 同时最多一个 `WAITING_USER`。
- inbox eventId 唯一；并发消费同事件只执行一次。
- 多 publisher 通过 claim/lease 处理 outbox，不漏、不永久锁死；进程在 publish 前后崩溃均可恢复。
- HIS 本地 transaction 回滚时不产生可发布 outbox。
- Flowable task complete 回滚时 task 仍存在且 platform outbox 不存在。

### Kafka/Testcontainers

- producer duplicate、consumer restart、rebalance、乱序 ack、毒 JSON、未知 version。
- key 相同事件保持顺序；不同业务键可并行。
- ack 早到进入 WAITING_CORRELATION，订阅出现后成功推进。
- 达最大重试进入 DLQ，保留原 payload hash、错误码和 correlation 信息，可人工重放。

### authz

- disabled：已认证且符合 Flowable candidate 的用户可办，不调用 auth server。
- shadow：allow/deny/error 都不拦截，但指标和审计正确。
- enforce：授权者 200/202，无权者 403；SpiceDB timeout/畸形响应 503 且不 complete task。
- task relation PENDING 时 fail-closed；READY 后可办；完成后即使删除 tuple 延迟，因 task 已不存在仍不能再次办理。
- tenant A 的 token 查询/办理 tenant B task 返回 404 或 403，不泄露存在性。

## 5. BPMN 模型测试

### `his-rx-review`

- start 创建一个 `pharmacistReview` UserTask。
- PASS/REJECT 两条 exclusive gateway 路径正确。
- 意见在 REJECT 必填，PASS 可空。
- action 发出后等待 `hisRxReviewApplied` message；仅匹配同 instance/actionId 的 ACK。
- ACK success 结束；business rejected/timeout 进入人工异常处理或 incident，不伪装成功。
- 驳回后本实例结束；医生重提创建下一 review round，已有 active task 时新 start 幂等合并。

### `his-refund-approval`

- APPROVE/REJECT 路由、意见规则、`hisRefundApplied` ACK。
- APPROVE 业务失败（发票已非 PAID）流程进入失败处理，不标 completed。
- 超时 timer 产生告警但不自动退款/驳回。

BPMN lint：唯一 element id、无裸 script task、无 signal catch、message 名称版本化、candidate group 显式、所有异步边界有 incident/timeout 策略。

## 6. 旧实现回归基线（重建的 22 项至少覆盖）

1. 药品提交后 PENDING_REVIEW。
2. 非药品提交后 SUBMITTED。
3. 含待审药嘱时无 invoice。
4. 药师待办可见。
5. 非药师不能办理。
6. 审方 PASS 后药嘱 SUBMITTED。
7. PASS 写审核记录。
8. PASS 后只生成一张 invoice。
9. 重复 PASS 不重复计费。
10. REJECT 后药嘱 REJECTED。
11. REJECT 意见持久化。
12. REJECT 不计费。
13. 驳回医嘱 resubmit 后重新待审。
14. 驳回医嘱 cancel 后解除计费阻塞。
15. 缴费后相关医嘱 EXECUTED。
16. 仅 PAID 发票能申请退款。
17. 退费请求出现在待办。
18. APPROVE 后 invoice REFUNDED。
19. APPROVE 最终把 EXECUTED 医嘱 CANCELLED。
20. REJECT 不改变 invoice PAID。
21. 四类通知按角色/用户可见。
22. 通知已读/未读计数正确。

实际旧脚本若断言名称不同，以恢复出来的真实脚本为准；上述是最低行为集，不宣称是原脚本逐字内容。

## 7. 新增异常/并发回归

- 20 并发 submit/start；两个药师相反决定；两个管理员同时退费。
- Kafka start 重复 100 次、action 重复 100 次、ACK 重复 100 次。
- workflow DB、Kafka、auth server、his-auth 分别短暂不可用后的恢复。
- 进程在 DB commit 前、commit 后/publish 前、publish 后/mark sent 前被 kill。
- BPMN v1 有在途实例时部署 v2。
- disabled→shadow→enforce→disabled 切换期间，旧在途与新实例路由不串线。
- 计费端故意重复接收 `OrderPlacedEvent`，仍只有一个 invoice。

## 8. 性能和容量基线

首期不是容量承诺，先建立可比较基线：

- 100 并发同 idempotencyKey start，成功/幂等响应率 100%，无重复实例；混合 cycle 压测不丢新轮次且不产生两个用户待办。
- 1000 个待办时分页查询不做全表 JVM 过滤，记录 p50/p95/p99。
- 100 并发 task complete，业务键互异时无全局锁串行。
- outbox 1 万积压在规定批量下可持续下降，不影响 task complete 的 DB 事务时长。
- 记录 Flowable async executor、Hikari pool、PG connection/lock、Kafka lag 和 authz latency。

具体 SLO 在 Phase 1 基线后确定；计划阶段不臆造生产流量数字。

## 9. 最终验收门禁

- 单元与组件测试全绿，禁止依赖外部服务而“自动跳过”的测试计入通过。
- 旧 22 项在 disabled 与 enforce 最终态各通过一次；异步断言允许等待但有超时。
- 新并发/故障矩阵全绿。
- `mvn verify`、前后端契约校验、BPMN lint、Compose smoke 有保存的命令与结果。
- 数据库对账：无重复 active link、无长期 PROCESSING inbox/outbox、无 workflow 完成而业务仍 REQUESTED/PENDING_REVIEW 的孤儿。
- 回滚演练成功：关闭新发起后，旧在途不受影响，workflow 在途可查询、可继续或人工处置。
