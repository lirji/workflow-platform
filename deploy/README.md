# 部署(deploy)

流程/审批中台本地/单机全栈:PostgreSQL + Redis + Kafka(KRaft)+ server(:8300)+ admin(:8301)。
前端 `workflow-console` 独立构建(见其 Dockerfile/nginx),此处不含。

## 一键起全栈

```bash
cd deploy
cp .env.example .env                     # 按需改端口/开关
docker compose -p workflow-platform down --remove-orphans   # 预检:清残留(见 compose-preflight.sh)
docker compose -p workflow-platform up -d --build            # 首次构建镜像(in-Docker Maven,较慢)
docker compose -p workflow-platform ps
curl -s localhost:${WORKFLOW_SERVER_PORT:-8300}/actuator/health   # {"status":"UP"}
```

- 镜像:`deploy/Dockerfile` 多阶段(build 全 reactor → server/admin 各取可执行 jar)。构建上下文=仓库根(见根 `.dockerignore`)。
- server/admin 连容器内 `postgres:5432`、`kafka:9092`;host 访问 Kafka 用 `:${WORKFLOW_KAFKA_HOST_PORT}`(默认 29092)。
- 端口全变量化,避开现有项目占用(his 9000-9007 / auth 8000,8200-8202 / langchain4j 9092 等)。

## 纪律与坑

- **端口冲突**:若已在 host 用 `mvn spring-boot:run` 跑 server(:8300)或跑着 shadow 联调栈,勿同时 `compose up server`(会抢 :8300);改 `WORKFLOW_SERVER_PORT` 或先停 host 实例。
- **复用现有 PG/Redis**:compose 用固定 `container_name`(workflow-postgres/redis),已在跑则复用(数据卷 `workflow-pg-data` 保留);Flyway 幂等续跑迁移(baseline + V1/V2)。
- **Kafka 独立**:本 compose 的 Kafka 用 `kafka:9092`(容器内)/`:29092`(host),与其它项目 :9092、shadow 临时 :9095 隔离。
- 起前务必 `down --remove-orphans`(risk/auth/his 都踩过 docker-proxy 残留占端口)。

## 鉴权

生产置 `WORKFLOW_SECURITY_ENABLED=true` 并配 `WORKFLOW_OIDC_JWKS`(Casdoor JWKS);租户从 JWT 派生需配 `WORKFLOW_TENANT_CLAIM`。详见 `docs/integration-guide.md` §4.3。

## 监控与告警

- **指标**:`workflow-platform-server` 暴露 `/actuator/prometheus`(WorkflowMetrics:发起/审方完成/落地/关联结果/DLQ/运维 计数器)。Prometheus 抓取 job 建议名 `workflow-platform-server`。
- **审计日志**:独立 logger `WORKFLOW_AUDIT`(key=value),记审方完成/运维干预/DLQ 重放,建议单独采集分流。
- **告警规则**:`deploy/prometheus/alerts.yml`(DLQ 落地、关联不匹配、终态失败、驳回率、server down),挂到 Prometheus `rule_files`。
- **生命周期事件**:server best-effort 投 `workflow.lifecycle.v1`(STARTED/COMPLETED/INCIDENT),供看板/观察者订阅(不参与正确性)。

## Schema 与迁移(ADR 0001)

两类表分开管理:
- **平台自有 `wf_*`**(process_link / inbox / outbox / dlq / task_authz_sync / deployment_audit / tenant_config):由 **Flyway** 版本化(`workflow-platform-core/.../db/migration/V*.sql`),应用启动时自动应用(baseline 既有 schema)。
- **Flowable `ACT_*`**:dev 由引擎自建(`WORKFLOW_FLOWABLE_SCHEMA_UPDATE=true`);**生产置 `false`**,用固化的官方 DDL 初始化——`deploy/postgres/flowable-7.1.0/{engine,history}.sql`(锁定 7.1.0)。

**干净库一键建表**:compose 把上述 Flowable DDL 挂到 postgres `docker-entrypoint-initdb.d`(仅空卷首次执行),应用启动再由 Flyway 建 `wf_*`。因此 `WORKFLOW_FLOWABLE_SCHEMA_UPDATE=false` 下全新库也能一次起好。

**迁移冒烟**(不依赖 Testcontainers,在运行中的 compose PG 上用 scratch 库):
```bash
bash deploy/scripts/phase1-migration-smoke.sh   # 应用全部 V*.sql,校验 7 张 wf_ 表 + 唯一/偏唯一约束
```
