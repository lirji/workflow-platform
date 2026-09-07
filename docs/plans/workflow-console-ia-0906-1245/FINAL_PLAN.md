# 流程台工作台 — 实施计划

> 状态：**已落地（2026-09-06）**。DEV 租户只读；小屏办理保持；Phase 1 + Phase 2 都做。`:8302` 已灌新 dist，请硬刷新。
> 决策：`DECISION_RECORD.md` 方案 **D**。
> 仓库：`workflow-platform/workflow-console`。
> Cursor 只做前端；Java / 租户推导 / 定义 registry 标「等 Codex」。

独立评审（2026-09-06）：3 Blocker + 7 Major 已并入下文。原 §11「同一视图同时出 HIS+SKU」作废。

---

## 1. Goals / Non-goals

### Goals

- 待办、轨迹、运维共用一套 **WorkbenchContext**（租户会话 + 筛选），侧栏切换不丢上下文。
- **一个待办箱 = 当前会话租户下的全部定义**。流程定义是筛选，不是宇宙切换。
- **不承诺**一次列出 `his` 审方和 `benefit-center`/`dev-tenant` SKU。那是 F-01 双租户部署，要 DEV 顶栏改租户或分两次看，禁止切流程改头扫他户。
- 废止「选审方就把 `X-Workflow-Tenant` 改成 his」。
- 现有 API 补齐：筛选、分页、跨页深链、阶段图例、`/tasks/:taskId`、中性文案。
- Phase 2：通用 complete + 认领/转办（后端已有、前端未接）。
- 视觉沿用现 token；小屏沿用 992 断点。

### Non-goals

- 不写后端、不改 yml 默认租户、不把租户 ID 写死进仓库默认。
- 不做 `/def/:key/inbox` 嵌套工作区（方案 C）。
- 不跨租户一次聚合待办；不发明定义元数据 API。
- 不把 Journey / 发奖 / 嵌套各台 SPA 拉进流程台。
- 不做移动端 BPMN 建模、深色模式、从控制台发起实例。
- 死信→轨迹：缺 businessKey，只说明无法跳转。

---

## 2. 视觉方向与设计参考

**沿用** `src/theme/colors.ts`、`theme.ts`、`global.css`：

- 主色 `#315EFB`、soft `#EEF3FF`、success `#16A36A`、warning `#D97706`、error `#D92D20`
- 圆角 8/12、字号 14、控件 36/40、内容区 1440 / padding 24（≤768 → 16）

| 模式 | 落到本项目 |
|---|---|
| 顶栏环境上下文 | `WorkbenchBar`：租户只在此处展示；流程 Select；业务键 Search；复制当前完整 URL |
| 列表筛选条 | 白底、`border: colors.border`、`borderRadius: 8` |
| 行内跳转 | `Button type="link"`：轨迹 / 打开待办 |
| 空态 CTA | `EmptyState` +「清除筛选」 |
| 阶段图例 | `PhaseLegend`：挂 `/tasks` PageHeader 下方、运维 instances 筛选条旁 |

登录渐变不进工作台。
**删除** AppLayout Header 里 `tenantForProcess` Tag；页内不再各放一套「流程种类=切租户」Switcher（Step 2 完成后从 Tasks/Trace/Instances 的 PageHeader extra 移除）。

---

## 3. 路由与页面流

```
/login /callback
[ProtectedRoute > AppLayout + WorkbenchBar]
  /                 → /tasks（可带上次 query）
  /tasks            统一待办箱
  /tasks/:taskId    useParams 命中开抽屉；未命中 Alert
  /process          → /process/{workbench.definitionKey ?? 待办反推 ?? 提示选择}
  /process/:key     ?businessKey=
  /ops              ?tab=&definitionKey=&phase=&businessKey=
  /designer         ?key=（ADMIN，不强制同步 Workbench）
```

侧栏跳转规则（必须写进 `AppLayout`，不是只改 `nav.path`）：

```
navigate({
  pathname: nextPath,          // /tasks | /process/:key | /ops
  search: merge(location.search, workbenchStore)
})
/process 的 pathname 用 definitionKey ?? lastSeenKey；无 key 则 /process（空态请选择）
```

运维 `Tabs.onChange`：**merge** `tab`，保留 `definitionKey` / `businessKey` / `phase`。禁止 `setSp({ tab })` 整表替换。

`/process` 重定向优先级：`workbench.definitionKey` → 待办反推的最近 key → 空态「请选择流程」。不再只用 `lastProcessKind()`。

用户流：登录见租户 → 待办列本租户任务 → 筛流程只改 query → 侧栏带参到轨迹/运维 → 权益深链只预填筛选，租户不对则空列表。

---

## 4. 组件树

**复用：** PageHeader、AsyncState、PhaseTag、TaskCard、ReviewDrawer、RecentReviews、各 Ops 面板、BpmnViewer、现有 hooks/auth。

**新建：**

- `src/store/workbenchStore.ts`
- `src/workbench/syncWorkbenchUrl.ts`
- `src/workbench/definitionLabel.ts`（`PROCESS_KINDS` label **迁到这里**；`processScope` 不再持有种类表）
- `src/components/layout/WorkbenchBar.tsx`
- `src/components/domain/PhaseLegend.tsx`
- `src/components/domain/CompleteTaskDrawer.tsx`（Phase 2）

**删除 / 收缩：**

- `applyProcessScope` / `setWorkflowTenantOverride` / `tenantForProcess` 副作用
- `visibleTaskKey` 客户端只留一种任务
- Header 双 Tag（流程+由流程推导的租户）
- 页内 `ProcessKindSwitcher` 宇宙模式（可改名为纯 `DefinitionFilter` 或直接并进 Bar）

---

## 5. 状态与边界

### WorkbenchBar

- ADMIN：`listDefinitions` 填 Select（可 skeleton）
- 非 ADMIN / 403：key 来自当前待办页出现过的 `processDefinitionKey` + `definitionLabel` fallback
- 零待办且 403：Select 仅「全部流程」+「联系管理员或确认租户」
- DEV / OIDC：租户**只读**（批准结论）。来自 `VITE_WORKFLOW_TENANT` / 未来 JWT，顶栏不出下拉。F-01 下看另一租户需改环境，不在 UI 切头。
- 「复制链接」：`navigator.clipboard.writeText(location.href)`（含当前 query；不写 secret）

### `/tasks`

- `definitionKey` 空：**不传**该参数；**不传** `candidateGroup`（避免 dev 默认 PHARMACIST 滤掉 SKU）。有 JWT 且产品要「仅我的组」时，传用户组**并集**，禁止 `resolveDefinitionKey → HIS → [PHARMACIST]`
- 分页：Table pagination ↔ URL `page`/`size`
- `:taskId`：`useParams`；在当前结果集命中则开 `ReviewDrawer`；否则 Alert「未找到或无权」
- empty / error / 202 语义同前；空态写会话租户，不写「请切 SKU」
- 小屏 TaskCard + 现有办理抽屉

### `/process/:key`

- 无 key：请选择
- 有 businessKey 无实例：本租户未找到
- 页内业务键 Search
- 小屏：Card + Timeline 全宽；extra 筛选项以 Bar 为准，页头 extra 可空

### `/ops`

- jobs/dlq：条上注明「本 Tab 不按流程过滤」
- 实例列名「业务键」；阶段筛选用中文标签
- 终止必填原因；`<992` 终止与 DLQ replay-all **disabled** +「请在桌面执行」
- Tab 切换 merge query

### `/designer`

- 不强制 Workbench；小屏只读（已有）

---

## 6. API 契约

| 用途 | 方法 | 本轮 |
|---|---|---|
| 待办 | `GET /api/v1/tasks/search` | `definitionKey` 可选；空则不传 `candidateGroup`（或传组并集） |
| 审方/SKU 办理 | `POST /tasks/{id}/complete-review` | 不变 |
| 通用办理 | `POST /tasks/{id}/complete` | Phase 2 |
| 认领/转办/撤回 | `POST .../claim?userId=` 等 | Phase 2；`userId` 取 `authStore.userId` |
| 定义 | `GET /admin/definitions` | **仅 ADMIN**；403 降级 |
| 头 | `X-Workflow-Tenant` | 只等于 `workbenchStore.tenantId` |

Phase 2 前端清单（现码只有 `completeReview`）：

- `api/types.ts`：`CompleteTaskRequest`
- `api/tasks.ts`：`completeTask` / `claimTask` / `reassignTask` / `unclaimTask`
- `hooks/useTasks.ts`：对应 mutations
- `CompleteTaskDrawer.tsx`：非 `complete-review` 任务走这里

---

## 7. 响应式与移动端

| 断点 | 策略 |
|---|---|
| ≥992 | Table + Drawer |
| <992 | 待办卡片；办理 bottom 全屏；轨迹全宽；运维横滚；危险操作禁用 |
| ≤768 | padding 16 |

`ReviewDrawer` 主按钮 `minHeight: 44`。
不设 viewport-fit-cover。

---

## 8. 文件级改动清单

| 文件 | 动作 |
|---|---|
| `src/store/workbenchStore.ts` | 新建 |
| `src/store/workbenchStore.test.ts` | 新建：改 definitionKey **不**改 tenantId |
| `src/workbench/syncWorkbenchUrl.ts` | 新建 |
| `src/workbench/definitionLabel.ts` | 新建（迁入 PROCESS_KINDS 文案） |
| `src/components/layout/WorkbenchBar.tsx` | 新建 |
| `src/components/domain/PhaseLegend.tsx` | 新建 |
| `src/components/layout/AppLayout.tsx` | 挂 Bar；**去掉 tenantForProcess Tag**；菜单 `mergeSearch` 再 navigate |
| `src/nav.tsx` | path 仍为静态 key；实际跳转在 Layout |
| `src/api/client.ts` | 头读 store.tenantId |
| `src/pages/processScope.ts` | 删 override；或整文件删除 |
| `src/pages/taskInbox.ts` | 删默认 HIS / `visibleTaskKey` |
| `src/pages/TasksPage.tsx` | 统一箱；不传空 definitionKey；不传默认 PHARMACIST；`useParams().taskId`；分页；行内轨迹；去掉页内 Switcher |
| `src/pages/ProcessTracePage.tsx` | 业务键 Search；小屏全宽；去掉页内宇宙 Switcher |
| `src/pages/OpsPage.tsx` | Tab merge query |
| `src/components/ops/InstancesPanel.tsx` | 读 context；`<992` 禁用终止 |
| `src/components/ops/DlqPanel.tsx` | `<992` 禁用 replay-all |
| `src/components/domain/ReviewDrawer.tsx` | 主按钮 44px |
| `src/components/domain/TaskCard.tsx` | 中性业务键 |
| `src/store/uiStore.ts` + `RecentReviews.tsx` | `RecentReview` 增加 `processDefinitionKey`；Row 按条查询；`newestProcessInstance` |
| `src/router/routes.tsx` | `/process` 按 §3 优先级 |
| Phase 2：`api/types.ts`、`api/tasks.ts`、`hooks/useTasks.ts`、`CompleteTaskDrawer.tsx` | 见 §6 |
| 测试 | 见 §10 |

不改 Java / Compose / Dockerfile ARG。

---

## 9. 实施步骤

1. workbenchStore + URL sync + client 改头 + store 单测（租户不随 key 变）
2. WorkbenchBar；AppLayout 去旧 Tag；**侧栏 mergeSearch**；删 `applyProcessScope`；**删掉三页内宇宙 Switcher**
3. 待办箱：可选 definitionKey、**空则不传 candidateGroup**、分页、业务键、轨迹链、`useParams` taskId、删 `visibleTaskKey`
4. 轨迹：业务键 Search；空态按会话租户
5. 运维：context；Tab merge；小屏禁用危险操作；jobs/dlq 说明
6. PhaseLegend 挂待办页头下 + 实例筛选旁；列头中性化
7. RecentReviews：存并使用 `processDefinitionKey` + `newestProcessInstance`
8. Phase 2（若 §14 勾选）
9. 测试 + 灌 `:8302` dist

---

## 10. 测试策略

必改：`TasksPage.test.tsx`、`taskInbox.test.ts`、`ProcessTracePage.test.tsx`、`InstancesPanel.test.tsx`、`ReviewDrawer.test.tsx`。
新增：`workbenchStore.test.ts`；拦截器或 client 测头不随 definitionKey 变。
`TasksPage` 新用例：无 `definitionKey` 时 `findTasks` **不带** `definitionKey`、**不带** 默认 `PHARMACIST`；同一 **tenant** 下若 mock 两条不同 definitionKey 的任务则都展示（不要写成跨 `his`/`dev-tenant`）。
`InstancesPanel`：补 `<992` 终止 disabled（复用 `DesignerPage.test` 的 `setViewport`/`matchMedia` 辅助，抽到 `src/test/viewport.ts`）。
`RecentReviews`：两条不同 definitionKey 的近期办理各自查 phase。
保留：202 无「已完成」；终止必填原因。

视口：390×844 待办卡片 + 办理可开 + 运维终止不可用；1280 桌面主路径。

---

## 11. 验收标准

- [ ] `/tasks` 不带 `definitionKey` 时，列出**当前会话租户**下全部定义的待办；**不**要求同时看到 `his` 审方和另一租户的 SKU
- [ ] 请求**不**因未选流程而带上 `candidateGroup=PHARMACIST`
- [ ] 切流程筛选 **不改变** `X-Workflow-Tenant`
- [ ] 权益深链只预填筛选；当前租户无任务则空列表，不改头扫他户
- [ ] 侧栏待办↔轨迹↔运维保留 `definitionKey`/`businessKey`（运维再切 Tab 也不丢）
- [ ] `/tasks/:taskId` 命中开抽屉，未命中有说明
- [ ] 运维「打开待办」可用；终止要原因；小屏终止不可用或提示桌面
- [ ] 办理 202「已受理」，无「已完成」
- [ ] PhaseLegend 在待办与实例运维可见
- [ ] 390 宽：待办卡片、能办理、工作台条可换行
- [ ] 无发奖表单、无 Mock 业务数据

---

## 12. 风险与回滚

| 风险 | 缓解 |
|---|---|
| F-01 双租户 | 验收不承诺混租户；DEV 顶栏改租户；空态写当前 tenant |
| 非 ADMIN 无定义列表 | 待办反推；零待办仅「全部流程」 |
| 首请求竞态 | 拦截器同步读 store；tenant 默认来自 env/JWT，不在 effect 才写入 |
| `:8302` 旧包 | 批准后 `pnpm build` 灌容器 |
| 多 Tab | tenant sessionStorage；筛选跟各 Tab URL |

回滚：还原相关提交或灌回旧 dist。

---

## 13. 独立评审记录

2026-09-06 只读评审：B1 混租户验收、B2 candidateGroup、B3 侧栏丢 query 已改入 §1/§3/§5/§9/§11。Major（RecentReviews key、Ops Tab、Header Tag、移动端文件、taskId、Phase 2 API 清单、definitions 权限措辞）已改入对应节。

---

## 14. 已批准选择（2026-09-06）

1. DEV 租户：**只读**
2. 小屏办理：**保持现有抽屉**
3. Phase 2：本轮接着做
4. 整体：批准实施
