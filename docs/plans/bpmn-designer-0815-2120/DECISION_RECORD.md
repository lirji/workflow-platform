# 可视化流程设计器（BPMN Modeler）决策记录

> 日期 2026-08-15。frontend-plan 工作流产物（6 个只读子代理并行调研综合）。
> 配套实施计划见同目录 `FINAL_PLAN.md`。**本记录仅记录决策，未改任何代码。**

## 背景

平台侧路线图已全部落地。运维面板「流程定义」Tab 现有 **Option-B-lite**：粘贴 BPMN XML 部署（`DefinitionsPanel.tsx` → `useDeployDefinition` → `POST /api/v1/admin/definitions/deploy {name,bpmnXml}`）。另有只读轨迹图（`BpmnViewer.tsx`，`NavigatedViewer`）。本轮目标：让 ADMIN 能**可视化拖拽建模 → 预览 XML → 直接部署**，复用既有后端与组件，不引新抽象。

## 关键事实（决定方案的硬约束）

1. **后端零改动即可用**：`deployDefinition(name,bpmnXml)` 同步返回 `ProcessDefinitionView{id,key,name,version,tenantId,suspended,deploymentId}`；key/version 由 Flowable 从 XML 的 `<process id=...>` 派生，`name` 只是部署包名。载入既有定义用 `getDefinitionXml(key)`（返回最新版含 DI 的原始 XML）。
2. **bpmn-js@17.11.1 已装，Modeler 随包提供**（`bpmn-js/lib/Modeler`），内置 palette/context-pad/双击改名/命令栈/键盘，**零额外 npm 依赖即可拖拽建模**。编辑态须额外 `import 'bpmn-js/dist/assets/bpmn-js.css'`（Viewer 未引这份）。API 经 context7 官方文档核实：`createDiagram()`/`importXML(xml)`/`saveXML({format:true})`/`get('commandStack')`/`get('modeling').updateProperties()`。
3. **裸 BPMN 跑不通中台可靠消息链路**（Requirements 调研关键发现）：真实可运行的流程需要 `flowable:candidateGroups`（否则候选组待办查不到、无人可办）、outbox 的 `flowable:delegateExpression`（依赖服务端已注册 Spring bean）、ACK 的顶层 `<message>` + `messageEventDefinition`。纯拖拽画布不会自动生成这些。→ **设计器的真实价值域取决于「能编辑多深的属性」。**
3a. **设计器产物无法从 console 发起或驱动实例**（独立评审核对后端确认，H1）：① 全仓无 start-process-instance 端点/UI，实例只由 Kafka `StartProcessCommandV1`（his-outpatient 发）经 `ProcessApplicationService.start` 发起 → 设计器新建的 key 不会自动获得实例；② 任务完成只有审方专用 `POST /tasks/{id}/complete-review`（硬编码 `decision∈{PASS,REJECT}` + 注入 `decision/opinion/actor*` 变量），无通用「完成任务(带任意变量)」端点 → 网关 `conditionExpression` **实际只有 `decision` 变量会被真正赋值**（写 `${amount>100}` 之类永远走不通）；③ `start` 把 link 初始 phase 硬编码 `WAITING_USER`（假设起步即人工节点）。→ **设计器的定位必须诚实界定为「可视化 BPMN 编辑 + 部署工具」（粘贴 XML 的严格升级），而非「能独立跑起来的流程生成器」；实例的发起/驱动是消费方 repo（发 StartProcessCommand）+ 服务端 delegate bean 的职责。**
4. **flowable 扩展属性 vs 标准属性**：`flowable:candidateGroups`/`flowable:assignee` 属 `http://flowable.org/bpmn` 命名空间，序列化需注册 moddle 描述符（可**内联**一个最小 JSON 描述符，零 npm 依赖）；`conditionExpression`（网关分支条件）是**标准 BPMN**，bpmn-js 原生支持。
5. **静默失败陷阱**：若 XML 无可执行 `<process isExecutable="true">`，Flowable 建了 deployment 但无 ProcessDefinition，后端 `toView(null)` 返回 null → 前端 toast 会显示「已部署 undefined vundefined」却不报错。→ 需前端部署前校验。
6. **无效 BPMN → HTTP 400**：后端 `IllegalArgumentException("BPMN 部署失败:…")` 被 `WorkflowExceptionHandler` 映射为 400 + `{error,message}`；前端 `opErrorText` 对 400 原样读 `message`。
7. **StrictMode 已开**（`main.tsx`）→ effect 双挂载/双 destroy 真实存在，须复刻 `BpmnViewer` 的 `useRef + cancelled` 幂等范式。
8. **jsdom 不能真渲染 bpmn-js**（缺 `getBBox`/`getScreenCTM` 等 SVG 量测桩）→ Vitest 必须 mock Modeler；真实渲染只能靠 Playwright（`tasks.smoke` 已证明）。
9. **无路径别名**（tsconfig 无 paths）→ 一律相对导入。页面/面板/Viewer=default export，工具/hook/小组件=named export。
10. **移动端建模已是既定 non-goal**（README / ops FINAL_PLAN / ROADMAP 三处先例）。

---

## 决策与备选对比

### D1 · 设计器放置形态 —— **推荐：独立懒加载路由 `/designer`**

| 方案 | 优点 | 缺点 | 裁决 |
|---|---|---|---|
| **A. 独立路由 `/designer`（推荐）** | 全屏画布空间足；独立 lazy chunk，Modeler 大包不进 OpsPage/待办首屏；`?key=` 深链「编辑某定义」；`useBlocker`/`beforeunload` 未保存拦截自然 | 需加一条路由 + 一个 nav 项 | ✅ 采纳 |
| B. OpsPage 新增 Tab | 复用 `?tab=` 深链范式 | Tabs 容器内边距挤压全屏建模；Modeler 逻辑并入 OpsPage；tab 切换频繁 mount/destroy 重实例 | ❌ |
| C. DefinitionsPanel 弹全屏 Modal | 改动最小、上下文最近 | Modal 内嵌大画布布局受限；路由深链/未保存拦截别扭 | ❌（可作过渡形态，不作主方案） |

**理由**：Modeler 产物 chunk 显著大于 Viewer 的 188KB，独立路由 = 干净的 chunk 边界 + 全屏建模体验。入口双通道：`系统运维` 分组加 `adminOnly` nav 项「流程设计器」；`DefinitionsPanel` 行内加「设计」按钮 → `/designer?key={key}`（编辑该定义最新版），页头加「新建流程」按钮 → `/designer`（空白）。

### D2 · 属性编辑深度 —— **推荐：Scope B（内置拓扑 + 内联 moddle 的极简属性抽屉，零新依赖）**

> **诚实界定（据 H1 修订）**：设计器是**可视化 BPMN 编辑 + 部署工具**（粘贴 XML 的严格升级 + 可视化编辑既有定义如 hisRxReview），**不是**「能独立跑起来的流程生成器」。产物是**结构上可部署、userTask 可被候选组查到、exclusiveGateway 可基于 `decision` 路由**的定义；能否真正跑实例取决于消费方 repo 发 `StartProcessCommand` + 服务端 delegate bean，超出 console 职责。

| 方案 | 能编辑 | 依赖成本 | 产物价值 | 裁决 |
|---|---|---|---|---|
| Scope A（拓扑骨架） | 元素 `name`（双击）、`id`、连线拓扑 | 零 | 只能画结构，userTask 缺 candidateGroups → 即便被发起也无人可办 | ❌ 太弱 |
| **Scope B（推荐）** | A 全部 + userTask `flowable:candidateGroups`/`assignee` + sequenceFlow `conditionExpression`（变量约束为 `decision`，给预设/提示）+ process `id/name/isExecutable` | **零新 npm 依赖**（内联最小 flowable moddle 描述符 + antd 自建属性抽屉，candidateGroups/assignee 走 `modeling.updateProperties`，condition 用 `moddle.create('bpmn:FormalExpression')`） | **可视化编辑出与 his-rx-review 同构的候选组人工任务 + decision 网关的定义，并可视化编辑既有已部署定义**；补齐让 userTask 可办、网关可路由的最小属性集 | ✅ 采纳 |
| Scope C（完整属性面板） | 全量 Flowable 执行属性（表单/监听器/serviceTask 等） | **新增** `bpmn-js-properties-panel` + `@bpmn-io/properties-panel`（含 preact 运行时）+ CSS + moddle 扩展；默认 provider 面向 **Camunda** 语义与 Flowable 不完全对味，数百 KB | 最强但体积/维护/语义错配成本高，且 serviceTask delegate bean 仍需服务端预建 | ❌ 本轮 non-goal，留后续增强 |

**理由**：用户目标明确含「编辑属性」，而调研证明**不编辑 candidateGroups 的人工任务即便被发起也无人可办**。Scope B 用内联 moddle 描述符（一个 JSON 对象传给 `moddleExtensions`，非 npm 依赖）+ antd 侧抽屉，精准补齐候选组与 decision 网关条件，让设计器能**可视化产出/编辑平台真正在跑的那类定义**（his-rx-review 同构），同时守住「零新依赖」。condition 编辑把可用变量约束为 `decision`（下拉/提示），不放任自由文本以免给出「能跑」的假承诺。`BpmnModeler` 的 props 预留 `enablePropertiesPanel` 开关，日后确有诉求再平滑升级到 Scope C。

### D3 · 编辑既有定义的语义 —— **推荐：载入最新版 → 部署为新版本（Flowable 版本自增）**

- 「设计」某定义 = `getDefinitionXml(key)` 拉最新版 XML → `importXML` 载入编辑 → 部署 = **同 process id 产出 v(N+1)**，旧版本仍在（Flowable 语义，非原地覆盖）。UI 文案明示「保存 = 发布新版本」。
- 不做版本选择器 / 回滚 / 删除（后端无 delete 端点）。载入源复用非 ADMIN 门控的 `/definitions/{key}/xml`（与只读轨迹页同源）——本轮接受，见 FINAL_PLAN 风险表。

### D4 · 部署前校验 —— **推荐：前端强校验，阻断静默失败**

部署前对 `saveXML` 产物解析：**有且仅有一个 `<process isExecutable="true">` 且 `id` 非空**，否则阻断并提示（避免后端返回 null → toast「已部署 undefined」）。可选提示 candidateGroups 大写规范（onboarding 约定：大写无前缀）。

### D5 · 移动端策略 —— **推荐：<992 只读降级 + 引导桌面（non-goal，与三处先例一致）**

小屏渲染「引导文案 Alert + 复用 `BpmnViewer` 只读预览」，隐藏 palette/属性抽屉/部署按钮。依据：拖拽建模指针密集、触屏不可行；README/ops-plan/ROADMAP 均已定移动端建模为 non-goal。验收：390×844 不白屏、不页面级横向溢出、写操作不可触发。

### D6 · e2e 是否真部署 —— **推荐：冒烟只验「能画 + 能导出」，不真部署**

后端**无 delete 端点**，真部署会永久留痕（只能 suspend）污染 `listDefinitions`。部署语义由 Vitest（mock）覆盖，与既有 `tasks.smoke`「不消耗真实数据」策略同构。

### D7 · 新建初始图 —— **推荐：最小 XML 模板 importXML（而非裸 `createDiagram()`）**

用内联最小模板（可控 `process id/name/isExecutable=true` + 一个 StartEvent + DI），比 `createDiagram()`（id 随机、语义不完全可控）更贴合部署契约。

---

## 待用户拍板（随 FINAL_PLAN 批准时）

1. **D2 属性编辑深度 + 定位**：确认设计器定位为「可视化 BPMN 编辑+部署工具」（非独立可运行流程生成器，实例发起/驱动仍归消费方+服务端），且属性深度取 Scope B（candidateGroups+decision 网关，零新依赖）？还是降级 A（纯拓扑骨架）/ 升级 C（完整属性面板，接受新依赖）？— 这是核心分水岭。
2. **D1 放置形态**：确认独立路由 `/designer` + nav 项 + DefinitionsPanel 入口？
3. **D3 编辑语义**：确认「载入最新版→部署新版本」，不做删除/回滚？
4. **载入源门控**：`/definitions/{key}/xml` 非 ADMIN 门控，设计器载入沿用是否可接受（否则需新增 admin 版载入接口）？
