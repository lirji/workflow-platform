import type { ReactElement } from 'react'
import { render } from '@testing-library/react'
import { ConfigProvider, App as AntdApp } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { makeTestQueryClient } from './renderWithProviders'

/**
 * 用 data router(createMemoryRouter + RouterProvider)渲染,供依赖 `useBlocker` 的页面测试。
 * `renderWithProviders` 用的 MemoryRouter 非 data router,useBlocker 会抛 invariant(评审 H3)。
 */
export function renderWithDataRouter(
  ui: ReactElement,
  opts?: { initialEntry?: string; queryClient?: QueryClient },
) {
  const queryClient = opts?.queryClient ?? makeTestQueryClient()
  const router = createMemoryRouter([{ path: '/designer', element: ui }], {
    initialEntries: [opts?.initialEntry ?? '/designer'],
  })
  const utils = render(
    <ConfigProvider>
      <AntdApp>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>,
  )
  return { queryClient, router, ...utils }
}
