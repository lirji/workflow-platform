# 流程台信息架构 — 决策记录

日期：2026-09-06
范围：`workflow-platform/workflow-console` 待办 / 轨迹 / 运维三页共用工作台，并在现有 API 上丰富能力。
对照：C1 双流程切换器（止血）、`docs/plans/workflow-console-0815-1323`、用户确认的「一个待办箱 + 筛选，租户不是流程下拉副作用」。

---

## 1. 冲突裁决

| 冲突 | 来源 | 裁决 |
|---|---|---|
| 保留 `applyProcessScope` 改租户头 vs 废掉切流程改租户 | 可复用组件调查建议保留；产品方向与架构调查建议废掉 | **废掉切流程改 `X-Workflow-Tenant`**。`processScope.ts` 演化为纯展示映射 + 租户会话读取，不再按 definitionKey 写 override |
| README / 0815 计划写「任意流程聚合待办是 non-goal」 | 旧计划 | **本轮显式推翻**：单租户内跨定义待办 in-scope；跨租户一次聚合仍 non-goal |
| 架构 A URL SSOT vs B Store vs C 定义工作区 | 架构调查 | **推荐 D = A+B 混合**，见下。C 列为中长期，本轮不做嵌套 `/def/:key` |
| 移动端办理：Q2 只读 vs 代码已可办理 | 0815 批准 Q2；现网已支持 | **沿用已实现的小屏办理**，不回退。设计器编辑、运维危险操作仍引导桌面 |
| 顶栏 Tag 与页内 Switcher 重复 | UI 调查 | **升格为全局工作台条**，页内不再各放一套「流程种类宇宙」 |
| 认领/转办 | 后端已有，0815 列为 non-goal | **本轮 Phase 2（可选）**，不挡 Phase 1 共用上下文 |

---

## 2. 架构备选

### A. URL 为唯一上下文

- 删 sessionStorage / 模块 override；侧栏带 query 跳转。
- 优点：可分享、可回退、测试直观。
- 缺点：拦截器若只读 `window.location`，navigate 与首请求仍可能竞态；流程列表仍易停在硬编码。

### B. Workbench Store 为 SSOT，URL 是投影

- 三页只订阅 store；拦截器读 `store.tenant`。
- 优点：租户头时机稳。
- 缺点：store ↔ URL 同步写错会双源；若 tenant 仍由 definitionKey 派生，只是换了写法。

### C. 以已部署定义为目录首页，三页变子路由 `/def/:key/inbox|trace|ops`

- 优点：平台感最强，定义 `tenantId` 可对齐部署实体。
- 缺点：改导航/书签/权限（`listDefinitions` 现挂 ADMIN）；本轮过大。

### D. 推荐：URL 筛选 + Workbench Store，**租户与流程解耦**

```
WorkbenchContext {
  tenantId        // 会话：JWT claim / DEV 显式值 / VITE_WORKFLOW_TENANT；不随流程变
  definitionKey?  // 筛选，空 = 本租户全部流程
  businessKey?    // 筛选
  phase?          // 运维实例用
  opsTab?         // instances|jobs|dlq|definitions
}
```

- URL 是可分享视图（`/tasks?...`、`/process/:key?businessKey=`、`/ops?tab=&definitionKey=&phase=`）。
- Store 与 URL 双向同步；`api/client` **只读 `store.tenantId`**，禁止 `tenantForProcess(definitionKey)`。
- 流程下拉数据：ADMIN 用 `listDefinitions`；非 ADMIN 用当前待办里出现过的 `processDefinitionKey` + 本地 label 表（无新 API）。
- 侧栏「待办 / 轨迹 / 运维」继承当前 query，不再丢上下文。

**为何选 D**：直接打中「一个待办箱 + 筛选」；修掉 C1 最危险的副作用；不强迫本轮做 C 的全路由迁移；比纯 A 更能避免租户头竞态。

---

## 3. 能力分层（丰富什么）

### 本轮 Phase 1（现有 API，必做）

1. 统一待办箱：`GET /tasks/search` 可不传 `definitionKey`；去掉 `visibleTaskKey` 客户端只留一行任务
2. 筛选条：流程定义 / 业务键 / 刷新；运维再加阶段
3. 全局工作台条：租户（只读或 DEV 可改）+ 当前筛选 + 复制深链
4. 跨页跳转：待办行 → 轨迹；实例 `WAITING_USER` → 待办；侧栏带参
5. `/tasks/:taskId`：列表命中则开抽屉，否则「未找到该待办」（无单条 GET 则不强造）
6. 分页 UI（search 已有 page/size/total）
7. 阶段图例：待办理 → 处理中(等 ACK) → 已落地
8. 空态 CTA：清筛选 / 核对租户，不再写「请先切到 SKU 宇宙」
9. 中性办理文案为默认；审方/SKU 仅作标签，不再 if/else 整页
10. 修复 `RecentReviews` 取最新实例与 `newestProcessInstance` 不一致

### 本轮 Phase 2（后端已有、前端未接，批准后做）

- 非审方任务走 `POST /tasks/{id}/complete`（outcome/comment）
- 认领 / 转办 / 撤回（claim/reassign/unclaim）
- `GET /admin/definitions` **仅 ADMIN**；非 ADMIN 403 → 从待办反推 definitionKey + 本地 label。零待办时下拉只有「全部流程」

### 等 Codex（本轮只预留 UI，不假造数据）

- JWT 派生 tenant，删除前端头（Phase 3）
- 定义元数据 registry（displayName、businessKeyLabel、办理表单）
- 跨租户一次查出待办
- 死信作业补 businessKey 才能跳轨迹
- 多租户 BPMN 部署（F-01）：空态说明「当前租户没有该定义」，不靠前端改头骗过

---

## 4. 视觉与移动端

- **沿用** `src/theme/colors.ts` + `theme.ts` + `global.css`（主色 `#315EFB`、圆角 8/12、内容区 1440/24）。
- 借鉴模式：SaaS / Ant Design Pro **顶栏环境上下文 + 列表筛选条 + 行内跳转**，不引入 Pro 包。
- 登录页渐变不进工作台。
- 断点：`lg=992` 切布局；`768` 只收 padding。
- 小屏：待办卡片 + 已有办理抽屉；轨迹单列；运维表横滚，终止/批量重放引导桌面；设计器编辑仍桌面。

---

## 5. 待用户确认（批准时勾选）

见同目录 `FINAL_PLAN.md` §14。默认假设：

- DEV 顶栏可改 `tenantId`（方便 `his` / `benefit-center` 联调）；OIDC 生产只展示、不可改。
- 小屏办理保持。
- Phase 2 认领/通用 complete **纳入同一计划、排在 Phase 1 之后**。
- **不承诺**同一待办箱同时出现 `his` 租户的审方和 `benefit-center` 的 SKU（F-01）。统一箱只聚合**当前会话租户**下的全部定义。
