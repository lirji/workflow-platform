# workflow-console 决策记录

> 来源:frontend-plan 6 方向只读子代理(需求/UI·UX/架构/可复用组件/移动端/测试风险)综合。
> 参考:auth-console(`/Users/liruijun/personal/LLM/auth-platform/auth-console`)、后端 workflow-platform 现状、FINAL_PLAN(`../workflow-platform-0815-0959/FINAL_PLAN.md`)。

## D0. 最关键前提:后端现状约束了本轮能做什么(所有子代理一致结论)
- 后端**目前只有 1 个 Controller、2 个端点**:`GET /api/v1/tasks?definitionKey=&businessKey=`(头 `X-Workflow-Tenant`,**无 candidate/state/分页**)、`POST /api/v1/tasks/{taskId}/complete-review`(返回 **202 `{actionId,status:PENDING_BUSINESS}`**)。
- **页面②(流程轨迹)、③(bpmn 设计/部署)所需 REST 全部未实现**(process-instances/timeline/definition/deploy 仅在 FINAL_PLAN "拟定")。
- **没有接口能查"业务是否已落地"**(phase 只在 `wf_process_link` 表,未经 REST 暴露)。
- ⚠️ 端点命名与 FINAL_PLAN §9 不一致:实现是 `complete-review`(计划写 `complete`),且无 `idempotencyKey`。**前端对齐实现,不对齐计划。**

## D1. 工程形态 —— 独立新前端,克隆 auth-console 约定(复制,不抽共享包)
| 备选 | 优 | 劣 | 裁决 |
|---|---|---|---|
| A. 独立新前端,复制 auth-console 模板 | 快、与既有一致、零新基建 | 两份 AppLayout/theme 可能漂移 | **选** |
| B. 抽 monorepo 共享 UI/鉴权包 | 不漂移 | 仓库无 pnpm workspace,改造大;当前仅 2 个 console 不值 | 否(记为后续可选) |

**裁决**:独立新前端 `/Users/liruijun/personal/LLM/workflow-platform/workflow-console`,整块复用 auth-console 的 `main.tsx` provider 栈 / `auth/*` / `api/client.ts` / `config` / `store/authStore` / `components/layout` / `AsyncState` / `PageHeader` / `theme` / `global.css` / `nav`。差异集中在少数文件(proxy、租户头注入、Casdoor client_id、组名)。

## D2. 范围与架构 —— Option A(轻量)+ C 骨架,B 留增量(核心决策)
| 方案 | 范围 | 后端依赖 | 体积/风险 | 裁决 |
|---|---|---|---|---|
| **A 轻量** | 待办中心(查+办理)+ 只读轨迹(NavigatedViewer) | 待办中心当前后端**可跑**(需补候选人过滤);轨迹需补 timeline | 只读 Viewer 小、风险低、覆盖运维 80% | **选(本轮)** |
| B 完整 | A + bpmn Modeler 建模 + 部署 + 定义/实例管理 | **阻塞**:多个 REST + admin 部署端点未实现 | Modeler+properties-panel+flowable moddle 重、编辑器状态机复杂、风险高 | 否(留下一轮增量) |
| C 同壳读写分层 | A 落地,Modeler 藏 feature-flag+admin 门控懒路由后,抽 `BpmnCanvas` + Viewer/Modeler 适配器 | 同 A | 让 B 变纯增量、不返工 | **采其骨架** |

**裁决**:本轮做 **Option A**,但按 **C 的骨架**组织代码(`BpmnCanvas` 抽象、懒路由、`useTaskListSync` hook),使未来 B(Modeler/部署)成为纯增量。

## D3. 待办刷新 —— react-query 轮询(无 SSE)
后端无 SSE(全仓确认)。**裁决**:手动刷新按钮(照 AuditPage)+ 办理成功 `invalidateQueries` + `refetchInterval` 轮询(10–15s,`visibilityState` 后台暂停);办理后"爆发轮询"(~2s×~30s 追一致性转移再退避)。全部封进 `useTaskListSync`,将来后端上 SSE/WS **只改 hook 内部**。

## D4. 办理"202 最终一致"呈现 —— 不伪装已完成,不做删除式乐观更新
后端明确"不伪装已落地"。**裁决**:办理成功显示 **"已受理 / 处理中(PENDING_BUSINESS)"**(warning 色 Tag + Tooltip),绝不显示"已完成";toast 用 `message.info`,回显 actionId;沿用 auth-console 的 `invalidateQueries` 重拉(而非 `setQueryData` 删行)——因 Flowable complete 后该 task 会消失,但业务未 ACK/REJECT 重提会回环出新 task,乐观删行会撒谎且与重拉打架。按钮办理后 loading/禁用防重复(409 兜底要优雅提示)。

## D5. bpmn-js 集成 —— 本轮只 NavigatedViewer,懒加载,useRef 持实例
**裁决**:本轮只引 `bpmn-js` 的 **NavigatedViewer**(只读,体积远小于 Modeler);**路由级 `React.lazy` + 独立 manualChunk**,不拖累待办主路径首屏;bpmn 自带 CSS 放懒块内;实例用 `useRef`(**绝不进 React state**),mount effect new 一次、`[xml]` effect importXML、cleanup destroy;容器给显式高度 + `ResizeObserver` 重 `fit-viewport`。Modeler/properties-panel/flowable moddle 留 Option B。

## D6. 移动端 —— 纯桌面为主,移动只读降级;设计器移动端为 non-goal
**裁决**:沿用 auth-console 断点(antd 默认 + `isMobile=!screens.lg`/992)。内部中后台系统,桌面一等公民;移动端仅保证**只读可达**(待办列表读、轨迹看)。**bpmn 建模在触屏/窄屏不可用 → 写进 non-goals**。待办列表小屏由横向滚动升级为**卡片式堆叠**(相对 auth-console 只横滚是新增,因待办要在手机可扫读)。办理抽屉小屏全屏化(是否支持移动端办理见待澄清)。

## D7. 测试 —— 引入 Vitest + Testing Library + 1 条 Playwright 冒烟
auth-console 零测试(仅 tsc)。但本项目核心 bug(202 建模、bpmn 交互)恰是类型检查抓不到的。**裁决**:workflow-console 引入 Vitest + @testing-library/react(组件/hook)+ 1 条 Playwright 冒烟(查待办→办理→断言"不显示已完成"+重拉)。契约以 protocol record 为唯一真值。(相对既有仓库惯例的**有意升级**,标为需你确认。)

## D8. 鉴权分期 —— dev 先无鉴权跑通,再叠 Casdoor;tenant/actor 收敛单点
**裁决**:Stage 1 dev vite proxy 直连 :8300、`X-Workflow-Tenant: his` 头 + actor 由前端从 OIDC token 填(shadow 语义);**tenant/actor 注入统一在 axios 拦截器单点**,Phase 3 后端改 JWT 派生时只删单点。Stage 2 叠 auth-console 的 Casdoor OIDC 模板(新 client_id + 组名 PHARMACIST/ADMIN 做菜单/路由门控)。

## 未决(转 FINAL_PLAN 待澄清 / AskUserQuestion)
1. 本轮是否**只做前端 + 现有后端**(页面①真跑,轨迹/落地状态受限),还是**允许我顺带补少量后端只读端点**(候选人过滤 + process-instance/timeline)让轨迹/落地状态也诚实可用?——这是范围最大分叉。
2. 移动端到底做到哪档:纯桌面(A)/ 只读可达(B,推荐)/ 可移动办理(C)。
3. 是否接受引入前端测试栈(D7)。
4. bpmn 本轮只读查看器确认(设计器留下一轮)。
