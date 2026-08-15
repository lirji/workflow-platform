# ADR 0001:Flowable 版本锁定与 schema 管理

- 状态:已接受(Phase 0 spike 实测通过)
- 日期:2026-08-15

## 背景

FINAL_PLAN 把"Flowable 7.x 与 Spring Boot 3.3.5 / JDK21 的兼容性"列为唯一未实证项、Phase 0 硬门禁,不过不进 Phase 1。需要真跑坐实版本、锁定官方 DDL、确认关键运行时能力。

## 决策

1. **锁定 `flowable.version = 7.1.0`**,starter 用 `flowable-spring-boot-starter-process`,版本经 `flowable-bom` 统一管理(父 pom `dependencyManagement` import)。
2. **生产 schema 由版本化官方 DDL 管理,`flowable.database-schema-update=false`**。已从 `flowable-engine-7.1.0.jar` 提取官方 PostgreSQL 建表脚本固化到 `deploy/postgres/flowable-7.1.0/`:
   - `flowable.postgres.create.engine.sql`(7 张 create table:ACT_RE_/ACT_RU_/ACT_GE_ 等)
   - `flowable.postgres.create.history.sql`(5 张 ACT_HI_*)
   dev 环境允许 `database-schema-update=true` 由引擎自建以加速迭代(server/admin 的 application.yml 现为 dev 默认)。
3. **admin 关闭 async executor(`async-executor-activate=false`),server 开启**;两者连同一 workflow 库。
4. **动态加签能力可用**:并行会签(multi-instance UserTask)可用 `RuntimeService.addMultiInstanceExecution(activityId, parentExecutionId, vars)` 运行时加签,其中 `parentExecutionId` 传 MI 根执行的父(顶层 MI 活动即流程实例根执行,id == processInstanceId)。因此 FINAL_PLAN §7.3 的"加签待验证"转为**支持**,无需在 v1 收窄为 FEATURE_NOT_SUPPORTED。

## 实测证据

H2 spike(`FlowableSpikeTest`,4/4 通过):
- 引擎启动 + tenant 部署 + 带 businessKey 发起 + UserTask(候选组)办理 + 历史落地(businessKey 保留)。
- 20 并发发起(不同 businessKey)各自独立成实例、各生成一个待办,零失败。
- 同 key 重复部署产生 v1/v2 两个版本。
- 并行会签运行时动态加签:2 → 3 个待办。

PostgreSQL 侧(server 连 25432 实跑):
- 引擎自动建 **39 张 ACT_* 表**,`act_ge_property.schema.version = 7.1.0.2`。
- `docker compose restart postgres` 后:数据与表数(39)、schema.version 不变(卷持久化);server Hikari 首次重试即 `/actuator/health` 200(连接恢复)。

## 影响 / 后续

- Phase 1 起:平台自有 `wf_*` 元数据表用 **Flyway** 管理(与 Flowable 自建的 ACT_* 分离);生产部署改 `database-schema-update=false` 并用固化的官方 DDL 初始化 ACT_*。
- 备选:7.1.0 若后续暴露问题,候选降级 7.0.x 或评估 7.2.0(其对齐 Boot 3.5.x,与固定 3.3.5 BOM 冲突风险更高)。不选 Flowable 8(Boot 4/Jackson 3 世代)。
