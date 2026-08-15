# 可视化流程设计器（BPMN Modeler）实施计划

> 状态：**已批准（2026-08-15）**。决策依据见同目录 `DECISION_RECORD.md`。frontend-plan 工作流产物。
> **批准决策**：① 属性深度 = **Scope C（完整属性面板）**——引入 `bpmn-js-properties-panel@5.63` + `@bpmn-io/properties-panel@3.48`，用 vanilla `BpmnPropertiesProviderModule`（标准 BPMN：id/name/文档/条件表达式/isExecutable 等）+ **自定义 Flowable provider**（补 `flowable:candidateGroups`/`assignee`/serviceTask `delegateExpression`）+ 内联 flowable moddle（`isAttr:true`）。**不用** Camunda/Zeebe provider（语义错配）。② 放置 = **独立路由 `/designer`**。
> 后端零改动（复用 `POST /api/v1/admin/definitions/deploy` 与 `GET /api/v1/definitions/{key}/xml`）。复用既有栈 React18+Vite5+TS+antd5+react-query。
> **诚实定位（H1）**：设计器是「可视化 BPMN 编辑+部署工具」，产物无法从 console 独立跑实例（发起归消费方 Kafka `StartProcessCommandV1`，完成走审方专用端点，condition 仅 `decision` 变量）。

## 1. Goals / Non-goals

> **定位（据独立评审 H1 诚实界定）**：本特性是**可视化 BPMN 编辑 + 部署工具**——粘贴 XML 的严格升级，能可视化新建/编辑并部署与 his-rx-review 同构的定义。它**不生产能从 console 独立跑起来的流程**：实例只由消费方（his-outpatient 经 Kafka `StartProcessCommandV1`）发起，任务完成走审方专用端点（`decision∈{PASS,REJECT}`）。设计器的产出「结构可部署、userTask 可被候选组查到、网关可基于 `decision` 路由」，真正跑通仍需消费方发起 + 服务端 delegate bean。

### Goals
- 新增 **流程设计器**（ADMIN）：ADMIN 在浏览器拖拽建模（StartEvent/EndEvent/UserTask/ServiceTask/Exclusive&Parallel Gateway/SequenceFlow/中间事件），连线、双击改 name。
- **属性编辑（Scope C · bpmn-js-properties-panel）**：右侧完整属性面板。vanilla `BpmnPropertiesProviderModule` 提供标准 BPMN 属性（元素 `id`/`name`/documentation、sequenceFlow `conditionExpression`、process `isExecutable` 等）；**自定义 Flowable provider** 补 userTask `flowable:candidateGroups`/`assignee`、serviceTask `flowable:delegateExpression`（`TextFieldEntry`+`useService('modeling').updateProperties`，内联 flowable moddle `isAttr:true`）。condition 面板旁给「平台仅注入 `decision` 变量」提示 + `${decision=='PASS'}` 预设说明。
- **两条流程**：① 新建（空白最小模板）；② 编辑既有定义（`getDefinitionXml(key)` 载入最新版 → 部署为新版本）。
- 预览生成的 BPMN XML（DetailDrawer），**部署**（复用 `useDeployDefinition`），部署前前端强校验（唯一可执行 process + id 非空）。
- 撤销/重做（bpmn-js commandStack）；未保存改动离开拦截。
- 门控 ADMIN（复用 `AdminRoute`，dev 逃生门）；诚实呈现部署结果（同步「已部署 key vN」，**绝不「已完成/已生效」**）。
- 引入配套 Vitest（mock Modeler）+ 1 条 Playwright 冒烟。

### Non-goals（附依据）
- **从 console 发起 / 端到端跑通设计器产物**（评审 H1 硬约束）：无 start-process-instance 端点（实例由 Kafka `StartProcessCommandV1` 驱动）、无通用完成端点（仅审方 `complete-review`，`decision∈{PASS,REJECT}`）。设计器只负责「画+改+部署定义」，发起/驱动归消费方 repo + 服务端 delegate bean。
- **导入 `.bpmn` 文件 / 下载 `.bpmn`**：本轮不做（评审 M6，工具栏无文件控件），载入来源仅「已部署定义下拉」。留后续。
- **Camunda/Zeebe provider**：不启用（语义与 Flowable 错配）；只用 vanilla BPMN provider + 自定义 Flowable provider。
- **serviceTask delegate bean 配置 / 表单设计器 / 多实例会签 / 定时器属性 / 消息关联**：delegate bean 是服务端事，带 outbox/ACK 的流程仍走「克隆 his-rx-review 改 XML」路径（DR/D2 事实 3）。
- **版本选择 / 回滚 / 删除定义**：后端无 delete 端点（DR/D3）。
- **移动端建模**：<992 只读降级 + 引导桌面（DR/D5，三处先例）。
- 后端改动、深色模式、协作/自动保存草稿持久化。

## 2. 视觉方向与设计参考（1:1 沿用既有，零新造）

沿用 `workflow-console/auth-console` 既有视觉语言（一致性 > 追新），全走 `theme/colors.ts` token：
- 主色 `#315EFB`、success `#16A36A`/warning `#D97706`/error `#D92D20`；圆角 8/12；字号 14；控件高 36/40。
- 画布容器沿用 `.bpmn-canvas`（白底圆角 8）；长 XML 用 `<pre className="mono">`（DetailDrawer pre-wrap 范式）。
- 脏标记/部署结果/元素状态一律「语义色 + 文字(+图标)」双编码（沿用 `PhaseTag`）；撤销/重做用 `disabled` 灰置。
- 工具栏沿用 `PageHeader{title,description,extra}` + `Space wrap`；图标取 `@ant-design/icons`：`FileAddOutlined/FolderOpenOutlined/UndoOutlined/RedoOutlined/CodeOutlined/CloudUploadOutlined`。
- bpmn-js palette/context-pad 用其**默认皮肤**（引 `bpmn-js.css`）——保持 bpmn-io 生态一致性，不覆写建模器内部样式，仅外层容器/工具栏用项目 token。

## 3. 路由与页面流

```
[ProtectedRoute > AppLayout]
  /designer            流程设计器(lazy 独立 chunk;AdminRoute 守卫:dev 放行/否则 ADMIN 否则 403)
    ?key=<definitionKey>   载入该定义最新版编辑(缺省=新建空白)
```
- `nav.tsx`：`系统运维` 分组新增 `adminOnly` 项「流程设计器」`/designer`（`ApartmentOutlined`/`EditOutlined`）。
- `DefinitionsPanel`：页头加「可视化新建」按钮 → `navigate('/designer')`；表格行操作加「设计」→ `/designer?key={r.key}`。保留现有「部署 BPMN(粘贴 XML)」Modal 作退路。
- 用户流：
  - **新建**：`/designer`（无 key）→ importXML 最小模板（process id 占位如 `newProcess`，含 1 个 StartEvent）→ 拖拽建模/改属性 →「导出 XML」预览 →「部署」Modal（name 预填 process id/name，必填）→ 前端校验通过 → `deployDefinition` → 204/视图 → `message.success('已部署 key vN')` → 清 dirty →（可选）跳 `/ops?tab=definitions`。
  - **编辑既有**：`/designer?key=hisRxReview` → `useQuery(['definition-xml',key])` 拉 XML（loading=PageSkeleton / error=ErrorState 重试）→ importXML → 编辑 → 部署 = 新版本（文案「将发布为新版本」）。
  - **未保存离开**：dirty 时切路由/新建/载入覆盖/关标签 → `modal.confirm`（okButtonProps.danger「仍然离开」/「留在本页」）+ `beforeunload`。

## 4. 组件树（复用 vs 新建）

**整块复用（不改）**：`components/layout/PageHeader`、`components/common/AsyncState`(PageSkeleton/ErrorState/EmptyState)、`components/ops/DetailDrawer`(XML 预览)、`components/bpmn/BpmnViewer`(小屏只读降级)、`auth/AdminRoute`、`api/admin#deployDefinition`、`api/process#getDefinitionXml`、`api/errors#{errMsg,opErrorText,statusOf}`、`hooks/useOps#{useDeployDefinition,useDefinitions}`、`theme/colors`、`api/types#ProcessDefinitionView`。

**新建**：
```
pages/DesignerPage.tsx              容器(smart):读 ?key;载入 XML;持 dirty/name;useBlocker 拦截;isMobile 降级;渲染工具栏+编辑器
components/bpmn/BpmnModeler.tsx      纯编辑器(dumb):Modeler 生命周期(useRef+destroy 幂等,复刻 BpmnViewer);
                                     new Modeler({container, propertiesPanel:{parent:propsRef}, moddleExtensions:{flowable},
                                       additionalModules:[BpmnPropertiesPanelModule, BpmnPropertiesProviderModule, FlowablePropertiesProviderModule],
                                       keyboard:{bindTo:document}});
                                     import 3 CSS(diagram-js/bpmn-js/bpmn-embedded)+ 'properties-panel.css';
                                     两个容器 div:左画布 .bpmn-canvas + 右属性面板 .bpmn-props;
                                     useImperativeHandle 暴露:getXML()/importXml(xml)/undo()/redo()/canUndo()/canRedo()/zoomFit();
                                     onChange(dirty= commandStack.canUndo())回调
components/bpmn/flowablePropertiesProvider.ts  自定义 provider(plain JS,免 JSX):registerProvider(500);
                                     UserTask→候选组/办理人组;ServiceTask→delegateExpression 组;
                                     entry.component=调用 TextFieldEntry(props) 返回 vnode;useService('modeling')更新;
                                     导出 FlowablePropertiesProviderModule({__init__,flowablePropertiesProvider:['type',Fn]})
components/bpmn/ModelerToolbar.tsx   工具栏(presentational):新建/载入定义(Select 复用 useDefinitions)/撤销/重做/适配/导出XML/部署
components/bpmn/flowableModdle.ts    内联最小 flowable moddle 描述符(namespace http://flowable.org/bpmn;
                                     FlowableUserTask candidateGroups/assignee + FlowableServiceTask delegateExpression;均 isAttr:true)
components/bpmn/bpmnTemplates.ts     最小空白模板 XML + 部署前校验(唯一 executable process + id 非空)工具
```
- `router/routes.tsx`：加 `const DesignerPage = lazy(()=>import('../pages/DesignerPage'))` + `path:'designer'`（包 `AdminRoute`+`Suspense`）。
- `nav.tsx`：加 `adminOnly` 项。

## 5. 状态与边界（逐状态）

| 状态 | 呈现 | 组件 |
|---|---|---|
| 新建空白 | importXML 最小模板；画布即空白骨架 | BpmnModeler |
| 载入中(编辑) | `getDefinitionXml` 拉取期 | PageSkeleton rows=10 |
| 载入失败 | XML 拉取失败/导入解析失败 | ErrorState(onRetry) / BpmnModeler 内 ErrorState「流程图解析失败」 |
| 编辑中/脏 | PageHeader description `<Tag icon color="warning">未保存</Tag>`；干净不显示 | **`dirty = commandStack.canUndo()`**（初次 import 后 canUndo=false→干净，避免误标；评审 M5） |
| 未保存离开 | `modal.confirm`(danger) + `beforeunload` | useBlocker(data router) |
| 导出预览 | `saveXML({format:true})` → `<pre className="mono">` | DetailDrawer（复制按钮见 §8 M7 裁决） |
| 部署 Modal | 去掉 XML 文本框；name 必填(预填 process id/name) | 仿 DefinitionsPanel |
| 部署成功 | `message.success('已部署 key vN')` + **先同步清 dirty 再**(可选)navigate（避免 useBlocker 拦自己，评审 T2） | useDeployDefinition |
| 部署成功但 view=null | `message.warning('部署完成但未解析出流程定义,请检查 XML 是否含可执行 process')`（后端 `toView(null)` 第二道防线，评审 T2） | useDeployDefinition |
| 部署失败 | `message.error(opErrorText(e))`：400→后端 message；403→权限；5xx→中性 | api/errors |
| 校验不通过 | 阻断部署 + `message.warning`(无可执行 process / process id 为空 / 多个 process) | bpmnTemplates 校验 |
| condition 编辑 | vanilla 面板的条件表达式 entry；旁附说明「平台仅注入 `decision` 变量，如 `${decision=='PASS'}`」 | BpmnPropertiesProviderModule |
| 小屏 <992（编辑既有,有 XML） | Alert 引导桌面 + BpmnViewer 只读预览；隐藏 palette/属性/部署 | isMobile 分支 |
| 小屏 <992（新建,无 XML） | **仅 Alert 引导桌面,不挂空 Viewer**（空 xml BpmnViewer 早返回不渲染，评审 T3） | isMobile 分支 |

**边界（必测）**：无可执行 process / process id 空 / 多个 process → 部署阻断；超长 XML → DetailDrawer pre-wrap 不撑破；载入 XML 与 importXml 就绪竞态（query 先 resolve、ref 未就绪）→ 统一 effect+cancelled 卫（评审 M4）；Select 载入定义 / 新建 在 dirty 时先 confirm；StrictMode 双挂载不泄漏；候选组小写 → 提示大写规范（软提示，不阻断）。

## 6. API 契约（零新增后端）

- **部署**：`deployDefinition(name, bpmnXml)` → `POST /api/v1/admin/definitions/deploy` body `{name,bpmnXml}` → `ProcessDefinitionView`。经 `useDeployDefinition`（成功 invalidate `admin-definitions`）。
- **载入**：`getDefinitionXml(key)` → `GET /api/v1/definitions/{key}/xml`（text，含 DI）。新增 react-query key `['definition-xml', key]`（staleTime 5min，同 ProcessTracePage）。
- **定义列表**（工具栏「载入定义」下拉）：`useDefinitions()` → `admin-definitions`。
- 无新增 TS 类型（复用 `ProcessDefinitionView`）。**部署前校验在前端**（bpmnTemplates），后端 400 兜底。

## 7. 响应式与移动端适配（<992 只读降级 + 引导桌面）

沿用 `Grid.useBreakpoint()` → `isMobile=!screens.lg`（992 单断点）。

| 视口 | 设计器页 | 写操作(palette/属性/部署) | 详情 |
|---|---|---|---|
| ≥992 桌面 | 完整 Modeler + 属性抽屉 + 工具栏(按钮 ≥44px 触屏友好) | 常驻可用 | DetailDrawer(right) |
| <992 移动 | `Alert`「建模请在桌面端(≥992)」+ `BpmnViewer` 只读预览(当前/载入的 XML) | 隐藏 | DetailDrawer(bottom 全屏) |

- safe-area 不依赖（无底部固定工具条则不需）；沿用既有全局，不引入 `viewport-fit`。
- 移动端验收：390×844 打开 `/designer` 呈引导+只读态、不白屏、不页面级横向溢出、部署/palette 不可触发。

## 8. 文件级改动清单

**依赖**：`package.json` +`bpmn-js-properties-panel@^5.63.0` +`@bpmn-io/properties-panel@^3.48.0`（Scope C，已装）。
**新增**：`pages/DesignerPage.tsx`、`components/bpmn/{BpmnModeler,ModelerToolbar}.tsx`、`components/bpmn/{flowablePropertiesProvider,flowableModdle,bpmnTemplates}.ts`、`test/renderWithDataRouter.tsx`（**评审 H3 新增**：`createMemoryRouter`+`RouterProvider` 包 ConfigProvider/AntdApp/QueryClientProvider，供含 `useBlocker` 的 DesignerPage 测试用；`renderWithProviders(MemoryRouter)` 仅用于不含 useBlocker 的子组件）、测试见 §10。
**修改**：`router/routes.tsx`（+`/designer` lazy+AdminRoute）、`nav.tsx`（+adminOnly 项）、`components/ops/DefinitionsPanel.tsx`（+「可视化新建」页头按钮 & 行「设计」入口）、`workflow-console/README.md`（设计器说明 + non-goals 更新）、`docs/ROADMAP.md`（4.3 剩余项「可视化设计器」更新状态）。
**M7 裁决（DetailDrawer 复制按钮）**：`DetailDrawer` 现仅接 `content:string` 无 footer/复制槽 → **本轮导出预览不加复制按钮，整块复用 DetailDrawer 不改**（用户可框选 `<pre>` 手动复制）；如需复制，另立独立 Drawer，不占用「整块复用」名额。
**（可选）**：`styles/global.css`（若属性抽屉/工具栏需极少量布局辅助 class；优先用 antd 内联 style 不加全局）。
**后端**：无改动。

## 9. 按依赖排序的实施步骤

1. **基础件（无 UI 依赖）**：`flowableModdle.ts`（moddle 描述符：`{name:'flowable', prefix:'flowable', uri:'http://flowable.org/bpmn', types:[{name:'FlowableUserTask', extends:['bpmn:UserTask'], properties:[{name:'candidateGroups', isAttr:true, type:'String'}, {name:'assignee', isAttr:true, type:'String'}]}]}` — **`isAttr:true` 必须**，否则序列化成子元素 Flowable 不认，评审 M2）+ `bpmnTemplates.ts`（最小模板 XML，含 `<process id="newProcess" isExecutable="true">`+1 StartEvent+DI；`validateForDeploy(xml)`：唯一 executable process + id 非空，返回 {ok,reason,processId,processName}）。附单测（含导出后断言含 `flowable:candidateGroups=` 属性形态）。
2. **自定义 provider**：`flowablePropertiesProvider.ts`（`registerProvider(500,this)`；`getGroups(element)`→push 组：`is(el,'bpmn:UserTask')`→候选组/办理人；`is(el,'bpmn:ServiceTask')`→delegateExpression；entry `component` 为 plain 函数返回 `TextFieldEntry({id,element,label,getValue,setValue,debounce:useService('debounceInput')})`，`setValue`=`useService('modeling').updateProperties(el,{'flowable:candidateGroups':v||undefined})`；`isEdited:isTextFieldEntryEdited`；导出 `FlowablePropertiesProviderModule`）。
3. **编辑器组件**：`BpmnModeler.tsx`（复刻 BpmnViewer 的 useRef+cancelled+幂等 destroy；`new Modeler({container, propertiesPanel:{parent}, moddleExtensions:{flowable}, additionalModules:[BpmnPropertiesPanelModule,BpmnPropertiesProviderModule,FlowablePropertiesProviderModule], keyboard:{bindTo:document}})`；import 4 CSS（diagram-js/bpmn-js/bpmn-embedded/properties-panel）；左画布+右属性两容器；`useImperativeHandle` 暴露命令式面（见 §4）；`commandStack.changed`→onChange(`dirty=canUndo()`，评审 M5)）。
4. **工具栏**：`ModelerToolbar.tsx`（撤销/重做灰置；载入定义 Select(useDefinitions)，dirty 时先 confirm；导出 XML→DetailDrawer；部署→Modal(name 预填+必填)→validateForDeploy→useDeployDefinition）。
5. **容器页**：`DesignerPage.tsx`（读 ?key；编辑用 useQuery 拉 XML；**统一 `effect([xml, modelerReady, key])` 触发 importXml + cancelled 卫**，未就绪则等待、key 变更重导，防「query 先 resolve、ref 未就绪丢 import」或旧 key XML 覆盖新图，评审 M4；持 dirty/name/selected；useBlocker+beforeunload；isMobile 降级：有 XML→BpmnViewer 只读，无 XML(新建)→仅 Alert；组装工具栏+编辑器+属性抽屉）。
6. **接线**：`routes.tsx` 加 `/designer` lazy+AdminRoute；`nav.tsx` 加项；`DefinitionsPanel` 加入口按钮。
7. **移动端 + 诚实文案**：isMobile 分支只读降级；核对所有 message 文案（已部署 key vN，无「已完成/已生效」）。
8. **测试**：Vitest（mock Modeler）+ Playwright 冒烟（§10）。
9. **交付**：`pnpm build`+`pnpm test`+`pnpm e2e` 全绿；README/ROADMAP 更新。

## 10. 测试策略（含移动端视口矩阵）

**测试基建两处硬前提（评审 H2/H3，写进测试正文而非脚注）**：
- **H2 · 桌面态必须覆盖 matchMedia**：`test/setup.ts` 的 matchMedia 桩恒 `matches:false` → `isMobile` 恒真 → DesignerPage 默认走**只读移动分支**（隐藏工具栏/部署/属性抽屉）。故凡测部署/属性/工具栏/`BpmnModeler.importXml 被调` 的桌面用例，**用例内先覆盖** `window.matchMedia`（令含 `992`/`min-width` 的 query 返回 `matches:true`）或 `vi.mock` `Grid.useBreakpoint`→`{lg:true,md:true,...}`，使 `isMobile=false`。移动降级用例用默认桩。
- **H3 · DesignerPage 必须用 data router 渲染**：`useBlocker` 在非 data router 抛 invariant，`renderWithProviders`(MemoryRouter) 一挂载即崩。DesignerPage 全部用例走**新增 `renderWithDataRouter`**（createMemoryRouter+RouterProvider）；ModelerToolbar/PropertyDrawer 不含 useBlocker，独立用 renderWithProviders 测。

**Vitest + RTL（mock 掉 `bpmn-js/lib/Modeler`）**：假 Modeler 暴露 `importXML`(resolve)/`saveXML`→{xml}/`createDiagram`/`get('commandStack'|'modeling'|'selection'|'moddle'|'canvas')`/`on/off`/`destroy`。
- 门控：复用 `AdminRoute.test` 的 `vi.hoisted` cfg + `useAuthStore.setState`（非 ADMIN→「无访问权限」；ADMIN/dev 放行）。
- 状态机（**桌面态,先覆盖 matchMedia**）：新建 importXML 模板；编辑 `vi.mock('../../api/process')` 喂 XML → **`BpmnModeler.importXml` 被调**（评审 T1：移动态喂的是 BpmnViewer 非 BpmnModeler，故须先置桌面）；改动 → dirty(canUndo) 置位 → PageHeader 显「未保存」。
- 校验：`validateForDeploy` 纯函数单测（无 process / 多 process / id 空 / 正常）。
- 自定义 provider 单测：`flowablePropertiesProvider` 的 `getGroups(userTask)` 含候选组组、`getGroups(serviceTask)` 含 delegateExpression 组、`getGroups(startEvent)` 不含 Flowable 组；entry 的 `getValue/setValue` 对 mock element/modeling 正确读写 `flowable:candidateGroups`（属性面板 preact 渲染在 jsdom 难测，故只测 provider 逻辑，不渲染面板）。moddle 序列化在 §9 step1 的 bpmnTemplates 单测里断言（导出含 `flowable:candidateGroups=` 属性形态；conditionExpression 断言用 `contains('conditionExpression')` 非精确 `xsi:type` 串，评审 T4）。
- 部署流（桌面态）：mock `saveXML` 返回带 `<process id="p">` 的 XML → 点部署 → `deployDefinition(name, xml)` 被调（`vi.mock('../../api/admin')`）→ toast「已部署 p vN」→ invalidate；返回 null → warning（评审 T2）。
- 错误映射：`mockRejectedValue({response:{status:400,data:{message:'BPMN 部署失败: …'}}})`→断言含「BPMN 部署失败」；403→权限；5xx→中性。
- **诚实守卫**：`expect(document.body.textContent).not.toContain('已完成')` 且 `.not.toContain('已生效')`。
- Dirty 拦截：DesignerPage 走 renderWithDataRouter 测「编辑后导航被拦/确认后放行」；beforeunload 处理器注册/注销单测。

**Playwright 冒烟**（`e2e/designer.smoke.spec.ts`，dev 无鉴权）：`goto('/designer')`→标题可见→画布 `.bpmn-canvas svg` 或 palette DOM 可见（timeout 15s）→点「导出 XML」→断言预览 XML 非空（渲染进可断言节点）→**不点部署**（无 delete 端点，避免留痕）→诚实守卫 `getByText('已完成').toHaveCount(0)`。

**移动端视口矩阵**：390×844（引导+只读降级、不溢出、写操作不可触发）/ 768（若桌面则属性抽屉不挤画布）/ 992 临界 / 1440（完整建模基准）。注：`setup.ts` matchMedia 桩恒 false → Vitest 只能测「未命中断点(=isMobile 真)」态，真实断点切换靠 Playwright `setViewportSize`。

## 11. 验收标准

- `pnpm build`（tsc+vite）通过；`pnpm test` 全绿；`pnpm e2e` 冒烟通过。
- 门控：非 ADMIN 深链 `/designer` 得 403；dev 可达。
- 新建：空白模板可拖拽出 start→userTask→gateway→end + 连线；双击改 name；属性抽屉可设 userTask candidateGroups（大写）与网关分支 conditionExpression；导出 XML 含 `flowable:candidateGroups` 与 `conditionExpression`。
- 编辑既有：`/designer?key=hisRxReview` 载入渲染既有图；部署产出 v(N+1)。
- 部署：前端校验拦住「无可执行 process」；成功 toast「已部署 key vN」**不含「已完成/已生效」**；400 显示后端 message。
- 未保存离开被 `modal.confirm` 拦截。
- **移动端（≥1 项）**：390×844 下 `/designer` 呈引导桌面 + 只读预览、不白屏、不横向溢出、写操作不可触发。
- 诚实：全程无「已完成/已生效」误导；异步无（部署是同步返回定义视图）。

## 12. 风险与回滚

| 风险 | 缓解 |
|---|---|
| StrictMode 双挂载致 Modeler 竞态/泄漏 | 复刻 BpmnViewer 的 useRef+cancelled+幂等 destroy |
| **误以为设计器产物能从 console 独立跑通**(评审 H1:无发起入口/仅审方完成端点/condition 仅 decision 变量) | 定位诚实界定为「可视化编辑+部署工具」(见 §Goals 顶部框);non-goals 明列「发起/驱动归消费方+服务端」;condition 编辑约束为 decision 预设;文案不承诺「可运行」 |
| 裸 BPMN 缺 candidateGroups → userTask 无人可办 | Scope B 补候选组;带 outbox/ACK 流程(delegate/message)走克隆 his-rx-review XML 路径 |
| 静默失败(无 executable process → toast undefined) | 部署前 `validateForDeploy` 强校验阻断 |
| Modeler chunk 体积拖慢首屏 | 独立路由 React.lazy + manualChunks.bpmn；绝不被 TasksPage/AppLayout 静态引用 |
| flowable 扩展属性序列化 | 内联 moddle 描述符注册 `flowable` 命名空间;导出后断言 XML 含该属性 |
| useBlocker 仅 data router 生效(renderWithProviders 用 MemoryRouter) | 路由级拦截用 createMemoryRouter 专测 + e2e;逻辑态用组件单测;beforeunload 兜刷新 |
| 载入源 `/definitions/{key}/xml` 非 ADMIN 门控 | 本轮接受(与只读轨迹页同源);如需一致门控留后续 admin 版接口(待澄清 4) |
| 无效 BPMN 400 直吐 Flowable 冗长串 | 测试断言「含 BPMN 部署失败」而非精确串;如需友好化在 opErrorText 加 400 分支(实现选择) |
| 移动端触屏误入建模 | <992 隐藏写操作 + 只读降级 + 引导 |
- **回滚**：纯增量（新路由/组件 + DefinitionsPanel 增量按钮）。移除 `/designer` 路由 + nav 项 + 组件即回滚，粘贴-XML 部署仍可用；**无新 npm 依赖**故无卸包成本。

## 13. 待澄清（随 AskUserQuestion 批准）

见 DECISION_RECORD「待用户拍板」：① D2 属性编辑深度(Scope B 推荐/A 降级/C 升级)；② D1 放置形态(独立路由)；③ D3 编辑语义(载入最新版→新版本，不做删除/回滚)；④ 载入源门控(沿用非 ADMIN 的 `/definitions/{key}/xml` 是否可接受)。
