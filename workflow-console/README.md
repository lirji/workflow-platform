# workflow-console

流程/审批中台管控台。本轮两页:**待办中心**(审方待办查看 + 办理)与**流程轨迹**(bpmn-js 只读渲染)。
技术栈克隆自 `auth-console`(React18 + Vite5 + TS + antd5 + react-query + zustand + oidc-client-ts,pnpm)。

后端:`workflow-platform-server`(REST :8300)。实施计划见
`docs/plans/workflow-console-0815-1323/{DECISION_RECORD,FINAL_PLAN}.md`。

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

## 环境变量(`.env.example`)

| 变量 | 说明 |
|---|---|
| `VITE_API_BASE_URL` | 空=相对路径(dev 走 vite proxy / prod 走 nginx 同源反代到 :8300) |
| `VITE_API_TARGET` | 仅 dev proxy 目标,不进产物 |
| `VITE_WORKFLOW_TENANT` | 试点单租户(默认 `his`),经 `X-Workflow-Tenant` 头单点注入;Phase 3 后端从 JWT 派生后可移除 |
| `VITE_AUTH_ENABLED` | 鉴权分期开关。`false`=dev 直连;`true`=接 Casdoor SSO |
| `VITE_CASDOOR_AUTHORITY` / `VITE_CASDOOR_CLIENT_ID` / `VITE_OIDC_SCOPE` | Casdoor OIDC(Stage 2) |

## 鉴权分期

- **Stage 1(dev)**:`VITE_AUTH_ENABLED=false`。`ProtectedRoute` 放行,直连 :8300 联调,头部显示"开发模式·未鉴权"。
- **Stage 2**:置 `true` + 配 Casdoor `client_id`。未登录跳 Casdoor;登录后按组门控;401 静默续期一次。

### Casdoor 组 ↔ BPMN candidateGroups 映射

BPMN 里候选组是**大写、无前缀**的 `PHARMACIST` / `ADMIN`。Casdoor token 的 `groups` 经
`normalizeGroup()` 归一化(取路径末段、去 `<org>_` 前缀、大写)后匹配:

| Casdoor 组(示例) | 归一化 | 含义 |
|---|---|---|
| `PHARMACIST` / `org/PHARMACIST` / `his_PHARMACIST` | `PHARMACIST` | 药师(可读+办理审方) |
| `ADMIN` / `his_admin` | `ADMIN` | 管理员 |

> 角色名本身不含下划线,故"取末段"归一稳定。改角色命名时同步改 `src/auth/oidcConfig.ts` 与后端 `/tasks/search`。

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
docker build -t workflow-console .   # nginx 静态托管,监听 8302,/api 反代到 workflow-platform-server:8300
```

`nginx.conf` 里 `/api` 的 upstream 默认指向 compose 服务名 `workflow-platform-server:8300`,按部署环境调整。

## 本轮 Non-goals

BPMN 建模器/部署(留 Option B 下一轮)、认领/转办、退费审批、跨服务患者明细、深色模式、移动端建模。
