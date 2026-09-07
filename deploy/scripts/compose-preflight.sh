#!/usr/bin/env bash
# 起应用容器前预检：清理本项目容器，并确认 dev_infra 的 PostgreSQL/Kafka 已就绪。
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT="${COMPOSE_PROJECT_NAME:-workflow-platform}"

PLATFORM_PORTS_LOADER="${PLATFORM_PORTS_LOADER:-../../auth-platform/deploy/load-platform-ports.sh}"
if [[ -r "${PLATFORM_PORTS_LOADER}" ]]; then
  # shellcheck source=/dev/null
  . "${PLATFORM_PORTS_LOADER}"
fi
WORKFLOW_UI_PORT="${WORKFLOW_UI_PORT:-8302}"

echo "==> down --remove-orphans (project=${PROJECT})"
COMPOSE_PROJECT_NAME="${PROJECT}" ./compose.sh down --remove-orphans || true

INFRA_NETWORK="${DEV_INFRA_NETWORK:-dev-infra}"
if ! docker network inspect "${INFRA_NETWORK}" >/dev/null 2>&1; then
  echo "!! 共享网络 ${INFRA_NETWORK} 不存在；请先在 dev-infra 执行 make init" >&2
  exit 1
fi

for service in postgres16 kafka38; do
  container_id="$(docker ps \
    --filter "network=${INFRA_NETWORK}" \
    --filter "label=com.docker.compose.service=${service}" \
    --filter status=running \
    --format '{{.ID}}' | head -n 1)"
  if [[ -z "${container_id}" ]]; then
    echo "!! dev_infra 服务 ${service} 未运行；请执行 ../../dev-infra/bin/dev-infra up ${service}" >&2
    exit 1
  fi
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container_id}")"
  if [[ "${health}" != "healthy" ]]; then
    echo "!! dev_infra 服务 ${service} 状态为 ${health}，请等待其健康后重试" >&2
    exit 1
  fi
  echo "dev_infra ${service} healthy"
done

SERVER_PORT="${WORKFLOW_SERVER_PORT:-8300}"
ADMIN_PORT="${WORKFLOW_ADMIN_PORT:-8301}"
for p in "${SERVER_PORT}" "${ADMIN_PORT}" "${WORKFLOW_UI_PORT}"; do
  if lsof -nP -iTCP:"${p}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "!! 端口 ${p} 仍被占用,请先释放:"
    lsof -nP -iTCP:"${p}" -sTCP:LISTEN | tail -n +1
    exit 1
  fi
  echo "端口 ${p} 空闲"
done
echo "==> 预检通过"
