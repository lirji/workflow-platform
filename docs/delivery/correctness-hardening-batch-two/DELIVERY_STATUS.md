# Delivery Status

## Goal

完成工作流平台第二批 P0 正确性加固，并以自动化测试和 Docker 全栈健康状态作为交付证据。

## State

- Phase: Phase 9 · Delivery complete
- Status: Gate B passed
- Last updated: 2026-08-18 16:18 (Asia/Taipei)

## Completed

- Gate A：用户回复“可以继续推进流程”，批准 AC-11–17 实施。
- AC-11：两套真实 PostgreSQL 测试均使用独立 Testcontainers 库；CI 强制非零执行且禁止 skip/共享库 URL。
- AC-12–13：outbox lease fencing、有界发送、`FAILED`/`DELIVERY_UNKNOWN`、租户级人工核账恢复、指标/告警已闭环。
- AC-14：契约校验、精确原始 JSON HMAC、source/tenant allowlist、生产 fail-fast 和安全 DLQ 重放已闭环。
- AC-15：phase/status 同 SQL、有限 CAS 重试、终态保护、终止事务与回滚后幂等修复脚本已完成。
- AC-16：最新实例、实例真实 definitionId、无实例空态、时间线错误与定义缓存失效已完成。
- AC-17：Maven 109/109、Vitest 62/62、Vite build、Compose config、shell syntax、diff check 均通过；最终镜像已重建，六服务 healthy。
- 真实持久库已从 Flyway V3 原地升级至 V5；业务 link/outbox 数量前后一致，既有 1 条 BPMN deployment 保留。
- 独立 Backend Architect、Software Architect 和 QA/Frontend 复审均无剩余 blocking/high finding。

## Gate B Evidence

- Backend: `mvn test` 成功，109 tests，0 failure/error/skipped。
- Frontend: 19 files / 62 tests 通过；3627 modules 生产构建成功。
- Runtime: PostgreSQL、Redis、Kafka、server、admin、console 全部 healthy；server/admin actuator=`UP`，console healthz=204。
- Smoke: server 直连和 console `/api` 反代的 `hisRxReview` XML 均返回 200。
- Schema: `flyway=5`；`wf_outbox_event.last_error`、`wf_dlq_event.signature`存在，DLQ 外部 key/topic 为 TEXT。

## Residual Risks And External Actions

- 应用层 HMAC/allowlist 不替代生产 Kafka SASL/mTLS、TLS 与 topic ACL；投产前必须在 broker 层完成。
- HMAC 目前为单 active key，尚无 `kid`/双钥无停机轮换。
- 远程 GitHub Actions、真实 IdP/Kafka ACL、多节点负载/故障注入仍是投产环境动作；本地全量门禁和单机 Compose 已通过。

## Next Action

以本批 Gate B 为基线进入真实 IdP/broker 集成和多副本容量/混沌验证；不需要继续修改本批功能代码。
