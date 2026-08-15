import type { ReactNode } from 'react'
import { useAuth } from 'react-oidc-context'
import { Navigate, useLocation } from 'react-router-dom'
import { Button, Result, Spin } from 'antd'
import { config } from '../config'
import { canRead, useAuthStore } from '../store/authStore'

const centered: React.CSSProperties = {
  minHeight: '60vh',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
}

/**
 * 路由守卫。Stage 1(authEnabled=false):直接放行,直连 :8300 联调。
 * Stage 2:未登录→跳自建品牌登录页 /login(带 state.from 回跳原深链);已登录但非 PHARMACIST/ADMIN→403。
 */
export default function ProtectedRoute({ children }: { children: ReactNode }) {
  const auth = useAuth()
  const location = useLocation()
  const authorities = useAuthStore((s) => s.authorities)

  // Stage 1:无鉴权直连联调。
  if (!config.authEnabled) return <>{children}</>

  if (auth.isLoading || auth.activeNavigator) {
    return (
      <div style={centered}>
        <Spin size="large" tip="加载中..." />
      </div>
    )
  }
  // 未登录/登录出错 → 落品牌登录页,由 /login 承载 SSO 入口与错误呈现;from 透传供回调回跳。
  if (!auth.isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (!canRead(authorities)) {
    return (
      <Result
        status="403"
        title="无访问权限"
        subTitle="需要 Casdoor 组 PHARMACIST 或 ADMIN"
        extra={<Button onClick={() => void auth.signoutRedirect()}>切换账号</Button>}
      />
    )
  }
  return <>{children}</>
}
