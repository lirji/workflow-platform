import { create } from 'zustand'
import { normalizeGroup } from '../auth/oidcConfig'

/** OIDC 会话在应用侧的镜像(供 UI/守卫同步读取;权威源是 oidc userStore)。 */
interface AuthState {
  status: 'loading' | 'authed' | 'anon'
  userId?: string
  username?: string
  authorities: string[]
  set: (p: Partial<AuthState>) => void
  clear: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  status: 'loading',
  authorities: [],
  set: (p) => set(p),
  clear: () => set({ status: 'anon', userId: undefined, username: undefined, authorities: [] }),
}))

// 组名归一化后匹配 BPMN candidateGroups(PHARMACIST/ADMIN,大小写不敏感、去 <org>_ 前缀)。
const has = (a: string[], role: string): boolean => a.map(normalizeGroup).includes(role)
export const isAdmin = (a: string[]): boolean => has(a, 'ADMIN')
export const canRead = (a: string[]): boolean => has(a, 'PHARMACIST') || has(a, 'ADMIN')
