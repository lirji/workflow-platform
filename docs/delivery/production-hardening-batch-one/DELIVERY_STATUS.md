# Delivery Status

## Goal

完成工作流平台第一批企业生产加固并达到 CI-ready。

## State

- Phase: Gate B · Final Acceptance
- Status: complete
- Last updated: 2026-08-18

## Completed

- Phase 1–4：仓库证据、产品规则、技术方案与 AC-01–10 已记录。
- Gate A：用户已批准第一批范围。
- Slice 1（AC-01–03）：生产启动 guard、issuer/audience validator、JWT tenant fail-closed、精确角色映射。
- Slice 2（AC-04–05）：任务候选/assignee 授权；实例、轨迹、运维与定义变更租户约束。
- Slice 3（AC-06）：SDK 动态 Bearer Token provider、缺 token 与 Noop 写操作 fail-fast。
- Slice 4（AC-07–09）：listener inbox 外层事务、发起 `REQUIRES_NEW`、correlation retry DB lease、DLQ broker ACK 后落状态。
- Slice 5（AC-10）：生产/架构/接入文档同步；GitHub Actions backend/frontend/deployment-config 门禁。
- Phase 6 review：修复 PostgreSQL claim SQL、ACK tenant/metadata、并发 claim、授权旁路和 SDK JSON converter；无遗留 high finding。
- Phase 7 QA：Maven、Vitest/build、配置/语法门禁完成；Docker/真实依赖项明确标为 blocked。
- Phase 8–9：文档与 CI 已同步；Gate B artifacts 已生成。

## Changed Files

- `docs/delivery/production-hardening-batch-one/DELIVERY_PLAN.md` - 交付计划与验收矩阵。
- `docs/delivery/production-hardening-batch-one/DELIVERY_STATUS.md` - 可恢复状态。
- `workflow-platform-core/src/main/**` - 任务授权上下文、租户查询、inbox lease、V3 迁移与事务传播。
- `workflow-platform-server/src/main/**` - JWT/生产 guard、受保护控制器、消息事务与可靠重放。
- `workflow-platform-sdk/src/main/**` - Bearer Token provider 与 fail-fast 配置。
- `workflow-console/src/auth/oidcConfig.ts` - 精确角色归一化。
- `workflow-platform-*/src/test/**`、`workflow-console/src/auth/oidcConfig.test.ts` - 新增/更新安全可靠性回归。
- `.github/workflows/ci.yml` - Maven、frontend 与 deployment config CI。
- `README.md`、`docs/{architecture,integration-guide,ROADMAP}.md`、`deploy/README.md`、`workflow-console/README.md` - 最终行为与投产边界。
- `docs/delivery/production-hardening-batch-one/{REVIEW_REPORT,QA_REPORT,DELIVERY_REPORT}.md` - review、QA 与交付证据。

## Verification Log

| Command or check | Result | Notes |
| --- | --- | --- |
| Pre-change `mvn -B test` | pass with limitation | 41 项有效测试；Docker 不可用使 migration Testcontainers 执行 0 项 |
| Pre-change `pnpm test && pnpm build` | pass | 57 项；存在现有 warning |
| Pre-change Playwright | conditional | 显式 dev 鉴权后 3/4；轨迹依赖已部署 BPMN，当前 404 |
| `mvn -B -DskipTests compile` | pass | 全 reactor 编译成功 |
| `mvn -B -pl workflow-platform-sdk,workflow-platform-server -am test` | pass with limitation | protocol 6 + SDK 3 + server 46；Testcontainers 因 Docker 不可用执行 0 |
| Final `mvn -B --no-transfer-progress test` | pass with limitation | BUILD SUCCESS；60 计入、58 pass、2 external-PG skipped；Testcontainers 类因 Docker 不可用未计入 |
| Final `pnpm test && pnpm build` | pass | 16 files、57/57；3627 modules production build |
| CI YAML / Compose config / shell syntax / diff check | pass | 全部本地校验 exit 0 |

## Decisions And Deviations

- 不启用子代理；按当前策略由同一 Codex 做分阶段实现与对抗复核。
- 不覆盖用户已有 deploy/console 未提交改动。
- review 发现并修复已有 outbox PostgreSQL claim SQL；与本批 inbox HA 正确性同属批准范围。
- SDK 真实 HTTP 测试暴露 JSON converter 缺失，补入 JSON starter，未改变公开 API。

## Blockers And Residual Risks

- 当前无实现阻塞，无遗留 critical/high finding。
- Docker daemon 与 localhost PostgreSQL 不可用；5 个 Testcontainers case、2 个真 PG 回环、实际镜像/Compose 启动留给远程 CI 或目标测试环境。
- 真实 IdP/Kafka、多副本负载/故障、备份恢复仍是投产前外部动作。

## Next Action

触发远程 GitHub CI，重点确认 Testcontainers PostgreSQL 的 V1–V3 与 outbox/inbox claim case；随后进入 shadow/双副本投产演练。
