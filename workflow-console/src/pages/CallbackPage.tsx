import { useEffect } from 'react'
import { useAuth } from 'react-oidc-context'
import { useNavigate } from 'react-router-dom'
import { Button, Result, Spin } from 'antd'

const centered: React.CSSProperties = {
  minHeight: '100vh',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
}

/** OIDC 回调页:react-oidc-context 自动用 code 换 token,完成后跳回原深链。 */
export default function CallbackPage() {
  const auth = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    if (!auth.isLoading && auth.isAuthenticated) {
      const state = auth.user?.state as { returnTo?: string } | undefined
      navigate(state?.returnTo ?? '/', { replace: true })
    }
  }, [auth.isLoading, auth.isAuthenticated, auth.user, navigate])

  if (auth.error) {
    return (
      <Result
        status="error"
        title="登录回调失败"
        subTitle={auth.error.message}
        extra={<Button type="primary" onClick={() => void auth.signinRedirect()}>重试登录</Button>}
      />
    )
  }
  return (
    <div style={centered}>
      <Spin size="large" tip="登录中..." />
    </div>
  )
}
