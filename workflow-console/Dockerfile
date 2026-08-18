# ---- build ----
FROM node:20-alpine AS build
WORKDIR /app
# 与本仓库生成 lockfile 的 pnpm 版本保持一致，避免 Corepack 自动选到要求 Node 22 的 pnpm 11。
RUN corepack enable && corepack prepare pnpm@9.15.9 --activate
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
# Vite 环境变量在构建期写入静态产物；compose 通过 build args 统一传入。
ARG VITE_API_BASE_URL=
ARG VITE_WORKFLOW_TENANT=his
ARG VITE_AUTH_ENABLED=false
ARG VITE_CASDOOR_AUTHORITY=http://localhost:8000
ARG VITE_CASDOOR_CLIENT_ID=
ARG VITE_OIDC_SCOPE="openid profile"
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL} \
    VITE_WORKFLOW_TENANT=${VITE_WORKFLOW_TENANT} \
    VITE_AUTH_ENABLED=${VITE_AUTH_ENABLED} \
    VITE_CASDOOR_AUTHORITY=${VITE_CASDOOR_AUTHORITY} \
    VITE_CASDOOR_CLIENT_ID=${VITE_CASDOOR_CLIENT_ID} \
    VITE_OIDC_SCOPE=${VITE_OIDC_SCOPE}
RUN pnpm build

# ---- serve ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8302
CMD ["nginx", "-g", "daemon off;"]
