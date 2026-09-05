# workflow-console 前端实施计划(frontend-plan 产出)

> 状态:**已获批(2026-08-15)、未实施**。已过独立对抗评审并修订。决策依据见同目录 `DECISION_RECORD.md`。
> **已批准的三项决定**:① 范围 = **A-乙**(前端 + 顺带补后端只读端点:候选人过滤+分页、process-instance/phase、timeline、定义XML,均只读不动业务;并先给审方 BPMN 补 DI);② 移动端 = **只读可达**(手机看待办卡片/轨迹,办理走桌面;设计器移动端 non-goal);③ **引入测试栈**(Vitest+RTL + 1 条 Playwright 冒烟)。下文带 † 的能力按 A-乙 全部启用。
> 参考模板:auth-console(`/Users/liruijun/personal/LLM/auth-platform/auth-console`)。后端:workflow-platform server :8300。

## 0. 独立评审修订记录(全部已并入下文)
独立只读评审发现 1 Blocker + 3 Major + 4 Medium + 6 改进,均已对照后端源码核实。处置:
- **B1(Blocker)审方 BPMN 无 BPMNDI 图形段 → bpmn-js 渲染不出**:属实(`his-rx-review-v1.bpmn20.xml` 无 `<bpmndi:*>`,是我 Phase 1 只写了语义模型)。**修订**:实施步骤新增"给该 BPMN 补 DI"(用 bpmn-js Modeler 往返生成或 `bpmn-auto-layout`,一次性、利于任何查看器/设计器);在此之前页面②不算可行。这是无论 Q1 如何都要做的后端资源改动(小、纯图形、不动流程语义)。
- **B2(Major)"处理中→已落地"纯前端无法兑现且自相矛盾**:属实——后端 `complete-review` 同步完成任务、恒返回字面量 `PENDING_BUSINESS`,真实 phase 只在 `wf_process_link` 表无 REST 暴露,任务 complete 后立即从 `GET /tasks` 消失(≠落地信号)。**修订**:范围改为**推荐 Option A-乙(顺带补后端只读端点)**;若选 A-甲(纯前端)则**如实降级**——办理仅 `message.info` 回显 actionId,无"处理中"持久行、无"已落地/INCIDENT"可见性,§1/§3/§5/§11 相应措辞已改为按 Q1 分叉。
- **B3(Major)PhaseTag/DecisionTag/语义色在 Option A 无数据源**:属实(TaskView 无 decision/phase;办结/驳回任务不在活动列表)。**修订**:A-甲 下这些 Tag 仅用于区分 `taskDefinitionKey`(pharmacistReview vs manualRepair)与瞬时 toast,**不表示行的语义状态**;完整语义状态需 A-乙 的 phase/timeline 端点。
- **B4(Major)候选组命名契约未定 → "待我办"可能恒空**:属实(BPMN candidateGroups 是大写 `PHARMACIST`/`ADMIN` 无前缀;token 组经 `groupsFromToken` 取末段、不处理下划线/大小写)。**修订**:①客户端过滤用**大小写不敏感 + 去 `<org>_` 前缀归一化**匹配,并把"Casdoor 组名 ↔ BPMN candidateGroups"映射钉进 README 与 `.env`;②A-乙 下改为**服务端按候选人过滤**从根上解决。列为页面①出数据的必验项。
- **B5(Medium)§4"整块复用"混入需改内容的文件**:**修订** §4 拆成"照抄"与"抄后必改(清除 authz-* 专有词)"两类,并列出清除清单:`authStore` 的 `canRead/isAdmin` 硬编码 `authz-admin/authz-viewer`(不改则人人 403)、`ProtectedRoute` 门控+403 文案、`api/client.ts` 加 `X-Workflow-Tenant` 注入且 `adminBaseUrl` 语义/命名改、`AppLayout` 品牌"权限管控台"文案。
- **B6(Medium)manualRepair(ADMIN)口径未定、与 ReviewDrawer 冲突**:**修订**:本轮待办中心**只显示 `pharmacistReview`**,manualRepair(ADMIN,无 decision 网关)默认过滤;其处置为 non-goal(留后续管理员视图)。ReviewDrawer 仅服务 pharmacistReview。
- **B7(Medium)移动端办理与 Q2 矛盾**:**修订**:移动端办理改为**条件项**——默认 Q2=只读可达(移动端不办理,办理走桌面);§7/§9/§11 的移动办理仅在 Q2 选"可移动办理"时构建/验收。
- **B8(Medium)无分页+全量拉取+爆发轮询载荷**:**修订**:A-甲 全量拉取仅在"药师待审队列小"假设下可接受(标为假设);A-乙 的服务端分页+候选过滤根治;爆发轮询加退避已有,风险栏补记。
- **C1** Vitest 测试文件在 `src/**` 会被 `tsc &&` 连带类型检查 → tsconfig 加 `types:[vitest/globals, @testing-library/jest-dom]` + jsdom 环境(§8/§10 已补)。**C2** bpmn 不进首屏靠 `React.lazy` 动态 import(manualChunks 仅辅助),措辞已改。**C3** 静态 XML 从 core 资源拷入前端有 drift,记入维护约定(A-甲)。**C4** actor 注入随步骤7鉴权补齐(步骤注明)。**C5** 后端错误 body `{error,message}`,前端读 `.message` 并做文案映射,不直吐后端串。**C6** 被他人 claim(assignee≠我)的任务显示口径顺带定义(shadow 下 assignee 多为 null,影响小)。
- **评审结论采纳**:因 B2/B3/B4 同源于"后端只读能力不足",**推荐本轮走 A-乙(补少量只读端点)**;若坚持 A-甲则按上述如实降级。B1 无论如何先补 DI。

## 1. Goals / Non-goals

### Goals(本轮 = Option A;A-甲 纯前端 / A-乙 +后端只读端点,见 §0 修订与 Q1)
> 下列以 **A-乙(推荐)** 为准;若选 A-甲,带 †的能力按 §0 如实降级。
- 新建独立前端 `workflow-platform/workflow-console`,克隆 auth-console 技术栈与约定(React18+Vite5+TS+antd5+react-query+zustand+oidc-client-ts,pnpm)。
- **待办中心(页面①)**:查审方待办 + 办理(通过/驳回,驳回意见前端必填);办理返回 202 如实呈现"已受理/处理中(PENDING_BUSINESS)",不伪装已完成;轮询追一致性。
- **流程轨迹只读查看(页面②)**:bpmn-js NavigatedViewer 渲染审方流程图(懒加载)。
- 应用外壳、鉴权分期(dev 先无鉴权跑通 → 叠 Casdoor SSO)、租户/actor 注入收敛到 axios 单点。
- 引入前端测试栈(Vitest + Testing Library + 1 条 Playwright 冒烟)。
- 按 C 骨架组织代码(`BpmnCanvas` 抽象、懒路由、`useTaskListSync`),使 Modeler/部署成为下一轮纯增量。

### Non-goals(本轮明确不做,写清依据)
- **BPMN 设计器(拖拽建模)/ 部署 / 定义版本管理**:后端 admin 部署端点未实现;Modeler 体积大、编辑器状态机复杂;留 Option B 下一轮。
- **移动端/触屏下的 BPMN 建模**:拖拽建模在触屏不可用(依据:移动端子代理),小屏仅只读查看或引导桌面。
- **认领/转办/加签**:后端未暴露(claim 仅在 service);resubmit 是 HIS 医生侧动作(走 Kafka,不经 console)。
- **退费(refund)审批**:后端本身押后到下一轮。
- **待办业务详情(患者/医嘱明细)**:需跨服务连 HIS detail API;本轮只显示 encounterId(businessKey)+ 候选组 + 时间。
- 深色模式。

## 2. 视觉方向与设计参考(沿用既有,不新造)
**结论:1:1 沿用 auth-console 视觉语言**(一致性 > 追新),直接搬 `src/theme/colors.ts` + `src/theme/theme.ts` + `src/styles/global.css` + `ConfigProvider(locale=zhCN, theme=appTheme)` 装配。落到本项目的 tokens(全部来自 auth-console 实测):
- 主色 `#315EFB`、选中底 `#EEF3FF`;success `#16A36A`、warning `#D97706`、error `#D92D20`;bgLayout `#F5F7FA`、border `#E6EAF0`、text `#172033`/secondary `#667085`。
- 圆角 8/12;字号 14;控件高 36/40;内容区 max-width 1440 居中、padding 24(≤768 降 16);Sider 224/72 白底;Table 头 `bgSubtle`。
- **流程语义 → token 映射**(新增约定,标为待确认假设):待办/待落地=warning(Tag gold/processing)、办结=success(green)、驳回=error(red)、审批中/当前节点=primary(blue/processing)、跳过/草稿=textTertiary(default)。
- 参考模式(仅借鉴、落到本项目 token,不引 Pro 组件库):Ant Design Pro「工作台/待办」列表 + 「审批详情时间线」;bpmn 高亮借鉴流程引擎控制台的"当前节点描边 primary、已走路径 success、驳回 error"。

## 3. 路由与页面流(数据式 router + 懒加载)
```
/callback                 公开(OIDC 回调,复用 auth-console CallbackPage)
[ProtectedRoute > AppLayout]
  /                       → 重定向到 /tasks
  /tasks                  待办中心(eager,核心页):列表 + 过滤 + 办理入口(Drawer)
  /tasks/:taskId          可选:深链打开办理 Drawer(或用查询参数)
  /process/:key           流程轨迹只读查看(lazy → 拉 bpmn Viewer chunk)
  *                       兜底跳 /tasks
```
用户流:
- **办理流**:登录 → /tasks → 看待办列表(按候选组过滤"待我办")→ 点一行开右侧 Drawer(显示 encounterId/候选组/时间 + PASS/REJECT 单选 + 驳回意见)→ 提交 → 202 → Drawer 关、行标"处理中" toast(actionId)→ 列表 invalidate + 爆发轮询 → 该任务落地后从活动列表消失/或进 INCIDENT。
- **看图流**:/process/hisRxReview → 懒加载 Viewer → 渲染流程图(本轮静态定义图;若接后端实例端点则高亮当前节点)。

## 4. 组件树(复用现有 vs 新建)
**整块复用 auth-console(复制,少量改)**:`main.tsx`、`auth/{oidcConfig,AppAuthProvider,AuthBridge,ProtectedRoute}`、`pages/CallbackPage`、`api/client.ts`、`config/index.ts`、`store/authStore.ts`、`components/layout/{AppLayout,PageHeader}`、`components/common/AsyncState`、`theme/*`、`styles/global.css`、`nav.tsx`(改菜单项)、`vite.config.ts`/`tsconfig.json`/`env.d.ts`/`Dockerfile`/`nginx.conf`(改 proxy 目标/端口)。
**新建**:
- `api/tasks.ts`(findTasks/completeReview + TS 类型,以 protocol TaskView/CompleteReviewRequest 为准)
- `hooks/useTasks.ts`(useQuery 列表 + useMutation 办理 + `useTaskListSync` 轮询封装)
- `store/uiStore.ts`(过滤/选中任务/Drawer 开关/轮询开关等 UI 态)
- `pages/TasksPage.tsx`(待办中心:桌面 Table / 小屏卡片 + 过滤 Segmented + 刷新)
- `components/domain/ReviewDrawer.tsx`(办理抽屉:PASS/REJECT + 意见,antd Form + rules)
- `components/domain/{PhaseTag,DecisionTag,TaskCard}.tsx`(状态标签、小屏卡片)
- `pages/ProcessTracePage.tsx`(轨迹页,lazy)
- `components/bpmn/BpmnViewer.tsx`(NavigatedViewer 封装,lazy)+ `components/bpmn/BpmnCanvas.tsx`(公共壳,为 B 预留)
- 测试:`*.test.tsx`(Vitest+RTL)+ `e2e/tasks.smoke.spec.ts`(Playwright)

## 5. 状态与边界(逐页 loading/empty/error/success)
**/tasks 待办中心**
- loading:首屏 `PageSkeleton`;翻页/过滤用 `Table loading`/`List loading`。
- empty:`EmptyState`「暂无待办」(区分"加载中"与"空")。
- error:`ErrorState + onRetry=refetch`;401 由 axios 单飞续期兜底(页面不处理)。
- success(办理):`message.info`「已受理,待业务落地(actionId…)」+ Drawer 关 + `invalidate(['tasks'])` + 爆发轮询;**不显示"已完成"**。
- 边界:驳回意见空 → antd Form rule 就地红字拦截(不提交);重复提交 → 按钮 loading/禁用;并发 409(WorkflowConflict)→ `message.warning` 友好提示 + 刷新;REJECT 重提回环出的新 task 自然由重拉显示。
- 数据边界:后端 `GET /tasks` 无候选人过滤 → 前端按当前用户 groups vs `candidateGroups` **客户端过滤**(数据量小可接受,标为 P0 后端待补);manualRepair(ADMIN)任务默认按候选组过滤掉,除非管理员视图。

**/process/:key 轨迹**
- loading:Viewer chunk 懒加载中 `Spin`/`PageSkeleton`。
- empty:无流程图 `EmptyState`。
- error:XML 解析失败 `ErrorState`「流程图解析失败」+ 查看原始 XML;bpmn 加载失败降级文案。
- success:渲染图 + `fit-viewport`;(接后端实例端点后)高亮当前/已走节点。

## 6. API 契约
### 6.1 本轮真对接(已实现)
- `GET /api/v1/tasks?definitionKey=&businessKey=`,头 `X-Workflow-Tenant: his` → `TaskView[]`(taskId/taskDefinitionKey/name/processInstanceId/processDefinitionKey/businessKey/tenantId/assignee/candidateGroups/createTimeEpochMs)。
- `POST /api/v1/tasks/{taskId}/complete-review`,头 `X-Workflow-Tenant`,body `CompleteReviewRequest{decision:'PASS'|'REJECT',opinion?,actorSub?,actorUsername?,actorDisplayName?}` → **202** `{actionId,status:'PENDING_BUSINESS'}`。错误:409(冲突)/400(参数)。
- TS 类型以 protocol record 为唯一真值,放 `api/types.ts`,与后端字段严格对齐。

### 6.2 前端依赖但后端待补(清单,Q1 决定本轮是否补)
| 优先级 | 端点 | 用途 | 现状 |
|---|---|---|---|
| P0 | `GET /api/v1/tasks?candidate=&state=&page=&size=` | "我的待办"精确过滤 + 分页 | 现仅 definitionKey/businessKey、无分页 → 本轮先客户端过滤 |
| P1 | `GET /api/v1/process-instances?businessKey=&tenant=`(或 `/{id}`)返回 phase/appliedStatus | 办理后展示"已落地/待落地/INCIDENT" | 未实现(phase 只在表) |
| P1 | `GET /api/v1/process-instances/{id}/timeline` | 轨迹页时间线 + 节点高亮 | 未实现 |
| P1 | `GET /api/v1/definitions/{key}/xml`(或 admin) | 轨迹页取 BPMN XML | 未实现 → 本轮先用打包的静态 XML |
| P2 | claim/transfer/add-signers、admin deploy/定义列表 | 下一轮 Option B | 未实现 |

## 7. 响应式与移动端适配策略
沿用 auth-console:antd 默认断点(xs<576/sm≥576/md≥768/lg≥992/xl≥1200/xxl≥1600)+ `Grid.useBreakpoint()` + `isMobile=!screens.lg`;`.app-content` max 1440 + padding 24/16;`.scroll-x`/`.mono` 工具类;外壳桌面折叠 Sider(224/72)/ 移动 Drawer。

| 视口 | 待办中心 | 办理 | 轨迹 | bpmn |
|---|---|---|---|---|
| ≥1200 桌面 | Table 多列 | 右侧 Drawer 480–560 | Timeline/图 | Viewer 全功能只读 |
| 992–1199 | Table `scroll.x` 横滚保列 | 同桌面收窄 | 单列 | Viewer(触屏用平移/缩放按钮) |
| <768 手机 | **表格→卡片堆叠**(TaskCard:encounterId+候选组+时间+主操作按钮全宽 ≥44px) | Drawer 全屏(placement bottom/right, width/height 100%)、控件全宽、意见输入全宽、提交/取消固定底部 ≥44px | 天然单列 | **只读预览 + 缩放平移;<992 提示"建模请用桌面"**(建模本就 non-goal) |
- 交互替换:行 hover 操作 → 触屏改行末常驻按钮/`Dropdown`(见待澄清 Q:偏好);节点 hover 详情 → 点击展开。
- 移动端验收:390×844 下能读待办卡片、能打开办理全屏抽屉并提交(若 Q2 选"可移动办理")、轨迹图能双指缩放/平移不溢出页面。

## 8. 文件级改动清单
### 新建 `/Users/liruijun/personal/LLM/workflow-platform/workflow-console/`
- 工程:`package.json`(scripts dev/build=tsc&&vite build/preview/test)、`vite.config.ts`(dev 5373,proxy `/api`→`:8300`,manualChunks 增 bpmn 块,loadEnv)、`tsconfig.json`(扁平 noEmit,**不用 composite+references**)、`env.d.ts`、`.env.example`、`index.html`、`Dockerfile`(nginx 8302)、`nginx.conf`、`.gitignore`。
- 复制改:`src/{main.tsx,config/index.ts,api/client.ts,store/authStore.ts,auth/*,pages/CallbackPage.tsx,components/layout/*,components/common/AsyncState.tsx,theme/*,styles/global.css,nav.tsx,router/routes.tsx}`。
- 新建见 §4 新建清单。
- 测试:`vitest.config.ts`、`playwright.config.ts`、`src/**/*.test.tsx`、`e2e/tasks.smoke.spec.ts`、`test/msw/*`(mock 后端契约)。
- `README.md`(启动/联调/鉴权分期说明)。
### (Q1 若选"顺带补后端")workflow-platform-server 增只读端点
- `ProcessQueryController`(GET process-instances by businessKey → phase/appliedStatus;GET timeline)、`DefinitionController`(GET definition xml);core 加 `ProcessApplicationService#get/#timeline`(用 HistoryService)+ ProcessLinkRepository 暴露查询。**不碰计费/审方业务逻辑。**

## 9. 按依赖排序的实施步骤
1. **脚手架**:克隆 auth-console 工程配置 + provider 栈,起空壳(登录暂关,dev proxy `/api`→:8300),`pnpm dev` 出空布局 + 菜单。
2. **api/类型层**:`api/types.ts`(protocol 对齐)+ `api/tasks.ts` + axios 单点注入 `X-Workflow-Tenant`。
3. **待办中心列表**:`TasksPage` Table + 过滤 + 刷新 + 客户端候选组过滤 + `useTasks` useQuery;空/错/载态。
4. **办理**:`ReviewDrawer`(Form + rules 意见必填)+ `useMutation` completeReview + 202"处理中"呈现 + invalidate + `useTaskListSync` 爆发轮询。
5. **移动端**:TaskCard 卡片式 + 全屏抽屉 + 断点切换;390/768 视口自测。
6. **轨迹页**:`BpmnCanvas`+`BpmnViewer`(NavigatedViewer,lazy chunk)渲染 BPMN XML(本轮静态 XML;Q1 若补后端则接 definition/timeline)。
7. **鉴权叠加**:接 auth-console Casdoor OIDC 模板(新 client_id + 组名门控),401 单飞续期,ProtectedRoute。
8. **测试**:Vitest 组件/hook(办理 202 态、重拉、驳回校验、409 分支)+ Playwright 冒烟。
9. **交付**:Dockerfile/nginx(8302)、README、`pnpm build` 通过。
(Q1 若选补后端:在步骤 3/6 前插入后端只读端点实现 + 冒烟。)

## 10. 测试策略(含移动端视口矩阵)
- **单元/组件(Vitest+@testing-library/react)**:办理后显示"处理中"非"已完成";invalidate 后列表重拉渲染;REJECT 意见空拦截;409/503/401 分支;axios 租户/actor 单点注入;`useTaskListSync` 轮询开关与 visibility 暂停。
- **契约**:以 protocol record 生成/校验 TS 类型;MSW mock 响应 shape 与真实 202 一致。
- **e2e 冒烟(Playwright,1 条起步)**:查待办→办理→断言不显示"已完成"+列表重拉。dev 无鉴权阶段先跑,叠 SSO 后补登录态注入。
- **bpmn 专项**:懒加载 chunk 按需(bundle 分析)、Viewer 只读渲染回归、大图性能。
- **移动端视口矩阵**:360×640 / 390×844(主力)/ 768×1024 / 1024×768(lg 临界)/ 1440×900。重点:待办卡片可扫读、办理全屏抽屉可提交、bpmn 小屏横滚/缩放不撑破页面。

## 11. 验收标准
> 按 §0 修订:带 † 项仅 A-乙 适用;A-甲 下这些项替换为"办理成功 toast 回显 actionId、任务从活动列表消失"这一诚实下限,并删除"处理中→已落地""当前节点高亮"等做不到的项。页面②验收以"补 DI 后能渲染流程图"为前提(B1)。
- `pnpm build`(tsc + vite)通过;`pnpm test`(Vitest)全绿;Playwright 冒烟通过。
- 待办中心:能列出 tenant=his 的 pharmacistReview 待办;办理 PASS/REJECT 成功返回 202,UI 显示"处理中/已受理"且**绝不显示"已完成"**;驳回未填意见被就地拦截;办理后列表经 invalidate+轮询正确更新;409 有友好提示。
- 轨迹页:懒加载 Viewer 渲染审方 BPMN 图,`fit-viewport` 正常;bpmn chunk 不进待办主路径首屏(bundle 验证)。
- 鉴权:未登录跳 Casdoor;登录后按组门控菜单/路由;401 静默续期一次。
- **移动端(至少一项)**:390×844 下待办以卡片呈现、可打开办理抽屉;bpmn 页在 <992 呈只读并给桌面引导。
- 租户/actor 注入仅在 axios 单点;去掉后(Phase 3 JWT 派生)业务组件零改动。

## 12. 风险与回滚
| 风险 | 缓解 |
|---|---|
| 后端缺 candidate/分页/timeline/定义 XML 端点 | 本轮客户端过滤 + 静态 XML 兜底;P0/P1 列入待补清单;Q1 决定是否顺带补 |
| 办理 202 最终一致被误呈现为"已完成" | 强制"处理中"标签 + 不做删除式乐观更新 + 组件测试守卫 |
| bpmn-js 体积拖累首屏 | 路由级 lazy + 独立 manualChunk + 只用 NavigatedViewer;保留 chunk 告警 |
| 鉴权分期返工 | tenant/actor 注入收敛 axios 单点;Casdoor 模板整块复用 |
| 端点命名/契约漂移(complete-review vs complete、无 idempotencyKey) | TS 类型对齐**实现**;mock 与真实 shape 一致;适配层隔离临时契约 |
| 两 console 视觉/外壳漂移 | 本轮复制;后续可抽共享包(记入 backlog) |
- **回滚**:SPA 独立部署,nginx 切回旧 dist 即回滚;workflow-console 与既有系统零耦合(只读消费 :8300),下线不影响 his/中台;**办理动作不可回滚**(后端语义),UI 对 PASS/REJECT 做二次确认 + 失败明确提示"未落地可重试",绝不让用户以为 202 是"已完成且可撤销"。

## 13. 待澄清(随 AskUserQuestion)
见 DECISION_RECORD「未决」四点:本轮范围(纯前端 vs 顺带补后端只读端点)、移动端档位、是否引入测试栈、bpmn 本轮只读确认。
