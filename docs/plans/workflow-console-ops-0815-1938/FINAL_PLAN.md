# workflow-console 运维面板 实施计划(P1-2.1 前端)

> 状态:**已批准(2026-08-15)**。决策依据见同目录 `DECISION_RECORD.md`。后端已就绪(commit `aec1c42`)。
>
> **批准决策**:① 移动端危险操作 = **与桌面完全等价**(不做 disabled/引导桌面,危险操作靠二次确认;§7 相应简化——移动端也可终止/批量重放,详情走全屏抽屉先读 payload);② dev 显示运维面板(逃生门 `!authEnabled||isAdmin`);③ replay-all **开放** + 强确认(明示上限 500);④ **顺带 companion 后端小改**:`ProcessInstanceView` 加 `suspended` 字段(`ProcessInstance.isSuspended()`)→ 前端做 `SuspendTag`+双击防重;`WorkflowExceptionHandler` 映射 `FlowableObjectNotFoundException`→404{error,message} 且 DLQ 404 带 message。故 R6/D6 的 companion 后端改**本轮一起做**;§8 改动清单含后端三处。
> 复用 auth-console 克隆栈(React18+Vite5+TS+antd5+react-query+zustand),不新造抽象。

## 0. 独立评审修订记录(全部已并入下文)
独立评审发现 6 处确定返工点 + 若干表述订正,均已修订:
- **R1(硬伤)`useTaskListSync` 泛化会双红**:TasksPage 无参调用 + 既有单测直接测它。**修订**:泛化 `useBurstInvalidate(queryKey,opts)` 后**保留薄封装** `export const useTaskListSync = (opts)=>useBurstInvalidate([TASKS_KEY],opts)`,TasksPage 与既有测试零改动;queryKey 用 ref/`JSON.stringify` 稳定化,避免每渲染重建定时器(§4/§9)。
- **R2(硬伤)菜单空分组渲染**:非 ADMIN 过滤掉 adminOnly 项后「系统运维」组 children 为空仍渲染空标题。**修订**:过滤后 `.filter(g=>g.children.length>0)`;加单测「非 ADMIN 菜单不含『系统运维』」(§4/§9/§10)。
- **R3(硬伤)matchMedia 桩致 `isMobile` 恒真**:`test/setup.ts` 桩使所有断点 false → 组件测试默认跑移动变体,而危险操作在移动端 disabled → §10 危险操作确认流测试会撞 disabled 而错。**修订**:危险操作确认流用例内 mock `Grid.useBreakpoint`(或 matchMedia 令 `min-width:992` 命中)强制桌面变体;移动端 disabled 用例用默认桩(§10)。
- **R4(硬伤)步骤前向依赖**:`DetailDrawer`/`DlqStatusTag` 被面板依赖却排在其后;`routes.tsx` 接线 `lazy(OpsPage)` 但 OpsPage 更晚建。**修订**:重排步骤——共享原子件(DetailDrawer/DlqStatusTag/AdminRoute)先于面板;routes 接线在 OpsPage 之后(§9)。
- **R5 terminate「reason 必填」**:`modal.confirm` 原生不支持带校验必填输入。**修订**:终止改用**专用 `Modal`+`Form`(Form.Item required)**,同 `ReviewDrawer` 范式,天然红字校验且可复用其测试套路(§3/§5/§10)。
- **R6 companion 后端改移出本轮**:D5 前端已按状态码自洽兜底。**修订**:`WorkflowExceptionHandler` 映射与 DLQ 404 带 message 列为**独立后端 ticket**,不入本前端计划改动清单(§8)。是否本轮顺带做由用户在批准时定。
- **表述订正**:①§2「零硬编码 token」对 Tag 不成立——Tag 沿用 PhaseTag 的 **antd 预设状态色名**('gold'/'processing'/'success'/'error'/'default'),colors.ts token 只用于非 Tag 行内色值;DlqStatusTag 档位(NEW=warning/REPLAYED=default)与 PhaseTag 语义一致,无误。②terminate 在 **INCIDENT 筛选态**下:终止后 phase→CANCELLED 会**从 INCIDENT 列表移出**(非"行变 CANCELLED");验收措辞据此改。③**500 文案改中性**「操作失败,请刷新后重试」(因 suspend already-suspended 等也落未映射 500)。④AdminRoute 保持极简(父 ProtectedRoute 保证 authorities 已就绪,ADMIN 不闪 403,勿加多余 loading)。⑤`?tab=` 非法值回退 'instances' + 切 Tab `setSearchParams({tab},{replace:true})`。⑥删冗余 `findIncidents`,直接 `findInstances({phase:'INCIDENT'})`。⑦移动端可逆操作(挂起/恢复/单条重试)**直接用 `modal.confirm`**(antd 响应式,底部弹层够读),不做"全屏抽屉可逆操作"第二套交互;抽屉只留 payload/异常详情。⑧safe-area/`viewport-fit=cover` 是**全局改动**,剔出本轮(列全局打磨),ops 底部抽屉不依赖它。
- **D6(SuspendTag/挂起态)再评估**:`running` 对挂起实例仍 true → 无列表反馈、双击挂起会 already-suspended→500。评审指出**数据可低成本暴露**(`ProcessInstance.isSuspended()` 加 `ProcessInstanceView.suspended` 字段)。**处置**:作为批准时的范围选择(见 §13/AskUserQuestion)——(a)前端自洽:保留 suspend/activate 但无列表反馈+中性 500,不做 SuspendTag;(b)顺带 companion 后端加 `suspended` 字段→SuspendTag 可做+防重;(c)本轮 suspend/activate 一并延后,实例只做 查询/终止/看轨迹。

## 1. Goals / Non-goals

### Goals
- 新增 **运维面板**(ADMIN):
  - **实例运维**:按 `definitionKey`/`phase` 查实例;挂起 / 恢复(可逆);**终止**(不可逆,置 CANCELLED,填 reason)。含 **incident 快捷**(phase=INCIDENT)。实例可跳「流程轨迹」页排查。
  - **Flowable 死信作业**:列出;**重试**(移回可执行队列,异步)。
  - **DLQ(Kafka)死信**:按 status 列出;**单条重放**;**批量重放**(replay-all)。
- 门控:菜单与路由仅 ADMIN 可见/可达;**dev(authEnabled=false)放行**(逃生门)。
- 诚实呈现:同步操作说「已终止/已挂起」;异步(重试/重放)说「已受理,追最终一致」,**绝不「已完成/已修复」**;操作后爆发式刷新列表体现真实落地。
- 复用既有视觉语言与组件;引入配套 Vitest 组件/hook 测试 + 1 条 Playwright 冒烟。

### Non-goals(本轮不做,附依据)
- 流程定义部署 / 版本管理(后端归「独立 admin 服务后续」,`AdminOpsController` 注释)。
- **挂起态标签**:`running` 无法区分挂起/活跃(D6),不做 SuspendTag。
- **死信作业→轨迹直达**:`DeadLetterJobView` 缺 businessKey,轨迹页只吃 businessKey(缺后端桥接)。
- 死信/DLQ 按租户过滤(后端跨租户全局);服务端分页(后端仅 limit 截断)。
- **移动端卡片降级 / 移动端不可逆·批量操作**:ops 桌面为主,移动端只读可达 + 危险操作引导桌面(D3);移动端建模/复杂协作(延续 README non-goals)。
- 深色模式、"近期运维动作"审计留痕。

## 2. 视觉方向与设计参考(沿用既有,零新造)
**结论:1:1 沿用 workflow-console/auth-console 既有视觉语言**(一致性 > 追新)。全部走 `theme/colors.ts` token,零硬编码色值:
- 主色 `#315EFB`、语义 success `#16A36A`/warning `#D97706`/error `#D92D20`;圆角 8/12;字号 14;控件高 36/40;`Table.headerBg=bgSubtle`、`headerColor #475467`。
- 危险按钮用 antd `danger` prop(映射 `colors.error`),不新造红;状态用「色 + 中文标签」双编码(同 PhaseTag,色盲可读)。
- 长 ID/topic/key/payload 用 `.mono`;长文本详情用 `<pre className="mono scroll-x">`。
- 参考模式(仅借鉴、落到本项目 token):Ant Design Pro「列表页 + 危险操作二次确认」;运维列表借鉴既有 `TasksPage`(PageHeader + 刷新 + Table scroll + 三态)。

## 3. 路由与页面流

```
[ProtectedRoute > AppLayout]
  /ops        运维面板(lazy;AdminRoute 守卫:dev 放行 / Stage2 需 ADMIN,否则 403 Result)
              antd Tabs(?tab= 深链):instances(默认) / jobs / dlq
```
- 菜单:`nav.tsx` 新增分组「系统运维」→ 项「运维面板」`/ops`,标 `adminOnly`。`AppLayout` 按 `showOps=!authEnabled||isAdmin(authorities)` 过滤菜单。
- 用户流(以危险操作为例):
  - **终止**:实例 Tab → 行「终止」→ `modal.confirm`(danger,含 **reason 必填**,文案「终止不可逆,实例将置 CANCELLED」)→ POST → 204 → `message.success('已终止,实例标记为已取消')` → 爆发刷新,行 phase 变 CANCELLED。
  - **DLQ 重放**:DLQ Tab(status=NEW)→ 行「重放」→ confirm(「投回原 topic,由原监听幂等消费,异步最终一致」)→ POST → 200 `message.info('已重放,消息已投回原 topic(异步最终一致)')` / 404 `message.warning('该死信不存在或已重放,已刷新')` → 爆发刷新。
  - **批量重放**:DLQ Tab 页头「全部重放」(桌面 only)→ 强 confirm(明示上限 500)→ `{replayed:n}` → `message.info('已受理重放 n 条(异步最终一致)')`。
  - **实例→轨迹**:行「查看轨迹」→ `/process/{processDefinitionKey}?businessKey={businessKey}`(复用现有轨迹页)。

## 4. 组件树(复用 vs 新建)

**整块复用(不改)**:`components/layout/PageHeader`、`components/common/AsyncState`(PageSkeleton/ErrorState/EmptyState)、`components/domain/PhaseTag`、`api/client`(apiClient)、`api/errors`(errMsg/statusOf)、`store/authStore`(isAdmin)、`config`。**复制范式(不复用组件本身)**:`ReviewDrawer` 的 `App.useApp().modal.confirm`+message+409/404 分支。

**新建**:
```
pages/OpsPage.tsx                     容器:PageHeader + Tabs(?tab=)
components/ops/InstancesPanel.tsx      实例表 + phase 筛选(含 INCIDENT 快捷) + 挂起/恢复/终止/查看轨迹
components/ops/DeadLetterPanel.tsx     死信作业表 + 重试(可填 retries,默认3) + 异常详情抽屉
components/ops/DlqPanel.tsx            DLQ 表 + status 筛选 + 单条重放/全部重放 + payload 详情抽屉
components/ops/DlqStatusTag.tsx        NEW=warning / REPLAYED=default(同 PhaseTag 写法)
components/ops/DetailDrawer.tsx        通用详情抽屉:<pre className="mono scroll-x">(payload/异常栈全文)
auth/AdminRoute.tsx                    ADMIN 路由守卫(镜像 ProtectedRoute 逃生门 + 403)
api/admin.ts                           findInstances/findIncidents/suspend/activate/terminate/findDeadLetterJobs/retryJob
api/dlq.ts                             listDlq/replayDlq/replayAllDlq
hooks/useOps.ts                        useInstances/useDeadLetterJobs/useDlq + mutations + useBurstInvalidate
```
- `nav.tsx`:`NavItem` 加可选 `adminOnly?: boolean`;新增运维项 + 分组。
- `hooks/useTasks.ts` 的 `useTaskListSync` **泛化**为 `useBurstInvalidate(queryKey, opts)`(保留 20s/2.5s + `document.hidden` 退避),放 `hooks/useOps.ts`(或原地导出),供 ops mutation 后追一致复用。

## 5. 状态与边界(逐页 loading/empty/error/success)

统一沿用 TasksPage 分支:`isLoading→PageSkeleton` / `isError→ErrorState(onRetry=refetch)` / 空→`EmptyState` / 有数据→`Table`。**加载态与空态文案不混用**。

| 面板 | loading | empty | error | success(操作) |
|---|---|---|---|---|
| 实例 | PageSkeleton | 「暂无实例」;phase=INCIDENT 时「当前无异常实例」(正向) | ErrorState+重试;403 越权文案 | 挂起/恢复 `message.success`;终止 `message.success('已终止…')` + 爆发刷新 |
| 死信作业 | PageSkeleton | 「无死信作业」 | 同上 | 重试 `message.info('已受理重试,已移回执行队列(稍后刷新查看)')` + 爆发刷新 |
| DLQ | PageSkeleton | 按 status:「无待重放死信」 | 同上 | 重放 `message.info('已重放…异步最终一致')`;404→warning;批量→`message.info('已受理重放 n 条')` + 爆发刷新 |

**边界(必测)**:
- 空列表(四类各一)。
- 超长 `payload`/`exceptionMessage`:列表内截断(`Typography.Text ellipsis` + tooltip),**不整行铺开**;全文进 `DetailDrawer` 的 `<pre className="mono scroll-x">`。
- **DLQ 404**(不存在/已重放):body 是 `{error}` 无 `message` → **按 `statusOf===404` 特判**文案 + 重拉,不用 errMsg。
- **500**(Flowable 未映射:终止已结束实例 / 重试不存在 job):状态码兜底「操作失败,实例可能已结束/作业不存在,请刷新」,**不**乐观改行。
- 409(WorkflowConflict)→ `message.warning` + 刷新。
- 批量重放 `{replayed:0}`→「无可重放」;`n<可见数`→「已重放 n 条,其余可能已处理」。
- 无分页:列表按 limit(默认100/replay-all 500)截断 → 页头显示「仅显示前 N 条,可调 limit」提示。
- **跨租户提示**:DLQ/死信作业为「全平台视角」,实例/incident 为当前租户 → UI 文案明示,避免误判归属。

## 6. API 契约(对齐后端 record,放 api/types.ts)

新增 TS 类型(照抄 record,可空性对齐):
- `DeadLetterJobView { jobId:string; processInstanceId:string; elementId:string; retries:number; exceptionMessage:string|null }`
- `DlqRecord { id:number; originalTopic:string; msgKey:string|null; payload:string; errorMessage:string|null; status:string; failedAtEpochMs:number|null; replayedAtEpochMs:number|null }`
- `DlqReplayResult { id:number; status?:'REPLAYED'; error?:string }`
- `ProcessInstanceView` 已存在。

`api/admin.ts`(复用 apiClient,**勿手动加 tenant 头**):
- `findInstances({definitionKey?,phase?,limit=100}) → ProcessInstanceView[]` `GET /api/v1/admin/instances`
- `findIncidents(limit=100)` = `findInstances({phase:'INCIDENT',limit})`(或 `/incidents`)
- `suspendInstance(id)/activateInstance(id) → void`(POST,无 body,path `encodeURIComponent`)
- `terminateInstance(id, reason?) → void`(POST,`params:{reason}`)
- `findDeadLetterJobs(limit=100) → DeadLetterJobView[]`
- `retryJob(jobId, retries=3) → void`(POST,`params:{retries}`)

`api/dlq.ts`:
- `listDlq(status='NEW', limit=100) → DlqRecord[]`
- `replayDlq(id:number) → DlqReplayResult`(POST `/api/v1/dlq/${id}/replay`;**id 数字不 encode**;404 由 axios reject,调用方 `statusOf===404` 映射)
- `replayAllDlq() → {replayed:number}`

react-query key:`['admin-instances',{definitionKey,phase,limit}]`(keepPreviousData)、`['dead-letter-jobs',{limit}]`、`['dlq',{status,limit}]`。mutation 成功 `invalidateQueries` 对应 key + `useBurstInvalidate` 追一致。**不做删除式乐观更新**。

## 7. 响应式与移动端适配策略(D3:只读可达 + 危险操作引导桌面)

沿用既有:`Grid.useBreakpoint()` → `isMobile=!screens.lg`(992);`.app-content` max 1440 padding 24/16;`.scroll-x`/`.mono`。

| 视口 | 列表 | 可逆操作(挂起/恢复/单条重试) | 不可逆·批量(终止/replay-all) | 详情(payload/异常) |
|---|---|---|---|---|
| ≥992 桌面 | Table 多列 + `scroll={{x}}` + 操作列 `fixed:right` | 行内常驻按钮/Dropdown | 行内/页头按钮 | Drawer(right) |
| <992 移动 | Table `scroll={{x}}` 横滚(**只读可达**,不做卡片) | 全屏抽屉(bottom)+ 二次确认可提交 | **隐藏或 disabled + "请在桌面执行"** | 全屏抽屉(bottom,height 100%)`pre.mono.scroll-x` |

- 触屏替换:hover 行操作 → 常驻按钮;多操作收进 `Dropdown`「更多」(复用 AppLayout Dropdown 范式);按钮 ≥44px。
- safe-area(companion,小):`index.html` viewport 加 `viewport-fit=cover`;底部抽屉 footer `padding-bottom: max(12px, env(safe-area-inset-bottom))`(不破坏桌面)。
- 移动端验收:390×844 下运维页可加载、Tabs 可切、列表横滚不撑破页面级、详情抽屉全屏可读、危险按钮呈引导桌面态。

## 8. 文件级改动清单

**新增**:`pages/OpsPage.tsx`、`components/ops/{InstancesPanel,DeadLetterPanel,DlqPanel,DlqStatusTag,DetailDrawer}.tsx`、`auth/AdminRoute.tsx`、`api/{admin,dlq}.ts`、`hooks/useOps.ts`、测试见 §10。
**修改**:`api/types.ts`(+3 类型)、`nav.tsx`(+adminOnly + 运维项/分组)、`components/layout/AppLayout.tsx`(菜单按 showOps 过滤 + 读 authorities)、`router/routes.tsx`(+/ops lazy+AdminRoute)、`hooks/useTasks.ts`(导出泛化的 useBurstInvalidate,或在 useOps 新建同构)、`index.html`+`styles/global.css`(safe-area,companion)、`README.md`(运维面板说明)。
**(companion,可选后端小改)**:`workflow-platform-server` 的 `WorkflowExceptionHandler` 映射 `FlowableObjectNotFoundException→404{error,message}`、DLQ 404 带 message —— 减少前端状态码兜底(D5)。

## 9. 按依赖排序的实施步骤
1. **类型 + API 层**:`api/types.ts` 加 3 类型;`api/admin.ts`、`api/dlq.ts`(对齐契约,复用 apiClient)。
2. **hooks**:泛化 `useBurstInvalidate`;`useOps.ts`(useInstances/useDeadLetterJobs/useDlq + mutations,onSuccess invalidate + 爆发刷新)。
3. **门控**:`nav.tsx` 加 `adminOnly` + 运维项/分组;`AppLayout` 菜单按 `showOps` 过滤;`auth/AdminRoute.tsx`;`routes.tsx` 加 `/ops` lazy + AdminRoute 包裹。
4. **实例面板**:`InstancesPanel`(Table + phase 筛选 + INCIDENT 快捷 + 挂起/恢复/终止[danger+reason]/查看轨迹)+ 状态/空/错/边界。
5. **死信作业面板**:`DeadLetterPanel`(Table + 重试[retries]+ 异常详情抽屉)。
6. **DLQ 面板**:`DlqPanel`(status 筛选 + 单条/批量重放 + `DlqStatusTag` + payload 详情抽屉)。`DetailDrawer` 通用抽屉。
7. **容器 + 移动端**:`OpsPage`(Tabs `?tab=`);逐面板 isMobile 横滚 + 危险操作桌面-引导;safe-area companion。
8. **测试**:Vitest 组件/hook + Playwright 冒烟(§10)。
9. **交付**:`pnpm build`+`pnpm test`+`pnpm e2e` 全绿;README 更新。

## 10. 测试策略(含移动端视口矩阵)
- **Vitest + RTL(renderWithProviders)**:
  - 门控:`showOps` 逻辑(authEnabled=false→放行;true&非ADMIN→拒);`AdminRoute`(非ADMIN→403)。
  - 列表渲染:`vi.mock('../../api/admin'|'../../api/dlq')` 喂数据→行数/关键列(PhaseTag/DlqStatusTag);空→EmptyState;错误→ErrorState+重试。
  - 危险操作确认流(每类一):点操作→`findByRole('button',{name:/确认/})`;**取消→API not.toHaveBeenCalled**;确认→以正确参数调用(terminate(pid,reason)/retryJob(jobId,3)/replayDlq(id))。
  - mutation 后:`spyOn(qc,'invalidateQueries')` 被调 + **`document.body.textContent).not.toContain('已完成')`**(诚实守卫)+ 不乐观抹行。
  - 错误映射:reject 404`{error}`→特判文案;500→兜底文案;409→warning。批量:`{replayed:n}`→计数文案;n=0→「无可重放」。
- **Playwright 冒烟**(`e2e/ops.smoke.spec.ts`,非侵入):`goto('/ops')`→heading 可见 + 三 Tab 加载(「有数据 or 空态」`.or()`);**不点任何危险按钮**(不污染数据);诚实守卫 `getByText('已完成').toHaveCount(0)`。
- **移动端视口矩阵**:390×844(主力)/ 768 / 992 临界 / 1440。重点:390 下运维页加载、Tab 切换、Table 横滚不页面级溢出、详情抽屉全屏可读、危险按钮桌面-引导态。

## 11. 验收标准
- `pnpm build`(tsc+vite)通过;`pnpm test` 全绿;`pnpm e2e` 冒烟通过。
- 门控:非 ADMIN 不见运维菜单、深链 `/ops` 得 403;dev(authEnabled=false)可见可达。
- 实例:能按 phase 查/筛(INCIDENT 快捷);挂起/恢复成功提示;**终止需填 reason + danger 二次确认**,成功后行 phase→CANCELLED;可跳轨迹页。
- 死信作业:列出;重试二次确认后调用,提示「已受理…异步」**不显示「已完成」**。
- DLQ:按 status 列;单条重放 200/404 各自文案 + 刷新;批量重放桌面可用、显示 replayed 计数;payload 长文本走详情抽屉不撑破布局。
- 诚实:全程无「已完成/已修复」误导;异步操作后靠列表刷新体现落地,无删除式乐观更新。
- **移动端(≥1 项)**:390×844 下运维页可读、Tab 可切、列表横滚不溢出、不可逆/批量操作呈引导桌面态。

## 12. 风险与回滚
| 风险 | 缓解 |
|---|---|
| dev 门控恒假致面板不可见(D4) | `showOps=!authEnabled||isAdmin` 逃生门 + 单测 |
| DLQ 404 / Flowable 500 无 message(D5) | 前端状态码兜底 + companion 后端映射(建议) |
| 危险操作误触不可逆(终止/replay-all) | danger 二次确认 + 终止 reason 必填 + 批量强确认(明示上限)+ 移动端引导桌面 |
| 异步被误呈现「已完成」 | 强制「已受理」文案 + 不乐观删行 + `not.toContain('已完成')` 守卫 |
| 大量死信/大 payload 渲染/载荷 | 列表截断+详情抽屉 + 无常驻轮询(手动刷新+爆发invalidate)+ limit 提示 |
| 跨租户 DLQ 误判归属 | UI 明示「全平台视角」 |
- **回滚**:纯新增页/路由 + 少量既有文件增量(nav/AppLayout/routes/types);移除 `/ops` 路由与菜单项即回滚,不影响待办/轨迹;**终止操作后端不可逆**——UI 靠 reason 必填 + danger 确认兜住,绝不让用户误以为可撤销。

## 13. 待澄清(随 AskUserQuestion)
见 DECISION_RECORD「未决」:移动端危险操作强制桌面?dev 显示运维面板?replay-all 是否开放前端?companion 后端错误映射是否本轮做?
