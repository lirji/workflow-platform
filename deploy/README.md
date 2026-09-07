# 部署(deploy)

流程/审批中台本地/单机全栈：`server(:8300) + admin(:8301) + console(:8302)`；PostgreSQL 16 与 Kafka 3.8 统一复用同级 `dev-infra`，不再重复启动公共组件。Redis 当前没有代码依赖，已取消预留容器。

## 一键起全栈

```bash
cd ../dev-infra
./bin/dev-infra up postgres16 kafka38       # 首次先按该仓库 README 完成 init

cd ../workflow-platform/deploy
cp .env.example .env                     # 按需改端口/开关
./scripts/init-dev-infra-resources.sh     # 首次幂等创建 database/role/topics
./scripts/compose-preflight.sh            # 校验共享服务、网络和应用端口
./compose.sh up -d --build                # 首次构建镜像(in-Docker Maven,较慢)
./compose.sh ps
curl -s localhost:${WORKFLOW_SERVER_PORT:-8300}/actuator/health   # {"status":"UP"}
curl -i localhost:${WORKFLOW_UI_PORT:-8302}/healthz               # HTTP 204
```

## 统一链路追踪

需要查看 server、admin 及跨平台请求瀑布图时，先启动共享观测栈，再叠加观测文件：

```bash
cd ../../dev-infra && make marketing-obs
cd ../workflow-platform/deploy
./compose.sh -f docker-compose.yml -f compose.observability.yml up -d --build
```

两个后端分别使用 `workflow-platform-server`、`workflow-platform-admin` 服务名，复用共享 OpenTelemetry Java Agent 并向 `infra-otel-collector:4318` 发送 trace。Grafana 地址为 `http://127.0.0.1:3001`。本地默认全采样；通过 `.env` 的 `OTEL_TRACES_SAMPLER=traceidratio`、`OTEL_TRACES_SAMPLER_ARG=0.1` 降采样，或以 `OTEL_SDK_DISABLED=true` 停用。HTTP 与直接 Kafka 调用自动传播 W3C 上下文；outbox、DLQ 重放及长流程跨越持久化异步边界时会形成新 trace，需结合 `eventId/actionId/businessKey` 与审计日志关联。完整规则见同级 `dev-infra/docs/observability.md`。

- 后端镜像:`deploy/Dockerfile` 多阶段(build 全 reactor → server/admin 各取可执行 jar)。构建上下文=仓库根(见根 `.dockerignore`)。
- 前端镜像:`workflow-console/Dockerfile` 构建 Vite 产物并由 nginx 托管；`/api` 同源反代到 compose 服务 `server:8300`。
- server/admin 通过外部网络 `dev-infra` 连接 `infra-postgres16:5432`、`infra-kafka38:9092`。
- 宿主机直接运行时，默认连接 `127.0.0.1:45432/workflow` 和 `127.0.0.1:49092`；端口以 `dev-infra/.env` 为准。
- 内部服务端口继续由本项目 `.env` 管理；浏览器入口只由 `auth-platform/deploy/platform-ports.env` 的 `WORKFLOW_UI_PORT` 管理。

## 纪律与坑

- **端口冲突**：若已在 host 用 `mvn spring-boot:run` 跑 server(:8300)，勿同时 `compose up server`；预检会明确失败，不会自动换端口。
- **资源隔离**：PostgreSQL 使用独立 database/role `workflow`；Kafka 使用 `workflow.*` topic 和 `workflow-server*` consumer group。不要对共享实例执行删库、`FLUSHALL` 或批量删 topic。
- **启动顺序**：跨 Compose 不能使用 `depends_on`。先启动 `dev_infra`，再运行预检和本项目 Compose；应用启动失败会有限次重启。
- **Redis**：首期正确性、缓存和限流代码均未引用 Redis，因此不连接共享 Redis，也不迁移旧 Redis 空实例。

## 共享资源与迁移

- 2026-09-05 已把旧 `workflow-postgres` 的 `workflow` 库迁到 `dev-infra` PostgreSQL；53 张 public 表逐表校验行数和内容哈希一致。
- 迁移前备份保存在 `dev-infra/backups/workflow-platform/`（该目录不提交 Git），旧卷保留用于回滚。
- 旧 Kafka 的四个 topic 均已过保留期（earliest offset 等于 latest offset），没有可复制记录；共享 Kafka 已存在五个 `workflow.*` topic，现有消费位点保持不变。
- 旧 Redis 没有业务依赖，迁移时 `DBSIZE=0`，无需复制。旧 PostgreSQL/Kafka/Redis 容器只停止、不删除数据卷。

回滚时先停止所有 workflow writer，再把 `WORKFLOW_DB_URL` 切回 `jdbc:postgresql://127.0.0.1:25432/workflow`，启动旧 PostgreSQL；Kafka 回滚前需单独核对共享集群中新产生的消息，禁止直接覆盖消费位点。

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
WORKFLOW_KAFKA_SOURCE_TENANT_BINDINGS=his-outpatient=his,benefit-center=benefit-prod
WORKFLOW_KAFKA_SOURCE_SIGNING_KEYS=his-outpatient=<Base64URL密钥>,benefit-center=<Base64URL密钥>,workflow-server=<Base64URL密钥>
WORKFLOW_FLOWABLE_SCHEMA_UPDATE=false
WORKFLOW_PG_PASSWORD=<强随机密码>
```

`prod` 启动 guard 会拒绝关闭鉴权、缺 issuer/audience/tenant claim、缺 Kafka source→tenant allowlist/per-source HMAC 密钥、开启 Flowable 自动改表、开启试点 BPMN 自动部署或使用默认数据库密码。JWT 租户 claim 是 REST 可信来源；Kafka producer 必须对精确原始 JSON 计算 HMAC-SHA256 并发送 Base64URL `workflow-signature-v1` header。应用层 HMAC/allowlist 不能替代 broker SASL/TLS/ACL。详见 `docs/integration-guide.md` §4.3。

每把解码后的 HMAC 密钥至少 32 字节。`workflow-server` 虽不是入站 tenant binding，也必须配置，因为平台需要用它签名 `workflow.action.requested.v1`；权益环境还要把 `WORKFLOW_BENEFIT_TENANT` 设为与 `benefit-center` binding 相同的 tenant。

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

**干净开发库建表**：先在共享 PostgreSQL 创建独立 `workflow` database/role；开发环境启动应用后，Flowable 创建 `ACT_*`，Flyway 创建 `wf_*`。`WORKFLOW_FLOWABLE_SCHEMA_UPDATE=false` 的生产式初始化需先人工执行固化 Flowable DDL，再启动应用，不能依赖共享 PostgreSQL 的 entrypoint。

**迁移冒烟**(不依赖 Testcontainers,在 dev_infra PostgreSQL 上用进程专属 scratch 库):
```bash
bash deploy/scripts/phase1-migration-smoke.sh   # 应用全部 V*.sql,校验 7 张 wf_ 表 + 唯一/偏唯一约束
```

**回滚后恢复新版 writer**:数据库保留 V4/V5，不执行降级。先停止所有旧 writer，使用应用数据库账号执行 `psql -v ON_ERROR_STOP=1 -f deploy/sql/reconcile-process-link-status.sql`；确认 `drifted_rows_after_repair=0` 后，再一次性恢复全部新 writer。脚本只修复 `wf_process_link.phase/status`，不删除业务数据。
