import type { ReactNode } from 'react'
import { Button, Result } from 'antd'
import { useNavigate } from 'react-router-dom'
import { config } from '../config'
import { isAdmin, useAuthStore } from '../store/authStore'

/**
 * ADMIN 路由守卫(防御纵深,配合后端 hasAuthority("ADMIN") 兜底)。
 * dev(authEnabled=false)放行以便联调;否则需 ADMIN,否则 403。
 * 父级 ProtectedRoute 已保证 authorities 就绪(canRead 为真才渲染 children),故此处无需 loading 分支。
 */
export default function AdminRoute({ children }: { children: ReactNode }) {
  const authorities = useAuthStore((s) => s.authorities)
  const navigate = useNavigate()
  if (!config.authEnabled || isAdmin(authorities)) {
    return <>{children}</>
  }
  return (
    <Result
      status="403"
      title="无访问权限"
      subTitle="运维面板需要 ADMIN 权限"
      extra={
        <Button type="primary" onClick={() => navigate('/tasks')}>
          返回待办中心
        </Button>
      }
    />
  )
}
