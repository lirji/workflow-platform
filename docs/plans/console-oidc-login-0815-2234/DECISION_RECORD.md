# 流程审批中台 Casdoor OIDC 接入 + 登录页 决策记录

> 日期 2026-08-15。frontend-plan 工作流产物(6 只读子代理并行调研综合)。配套 `FINAL_PLAN.md`。**只记录决策,未改任何代码。**

## 背景

用户要求:把"流程审批中台"(workflow-console + workflow-platform-server)接入 Casdoor 走 OIDC 登录,并做"整个登录页面,与其他平台保持一致"。

## 关键事实(决定方案)

1. **OIDC 基建已几乎全建好、可零改复用**:`src/auth/{oidcConfig(授权码+PKCE),AppAuthProvider,AuthBridge,ProtectedRoute}`、`store/authStore`(canRead=PHARMACIST|ADMIN / isAdmin=ADMIN)、`pages/CallbackPage`(returnTo 回跳)、`api/client.ts`(**已在 authEnabled 时自动带 Bearer + 401 单飞 signinSilent 续期→失败 signinRedirect**)、`AppLayout`(登出已实现)。当前 `VITE_AUTH_ENABLED=false`(Stage1,ProtectedRoute 直接放行直连 :8300)。
2. **当前没有自建登录页**:未登录时 `ProtectedRoute` 直接 `auth.signinRedirect()` 裸跳 Casdoor,只显示一个 Spin。`/login` 路由/LoginPage 均不存在。
3. **"其他平台"不统一**:auth-console/dify/agentscope/search = 无登录页、裸 Casdoor 重定向;**his-platform/his-web = 自建 `/login`**(idp 模式 `SsoLogin` 纯 SSO 卡片 / dual 模式带账密表单)、**risk-console = 自建 `LoginPage`**(两栏 + 纯 SSO + 租户框)。→ "做整个登录页面,与其他平台一致" 指**自建品牌登录页**,基准落在 **his-web `SsoLogin`/`DualLogin`**(与 workflow 同栈 antd5、浅色、纯 SSO)。
4. **workflow 后端是纯 OAuth2 Resource Server,只认 Casdoor JWT**(`SecurityConfig`),**无 `/auth/login`、无本地账号库、无 passwordEncoder**。his-web 的账号密码登录打的是它自己的 HS256 后端——workflow **不具备账号密码直登的后端条件**。→ workflow 登录页只能做**纯 SSO 按钮式**。
5. **his-web 的串接方式 = 方案 A**:`ProtectedRoute` 未登录 `<Navigate to="/login">`(而非直跳),`/login` 与 `/callback` 同为公开路由,登录页放"使用统一身份登录"按钮才 `signinRedirect`。
6. **后端启用鉴权会打断当前 shadow :8300**(评审级风险 R1):`security.enabled=true` 后 `anyRequest().authenticated()` 强制所有 /api 带 Bearer,而 shadow(his-outpatient:9004→:8300)只带明文 `X-Workflow-Tenant` 头、无 JWT → 全 401。
7. **Casdoor 侧配置缺失**:`casdoorClientId` 默认空串;issuer/JWKS 实际地址、groups claim 映射、redirect_uri 注册均未提供/未验证。
8. **前端生产托管已就绪**:`workflow-console/nginx.conf` 已有 SPA 回退(`try_files → index.html`),`/login`、`/callback` 刷新不 404;Dockerfile 也在。R7 无需改动。
9. **视觉 token**:`theme/colors.ts` primary `#315EFB`、圆角 8/12、字号 14、控件高 36/40、bgLayout `#F5F7FA`;品牌 `DeploymentUnitOutlined` + "流程审批中台"(`AppLayout`)。workflow 只有浅色单主题。

---

## 决策与备选对比

### D1 · 登录形态 —— **推荐:方案 A(自建 `/login` 品牌页 + 守卫改跳 /login)**

| 方案 | 说明 | 裁决 |
|---|---|---|
| **A. 自建 `/login`(推荐)** | ProtectedRoute 未登录 `<Navigate to="/login">`;登录页展示品牌 + "使用统一身份登录"按钮 → 点了才 signinRedirect。**与 his-web idp 模式一致**,有可见品牌落地页,体验优于裸 Spin;改动小、与 `/callback` 公开路由同构,零后端改动 | ✅ 采纳 |
| B. 保留自动 signinRedirect(现状,无自建页) | 最省事、"与 auth-console 裸重定向一致",但**没有登录页面**,不满足"做整个登录页面"诉求 | ❌ |
| C. 账号密码表单直登 workflow 后端 | 后端纯 Resource Server 无账密端点/用户库,需新建一整套后端鉴权,超范围且与"只认 Casdoor JWT"架构冲突 | ❌ 不可行 |

### D2 · 登录方式 —— **推荐:纯 SSO 单按钮(不做账号密码)**

依据事实 4:workflow 后端做不了账密直登。登录页只保留"使用统一身份登录"按钮跳 Casdoor(his-web `SsoLogin` 范式)。若坚持要账密,须先立后端登录端点 + 用户库(单独大改,本轮 non-goal)。

### D3 · 布局 —— **推荐:左右两栏(左品牌渐变栏 + 右 SSO 认证卡),小屏塌单列**

| 方案 | 说明 | 裁决 |
|---|---|---|
| **两栏(推荐)** | 照 his-web `DualLogin`/risk `LoginPage` 的平台统一范式:左品牌渐变栏(logo+平台名+标语+3 条特性)+ 右居中认证卡(纯 SSO 按钮)。**跨平台一致性最强**;<992 隐藏品牌栏、单列;<768 卡片近满宽 | ✅ 采纳 |
| 极简居中卡(his `SsoLogin`) | 满屏渐变 + 单卡片,更省;但品牌感弱、与 his DualLogin/risk 两栏范式不如两栏一致 | 次选(可作降级) |

### D4 · 品牌渐变第二色 —— **推荐:同色深蓝 `#315EFB → #1E3A8A`**

his-web 用 primary→紫(#722ed1),workflow 无紫色品牌。取同色深蓝更稳重、贴合 #315EFB 主色;蓝紫 `#6D3EF5`(更贴 his 气质)作备选。标为待确认假设。

### D5 · 范围与分期 —— **推荐:本轮只做"前端登录页 + OIDC 接线(可开关)",后端强制 JWT 与 Casdoor 注册留 Phase 2**

依据风险 R1:后端 `security.enabled=true` 会打断跑着的 shadow。故:
- **Phase 1(本轮,可安全合入)**:建 LoginPage + 守卫改跳 /login + 接线,**`VITE_AUTH_ENABLED` 默认仍 false**(shadow 不受影响),测试全绿。合入后前端"具备"登录能力但默认不强制。
- **Phase 2(运维/单独,需你决策)**:Casdoor 注册 workflow-console 应用(client_id / redirect_uri / groups claim)→ 配 `.env` `VITE_AUTH_ENABLED=true` + client_id/authority → 前端可真跳 Casdoor;后端 `WORKFLOW_SECURITY_ENABLED=true` + JWKS**会断 shadow 明文头路径**,需在 shadow 迁移完/单独环境再开。两侧开关独立、可逆。

---

## 待用户拍板(随 FINAL_PLAN 批准)

1. **D1**:确认做自建 `/login` 品牌页(方案 A)?
2. **D2**:确认纯 SSO、不做账号密码(后端无账密条件)?
3. **D3**:两栏品牌页 还是 极简居中卡?
4. **D4**:渐变第二色 深蓝 `#1E3A8A` 还是 蓝紫 `#6D3EF5`?品牌标语/3 条特性文案(默认"待办审批 · 流程编排(BPMN)· 运维治理")是否可用?
5. **D5 分期**:确认本轮只做前端登录页 + 接线(authEnabled 默认关、不动 shadow),后端强制 JWT + Casdoor 注册作为 Phase 2 单独推进?或你已能提供 Casdoor **client_id / issuer·JWKS**,要我把 Phase 2 一并接上并在真实 Casdoor 联调?
