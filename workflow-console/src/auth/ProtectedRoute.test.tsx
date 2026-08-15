import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ConfigProvider, App as AntdApp } from 'antd'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import ProtectedRoute from './ProtectedRoute'
import { useAuthStore } from '../store/authStore'

vi.mock('react-oidc-context', () => ({ useAuth: vi.fn() }))
const cfg = vi.hoisted(() => ({ authEnabled: true }))
vi.mock('../config', () => ({ config: cfg }))

const mockUseAuth = vi.mocked(useAuth)

function setAuth(a: Partial<ReturnType<typeof useAuth>>) {
  mockUseAuth.mockReturnValue({
    isAuthenticated: false,
    isLoading: false,
    activeNavigator: undefined,
    error: undefined,
    signinRedirect: vi.fn(),
    signoutRedirect: vi.fn(),
    ...a,
  } as unknown as ReturnType<typeof useAuth>)
}

function renderProtected() {
  return render(
    <ConfigProvider>
      <AntdApp>
        <MemoryRouter initialEntries={['/ops']}>
          <Routes>
            <Route
              path="/ops"
              element={
                <ProtectedRoute>
                  <div>PROTECTED_CONTENT</div>
                </ProtectedRoute>
              }
            />
            <Route path="/login" element={<div>LOGIN_PAGE</div>} />
          </Routes>
        </MemoryRouter>
      </AntdApp>
    </ConfigProvider>,
  )
}

beforeEach(() => {
  cfg.authEnabled = true
  useAuthStore.setState({ authorities: [] })
})
afterEach(() => vi.clearAllMocks())

describe('ProtectedRoute', () => {
  it('authEnabled=false → 直接放行(dev 直连)', () => {
    cfg.authEnabled = false
    setAuth({ isAuthenticated: false })
    renderProtected()
    expect(screen.getByText('PROTECTED_CONTENT')).toBeInTheDocument()
  })

  it('未登录 → 重定向到 /login', () => {
    setAuth({ isAuthenticated: false })
    renderProtected()
    expect(screen.getByText('LOGIN_PAGE')).toBeInTheDocument()
    expect(screen.queryByText('PROTECTED_CONTENT')).not.toBeInTheDocument()
  })

  it('isLoading → 加载中,不放行不跳转', () => {
    setAuth({ isAuthenticated: false, isLoading: true })
    renderProtected()
    expect(screen.queryByText('PROTECTED_CONTENT')).not.toBeInTheDocument()
    expect(screen.queryByText('LOGIN_PAGE')).not.toBeInTheDocument()
  })

  it('已登录 + PHARMACIST → 放行', () => {
    setAuth({ isAuthenticated: true })
    useAuthStore.setState({ authorities: ['PHARMACIST'] })
    renderProtected()
    expect(screen.getByText('PROTECTED_CONTENT')).toBeInTheDocument()
  })

  it('已登录但无相关组 → 403', () => {
    setAuth({ isAuthenticated: true })
    useAuthStore.setState({ authorities: ['GUEST'] })
    renderProtected()
    expect(screen.getByText('无访问权限')).toBeInTheDocument()
    expect(screen.queryByText('PROTECTED_CONTENT')).not.toBeInTheDocument()
  })
})
