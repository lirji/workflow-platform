# 决策记录:workflow-console 运维面板(P1-2.1 前端)

> 由 frontend-plan 工作流产出。基于 5 个只读子代理(需求/UIUX·视觉/架构·可复用/移动端/测试·风险)对 workflow-console 与后端 `AdminOpsController`/`DlqController` 的调查综合。日期 2026-08-15。
> 后端已就绪(commit `aec1c42`);本记录只裁决前端设计,后端契约作为约束。

## 背景

后端已提供运维 REST(`/api/v1/admin/*` 实例查询/挂起/恢复/终止、Flowable 死信作业列/重试;`/api/v1/dlq/*` 死信列/重放/批量重放),鉴权启用时需 ADMIN。前端 workflow-console 需新增运维面板消费它们,复用既有 auth-console 克隆栈。

## 决策与备选

### D1 · 信息架构:单页 Tabs vs 多子路由 —— 选 **A(单 `/ops` 页 + antd Tabs)**
- **A(推荐)**:一个 `/ops` 懒加载页,内 antd Tabs(实例 / 死信作业 / DLQ),`?tab=` 深链。**优点**:与既有扁平 `nav.tsx` 单源 + `AppLayout` 两级面包屑天然契合;路由改动最小(照抄 `ProcessTracePage` 的 `lazy+Suspense`);三表轻量(无 bpmn)单 chunk 可忽略。**缺点**:面包屑停在"运维面板"、tab 切换非真路由。
- B(多子路由 `/ops/instances|jobs|dlq` 各懒加载):分 chunk 更细、深链更规范,但需 `OpsLayout`+嵌套 `Outlet`+改造面包屑,破坏"单一配置源"简洁性,改动面大。
- **裁决**:三表轻量、纯 ADMIN、低频,A 的收益/成本最优;某面板显著变重时再演进到 B(面板已拆独立组件,迁移成本可控)。

### D2 · incident 处置:独立 Tab vs 实例页内 phase 筛选 —— 选 **实例 Tab 内 phase 筛选(INCIDENT 快捷)**
- 需求子代理证实:`/incidents` 与 `instances?phase=INCIDENT` **语义等价**,incident 不是独立数据源。故不建第 4 个 Tab,在「实例」Tab 顶部放 phase 筛选(Segmented/Select),含醒目「只看异常」快捷项。空态用正向文案「当前无异常实例」。

### D3 · 移动端策略:卡片降级 vs 横滚表格 + 危险操作分级 —— 选 **只读可达(横滚表格)+ 不可逆/批量操作引导桌面**
- 移动端子代理主张卡片降级(与 TasksPage 一致);架构子代理主张横滚表格(收敛范围,ADMIN 桌面为主)。**冲突裁决**:运维是**低频、桌面为主、小 ADMIN 群体**的工具,数据以长 ID 为主。取折中——**移动端只读可达**(列表用 `Table scroll={{x}}` 横滚 + 三态,不为 3 个面板各做卡片变体,收敛工作量),**危险操作分级**:可逆(挂起/恢复/单条重试)允许移动端经全屏抽屉+二次确认办理;**不可逆/批量(终止、replay-all)在移动端隐藏或 disabled + "请在桌面执行"提示**(依据:不可逆爆炸半径大、需读长 payload/异常栈决策,小屏阅读质量差)。移动端验收保留至少 390×844 一项(见 FINAL_PLAN §11)。
- 代价:移动端 ops 列表是横滚而非卡片,与 TasksPage 风格略不一致——已在 non-goals 标注并说明依据。

### D4 · dev 门控逃生门:纯 isAdmin vs `!authEnabled || isAdmin` —— 选 **加逃生门**
- **关键坑(3 个子代理独立发现)**:dev(`authEnabled=false`)时 `AuthBridge` 走 `clear()` → `authorities=[]` → `isAdmin([])===false`。若菜单/路由只 gate `isAdmin`,则 **dev 下运维面板完全不可见/不可达**,而后端 dev 是全放行,联调受阻。
- **裁决**:镜像 `ProtectedRoute.tsx:33` 的逃生门——`showOps = !config.authEnabled || isAdmin(authorities)`,用于菜单过滤 + 新 `AdminRoute` 守卫。dev 放行、Stage2 按 ADMIN。

### D5 · 后端错误契约缺口:前端防御 vs 后端补齐 —— 选 **前端防御 + 建议后端小改(companion)**
- **缺口**:① DLQ 404 返回 `{id, error:...}` **无 `message` 字段**,而 `errMsg` 只读 `.message` → 会退化成 "Request failed with status code 404";② Flowable `FlowableObjectNotFoundException`(终止已结束实例/重试不存在 job)**未进** `WorkflowExceptionHandler` → Spring 默认 500 无有效 message。
- **裁决**:前端**按状态码兜底**(404→"该死信已重放或不存在,已刷新";500→"操作失败,实例可能已结束/作业不存在,请刷新"),不依赖 `errMsg` 单一路径;**同时建议一个 companion 后端小改**(把 Flowable NotFound 映射为 404 带 message、DLQ 404 也带 message),列入 FINAL_PLAN 风险/后续。前端不因后端是否改而阻塞。

### D6 · 挂起态标签 SuspendTag:显示 vs 放弃 —— 选 **放弃(数据不支持)**
- `ProcessInstanceView.running` 由 Flowable runtime count 派生,**挂起实例 running 仍为 true**,无独立 suspended 字段 → 无法可靠区分"挂起 vs 活跃"。若强做 SuspendTag 会误导。
- **裁决**:本轮不做 SuspendTag;挂起/恢复成功仅 `message.success` 反馈,列表 phase 不变。作为已知限制记入 non-goals + 待澄清(后端后续可补 suspended 字段)。

### D7 · 诚实的异步反馈:统一"已完成" vs 分档 —— 选 **按同步/异步分档**
- 后端语义:`terminate/suspend/activate` = Flowable **同步** 204(终止同步置 CANCELLED),可诚实说「已终止/已挂起」;`retry/replay/replay-all` = **异步再处理**(移回可执行队列 / 投回 topic 由监听幂等消费),204/REPLAYED 只代表「已受理/已投回」。
- **裁决**:延续 PhaseTag/RecentReviews 红线——异步操作**严禁**「已修复/已完成」,用「已受理,追最终一致(结果以列表刷新为准)」;成功后触发**爆发式列表刷新**(泛化 `useTaskListSync`),由「NEW→消失/REPLAYED」「incident→drain」在列表真实体现,不做删除式乐观更新。

## 复用总纲(不新造)
- 数据:`apiClient`(单点注入 tenant/鉴权,勿新建 axios 实例)、react-query(key=常量前缀+params,mutation 成功 invalidate)、`errMsg`/`statusOf`。
- UI:`PageHeader`、`AsyncState`(骨架/错误/空)、`PhaseTag`(实例 phase 列直接复用)、`ReviewDrawer` 的 `App.useApp().modal.confirm`+message 范式(复制范式,不复用组件)、`TasksPage` 列表范式。
- 门控:`store/authStore.isAdmin`、`config.authEnabled`;新增 `AdminRoute` + `nav` 的 `adminOnly`。
- 新建原子标签:`DlqStatusTag`(NEW=warning/REPLAYED=default,同 PhaseTag 写法)。

## 未决(随 AskUserQuestion 在批准时确认)
1. 移动端危险操作:采纳 D3「不可逆/批量强制桌面」,还是要求移动桌面完全等价?
2. dev 可见性:采纳 D4「dev 显示运维面板」?
3. 批量重放 replay-all:是否开放前端(风险:最多 500 条重复消费)?
4. companion 后端错误映射小改(D5)是否本轮一起做?
