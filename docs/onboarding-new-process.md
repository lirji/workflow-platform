# 新审批流程 onboarding 配方

> 以已落地的「审方 `hisRxReview`」和「权益 SKU 上线 `benefitSkuGoLive`」为模板，端到端接一个新审批场景（示例：退费审批 `hisRefundReview`）。
> 中台侧成本很低(部署一个 BPMN);主要工作量在消费方(另一个 repo)的 outbox 发起 + 落地消费。

## 中台侧(本仓库,约半天)

1. **设计 BPMN**(参照 `workflow-platform-core/.../bpmn/his-rx-review-v1.bpmn20.xml`):
   - 候选组用**大写无前缀**(如 `REFUND_REVIEWER`),与前端 `normalizeGroup` / token 组名对齐。
   - 若要在 console 轨迹页渲染,BPMN **必须含 BPMNDI 图形段**(见 ADR/FC-A;可用 bpmn-js Modeler 往返生成)。
   - 约定 `action` 类型(如 `REFUND_APPROVE` / `REFUND_REJECT`),供消费方按类型做业务副作用。
2. **部署**(三选一):
   - 运维面板「流程定义」Tab 粘贴 XML 部署(最快);
   - `POST /api/v1/admin/definitions/deploy` `{name, bpmnXml}`(ADMIN);
   - 打包进 classpath 由 admin 服务部署(纳入版本管理)。
3. **(可选)自定义落地动作**:若新流程要写 outbox 发 `action.requested`,加 serviceTask + delegate,参照
   `RxReviewActionOutboxDelegate`；需要独立 action/message 的场景参照 `SkuGoLiveActionOutboxDelegate`。同时必须在 `MessageCorrelationService.ackMessageName` 登记该流程自己的 ACK message，不能复用其它流程订阅。纯人工审批（无异步落地）则无需。

## 消费方侧(消费方 repo,约 1~2 天)

见 `docs/integration-guide.md`。要点:
1. **发起**:业务事务内写自己的 outbox → `StartProcessCommandV1(processDefinitionKey="hisRefundReview", businessKey=退费单号, idempotencyKey=cycle)`。
2. **落地**:消费 `workflow.action.requested.v1`(自有 group)→ 按 `action`(如 `REFUND_APPROVE`)做业务副作用(按 `actionId` 幂等)→ 事务内写 outbox 回 `workflow.action.applied.v1`。
3. **查询/办理**:用 workflow-console 待办中心(候选组过滤)或 SDK `findTasks`/`completeReview`。

## 前端(workflow-console)

- **轨迹页**天然参数化:`/process/{definitionKey}?businessKey=`,新流程零改动即可看图/轨迹。
- 待办中心已通过 query 参数支持两个内置流程：`/tasks` 默认 `hisRxReview`，`/tasks?definitionKey=benefitSkuGoLive&businessKey=<skuId>` 展示 SKU 审批。候选组、可见 task key 和文案集中在 `src/pages/taskInbox.ts`。
- 新增第三种流程时，需要在 `taskInbox.ts` 增加 definition→候选组/task key/文案映射；当前还没有任意定义下拉或服务端聚合多流程待办。
- 办理抽屉 `ReviewDrawer` 支持通用 PASS/REJECT；若新流程决定项不是二选一，需同步扩协议、服务端办理语义与前端表单，不能只改文案。

## Checklist
- [ ] BPMN(候选组大写、含 DI)+ action 类型约定
- [ ] 部署(运维面板/REST/classpath)
- [ ] (可选)outbox delegate
- [ ] 使用独立 ACK message，并在 `MessageCorrelationService` 登记 definition→message 映射
- [ ] 消费方:发起 outbox + 落地消费 + 回执(幂等/租户)
- [ ] 前端:`taskInbox.ts` 增加 definitionKey/候选组/task key/文案映射
- [ ] 冒烟:发起→待办→办理→202→落地→轨迹全绿

## 成本与复用
- **中台侧**基本是「部署一个 BPMN」,可靠消息/幂等/鉴权/指标/审计/运维/DLQ **全部复用**,无需改中台代码(纯人工审批场景)。
- **消费方侧**是主要工作量(发起 + 落地 + inbox/outbox),但有审方作范例、有 SDK 与接入指南。
- **结论**:平台侧 onboarding 成本已很低;瓶颈在消费方业务集成与前端多流程适配(均为小到中等改动)。
