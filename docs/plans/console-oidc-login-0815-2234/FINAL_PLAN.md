# 流程审批中台 Casdoor OIDC 接入 + 登录页 实施计划

> 状态:**待批准**。决策依据见同目录 `DECISION_RECORD.md`。frontend-plan 工作流产物。
> 复用既有 OIDC 基建(oidcConfig/AppAuthProvider/AuthBridge/authStore/CallbackPage/api client),不引新依赖/新抽象。

## 1. Goals / Non-goals

### Goals(Phase 1,本轮,可安全合入)
- 新增**自建品牌登录页** `pages/LoginPage.tsx`(公开路由 `/login`):左品牌渐变栏 + 右 SSO 认证卡,"使用统一身份登录"按钮 → `signinRedirect({state:{returnTo}})`。与 his-web 登录范式一致,落到 workflow token。
- `ProtectedRoute` 未登录由"自动 signinRedirect"改为 `<Navigate to="/login" state={{from}}>`,登录页承载品牌/loading/error/SSO 入口。
- 复用既有:oidcConfig / AppAuthProvider / AuthBridge / authStore / CallbackPage(returnTo)/ api client(Bearer+401)/ AppLayout 登出。**零后端改动、零新依赖**。
- 分期开关不变:`VITE_AUTH_ENABLED` **默认仍 false**(dev/shadow 不受影响,直连 :8300 免登录);置 true 时走完整 SSO。
- 配套 Vitest(建立 `react-oidc-context` useAuth mock 范式)+ 保持现有 e2e 在 authEnabled=false 绿。
- 产出 **Phase 2 启用 Runbook**(Casdoor 注册 + env + 后端分期),写进文档。

### Non-goals(附依据)
- **账号密码登录 / 本地账号库 / 注册找回**:后端纯 Resource Server 只认 Casdoor JWT,无账密端点(DR/D2、事实4)。
- **后端强制 JWT 上线 / 打开 shadow 的 security.enabled**:会打断跑着的 shadow :8300(R1),留 Phase 2 单独环境/迁移后。
- **多租户/组织选择框**(risk-console 那种):单租户 `his` 固定,经 `X-Workflow-Tenant` 头。
- **暗色主题**:workflow 仅浅色单主题;登录页只做浅色。
- **Casdoor 应用注册本身**:需 Casdoor 管理操作 + client_id,属 Phase 2 前置(见 §Runbook)。
- refresh_token(offline_access)续期:维持现状 `automaticSilentRenew` + 401 兜底;如需更稳留后续。

## 2. 视觉方向与设计参考(照 his-web 范式,落到 workflow token)

**结论:以 his-web `DualLogin` 两栏骨架 + `SsoLogin` 纯 SSO 认证区为范式,全部映射到 workflow-console 既有 token**(一致性 > 追新):

- 根:`minHeight:100vh; display:flex; background: colors.bgLayout(#F5F7FA)`。
- **左品牌区**(`flex:1`,class `wf-login-brand`,<992 隐藏):`linear-gradient(135deg,#315EFB 0%,#1E3A8A 100%)`(D4 待确认),白字,`padding:64px 56px`,竖排 gap24。含:56×56 圆角16 `rgba(255,255,255,.18)` 徽章内嵌 `DeploymentUnitOutlined`(与 AppLayout 品牌一致)+ 品牌名"流程审批中台"(fontSize30/800)+ `Typography.Title level2` 白色标语 + `Paragraph rgba(255,255,255,.85)` 副标 + 3 条特性(`CheckCircleOutlined/ApartmentOutlined/SafetyCertificateOutlined`,文案"待办审批 · 流程编排(BPMN)· 运维治理")。
- **右认证区**(`width:460;max-width:100%`,居中,`padding:32`),内层 `maxWidth:360`:`Title level3`"欢迎登录"(colorText #172033)+ `Text type="secondary"`(#667085)"使用统一身份认证(SSO)登录" + **`Button type="primary" block size="large" icon={<LoginOutlined/>}`**(高 40/圆角 8)"使用统一身份登录" + `Divider` + `Text type="secondary" fontSize12`(#98A2B3)"OIDC 授权码 + PKCE · Casdoor 单点登录"。
- 错误:`Alert type="error" showIcon role="alert"`(colorError #D92D20)显示 `auth.error.message`,主按钮变"重试登录"。
- **Stage1(authEnabled=false)**:不展示 SSO(无 IdP),改显示"开发模式 · 免登录"提示 + "进入控制台"按钮 → `navigate('/tasks')`(呼应 AppLayout 的"开发模式·未鉴权"橙标);或直接 `useEffect` 跳走(见 §5)。
- 圆角卡 `borderRadiusLG=12`、控件 `borderRadius=8`;装饰元素 `aria-hidden`;对比度达 WCAG AA。

## 3. 路由与页面流

```
[公开] /callback   —— OIDC 回调(现有,零改)
[公开] /login      —— 新增品牌登录页(eager import,不 lazy)
[ProtectedRoute > AppLayout] /tasks /process /ops /designer …(现有)
```
- 用户流(authEnabled=true):未登录访问 `/ops` → ProtectedRoute `<Navigate to="/login" state={{from:'/ops'}}>` → 登录页点"使用统一身份登录" → `signinRedirect({state:{returnTo:from}})` → Casdoor → `/callback` 换 token → CallbackPage `navigate(returnTo)` → 落 `/ops`。
- 已登录访问 `/login` → `useEffect` `navigate(returnTo ?? '/tasks', {replace:true})`(不停留)。
- 登出:AppLayout 下拉 → `signoutRedirect()` → Casdoor 结束会话 → `post_logout_redirect_uri`(见 §8 可选改 /login)。
- 403(已登录非 PHARMACIST/ADMIN):沿用 ProtectedRoute 现有 403 Result,不在登录页重复。

## 4. 组件树(复用 vs 新建)

**整块复用(零改)**:`auth/{oidcConfig,AppAuthProvider,AuthBridge}`、`store/authStore`、`pages/CallbackPage`、`api/client`、`components/layout/AppLayout`(登出)、`theme/{colors,theme}`、`components/common/AsyncState`。
**新建**:
```
pages/LoginPage.tsx        品牌登录页(default export,eager):useAuth()+config;两栏;SSO 按钮 signinRedirect;
                           已登录/dev useEffect 跳走;loading/error 状态
pages/LoginPage.test.tsx   Vitest(mock react-oidc-context useAuth + vi.mock('../config'))
```
**修改**:
```
auth/ProtectedRoute.tsx    未登录分支:删 useEffect 自动 signinRedirect,改 <Navigate to="/login" state={{from}}>;
                           保留 authEnabled=false 放行 + isLoading Spin + 403
router/routes.tsx          +公开路由 { path:'/login', element:<LoginPage/> }(eager,与 /callback 并列)
styles/global.css          +.wf-login-brand 渐变/布局 + @media(max-width:992){display:none} 塌单列(或用 Grid.useBreakpoint 内联)
(可选) auth/oidcConfig.ts   post_logout_redirect_uri: origin+'/login'(登出直落登录页;否则落 / 再被守卫弹到 /login,多一跳)
.env.example / README      Phase 2 启用说明(client_id/authority/authEnabled)
```
无需改:authStore/AuthBridge/AppAuthProvider/CallbackPage/AppLayout/api client。

## 5. 状态与边界(登录页逐状态)

| 状态 | 呈现 |
|---|---|
| 初始(authEnabled=true,未登录) | 品牌 + SSO 按钮,可点/可 Tab 聚焦 |
| 提交中/跳转中 | `auth.isLoading || auth.activeNavigator` → 按钮 loading,文案"正在跳转登录…",禁重复点 |
| 登录失败 | `auth.error` → `Alert error role=alert` + 按钮"重试登录" |
| 已登录访问 /login | `useEffect` → `navigate(returnTo ?? '/tasks',{replace:true})` |
| Stage1(authEnabled=false) | 显示"开发模式 · 免登录"卡 + "进入控制台"→ navigate('/tasks');或直接跳走 |
| 回调中 | 走现有 CallbackPage(Spin"登录中…"),不在本页 |
| 无权限(403) | 走 ProtectedRoute 现有 403 Result,不在本页 |

**边界**:returnTo 经 `location.state.from` → signinRedirect state → CallbackPage 消费(沿用现有机制,不新增 query);未登录直接手输 /login 亦可(from 缺省 → returnTo='/tasks');`/login` 必须在 ProtectedRoute 外(否则死循环)。

## 6. API 契约

无新增前后端接口。OIDC 走既有 `signinRedirect/signinSilent/signoutRedirect`(react-oidc-context);token 与 groups 解析沿用 oidcConfig。`api/client.ts` 已在 authEnabled 时带 Bearer + 401 续期,**本轮零改**。
**已知不一致(评审 M3,有意保留)**:`api/client.ts` 会话中途 401 静默续期失败时直接 `signinRedirect`(裸跳 Casdoor),**是唯一不经品牌 /login 的重认证入口**——有意保留:token 中途过期时直接重认证、避免多一跳登录页打断 API 上下文。守卫层(冷启动未登录)才落 /login。

## 7. 响应式与移动端适配(沿用平台登录页范式)

沿用 `Grid.useBreakpoint()` → `isMobile=!screens.lg`(992);global.css 768 断点。

| 视口 | 登录页 |
|---|---|
| ≥992 桌面 | 左品牌栏 + 右认证卡两栏;卡片内容宽 `min(360px,100%)` |
| <992 | 隐藏左品牌栏(`.wf-login-brand{display:none}` 或 isMobile 不渲染),单列居中卡 |
| <768 手机 | 卡片近满宽,padding 收 24→16,SSO 按钮 `block` 全宽,`minHeight:44` 触控友好;输入类字号≥16(本页无输入框,免 iOS 缩放) |

- 不套 AppLayout(独立全屏,无 Sider/Header)。safe-area 非必需(无贴边固定栏)。
- 移动端验收:390×844 登录页居中可读、SSO 按钮可点全宽、不横向溢出、品牌栏隐藏。

## 8. 文件级改动清单

**新增**:`pages/LoginPage.tsx`、`pages/LoginPage.test.tsx`。
**修改**:`auth/ProtectedRoute.tsx`(未登录→Navigate /login)、`router/routes.tsx`(+/login 公开)、`styles/global.css`(+.wf-login-brand 响应式)、`.env.example`+`workflow-console/README.md`(Phase 2 启用说明)、(可选)`auth/oidcConfig.ts`(post_logout_redirect_uri→/login)。
**后端**:无改动(Phase 2 仅环境变量:WORKFLOW_SECURITY_ENABLED + WORKFLOW_OIDC_JWKS/ISSUER,不改代码)。
**测试基建**:LoginPage.test 用 `renderWithProviders`(已含 MemoryRouter,不含 AuthProvider,正好留给 useAuth mock)。

## 9. 按依赖排序的实施步骤

1. **LoginPage.tsx**:两栏布局 + SSO 按钮(signinRedirect state.returnTo)+ 已登录/dev useEffect 跳走 + loading/error 状态 + token 映射视觉。
2. **global.css**:`.wf-login-brand` 渐变/布局 + <992 隐藏(或 LoginPage 内 isMobile 条件渲染,二选一,倾向 CSS)。
3. **接线**:`routes.tsx` 加公开 `/login`;`ProtectedRoute` 未登录改 `<Navigate to="/login" state={{from}}>`(保留 authEnabled=false 放行 + isLoading/error/403)。
4. **(可选)** oidcConfig `post_logout_redirect_uri`→/login。
5. **测试**:LoginPage.test(渲染/SSO 点击调 signinRedirect 带 returnTo/isLoading→loading/error→重试/已登录跳走/dev 免登录);补 ProtectedRoute.test(authEnabled 分期 + 未登录→/login 重定向 + 403);建立 useAuth mock 范式。
6. **文档**:`.env.example` + README 加 Phase 2 Runbook。
7. **交付**:`pnpm build` + `pnpm test` 全绿;`pnpm e2e` 保持 authEnabled=false 三冒烟绿 + 新增 `/login` 可达冒烟(不强制登录)。

## 10. 测试策略(含移动端视口矩阵)

- **useAuth mock 范式(新)**:`vi.mock('react-oidc-context',()=>({useAuth:vi.fn()}))`,每用例返回 `{isLoading,isAuthenticated,error,activeNavigator,user,signinRedirect:vi.fn(),signoutRedirect:vi.fn()}`;配 `vi.mock('../config')` 切 authEnabled、`useAuthStore.setState` 切 authorities。
- **LoginPage**:渲染(SSO 按钮+品牌)/ 点 SSO → `signinRedirect` 被调且 `state.returnTo` 正确 / isLoading→按钮 loading / error→Alert+重试 / 已登录→navigate 跳走 / authEnabled=false→"开发模式"入口。
- **ProtectedRoute**:authEnabled=false 放行;true+未登录→重定向 /login(MemoryRouter 断言);true+已登录+PHARMACIST/ADMIN→children;true+无关组→403;isLoading→Spin;error→Result。
- **诚实/回归**:AdminRoute/authStore/oidcConfig 现有测试不受影响。
- **e2e**:保持 `VITE_AUTH_ENABLED=false` 跑现有 3 冒烟(否则被重定向挂);新增 `/login` 冒烟——**dev 模式断言"开发模式 · 免登录"文案可见 + "进入控制台"可点→/tasks**(评审 M1:authEnabled=false 下按设计不渲染 SSO 区,故不能断言 SSO 可见);且 goto /tasks 仍免登录进。真实 Casdoor 往返不入 CI。
- **移动端视口矩阵**:390×844(品牌栏隐藏、卡片居中、SSO 全宽、不溢出)/ 768 / 992 临界 / 1440。注意 `setup.ts` matchMedia 恒 false → 默认移动态;测桌面两栏须用例内覆写 matchMedia 命中 lg(同 designer 做法)。

## 11. 验收标准

- `pnpm build` 通过;`pnpm test` 全绿(含 LoginPage/ProtectedRoute 新测);`pnpm e2e` 三冒烟 + /login 冒烟绿。
- authEnabled=false(默认):行为不变,直连 :8300 免登录,shadow 不受影响。
- authEnabled=true(手动/Phase 2):未登录访问受保护路由 → 落品牌 `/login` → 点 SSO → 跳 Casdoor → 回调 → returnTo 回跳;登出→Casdoor 结束会话;403 正常。
- 视觉:登录页沿用 workflow token(#315EFB 等)、品牌"流程审批中台"、与 his-web 范式一致。
- **移动端(≥1 项)**:390×844 登录页品牌栏隐藏、卡片居中可读、SSO 按钮全宽可点、不横向溢出。
- 诚实:Stage1 明确"开发模式 · 免登录",不误导已鉴权。

## 12. 风险与回滚

| 风险 | 缓解 |
|---|---|
| **R1 后端 security.enabled=true 打断 shadow :8300** | 本轮不动后端;authEnabled 默认 false;Phase 2 在 shadow 迁移后/单独环境再开(§Runbook 明列) |
| R2 authEnabled=true 后现有 e2e 全被重定向挂 | e2e 保持 authEnabled=false;登录跳转用 mock/拦截验证,不跑真实 SSO |
| R3 casdoorClientId 空 → signinRedirect 缺 client_id 失败 | Phase 2 前置:Casdoor 注册应用拿 client_id 填 env,未配则保持 authEnabled=false |
| R4 silent renew 依赖 Casdoor iframe 会话/三方 Cookie 可能失败 | 已有 401→交互式 signinRedirect 兜底;必要时 Phase 2 加 offline_access |
| R5 returnTo/多标签/登出残留会话 | 沿用 state.returnTo;sessionStorage 关标签即清;登出用 signoutRedirect 结束 Casdoor 会话 |
| R6 redirect_uri 需与 Casdoor 注册逐字一致(localhost/127.0.0.1 双栈) | Runbook 注明 redirect_uri=origin+/callback,注册值与访问域名一致 |
| SPA 刷新 404 | nginx.conf 已有 try_files→index.html,无需改(已核实) |
- **回滚**:纯增量(新 /login + LoginPage + 守卫微调)。移除 /login 路由 + 还原 ProtectedRoute 即回滚;authEnabled/后端 security.enabled 两侧开关独立可逆,随时关回 Stage1。

## 13. Phase 2 启用 Runbook(需你决策/提供,不在本轮代码内)

1. **Casdoor 注册 workflow-console 应用**:得 client_id;Redirect URLs 含 `<origin>/callback`;Post-logout 含 `<origin>/login`(或 /);grant=authorization_code+PKCE(public,无 secret);**access_token 注入 `groups` claim** 且用户加入 PHARMACIST/ADMIN 组;scope `openid profile`。
2. **前端 env**:`VITE_AUTH_ENABLED=true`、`VITE_CASDOOR_AUTHORITY=<issuer>`、`VITE_CASDOOR_CLIENT_ID=<client_id>`。
3. **后端(会断 shadow,择机)**:`WORKFLOW_SECURITY_ENABLED=true` + `WORKFLOW_OIDC_JWKS=<Casdoor JWKS>`(或 ISSUER);`tenant-claim` 留空则仍取 `X-Workflow-Tenant` 头。
4. **验证**:未登录跳 /login→SSO→回调→进站;admin 端点非 ADMIN→403;shadow 服务间调用改带 Bearer 或迁出。

## 14. 待澄清(随 AskUserQuestion)

见 DECISION_RECORD「待用户拍板」:D1 自建页、D2 纯 SSO、D3 两栏 vs 居中卡、D4 渐变色/文案、D5 分期(本轮只前端 vs 一并接 Phase 2 需 client_id)。
