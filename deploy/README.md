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
