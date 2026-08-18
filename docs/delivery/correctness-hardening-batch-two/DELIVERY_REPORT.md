# Delivery Report

## Outcome

第二批 P0 正确性加固已完成，Gate B 通过。实现、独立复审、QA、文档/CI 和 Docker 重部署全部闭环；最终运行栈已包含当前源码。

## Requirement Coverage

| AC | Delivered behavior | Verification | Status |
| --- | --- | --- | --- |
| AC-11 | 真 PG 测试独占 Testcontainers，共享 URL 静态禁止，CI 强制非 skip | 14 个真 PG/loop cases，0 skipped | complete |
| AC-12 | outbox 全部写回校验 owner/status/未过期 lease，每行续租 | PostgreSQL stale-owner cases | complete |
| AC-13 | 有界发送、明确失败重试/FAILED、模糊结果 UNKNOWN、租户核账恢复、指标告警 | publisher/recovery/config/PG tests | complete |
| AC-14 | v1 契约、raw JSON HMAC、source/tenant allowlist、prod guard、签名保真 DLQ 重放 | codec/trust/listener/replay/guard tests | complete |
| AC-15 | phase/status 同步、CAS 重试、终态保护、admin terminate 事务化、回滚修复脚本 | unit + PG + full loop | complete |
| AC-16 | 最新实例、实例对应定义、空/错误态、缓存失效 | backend/frontend tests + live XML smoke | complete |
| AC-17 | 全量测试/build/config + 当前源码 Docker rebuild/health/schema/smoke | 109 backend、62 frontend、6 healthy、Flyway 5 | complete |

## Main Changes

- Core/database: V4 `last_error` 与 phase/status 回填；V5 DLQ 签名与外部字段扩容；outbox lease fencing/核账恢复；状态转移服务与实例 definitionId 查询。
- Server: 有界 outbox publisher、HMAC 信任验证、安全 DLQ landing/replay/stopping handler、生产 guard、租户级 recovery API、事务化终止。
- Console: 最新实例选择、实例精确 XML、空态/时间线错误、部署后 definition cache 失效。
- CI/ops: Docker 29 Testcontainers API 兼容、两套 PG 非 skip 门禁、共享 DB URL 门禁、outbox 告警、回滚后幂等修复脚本。
- Docs: README、architecture、integration guide、deploy guide、roadmap 与本批交付 artifacts 已同步。

## Build, Test, And Runtime Evidence

- Backend: `mvn test` BUILD SUCCESS；109/109，0 failure/error/skipped。
- Frontend: Vitest 19 files / 62 tests；Vite production build success，3627 modules。
- Static/config: Compose config、deploy scripts、shared-DB guard、`git diff --check` 通过。
- Docker command: `docker compose -p workflow-platform -f deploy/docker-compose.yml up -d --build`。
- Runtime: PostgreSQL、Redis、Kafka、server、admin、console 全部 healthy；server/admin health=`UP`，console=204。
- Data/schema: Flyway V3→V5；links/outbox/deployments 从 `0/0/1` 到 `0/0/1`，证明卷和已有部署保留。
- Smoke: server `/api/v1/definitions/hisRxReview/xml`=200，console 同源反代=200，console root=200。

## Review And QA

- Backend Architect: pass；租户核账边界、DLQ 自循环、Kafka timeout/interrupt 全部闭环。
- Software Architect: code Gate pass；先前租约、批量预算、source 过度声明、V4/V5 和发布语义问题均处理。
- QA/Frontend: pass；终态、空实例、时间线错误、定义缓存和最终容器时间证据均闭环。

## Rollout And Rollback

1. 生产禁止新旧 writer 混跑；停止写入并预检漂移/表大小后应用 V4/V5，再一次性发布新 writer。
2. 投产前配置 per-source HMAC 和 broker SASL/mTLS/TLS/ACL，校验 producer 超时预算，观察 FAILED/DELIVERY_UNKNOWN/DLQ 指标。
3. 紧急回滚保留 V4/V5，不做数据库降级；旧 writer 期间可能失去 phase/status 一致性。
4. 恢复新版本前停止旧 writer，执行 `deploy/sql/reconcile-process-link-status.sql`，确认漂移为 0 再恢复服务。

## Remaining External Actions

- 运行远程 GitHub Actions 并保存首次记录（本轮未 commit/push，因此未触发）。
- 以真实 IdP/JWT、Kafka principal/ACL 和生产级密钥做 shadow 验证。
- 在多副本环境进行容量、kill/restart、Kafka/PG 故障注入和备份恢复演练。
- 后续增加 HMAC `kid`/双钥轮换；这不阻断本批 AC-11–17。
