import type { ReactElement, ReactNode } from 'react'
import { render } from '@testing-library/react'
import { ConfigProvider, App as AntdApp } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

/** 测试专用 QueryClient:关闭重试,避免失败用例挂起。 */
export function makeTestQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

/** 用应用同款 Provider 栈渲染(ConfigProvider + AntdApp + react-query + Router)。 */
export function renderWithProviders(ui: ReactElement, opts?: { queryClient?: QueryClient }) {
  const queryClient = opts?.queryClient ?? makeTestQueryClient()
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <ConfigProvider>
      <AntdApp>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter>{children}</MemoryRouter>
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>
  )
  return { queryClient, ...render(ui, { wrapper: Wrapper }) }
}
