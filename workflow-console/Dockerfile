# ---- build ----
FROM node:20-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
# 产物为纯静态(baseURL 空 → 相对 /api,交给 nginx 同源反代);鉴权/租户经运行时 env 由前端读取。
RUN pnpm build

# ---- serve ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8302
CMD ["nginx", "-g", "daemon off;"]
