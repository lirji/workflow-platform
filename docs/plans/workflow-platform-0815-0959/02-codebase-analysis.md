# 现有代码与影响面分析

## 1. 仓库事实概览

工作区根目录不是 Git 仓库；`auth-platform` 与 `his-platform` 各自有 Git 状态。分析时发现用户已有未提交鉴权改动，尤其是 `his-security/authz/**` 与 `EncounterController#get` 的 `@DataScope`。实施 Agent 必须保留这些改动并先做冲突审计。

当前不存在 `/Users/liruijun/personal/LLM/workflow-platform`。因此所有 workflow 文件均为拟新增；HIS/auth 文件才是现有改动面。

## 2. auth-platform 可复用结构

### 2.1 模块和依赖

父 POM 在 `auth-platform/pom.xml:22-28` 声明 `protocol/core/sdk/server/admin`；Spring Boot 3.3.5 与 Java 21 在 `auth-platform/pom.xml:7-16`。依赖方向由各 POM 证实：

- `auth-platform-protocol` 是纯 Java 契约（`auth-platform-protocol/pom.xml:11-20`）。
- `auth-platform-core` 依赖 protocol 并实现 SpiceDB 适配（`auth-platform-core/pom.xml:11-31`）。
- `auth-platform-sdk` 只依赖 protocol、Spring 和 HTTP（`auth-platform-sdk/pom.xml:11-40`）。
- server/admin 依赖 core 并各自启动（`auth-platform-server/pom.xml:11-40`、`auth-platform-admin/pom.xml:11-60`）。

可复用的是模块形态、Starter 自动配置方式、HTTP 超时、OIDC 加固和冒烟脚本风格；不可复制 `AuthzEngine` 式引擎端口到 workflow。

### 2.2 SDK 和安全

- `AuthzSdkAutoConfiguration#authzEngine` 在 `auth-platform-sdk/.../AuthzSdkAutoConfiguration.java:13-30` 注册远程客户端。
- `AuthzClientProperties.enabled` 默认 `true`（`AuthzClientProperties.java:9-22`），与本任务“workflow.authz 默认关”不同。workflow 必须显式把 `authz.client.enabled` 绑定为 false，不能只引依赖后期待 Noop。
- `RemoteAuthzEngine` 使用 JDK HttpClient、2s/5s 超时（`RemoteAuthzEngine.java:38-50`），可直接复用。
- admin 的 `SecurityConfig` 已做 JWKS、issuer、可选 audience、groups claim 映射（`auth-platform-admin/.../SecurityConfig.java:35-90`）。Casdoor token 大时还需 `server.max-http-request-header-size:64KB`（`application.yml:1-6`）。
- auth server 的 service bearer 默认关闭（`auth-platform-server/src/main/resources/application.yml:12-18`）；workflow 生产接入必须要求它开启，或采用独立受保护实例。

### 2.3 SpiceDB schema 部署风险

`deploy/spicedb-smoke.sh:12-16` 明确 `schema/write` 是全量替换；当前共享实例写 `knowledge.zed + his.zed`。`risk-authz-fixture.sh:1-15` 又明确 risk 使用独立 8545 实例。故新增 `workflow.zed` 不能单文件写到 8543，必须更新所有指向该实例的全量合并脚本，或为 workflow 单开未分配端口的实例。

## 3. HIS 审方真实调用链

### 3.1 发起

1. `EncounterController#submit` (`his-outpatient/.../EncounterController.java:67-72`) 调 `EncounterService#submitOrders`。
2. `submitOrders` 查询 `CREATED` 医嘱，DRUG 触发 `SUBMIT_FOR_REVIEW`，其他触发 `SUBMIT`；保存后把 encounter 置 `SUBMITTED`（`EncounterService.java:85-105`）。
3. 有药品时通过 `OrderEventPublisher#publishRxReviewRequested` 发 `his.rx.review.requested`（`EncounterService.java:107-111`、`OrderEventPublisher.java:31-34`）。
4. 随即调用 `tryBill`；存在 `CREATED/PENDING_REVIEW/REJECTED` 就返回（`EncounterService.java:119-140`）。

### 3.2 人工办理

- `PrescriptionReviewController` 暴露 pending/pass/reject，均由 `PHARMACIST` RBAC 控制（`PrescriptionReviewController.java:35-53`）。
- `PrescriptionReviewService#pass` 批量把当前 encounter 的待审医嘱转为 `SUBMITTED`，写 `PrescriptionReview(PASS)`，发布结果，再调用 `tryBill`（`PrescriptionReviewService.java:72-89`）。
- `#reject` 批量转 `REJECTED`，写带意见的审核记录并发布结果，不计费（同文件 `91-107`）。
- `EncounterService#resubmitRejectedOrder` 将单个驳回医嘱重新设为 `PENDING_REVIEW` 并再发待审事件（`EncounterService.java:172-184`）；这会影响 BPMN 的“同 encounter 单活动实例”去重。

### 3.3 状态、审计和并发

- 合法流转定义在 `OrderStateMachineConfig#configure` (`37-60`)，BPMN 不应复制替代它。
- `Order`、`Encounter`、`PrescriptionReview` 都继承 `AuditableEntity`；`AuditableEntity.version` 为 `@Version`（`his-common/.../AuditableEntity.java:32-34`）。
- `Encounter.billed` 位于 `Encounter.java:57-59`，由 V2 migration 添加（`V2__rx_review.sql:3-4`）。
- `tryBill` 是“先查 billed/待处理，再 markBilled+send”的查后改；两个事务并发仍可能都到发送点。乐观锁会让一个数据库提交失败，但 Kafka send 可能已经成功。因此 billing 唯一约束是不可删除的最终防线。

## 4. HIS 退费真实调用链

1. `RefundController#request` 仅 CASHIER 可调用；approve/reject 仅 ADMIN（`RefundController.java:33-58`）。
2. `RefundService#request` 校验发票 `PAID`，创建 `RefundRequest.REQUESTED` 并发布 `RefundRequestedEvent`（`RefundService.java:39-52`）。
3. `#approve` 依次 `req.approve`、`invoice.refund`、保存两者并发布 `RefundReviewedEvent(orderIds)`（同文件 `60-71`）。
4. `#reject` 只改变请求并发拒绝结果（同文件 `74-81`）。
5. `RefundRequest#ensurePending` 阻止二次审批（`RefundRequest.java:77-95`）；实体有 `@Version`。
6. `RefundReviewedListener#onRefundReviewed` 在 approved 时调用 `EncounterService#cancelExecutedOrders`（`his-outpatient/.../RefundReviewedListener.java:25-31`），后者只处理仍为 EXECUTED 的医嘱，天然幂等（`EncounterService.java:143-153`）。

当前 migration 只有普通 `idx_refund_invoice`，没有“同一 invoice 仅一个 REQUESTED”的唯一约束（`his-billing/.../V2__refund.sql:5-24`），并发重复申请是迁移时应补测/决议的业务边界。

## 5. Kafka 与通知现状

- 主题常量和六类 DTO 在 `his-api/src/main/java/com/lrj/his/api/event`；四个审批主题定义于 `Topics.java:14-24`。
- producer 以 patientId 或 invoiceId 为 key（`OrderEventPublisher.java:26-38`、`RefundEventPublisher.java:25-32`），不是 workflow 三元业务键。
- outpatient/billing/notify 的 `JsonDeserializer` 只信任 `com.lrj.his.api.event`（各自 `application.yml:22-33`）。如直接消费 workflow protocol DTO，必须扩充 trusted packages；更稳妥的是用 String/byte[] 后由契约化 ObjectMapper 显式解析。
- `NotificationListeners` 对四类事件创建药师角色待办、医生结果、ADMIN 角色待办、申请人结果（`his-notify/.../NotificationListeners.java:30-68`）。
- `notification` 没有 eventId 唯一列；重复 Kafka 消息可能生成重复通知。这是既有风险，迁移验收应覆盖，不应把它误认为 BPMN 的问题。

## 6. 身份与授权现状

- HIS 下游 `CurrentUser` 只有数值 `userId`、username、deptId、roles（`his-common/.../CurrentUser.java:6-17`）。
- Casdoor/IdP 路径在 gateway 中从 token 取 username，再调用 `/auth/internal/users/{username}` 回查数值身份（`IdpIdentityGlobalFilter.java:59-89`）。
- 已存在 `AuthInternalApi#getByUsername`（`his-api/.../AuthInternalApi.java:24-26`）可供异步 workflow 回调把可信 username 映射回本地 ID。
- 未提交的 `HisDataPermission` 当前把数值 userId 当 SpiceDB subject（`his-security/.../HisDataPermission.java:31-40`），而 auth 接入指南要求 Casdoor `sub`。实施 workflow authz 时不能照搬这个主体选择。

## 7. 测试与运维证据

- `README.md:98-106` 与 `CLAUDE.md:80-86` 记录 Step 6 的 22 项真机断言通过。
- 仓库当前没有该 22 项脚本；`his-platform/scripts` 只有数据库初始化和监控资源。不能直接“复用脚本”，必须先恢复为受版本控制的可执行测试。
- Compose 名称为 `his-platform`，主机端口包括 PG 5433、Kafka 9092 等（`docker-compose.yml:1-69`）；workflow 必须使用独立 project name/容器名/卷名和给定端口。
- auth Compose 已在注释中要求起前 `docker compose -p auth-platform down --remove-orphans`（`auth-platform/deploy/docker-compose.yml:1-5`），workflow 应采用同一运维纪律。

## 8. 已存在且预计修改的文件

以下仅列由真实调用链证明会受影响的现有文件；最终方案会再列拟新增文件。

### his-platform

- `pom.xml`
- `docker-compose.yml`
- `his-api/src/main/java/com/lrj/his/api/event/Topics.java`
- `his-outpatient/pom.xml`
- `his-outpatient/src/main/resources/application.yml`
- `his-outpatient/src/main/java/com/lrj/his/outpatient/service/EncounterService.java`
- `his-outpatient/src/main/java/com/lrj/his/outpatient/service/PrescriptionReviewService.java`
- `his-outpatient/src/main/java/com/lrj/his/outpatient/web/PrescriptionReviewController.java`
- `his-outpatient/src/main/java/com/lrj/his/outpatient/event/OrderEventPublisher.java`
- `his-outpatient/src/main/resources/db/migration/`（新增版本，不改 V1/V2）
- `his-billing/pom.xml`
- `his-billing/src/main/resources/application.yml`
- `his-billing/src/main/java/com/lrj/his/billing/service/RefundService.java`
- `his-billing/src/main/java/com/lrj/his/billing/web/RefundController.java`
- `his-billing/src/main/java/com/lrj/his/billing/event/RefundEventPublisher.java`
- `his-billing/src/main/resources/db/migration/`（新增版本，不改 V1/V2）
- `his-notify/src/main/java/com/lrj/his/notify/event/NotificationListeners.java`（只在去重/待办权威切换阶段）
- `his-notify/src/main/resources/db/migration/`（如补 eventId 去重）
- `README.md`、`CLAUDE.md`、`docs/能力清单.md`

### auth-platform

- `auth-platform-core/src/main/resources/schemas/`（新增 `workflow.zed`）
- `deploy/spicedb-smoke.sh` 与 `deploy/his-smoke.sh`（若复用 8543，必须全量合并 workflow schema）
- `deploy/`（新增 workflow fixture/provision 脚本）
- 平台能力/接入文档（说明 workflow subject、resource 与部署拓扑）

### 明确不应修改

- 既有 Flyway V1/V2 文件。
- `Encounter.billed`、`Invoice.encounterId` 唯一约束及其语义。
- `OrderStateMachineConfig` 的医嘱状态权威；如无业务规则变化不改。
- 本轮 `his-web` 与拟建 `workflow-console` 实现。
