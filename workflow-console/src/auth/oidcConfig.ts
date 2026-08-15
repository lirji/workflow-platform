import { UserManager, WebStorageStateStore, type UserManagerSettings } from 'oidc-client-ts'
import { config } from '../config'

// 授权码 + PKCE 接 Casdoor。token 存 sessionStorage(关标签页即清)。
export const oidcSettings: UserManagerSettings = {
  authority: config.casdoorAuthority,
  client_id: config.casdoorClientId,
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: `${window.location.origin}/login`,
  response_type: 'code',
  scope: config.oidcScope,
  loadUserInfo: false, // Casdoor 把 groups 放进 access_token,无需 userinfo(免额外 CORS)
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
}

// 供 axios 等命令式读取当前 token 的独立实例(不自动续期,避免与 AuthProvider 双续期);共享同一 sessionStorage。
export const userManager = new UserManager({ ...oidcSettings, automaticSilentRenew: false })

/**
 * 归一化组名以匹配 BPMN candidateGroups(评审 B4)。
 * BPMN 里是大写、无前缀的 PHARMACIST/ADMIN;Casdoor token 组名可能带路径段(org/PHARMACIST)
 * 或 <org>_ 下划线前缀(his_PHARMACIST)、大小写不定。角色名本身不含下划线,故取末段并大写即可归一。
 */
export function normalizeGroup(g: string): string {
  const afterSlash = g.slice(g.lastIndexOf('/') + 1)
  const afterUnderscore = afterSlash.slice(afterSlash.lastIndexOf('_') + 1)
  return afterUnderscore.toUpperCase()
}

/** 从 access_token 解出归一化(shortName)后的 groups。 */
export function groupsFromToken(accessToken?: string): string[] {
  if (!accessToken) return []
  try {
    let b64 = accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    b64 += '='.repeat((4 - (b64.length % 4)) % 4)
    const payload = JSON.parse(atob(b64)) as { groups?: unknown }
    if (Array.isArray(payload.groups)) {
      return payload.groups.map((s: unknown) => {
        const str = String(s)
        const i = str.lastIndexOf('/')
        return i >= 0 ? str.slice(i + 1) : str
      })
    }
  } catch {
    // ignore
  }
  return []
}
