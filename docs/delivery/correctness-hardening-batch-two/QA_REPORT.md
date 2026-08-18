# QA Report

## Environment Profile

- Date/timezone: 2026-08-18, Asia/Taipei。
- Runtime: Java 21、Maven、Node 20/pnpm/Vitest/Vite、Docker Desktop 29.7.2。
- Data isolation: 两套 PostgreSQL suite 使用随机端口 Testcontainers；Docker 部署验收保留 `workflow-pg-data`，未清库。
- Target: 当前工作区最终源码；无 commit/push。

## Acceptance Results

| Case | AC | Setup and observable assertion | Evidence | Verdict |
| --- | --- | --- | --- | --- |
| QA-11 | AC-11 | 独占 DB/随机端口，禁止 `localhost:25432/workflow`，CI 不允许 PG suite skip | metadata 12/12 + loop 2/2；URL `rg` 无命中 | pass |
| QA-12 | AC-12 | 租约过期未接管及新 owner 接管后，旧 owner 的 sent/retry/fail/unknown 写回均失败 | 真 PostgreSQL repository cases | pass |
| QA-13 | AC-13 | success、明确失败/退避/FAILED、Java/Kafka timeout、cancel、两类 interrupt、旧租约、配置预算、租户核账恢复 | publisher 12/12 + recovery 3/3 + PG cases | pass |
| QA-14 | AC-14 | 错误 version/type/必填字段、无效 HMAC、篡改 JSON、越权 source/tenant 在 inbox 前拒绝；DLQ 异常边界/重放验证 | codec/validator/listener/guard/replay tests | pass |
| QA-15 | AC-15 | phase/status 一致，CAS 有限重试，COMPLETED/CANCELLED/INCIDENT 不被普通路径回退，完整审方 loop 收敛 | service 4/4 + PG mapping + loop 2/2 | pass |
| QA-16 | AC-16 | 选最新实例且按其 definitionId 取 XML；错 tenant/key 404；空实例/时间线错误/缓存失效 | controller/service/frontend tests；容器 XML 直连/反代 200 | pass |
| QA-17 | AC-17 | 全量测试、构建、配置检查、Docker 重建和健康验收 | Maven 109；Vitest 62；build/config/shell/diff pass；6 healthy | pass |

## Full Regression

- `mvn test`: BUILD SUCCESS；protocol 6 + core 16 + SDK 4 + server 83 = 109，0 failure/error/skipped。
- `pnpm test -- --run`: 19 files、62/62 pass。
- `pnpm build`: success，3627 modules transformed。
- `docker compose ... config --quiet`、`bash -n deploy/scripts/*.sh`、`git diff --check`: exit 0。
- Docker runtime: PostgreSQL/Redis/Kafka/server/admin/console 全部 healthy；server/admin `UP`；console `/healthz`=204。
- Persistence/schema: Flyway 3→5，links 0→0，outbox 0→0，deployments 1→1；V4/V5 列和 TEXT 扩容已生效。

## Defects And Retests

- 首次全量 Maven 在 `AdminSecurityTest` 暴露 WebMvc slice 未 mock 新增 `OutboxRecoveryService`；补 mock 后定点 3/3 及全量 109/109 均通过。
- 轨迹时间线后台刷新失败时仍可能显示 cached entries；错误态现已强制隐藏旧时间线，定点及全量前端测试通过。
- 人工 requeue 时旧错误满长可吞掉原因；改为保留尾部，真 PG 覆盖 4000 字符边界并通过。
- 已存在的 React `act(...)`、React Router future flag 和 Ant Design deprecated-property 警告不影响退出码；本批不扩展修改这些旧测试组件。

## Verdict

pass
