#!/usr/bin/env bash
# Workflow Compose 统一入口：本地工作区自动加载 auth-platform 中央门户端口。
# 加 --secure 叠加 compose.secure.yml（Casdoor JWT + 控制台 OIDC）。

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_PORTS_LOADER="${PLATFORM_PORTS_LOADER:-${SCRIPT_DIR}/../../auth-platform/deploy/load-platform-ports.sh}"
ENV_ARGS=()
[[ -f "${SCRIPT_DIR}/.env" ]] && ENV_ARGS+=(--env-file "${SCRIPT_DIR}/.env")
if [[ -r "${PLATFORM_PORTS_LOADER}" ]]; then
  # shellcheck source=/dev/null
  . "${PLATFORM_PORTS_LOADER}"
  ENV_ARGS+=(--env-file "${PLATFORM_PORTS_FILE}")
fi
export WORKFLOW_UI_PORT="${WORKFLOW_UI_PORT:-8302}"
PROJECT="${COMPOSE_PROJECT_NAME:-workflow-platform}"
cd "${SCRIPT_DIR}"

SECURE=0
COMPOSE_ARGS=()
for arg in "$@"; do
  if [[ "${arg}" == "--secure" ]]; then
    SECURE=1
  else
    COMPOSE_ARGS+=("${arg}")
  fi
done
if [[ "${SECURE}" -eq 1 ]]; then
  exec docker compose "${ENV_ARGS[@]}" -p "${PROJECT}" \
    -f docker-compose.yml -f compose.secure.yml "${COMPOSE_ARGS[@]}"
fi
exec docker compose "${ENV_ARGS[@]}" -p "${PROJECT}" -f docker-compose.yml "${COMPOSE_ARGS[@]}"
