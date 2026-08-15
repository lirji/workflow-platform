import type { ReactNode } from 'react'
import { AuthProvider } from 'react-oidc-context'
import { oidcSettings } from './oidcConfig'
import AuthBridge from './AuthBridge'

// 回调成功后清掉 URL 上的 code/state(保留路径,由 CallbackPage 再跳 returnTo)。
const onSigninCallback = () => {
  window.history.replaceState({}, document.title, window.location.pathname)
}

// 始终挂载 AuthProvider(Stage 1 无鉴权时也挂,保证 useAuth 上下文存在);是否发起登录由 ProtectedRoute 依 authEnabled 决定。
export default function AppAuthProvider({ children }: { children: ReactNode }) {
  return (
    <AuthProvider {...oidcSettings} onSigninCallback={onSigninCallback}>
      <AuthBridge />
      {children}
    </AuthProvider>
  )
}
