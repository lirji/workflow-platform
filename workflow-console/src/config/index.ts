// 唯一读取 import.meta.env 的出口(去尾斜杠 + 空串默认)。
const trimSlash = (s: string) => (s || '').replace(/\/+$/, '')

export const config = {
  /** workflow-platform-server(:8300)基址。空=相对路径→dev vite proxy / prod nginx 同源反代。 */
  apiBaseUrl: trimSlash(import.meta.env.VITE_API_BASE_URL ?? ''),
  /** 试点单租户;经 X-Workflow-Tenant 头单点注入。Phase 3 后端从 JWT 派生后可移除。 */
  workflowTenant: import.meta.env.VITE_WORKFLOW_TENANT ?? 'his',
  /** 鉴权分期开关:Stage 1 dev=false 直连 :8300 联调;Stage 2=true 接 Casdoor SSO。 */
  authEnabled: (import.meta.env.VITE_AUTH_ENABLED ?? 'false') === 'true',
  /** Casdoor OIDC authority(issuer)。 */
  casdoorAuthority: trimSlash(import.meta.env.VITE_CASDOOR_AUTHORITY ?? 'http://localhost:8000'),
  /** Casdoor 应用 client_id(Stage 2 鉴权用)。 */
  casdoorClientId: import.meta.env.VITE_CASDOOR_CLIENT_ID ?? '',
  oidcScope: import.meta.env.VITE_OIDC_SCOPE ?? 'openid profile',
}
