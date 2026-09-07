#!/usr/bin/env bash
# 在共享 dev_infra 中幂等创建本项目专属 database/role 与 Kafka topics。
# 已存在的数据库账号不会被改密；脚本会用项目 .env 中的密码做 TCP 登录校验，避免静默使用错误凭据。
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${WORKFLOW_ENV_FILE:-${DEPLOY_DIR}/.env}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}；请先复制 deploy/.env.example" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

INFRA_NETWORK="${DEV_INFRA_NETWORK:-dev-infra}"
PG_CONTAINER="$(docker ps \
  --filter "network=${INFRA_NETWORK}" \
  --filter 'label=com.docker.compose.service=postgres16' \
  --filter status=running \
  --format '{{.ID}}' | head -n 1)"
KAFKA_CONTAINER="$(docker ps \
  --filter "network=${INFRA_NETWORK}" \
  --filter 'label=com.docker.compose.service=kafka38' \
  --filter status=running \
  --format '{{.ID}}' | head -n 1)"

if [[ -z "${PG_CONTAINER}" || -z "${KAFKA_CONTAINER}" ]]; then
  echo "dev_infra PostgreSQL/Kafka 未同时运行；请先执行 dev-infra/bin/dev-infra up postgres16 kafka38" >&2
  exit 1
fi

APP_DB="${WORKFLOW_PG_DB:-workflow}"
APP_USER="${WORKFLOW_PG_USER:-workflow}"
APP_PASS="${WORKFLOW_PG_PASSWORD:?set WORKFLOW_PG_PASSWORD in deploy/.env}"

docker exec -i \
  -e APP_DB="${APP_DB}" \
  -e APP_USER="${APP_USER}" \
  -e APP_PASS="${APP_PASS}" \
  "${PG_CONTAINER}" sh -lc \
  'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v app_db="$APP_DB" -v app_user="$APP_USER" -v app_pass="$APP_PASS"' <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_pass')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec
SELECT format('CREATE DATABASE %I OWNER %I ENCODING %L TEMPLATE template0', :'app_db', :'app_user', 'UTF8')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'app_db') \gexec
SQL

# 必须走 TCP 才会验证密码；容器内 Unix socket 可能配置为 trust。
docker exec -e PGPASSWORD="${APP_PASS}" "${PG_CONTAINER}" \
  psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "${APP_USER}" -d "${APP_DB}" -Atc 'SELECT 1' >/dev/null

DB_OWNER="$(docker exec -e PGPASSWORD="${APP_PASS}" "${PG_CONTAINER}" \
  psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "${APP_USER}" -d "${APP_DB}" -Atc \
  'SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()')"
if [[ "${DB_OWNER}" != "${APP_USER}" ]]; then
  echo "database ${APP_DB} owner=${DB_OWNER}，期望 ${APP_USER}；为避免越权修改，脚本停止" >&2
  exit 1
fi

TOPICS=(
  workflow.command.start.v1
  workflow.action.requested.v1
  workflow.action.applied.v1
  workflow.lifecycle.v1
  workflow.dlq.v1
)
for topic in "${TOPICS[@]}"; do
  docker exec "${KAFKA_CONTAINER}" /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create --if-not-exists \
    --topic "${topic}" \
    --partitions "${WORKFLOW_KAFKA_PARTITIONS:-3}" \
    --replication-factor 1 >/dev/null
done

echo "dev_infra resources ready: database=${APP_DB}, topics=${#TOPICS[@]}"
