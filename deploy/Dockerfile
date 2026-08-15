# syntax=docker/dockerfile:1
# 多阶段:build 一次编译全 reactor,server / admin 各取自己的可执行 jar 组成运行时镜像。
# compose 用 target=server|admin 复用同一 build 阶段(BuildKit 缓存),避免重复编译。

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# 缓存本地仓库,加速重复构建;跳过测试(测试在 CI/host 跑,镜像只负责打包)。
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests clean package

FROM eclipse-temurin:21-jre AS server
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/workflow-platform-server/target/workflow-platform-server-*.jar /app/app.jar
EXPOSE 8300
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:21-jre AS admin
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/workflow-platform-admin/target/workflow-platform-admin-*.jar /app/app.jar
EXPOSE 8301
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
