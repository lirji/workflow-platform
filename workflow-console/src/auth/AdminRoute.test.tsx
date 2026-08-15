import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'
import AdminRoute from './AdminRoute'
import { useAuthStore } from '../store/authStore'

// config 用可控 mock:切换 authEnabled 验证 dev 逃生门 vs 生产 ADMIN 门控。
const cfg = vi.hoisted(() => ({ authEnabled: true }))
vi.mock('../config', () => ({ config: cfg }))

afterEach(() => {
  useAuthStore.setState({ authorities: [] })
})

const Child = () => <div>运维内容</div>

describe('AdminRoute 门控', () => {
  it('dev(authEnabled=false)放行,不看角色', () => {
    cfg.authEnabled = false
    useAuthStore.setState({ authorities: [] })
    renderWithProviders(<AdminRoute><Child /></AdminRoute>)
    expect(screen.getByText('运维内容')).toBeInTheDocument()
  })

  it('生产非 ADMIN → 403,不渲染内容', () => {
    cfg.authEnabled = true
    useAuthStore.setState({ authorities: ['PHARMACIST'] })
    renderWithProviders(<AdminRoute><Child /></AdminRoute>)
    expect(screen.queryByText('运维内容')).not.toBeInTheDocument()
    expect(screen.getByText('无访问权限')).toBeInTheDocument()
  })

  it('生产 ADMIN → 放行', () => {
    cfg.authEnabled = true
    useAuthStore.setState({ authorities: ['ADMIN'] })
    renderWithProviders(<AdminRoute><Child /></AdminRoute>)
    expect(screen.getByText('运维内容')).toBeInTheDocument()
  })
})
