# Workflow Platform 实施进度

> 权威进度文件。上下文压缩或会话中断后以本文件为准恢复。对照 FINAL_PLAN.md 各阶段完成标准推进。

## 已批准范围(2026-08-15)
- **首轮里程碑 = 「审方(rx-review)enforce 跑通」。退费(refund)押到下一轮。**
- 方案:A(可靠事件闭环)长期数据面 + D(影子 Strangler)迁移护栏。
- 4 条 ADR 见 [[workflow-platform-project]] 记忆 / FINAL_PLAN §2。

## 环境(已确认)
- JDK 21.0.11 / Maven 3.9.12 / Docker 29.7.2。本地 Maven 仓库 `/Users/liruijun/personal/repository`。
- 跑 mvn 前:`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` + Maven PATH `/Users/liruijun/personal/devUtils/apache-maven-3.9.12/bin`。
- 端口 8300/8301/25432/26379 起始全空闲。

## 阶段进度

### Phase 0 — 版本与基建 Spike(硬门禁)  [✅ 完成 2026-08-15]
目标:证明 Flowable 7.x 在 Boot 3.3.5/JDK21 可用;锁版 + 官方 DDL;写 ADR。
- [x] 多模块 Maven 骨架(protocol/core/server/admin/sdk),groupId com.lrj.workflow,BUILD SUCCESS
- [x] docker-compose:独立 PG 25432 + Redis 26379(name: workflow-platform)+ 预检脚本,均 healthy
- [x] server(:8300)/admin(:8301)最小启动;server 连 PG 实跑 health UP
- [x] Flowable **7.1.0** 真跑(H2 spike FlowableSpikeTest 4/4):deploy/start(tenant+businessKey)/complete/history
- [x] PG:引擎自建 39 张 ACT_*、schema.version=7.1.0.2;restart 后数据存活 + Hikari 首试重连 200
- [x] 20 并发发起独立成实例、v1/v2 多版本
- [x] **multi-instance 动态加签 API 可用**(addMultiInstanceExecution,parentExecutionId=流程实例根)→ §7.3 转"支持"
- [x] 锁定 flowable.version=7.1.0 + 固化官方 DDL 到 deploy/postgres/flowable-7.1.0/,写 ADR 0001
- 完成标准:兼容矩阵全绿、版本/DDL 固定、无未解释依赖冲突。**已满足。**

已新增/改动文件(Phase 0):
- workflow-platform/pom.xml + 5 模块 pom
- protocol/ProtocolInfo.java、sdk/SdkInfo.java
- server/WorkflowPlatformServerApplication.java + application.yml + test/application.yml
- server/test:FlowableSpikeTest.java + bpmn/spike-hello.bpmn20.xml + bpmn/spike-multi.bpmn20.xml
- admin/WorkflowPlatformAdminApplication.java + application.yml
- deploy/.env.example、docker-compose.yml、scripts/compose-preflight.sh
- deploy/postgres/flowable-7.1.0/{engine,history}.create.sql(官方 DDL)
- docs/adr/0001-flowable-version-and-schema.md

### Phase 1 — 数据结构与领域模型  [✅ 完成 2026-08-15]
- [x] protocol 事件契约(EventEnvelopeV1/StartProcessCommandV1/WorkflowActionRequestedV1/WorkflowActionAppliedV1/WorkflowActionStatus/Actor/WorkflowTopics)+ ProtocolGoldenTest 3/3(round-trip + 字段名钉死)
- [x] workflow 自有 Flyway V1(6 表:wf_process_link/inbox/outbox/task_authz_sync/deployment_audit/tenant_config),四元组唯一 + WAITING_USER 偏唯一
- [x] 迁移 + 约束用 phase1-migration-smoke.sh 打真 compose PG 验证:**6/6 PASS**(建表/幂等唯一/WAITING_USER 偏唯一且 WAITING_BUSINESS 不阻塞新 cycle)
- [x] WorkflowMetadataMigrationTest(Testcontainers,Docker 不可用则跳过,CI 备用)
- [x] 审方 BPMN his-rx-review-v1.bpmn20.xml(§7.1:药师审方 UserTask→结论网关→prepareAction 写 outbox→等 message ACK→落地网关→end/人工处置)+ RxReviewBpmnModelTest 1/1
- 完成标准:迁移从空库应用过、并发 dedup 依赖的唯一约束成立、DTO golden、BPMN 模型校验。**已满足(迁移用冒烟脚本代 Testcontainers)。**
新增文件:protocol/event/*.java(7)+ test ProtocolGoldenTest;core V1__workflow_platform_metadata.sql + bpmn/his-rx-review-v1.bpmn20.xml + test WorkflowMetadataMigrationTest;server test RxReviewBpmnModelTest;deploy/scripts/phase1-migration-smoke.sh;各 pom 加 flyway/testcontainers 依赖。
### Phase 2 — 核心业务逻辑(仅审方 shadow→enforce)  [进行中]
- [x] **2a 中台侧闭环(提交 3a2d607)**:ProcessApplicationService(幂等发起)、TaskApplicationService.completeReview(同事务 complete+outbox+阶段)、MessageCorrelationService(ACK 关联,早到重试)、3 仓储、rxReviewActionOutboxDelegate、OutboxPublisher、两个 Kafka 监听器、CorrelationRetryJob。RxReviewLoopTest 打真 PG 2/2(幂等发起→PASS→关联ACK→COMPLETED;错误 actionId 被拒)。
- [x] **2b his-platform 适配器(代码完成+编译通过,未提交 —— his 仓库有你未提交的 authz 改动,留你审)**:
  outpatient V3(rx_review_cycle + workflow_inbox/outbox_event);workflow 包(WorkflowMode/Properties/KafkaConfig[String 收发]/HisWorkflowStore[JdbcTemplate]/OutboxPublisher/RxReviewWorkflowGateway/ActionListener/IntegrationConfig);
  EncounterService.submitOrders 同事务写 start 发件箱;PrescriptionReviewService 抽 doPass/doReject + pass/reject 加 shadow 镜像 + applyWorkflowDecision(enforce,经 AuthInternalApi 回查药师);application-workflow-shadow.yml(默认不激活)。**his.workflow.enabled 默认 false → 行为零改变;红线 tryBill/计费幂等未碰**。
  ⚠️ 编译要用 **system mvn**(workflow 制品在 /personal/repository,不在 ~/.m2;./mvnw 找不到)。

### Phase 3(部分,随 2b 提前)  [中台侧完成]
- [x] **3a**(提交 a9f9a2d):protocol TaskView/CompleteReviewRequest;server TaskController(查待办 + 办理,202 PENDING_BUSINESS)+ 异常映射;sdk WorkflowClient/Remote/Noop(enabled 默认 false)。TaskControllerTest 2/2。
- [x] **3b**(提交 9f0adf7):server BpmnAutoDeployer(启动部署 hisRxReview)+ shadow-e2e-runbook.md。
- [ ] 待做(本轮里程碑后):admin(部署/版本/审计)、auth workflow.zed + 鉴权三态、待办中心/流程设计器前端(走 /frontend-plan)。

### shadow 端到端  [✅ 全栈真机跑通 2026-08-15]
- **全栈实跑通过**:his-nacos/his-postgres(5433)+ 临时 Kafka(9095,避开被占的 9092)+ 中台 server(8300)+ workflow PG(25432)+ his-outpatient(9004,workflow-shadow profile)。
- `deploy/scripts/shadow-e2e-smoke.sh` **5/5 通过(可复现,enc 90001+90002)**:提交→影子 WAITING_USER;药师 legacy 通过→(SDK 镜像办理→action.requested→echo-ACK→action.applied→关联)→影子 COMPLETED;legacy `prescription_review=PASS`(权威)+ `med_order=SUBMITTED` + `encounter.billed=t`(计费幂等红线未破坏)。**4 跳 Kafka 闭环成立。**
- 完成标准全部满足:影子实例 COMPLETED 且决定==legacy;计费不受影响;去掉 profile 即回 legacy 零影响。
- **环境要点**:临时 Kafka 用 9095:9095 同号映射 + advertised localhost:9095(自洽);his-outpatient 编译/运行用 **system mvn**(his 与 workflow 制品都 install 进 /personal/repository)。

### 首轮里程碑达成度
- 用户批准里程碑=「审方 enforce」。**当前=审方 shadow 全栈跑通**(enforce 代码已写:mode=ENFORCE 时 action.requested→applyWorkflowDecision;但 enforce e2e 需药师改由中台待办办理[REST/console 触发],未跑)。
- 剩余到 enforce:Phase 3 待办中心/流程设计器前端(走 /frontend-plan)+ admin(部署/版本/审计)+ auth workflow.zed 鉴权三态;Phase 4 恢复 22 项基线→shadow 对账→enforce 灰度+故障演练。
### Phase 3 — 接口/适配层 + auth 鉴权三态(默认关)  [未开始]
### Phase 4 — 审方灰度迁移(先 legacy 恢复 22 项基线 → shadow 对账 → enforce)  [未开始]
### Phase 5 — 文档与最终检查  [未开始]

## 变更记录(与计划的偏差)
- **P1-1** protocol 的 REST API DTO(TaskView/CompleteTaskRequest 等)推迟到 Phase 3 随 controller 落地;Phase 1 protocol 只做 Kafka 事件契约(Published Language)。理由:更内聚、Phase 1 完成标准只需事件 golden。
- **P1-2** 审方 BPMN 放 `workflow-platform-core/src/main/resources/bpmn/`(classpath)而非计划的 `deploy/bpmn/`。理由:admin 用 `addClasspathResource` 部署、测试可直接加载,BPMN 作为版本化代码资源更合适。
- **P1-3** HIS 侧 rx_review_cycle + 本地 inbox/outbox 迁移(计划列在 Phase 1)挪到 **Phase 2** 与使用它们的 HIS 适配器一起落地。理由:Phase 1 保持在 workflow-platform 内自足、可独立测试;避免过早改动 his-platform。
- **P1-4** wf_* 迁移验证用 `phase1-migration-smoke.sh` 打运行中的 compose PG(与 auth/his/recsys 的 deploy/*-smoke.sh 约定一致);Testcontainers 测试保留但"Docker 不可用则跳过"。理由:本机 Testcontainers 的 docker-java 无法发现 Docker(见 [[local-dev-env]]),且冒烟脚本才是本项目群既有约定。
- **P1-5(Phase 2 生效)** Kafka 决策:workflow 平台**复用 his-platform 现有 Kafka broker(localhost:9092,apache/kafka:3.8.0)**,不另起 broker;workflow.* 为独立 topic。his 消费方对 workflow protocol DTO 用 String/byte[]+显式 ObjectMapper 解析(his 的 JsonDeserializer trusted.packages 仅 com.lrj.his.api.event)。

## 已改/新增文件清单
- 追踪:本文件 + 记忆 workflow-platform-project.md + MEMORY.md 索引。
