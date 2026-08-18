# Code Review Report

## Scope And Verdict

- Scope: AC-11–17 所涉及的 core/server/console、Flyway V4/V5、Kafka/DLQ、CI、部署与文档。
- Reviewers: 独立 Backend Architect、Software Architect、QA/Frontend 角色的多轮对抗复审。
- Verdict: pass；最终树无未解决 blocking/high finding。
- Diff base: 当前 `HEAD` 对工作区差异；保留用户已有改动，未做 reset/回退，未 commit/push。

## Findings And Resolution

| Severity | Finding | Failure scenario | Resolution and evidence |
| --- | --- | --- | --- |
| High | outbox 写回未校验租约过期 | 旧 owner 在过期未接管时仍覆盖结果 | 全部写回 CAS 增加 `lease_until>=now()`，真 PG 覆盖过期未接管/已接管 |
| High | 批量串行 send 可超出统一 lease | 后续行尚未发送就过期，发生重复与陈旧写回 | 每行发送前续租；校验 max.block + ACK wait < lease 且 delivery timeout 不超过应用等待 |
| High | Kafka timeout/cancel/interruption 被当成明确失败 | broker 可能已收到却自动重发 | Java/Kafka timeout、cancel、checked/同步 Kafka interrupt 统一进 `DELIVERY_UNKNOWN`；中断后停止整批 |
| High | `DELIVERY_UNKNOWN` 无恢复或可跨租户恢复 | 流程永久等待，或 tenant A 恢复 tenant B 事件 | ADMIN 核账 API 使用 JWT/header 解析租户，SQL 同时 CAS `eventId/status/payload.tenantId`，保留同 eventId |
| High | source/tenant 只比较 envelope 自报字段 | 任一共享 topic producer 可伪装获准 source | 对精确 raw JSON 做 per-source HMAC-SHA256，再做 allowlist；文档明确 broker principal/ACL 仍为生产边界 |
| High | DLQ 保存不可信 header/key 可击穿 DB，且 sink 失败会自回投 | 毒消息或 DB 故障形成 DLQ-of-DLQ 循环 | 签名仅保存解码后精确 32B 值，key/topic 扩为 TEXT；DLQ 专用 stopping error handler 保留位点并停止容器 |
| High | `INCIDENT` 未按终态保护，terminate 非事务 | 异常状态被普通推进覆盖，Flowable/link 可分裂 | 状态机显式保护 INCIDENT，terminate 加事务；phase/status 同 SQL |
| High | 轨迹无实例时显示最新定义，且错误被静默吞掉 | 用户把定义图/旧缓存误认为实例轨迹 | 无实例禁用 XML/timeline 查询；按实例 definitionId 取图；时间线错误不再展示 cached entries |
| High | 已执行迁移可能被原地修改 | 存量库启动出现 Flyway checksum mismatch | 保持 V4 稳定，DLQ 变更独立 V5；真 PG 验证空库与 V3→V5 |
| Medium | 旧 `last_error` 满4000字符时吃掉人工核账原因 | DB 诊断链缺最新人工证据 | 保留字符串尾部，真 PG 断言仍以 `manual-requeue` 原因结尾 |

## Checks Rerun After Fixes

- Maven reactor: 109/109，0 failure/error/skipped。
- PostgreSQL: `WorkflowMetadataMigrationTest` 12/12，`RxReviewLoopTest` 2/2，均真实 Testcontainers PostgreSQL。
- Frontend: 19 files / 62 tests；Vite production build 成功。
- Compose config、全部 deploy shell `bash -n`、共享库 URL guard、`git diff --check`: 通过。
- Docker: 最终镜像重建后六服务 healthy，Flyway V5，直连/反代冒烟 200。

## Residual Risks

- HMAC 密钥仍是单 active key，需后续引入 `kid` 和双钥轮换窗口。
- 生产 Kafka SASL/mTLS/TLS/topic ACL、真实 IdP、多副本容量与故障注入不能由本地单机代码证明，属于投产前环境验证。

## Verdict

pass
