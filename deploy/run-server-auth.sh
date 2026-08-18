#!/usr/bin/env bash
# 鉴权版启动 workflow-platform-server(:8300) —— Casdoor OIDC 全强制。
# 在自己的终端里跑(前台常驻);后端明文头 shadow 联调会 401,要联调改用关鉴权版。
# 前端另开一个终端:cd workflow-console && pnpm dev(.env.local 已设 VITE_AUTH_ENABLED=true)。
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="/Users/liruijun/personal/devUtils/apache-maven-3.9.12/bin:$PATH"
export WORKFLOW_KAFKA="${WORKFLOW_KAFKA:-localhost:9095}"
export WORKFLOW_SECURITY_ENABLED=true
export WORKFLOW_OIDC_JWKS="${WORKFLOW_OIDC_JWKS:-http://localhost:8000/.well-known/jwks}"

echo "启动鉴权版 server :8300  (SECURITY_ENABLED=true, JWKS=$WORKFLOW_OIDC_JWKS, KAFKA=$WORKFLOW_KAFKA)"
exec mvn -pl workflow-platform-server spring-boot:run
