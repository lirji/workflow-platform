import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider, App as AntdApp } from 'antd'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import LoginPage from './LoginPage'

// react-oidc-context 的 useAuth 用 mock 注入(全仓首次建立此范式);config.authEnabled 用可变对象切分期。
vi.mock('react-oidc-context', () => ({ useAuth: vi.fn() }))
const cfg = vi.hoisted(() => ({ authEnabled: true }))
vi.mock('../config', () => ({ config: cfg }))

const mockUseAuth = vi.mocked(useAuth)

type AuthShape = Partial<ReturnType<typeof useAuth>> & { signinRedirect?: ReturnType<typeof vi.fn> }
function setAuth(a: AuthShape) {
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

function renderLogin(state?: unknown) {
  return render(
    <ConfigProvider>
      <AntdApp>
        <MemoryRouter initialEntries={[{ pathname: '/login', state }]}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/tasks" element={<div>TASKS_PAGE</div>} />
            <Route path="/ops" element={<div>OPS_PAGE</div>} />
          </Routes>
        </MemoryRouter>
      </AntdApp>
    </ConfigProvider>,
  )
}

beforeEach(() => {
  cfg.authEnabled = true
})
afterEach(() => vi.clearAllMocks())

describe('LoginPage · 生产(authEnabled=true)', () => {
  it('未登录:显示品牌 + SSO 按钮', () => {
    setAuth({ isAuthenticated: false })
    renderLogin()
    expect(screen.getByText('欢迎登录')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /使用统一身份登录/ })).toBeInTheDocument()
    // 品牌栏节点在 DOM(纯 CSS 隐藏,jsdom 不套 media)
    expect(screen.getByText('流程审批中台')).toBeInTheDocument()
  })

  it('点 SSO → signinRedirect 带 state.returnTo(from 透传)', async () => {
    const signinRedirect = vi.fn()
    setAuth({ isAuthenticated: false, signinRedirect })
    const user = userEvent.setup()
    renderLogin({ from: '/ops' })
    await user.click(screen.getByRole('button', { name: /使用统一身份登录/ }))
    expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/ops' } })
  })

  it('无 from 时 returnTo 缺省 /tasks', async () => {
    const signinRedirect = vi.fn()
    setAuth({ isAuthenticated: false, signinRedirect })
    const user = userEvent.setup()
    renderLogin()
    await user.click(screen.getByRole('button', { name: /使用统一身份登录/ }))
    expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/tasks' } })
  })

  it('登录出错 → Alert + 按钮变"重试登录"', () => {
    setAuth({ isAuthenticated: false, error: new Error('token 无效') as never })
    renderLogin()
    expect(screen.getByText('登录出错')).toBeInTheDocument()
    expect(screen.getByText('token 无效')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /重试登录/ })).toBeInTheDocument()
  })

  it('activeNavigator → 按钮显示"正在跳转登录…"', () => {
    setAuth({ isAuthenticated: false, activeNavigator: 'signinRedirect' as never })
    renderLogin()
    expect(screen.getByRole('button', { name: /正在跳转登录/ })).toBeInTheDocument()
  })

  it('已登录访问 /login → 回跳 returnTo(/ops)', () => {
    setAuth({ isAuthenticated: true })
    renderLogin({ from: '/ops' })
    expect(screen.getByText('OPS_PAGE')).toBeInTheDocument()
  })
})

describe('LoginPage · 开发模式(authEnabled=false)', () => {
  it('显示"开发模式·免登录",无 SSO 按钮,可进控制台', async () => {
    cfg.authEnabled = false
    setAuth({ isAuthenticated: false })
    const user = userEvent.setup()
    renderLogin()
    expect(screen.getByText(/开发模式 · 免登录/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /使用统一身份登录/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '进入控制台' }))
    expect(screen.getByText('TASKS_PAGE')).toBeInTheDocument()
  })
})
