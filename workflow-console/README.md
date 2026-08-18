# workflow-console

流程/审批中台管控台。本轮两页:**待办中心**(审方待办查看 + 办理)与**流程轨迹**(bpmn-js 只读渲染)。
技术栈克隆自 `auth-console`(React18 + Vite5 + TS + antd5 + react-query + zustand + oidc-client-ts,pnpm)。

后端:`workflow-platform-server`(REST :8300)。

## 相关文档

- 📘 **[工作流中台接入指南](../docs/integration-guide.md)** —— 其他业务系统如何接入中台(Kafka 事件契约、SDK/REST、发起/落地代码骨架、JSON 样例、Nexus 发布)。前端消费的 REST 端点、`X-Workflow-Tenant` 租户头、办理 202 最终一致语义均以该指南为准。
- 实施计划:`../docs/plans/workflow-console-0815-1323/{DECISION_RECORD,FINAL_PLAN}.md`

## 快速开始(dev,无鉴权直连联调)

```bash
pnpm install
cp .env.example .env.local     # 默认 VITE_AUTH_ENABLED=false,直连 :8300
pnpm dev                       # http://localhost:5373
```

`vite.config.ts` 把 `/api` 同源反代到 `VITE_API_TARGET`(默认 `http://localhost:8300`),免 CORS。
需要中台 server 在 :8300 运行,并有 tenant=`his` 的审方待办(businessKey 即就诊/encounter)。

- 待办中心:`/tasks`
- 流程轨迹:`/process/hisRxReview`(叠加实例轨迹高亮:`/process/hisRxReview?businessKey=<就诊号>`)
- 运维面板:`/ops`(ADMIN;实例运维 / 死信作业 / DLQ / 流程定义)
- 流程设计器:`/designer`(ADMIN;可视化拖拽建模 + 部署,`/designer?key=<定义key>` 编辑既有定义最新版)

### 流程设计器(bpmn-js Modeler)

`/designer` 提供**可视化 BPMN 编辑 + 部署**:bpmn-js Modeler 拖拽建模 + `bpmn-js-properties-panel` 完整属性面板
(vanilla BPMN provider + 自定义 **Flowable** provider,可编 `flowable:candidateGroups`/`assignee`/`delegateExpression`,
内联 flowable moddle),导出 XML 预览,部署复用 `POST /api/v1/admin/definitions/deploy`(零后端改动)。
入口:运维面板「流程定义」Tab 的「可视化新建」/ 行「设计」按钮,或侧栏「流程设计器」。桌面为主;<992 只读降级 + 引导桌面。

> **定位诚实说明**:设计器是「可视化编辑+部署工具」(粘贴 XML 的升级),产物**无法从 console 独立跑实例**——
> 实例发起由消费方经 Kafka `StartProcessCommandV1` 驱动,任务完成走审方专用端点(`decision=PASS/REJECT`),
> 网关条件仅 `decision` 变量可用。带 outbox/ACK(serviceTask delegate、message 关联)的流程仍走「克隆 his-rx-review 改 XML」路径。
> 详见 `../docs/plans/bpmn-designer-0815-2120/{DECISION_RECORD,FINAL_PLAN}.md`。

## 环境变量(`.env.example`)

| 变量 | 说明 |
|---|---|
| `VITE_API_BASE_URL` | 空=相对路径(dev 走 vite proxy / prod 走 nginx 同源反代到 :8300) |
| `VITE_API_TARGET` | 仅 dev proxy 目标,不进产物 |
| `VITE_WORKFLOW_TENANT` | 试点单租户(默认 `his`),经 `X-Workflow-Tenant` 头单点注入;Phase 3 后端从 JWT 派生后可移除 |
| `VITE_AUTH_ENABLED` | 鉴权分期开关。`false`=dev 直连;`true`=接 Casdoor SSO |
| `VITE_CASDOOR_AUTHORITY` / `VITE_CASDOOR_CLIENT_ID` / `VITE_OIDC_SCOPE` | Casdoor OIDC(Stage 2) |

## 鉴权分期

- **Stage 1(dev)**:`VITE_AUTH_ENABLED=false`。`ProtectedRoute` 放行,直连 :8300 联调,头部显示"开发模式·未鉴权";`/login` 显示"开发模式·免登录"入口。
- **Stage 2**:置 `true` + 配 Casdoor `client_id`。未登录 → 品牌登录页 `/login` → "使用统一身份登录" → Casdoor SSO(授权码+PKCE)→ `/callback` 回跳原深链;登录后按组门控(非 PHARMACIST/ADMIN→403);会话中途 401 静默续期一次,失败再交互式登录。

### 登录页 `/login`(与其他平台一致)

自建品牌登录页,范式沿用 his-web(左品牌渐变栏 + 右 SSO 认证卡),落到本项目 token(#315EFB / 圆角 8·12)。**纯 SSO**——workflow 后端是纯 Casdoor JWT Resource Server,无账号密码/本地账号库(与 his-web 账密登录不同,那是打 his 自己的后端)。`ProtectedRoute` 未登录 `<Navigate to="/login">`;登出后落 `/login`。计划见 `../docs/plans/console-oidc-login-0815-2234/`。

### Casdoor OIDC 接入(Phase 2 启用 Runbook)

登录页 + OIDC 接线已就绪(Phase 1),默认 `VITE_AUTH_ENABLED=false` 不影响 shadow。**真正启用鉴权**按下列步骤(需 Casdoor 管理 + 会打断明文头 shadow,择机分期):

1. **Casdoor 注册 workflow-console 应用**:得 `client_id`;Redirect URLs 含 `<origin>/callback`;Post-logout 含 `<origin>/login`;grant=authorization_code + PKCE(public client,无 secret);**access_token 注入 `groups` claim** 且用户加入 `PHARMACIST`/`ADMIN` 组;scope `openid profile`。
2. **前端 env**:`VITE_AUTH_ENABLED=true`、`VITE_CASDOOR_AUTHORITY=<issuer>`、`VITE_CASDOOR_CLIENT_ID=<client_id>`。
3. **后端**:生产用 `prod` profile，配置 `WORKFLOW_SECURITY_ENABLED=true`、`WORKFLOW_OIDC_ISSUER`、`WORKFLOW_OIDC_JWKS`、`WORKFLOW_OIDC_AUDIENCE` 与 `WORKFLOW_TENANT_CLAIM`；tenant claim 缺失或头不一致会返回 403。
4. **验证**:未登录跳 `/login`→SSO→回调进站;admin 端点非 ADMIN→403;服务间调用(his-outpatient→:8300)改带 Bearer 或迁出。

### Casdoor 组 ↔ BPMN candidateGroups 映射

BPMN 里候选组是**大写、无前缀**的 `PHARMACIST` / `ADMIN`。Casdoor token 的 `groups` 经
`normalizeGroup()` 归一化（只取路径末段并大写）后精确匹配：

| Casdoor 组(示例) | 归一化 | 含义 |
|---|---|---|
| `PHARMACIST` / `org/PHARMACIST` | `PHARMACIST` | 药师(可读+办理审方) |
| `ADMIN` / `org/ADMIN` | `ADMIN` | 管理员 |
| `his_PHARMACIST` / `his_ADMIN` | 保持原值 | 不授权；避免租户前缀导致权限提升 |

> IdP 中请直接配置精确角色名。改角色命名时同步改 `src/auth/oidcConfig.ts`、后端安全配置与 BPMN `candidateGroups`。

## 关键设计(诚实的最终一致)

- 办理 `POST /tasks/{id}/complete-review` 恒返回 **202 `PENDING_BUSINESS`**:人工决定已受理,业务落地经
  Kafka 异步最终一致。UI **绝不显示"已完成"**——"近期办理"区轮询 `/process-instances` 的
  `phase`,诚实呈现 `处理中 → 已落地 / 异常`。
- 办理不可回滚:提交前二次确认;失败提示"未落地,可重试"。
- bpmn 仅 `NavigatedViewer` 只读 + `React.lazy` 懒加载(不进待办首屏);viewer 实例存 `useRef`。
- 租户/actor 注入收敛到 `api/client.ts` 单点(`X-Workflow-Tenant`),Phase 3 后端从 JWT 派生后业务组件零改动。

## 测试

```bash
pnpm test            # Vitest + Testing Library(组件/hook;含"办理 202 显示已受理不显示已完成""驳回意见必填""409 分支")
pnpm e2e:install     # 首次:装 chromium
pnpm e2e             # Playwright 冒烟(需 dev server 在 :5373)
```

## 构建与交付

```bash
pnpm build           # tsc + vite build → dist/
docker build -t workflow-console .   # nginx 静态托管,监听 8302,/api 反代到 compose 的 server:8300
```

`nginx.conf` 里 `/api` 的 upstream 默认指向 compose 服务名 `server:8300`,按部署环境调整。
`GET /healthz` 返回无业务数据的 204，并开放无凭据 CORS，供统一能力门户探测控制台是否可访问。

## 本轮 Non-goals

认领/转办、退费审批、跨服务患者明细、深色模式、移动端建模。
BPMN 建模器/部署已交付(`/designer`,见上)。设计器本轮不做:完整表单设计器、多实例/会签、`.bpmn` 文件导入导出、Camunda/Zeebe 属性 provider、从 console 发起/驱动实例(归消费方+服务端)。
