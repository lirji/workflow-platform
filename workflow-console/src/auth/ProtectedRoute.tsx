import type { ReactNode } from 'react'
import { useEffect } from 'react'
import { useAuth } from 'react-oidc-context'
import { useLocation } from 'react-router-dom'
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
 * Stage 2:未登录→跳 Casdoor;已登录但非 PHARMACIST/ADMIN→403。
 */
export default function ProtectedRoute({ children }: { children: ReactNode }) {
  const auth = useAuth()
  const location = useLocation()
  const authorities = useAuthStore((s) => s.authorities)

  useEffect(() => {
    if (!config.authEnabled) return
    if (!auth.isLoading && !auth.isAuthenticated && !auth.activeNavigator && !auth.error) {
      void auth.signinRedirect({ state: { returnTo: location.pathname } })
    }
  }, [auth.isLoading, auth.isAuthenticated, auth.activeNavigator, auth.error, location.pathname])

  // Stage 1:无鉴权直连联调。
  if (!config.authEnabled) return <>{children}</>

  if (auth.isLoading || auth.activeNavigator) {
    return (
      <div style={centered}>
        <Spin size="large" tip="加载中..." />
      </div>
    )
  }
  if (auth.error) {
    return (
      <Result
        status="error"
        title="登录失败"
        subTitle={auth.error.message}
        extra={<Button type="primary" onClick={() => void auth.signinRedirect()}>重试登录</Button>}
      />
    )
  }
  if (!auth.isAuthenticated) {
    return (
      <div style={centered}>
        <Spin size="large" tip="跳转登录..." />
      </div>
    )
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
