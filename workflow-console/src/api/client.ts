import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { User } from 'oidc-client-ts'
import { config } from '../config'
import { userManager } from '../auth/oidcConfig'

/** 空 baseURL → 相对路径 → dev vite proxy / prod nginx 同源反代到 workflow-platform-server:8300。 */
export const apiClient = axios.create({
  baseURL: config.apiBaseUrl,
  timeout: 15000,
})

// 请求拦截(单点注入):租户头(试点单租户,Phase 3 后端从 JWT 派生后移除)+ 鉴权开启时附 Casdoor token。
apiClient.interceptors.request.use(async (cfg) => {
  cfg.headers['X-Workflow-Tenant'] = config.workflowTenant
  if (config.authEnabled) {
    const user = await userManager.getUser()
    if (user && !user.expired && user.access_token) {
      cfg.headers.Authorization = `Bearer ${user.access_token}`
    }
  }
  return cfg
})

// 响应拦截:鉴权开启时 401 → 单飞静默续期(共享 in-flight promise 防惊群)重试一次;仍失败 → 交互式登录。
// Stage 1 dev(authEnabled=false)直连 :8300,不做续期,错误透传给调用方处理。
let refreshing: Promise<User | null> | null = null

apiClient.interceptors.response.use(
  (r) => r,
  async (error: AxiosError) => {
    if (!config.authEnabled) return Promise.reject(error)
    const original = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined
    if (error.response?.status === 401 && original && !original._retried) {
      original._retried = true
      try {
        if (!refreshing) {
          refreshing = userManager.signinSilent().finally(() => {
            refreshing = null
          })
        }
        const user = await refreshing
        if (user?.access_token) {
          original.headers.Authorization = `Bearer ${user.access_token}`
          return apiClient(original)
        }
      } catch {
        // 续期失败,落到交互式登录
      }
      await userManager.signinRedirect({ state: { returnTo: window.location.pathname } })
    }
    return Promise.reject(error)
  },
)
