#!/usr/bin/env bash
# 起容器前预检:先按独立 project name 清理(防 docker-proxy 残留占端口,risk/auth 都踩过),再核对端口空闲。
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT="${COMPOSE_PROJECT_NAME:-workflow-platform}"

echo "==> down --remove-orphans (project=${PROJECT})"
docker compose -p "${PROJECT}" down --remove-orphans || true

# 从 .env 读端口(缺省回退)
PG_PORT="${WORKFLOW_PG_PORT:-25432}"
REDIS_PORT="${WORKFLOW_REDIS_PORT:-26379}"

for p in "${PG_PORT}" "${REDIS_PORT}"; do
  if lsof -nP -iTCP:"${p}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "!! 端口 ${p} 仍被占用,请先释放:"
    lsof -nP -iTCP:"${p}" -sTCP:LISTEN | tail -n +1
    exit 1
  fi
  echo "端口 ${p} 空闲"
done
echo "==> 预检通过"
