# 部署(deploy)

流程/审批中台本地/单机全栈:PostgreSQL + Redis + Kafka(KRaft)+ server(:8300)+ admin(:8301)+ console(:8302)。

## 一键起全栈

```bash
cd deploy
cp .env.example .env                     # 按需改端口/开关
docker compose -p workflow-platform down --remove-orphans   # 预检:清残留(见 compose-preflight.sh)
docker compose -p workflow-platform up -d --build            # 首次构建镜像(in-Docker Maven,较慢)
docker compose -p workflow-platform ps
curl -s localhost:${WORKFLOW_SERVER_PORT:-8300}/actuator/health   # {"status":"UP"}
curl -i localhost:${WORKFLOW_CONSOLE_PORT:-8302}/healthz          # HTTP 204
```

- 后端镜像:`deploy/Dockerfile` 多阶段(build 全 reactor → server/admin 各取可执行 jar)。构建上下文=仓库根(见根 `.dockerignore`)。
- 前端镜像:`workflow-console/Dockerfile` 构建 Vite 产物并由 nginx 托管；`/api` 同源反代到 compose 服务 `server:8300`。
- server/admin 连容器内 `postgres:5432`、`kafka:9092`;host 访问 Kafka 用 `:${WORKFLOW_KAFKA_HOST_PORT}`(默认 29092)。
- 端口全变量化,避开现有项目占用(his 9000-9007 / auth 8000,8200-8202 / langchain4j 9092 等)。默认前端入口为 `http://localhost:8302/login`。

## 纪律与坑

- **端口冲突**:若已在 host 用 `mvn spring-boot:run` 跑 server(:8300)或跑着 shadow 联调栈,勿同时 `compose up server`(会抢 :8300);改 `WORKFLOW_SERVER_PORT` 或先停 host 实例。
- **复用现有 PG/Redis**:compose 用固定 `container_name`(workflow-postgres/redis),已在跑则复用(数据卷 `workflow-pg-data` 保留);Flyway 幂等续跑迁移(baseline + V1–V5)。
- **Kafka 独立**:本 compose 的 Kafka 用 `kafka:9092`(容器内)/`:29092`(host),与其它项目 :9092、shadow 临时 :9095 隔离。
- 起前务必 `down --remove-orphans`(risk/auth/his 都踩过 docker-proxy 残留占端口)。

## 鉴权

生产使用 `prod` profile，并至少配置：

```dotenv
WORKFLOW_SPRING_PROFILES_ACTIVE=prod
WORKFLOW_SECURITY_ENABLED=true
WORKFLOW_OIDC_ISSUER=https://sso.example.com
WORKFLOW_OIDC_JWKS=https://sso.example.com/.well-known/jwks
WORKFLOW_OIDC_AUDIENCE=workflow-platform
WORKFLOW_TENANT_CLAIM=tenant_id
WORKFLOW_KAFKA_TRUST_ENABLED=true
WORKFLOW_KAFKA_SOURCE_TENANT_BINDINGS=his-outpatient=his
WORKFLOW_KAFKA_SOURCE_SIGNING_KEYS=his-outpatient=<至少32字节随机密钥的Base64URL>
WORKFLOW_FLOWABLE_SCHEMA_UPDATE=false
WORKFLOW_PG_PASSWORD=<强随机密码>
```

`prod` 启动 guard 会拒绝关闭鉴权、缺 issuer/audience/tenant claim、缺 Kafka source→tenant allowlist/per-source HMAC 密钥、开启 Flowable 自动改表、开启试点 BPMN 自动部署或使用默认数据库密码。JWT 租户 claim 是 REST 可信来源；Kafka producer 必须对精确原始 JSON 计算 HMAC-SHA256 并发送 Base64URL `workflow-signature-v1` header。应用层 HMAC/allowlist 不能替代 broker SASL/TLS/ACL。详见 `docs/integration-guide.md` §4.3。

## 监控与告警

- **指标**:`workflow-platform-server` 暴露 `/actuator/prometheus`(WorkflowMetrics:发起/审方完成/落地/关联结果/outbox FAILED/DELIVERY_UNKNOWN/DLQ/运维计数器)。鉴权开启时抓取请求需带 `OBSERVABILITY` 或 `ADMIN` 权限的 Bearer Token；Prometheus job 建议名 `workflow-platform-server`。
- **审计日志**:独立 logger `WORKFLOW_AUDIT`(key=value),记审方完成/运维干预/DLQ 重放,建议单独采集分流。
- **告警规则**:`deploy/prometheus/alerts.yml`(outbox 超限失败、DLQ 落地、关联不匹配、终态失败、驳回率、server down),挂到 Prometheus `rule_files`。
- **生命周期事件**:server best-effort 投 `workflow.lifecycle.v1`(STARTED/COMPLETED/INCIDENT),供看板/观察者订阅(不参与正确性)。

## HA / 水平扩展

server 无状态,可多副本水平扩展。多副本下的正确性由以下机制保证(**均已实现**):
- **outbox**:`claimBatch` 用 `FOR UPDATE SKIP LOCKED` + 一次性 fencing token 领取；发送前逐行续租，完成写回均校验 `PROCESSING + lease_owner + lease 未过期`，旧租约不能覆盖新 owner。明确 broker 失败才退避重试并在超限后落 `FAILED`；等待超时/取消/中断意味着投递结果不确定，落 `DELIVERY_UNKNOWN` 且不自动重发，两种终态都保留 `last_error` 并告警。业务消费者仍须按事件 ID 幂等。
- **inbox**:listener 在事务内完成领取/处理/状态更新；按 `eventId` 去重，关联重试用 `SKIP LOCKED` + 租约，多副本安全接管；业务再按 `actionId` 二次幂等。
- **DLQ**:落库时保存原 `workflow-signature-v1`，重放时原样携带且只允许两个平台入站 topic；平台不会为缺失/无效签名重新签名。DLQ 落库失败会停止其独立 listener（位点不提交、不会自回投）；数据库恢复后重启 server。
- **Flowable 作业**:每副本各跑一套 async executor,作业获取用 `ACT_RU_JOB` 悲观锁,多节点安全(不会重复执行同一 job)。
- **发起幂等**:`wf_process_link` 四元组唯一 + WAITING_USER 偏唯一约束,并发发起收敛到一个实例。

### 调优(按压测结果调整)
| 项 | 环境变量 | 默认 |
|---|---|---|
| async executor 核心/最大线程 | `WORKFLOW_ASYNC_CORE_POOL` / `WORKFLOW_ASYNC_MAX_POOL` | 8 / 8 |
| async executor 队列 / 每次获取作业数 | `WORKFLOW_ASYNC_QUEUE` / `WORKFLOW_ASYNC_MAX_JOBS` | 100 / 8 |
| outbox 批量 / 轮询 / 租约 | `workflow.outbox.*`(batch-size/poll-ms/lease-seconds) | 100 / 1000 / 30 |
| outbox ACK 超时 / 最大尝试 / 基础退避 | `WORKFLOW_OUTBOX_SEND_TIMEOUT_SECONDS` / `WORKFLOW_OUTBOX_MAX_ATTEMPTS` / `WORKFLOW_OUTBOX_RETRY_BACKOFF_SECONDS` | 10 / 10 / 5 |
| DLQ 重试 / 退避 | `workflow.dlq.*`(max-attempts/backoff-ms) | 3 / 1000 |

### 水平扩展方式
compose 用固定 `container_name`/端口,不能直接 `--scale`;生产用 K8s Deployment `replicas>1`(去掉固定名/用 Service 负载均衡),或去掉 compose 的 container_name + 用端口范围。所有副本连同一 PG + Kafka。

### 压测方案(需多节点环境执行,本仓库不含负载环境)
1. 起 N(≥2)个 server 副本 + 1 PG + Kafka;用 `deploy/scripts/shadow-e2e-smoke.sh` 造种子,批量发 `command.start`(如 1k~10k 不同 businessKey)。
2. 观测 `/actuator/prometheus`:`workflow_process_started_total`、outbox 积压(`wf_outbox_event status=READY` 计数)、关联结果分布、无 `ACTION_MISMATCH`。
3. 断言:实例数 == 发起数(幂等无重复/丢失)、每 businessKey 恰一待办、outbox 最终清空、无重复 Kafka 消费(inbox 去重)。
4. 混沌:压测中 `kill` 一个副本,验证租约到期后另一副本接管未发 outbox、Flowable 作业不重复执行。

### DELIVERY_UNKNOWN 核账恢复

1. 收到 `WorkflowOutboxDeliveryUnknown` 后先按 `eventId` 查询目标 topic/消费者 inbox；已送达则保持原记录，禁止重发。
2. 只有确认目标未收到时，ADMIN 才调用 `POST /api/v1/admin/outbox/{eventId}/requeue-delivery-unknown?reason=<核账证据>`；服务以同一 eventId 放回 `READY` 并写审计日志。
3. 无法确认时升级人工处置，不得通过改库或反复调用接口猜测；目标消费者仍必须按 eventId/actionId 幂等。

## Schema 与迁移(ADR 0001)

两类表分开管理:
- **平台自有 `wf_*`**(process_link / inbox / outbox / dlq / task_authz_sync / deployment_audit / tenant_config):由 **Flyway** 版本化(`workflow-platform-core/.../db/migration/V*.sql`),应用启动时自动应用(baseline 既有 schema)。
- **Flowable `ACT_*`**:dev 由引擎自建(`WORKFLOW_FLOWABLE_SCHEMA_UPDATE=true`);**生产置 `false`**,用固化的官方 DDL 初始化——`deploy/postgres/flowable-7.1.0/{engine,history}.sql`(锁定 7.1.0)。

**干净库一键建表**:compose 把上述 Flowable DDL 挂到 postgres `docker-entrypoint-initdb.d`(仅空卷首次执行),应用启动再由 Flyway 建 `wf_*`。因此 `WORKFLOW_FLOWABLE_SCHEMA_UPDATE=false` 下全新库也能一次起好。

**迁移冒烟**(不依赖 Testcontainers,在运行中的 compose PG 上用 scratch 库):
```bash
bash deploy/scripts/phase1-migration-smoke.sh   # 应用全部 V*.sql,校验 7 张 wf_ 表 + 唯一/偏唯一约束
```

**回滚后恢复新版 writer**:数据库保留 V4/V5，不执行降级。先停止所有旧 writer，使用应用数据库账号执行 `psql -v ON_ERROR_STOP=1 -f deploy/sql/reconcile-process-link-status.sql`；确认 `drifted_rows_after_repair=0` 后，再一次性恢复全部新 writer。脚本只修复 `wf_process_link.phase/status`，不删除业务数据。
