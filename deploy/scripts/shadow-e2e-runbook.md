# 审方 Shadow 端到端运行手册

> 目标:legacy 审方权威运行,同时中台跑一个影子流程并镜像 legacy 决定,最终影子实例 COMPLETED,可与 legacy 对账。
> 前置:workflow-platform 已 `mvn install`;his-platform his-outpatient 已用 **system mvn**(本地仓库 `/Users/liruijun/personal/repository`)编译过。

## 拓扑
- 中台:workflow-server(:8300)+ 独立 PG(:25432)。复用 his 的 Kafka(:9092)。
- his:his 基础设施(Nacos/PG/Redis/**Kafka 9092**)+ his-outpatient(:9004,`workflow-shadow` profile)。
- 事件流:his `workflow.command.start` → 中台起影子流程;药师 legacy pass → his 经 SDK 镜像办理中台任务 → 中台 `workflow.action.requested` → his echo-ACK `workflow.action.applied` → 中台影子实例 COMPLETED。

## 步骤

### 1) 起中台基础设施 + 服务
```bash
cd workflow-platform/deploy
cp -n .env.example .env
bash scripts/compose-preflight.sh && docker compose up -d      # PG 25432 / Redis 26379
cd ..
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="/Users/liruijun/personal/devUtils/apache-maven-3.9.12/bin:$PATH"
mvn -q install -DskipTests
mvn -pl workflow-platform-server spring-boot:run    # :8300,启动即部署 hisRxReview(tenant=his),jobs+listeners on
# 校验:curl -s localhost:8300/actuator/health  → UP
```

### 2) 起 his 基础设施 + his-outpatient(shadow)
```bash
cd ../his-platform
docker compose up -d        # Nacos 8848 / PG 5433 / Redis 6379 / Kafka 9092(与中台共享)
# his-outpatient 以 shadow profile 起(用 system mvn,确保能解析 /personal/repository 的 workflow 制品):
mvn -pl his-outpatient spring-boot:run \
  -Dspring-boot.run.profiles=workflow-shadow
# 或容器/jar 方式追加 SPRING_PROFILES_ACTIVE=...,workflow-shadow
```
> ⚠️ 若本机已跑 his 容器版 his-outpatient,会与本地 jar 抢 9004,二选一(见 his CLAUDE.md)。
> ⚠️ his-outpatient 依赖 Nacos 拉配置;`workflow-shadow` 是本地 profile,覆盖 his.workflow.* 与 workflow.client.*。

### 3) 触发一次审方(经网关或直连 his-outpatient)
```bash
# 建就诊→开药品医嘱→提交(具体接口见 his README;提交会:legacy 送审 + 影子发起)
# 提交后应能在中台看到影子实例:
docker exec workflow-postgres psql -U workflow -d workflow -tAc \
  "SELECT business_key,phase FROM wf_process_link WHERE business_key='<encounterId>';"   # 期望 WAITING_USER
```

### 4) 药师 legacy 审方通过 → 观察影子闭环
```bash
# 调 his 审方通过接口(PHARMACIST)。his 会:legacy 落地 + 经 SDK 镜像办理中台任务。
# 稍候(outbox/关联),中台影子实例应 COMPLETED:
docker exec workflow-postgres psql -U workflow -d workflow -tAc \
  "SELECT business_key,phase FROM wf_process_link WHERE business_key='<encounterId>';"   # 期望 COMPLETED
```

### 5) 对账
- 中台 `wf_process_link.phase=COMPLETED` 且 `wf_outbox_event` 有 action.requested、`wf_inbox_event` 有 action.applied。
- his `prescription_review` 有对应 PASS/REJECT 记录(legacy 权威),计费不受影响(`encounter.billed` 语义不变)。
- 影子决定 == legacy 决定 → shadow 对账通过。

## 回退
- his-outpatient 去掉 `workflow-shadow` profile(或 his.workflow.enabled=false)→ 立即回 legacy,零影响。中台影子数据不影响 his 业务。

## 已知注意
- workflow-server 与 his-outpatient **共用 Kafka 9092**;两侧对 workflow.* 事件都用 String + ObjectMapper 显式序列化(不动 his 的 spring.json.trusted.packages)。
- Testcontainers 在本机不可用,集成校验一律走运行中的容器 + 冒烟(见 workflow-platform 与 his 的 deploy/*.sh)。
