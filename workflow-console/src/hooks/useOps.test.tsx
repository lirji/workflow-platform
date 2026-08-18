import type { ReactNode } from 'react'
import { act, renderHook } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { makeTestQueryClient } from '../test/renderWithProviders'
import { DEFS_KEY, useDeployDefinition } from './useOps'
import { deployDefinition } from '../api/admin'

vi.mock('../api/admin', () => ({ deployDefinition: vi.fn() }))
vi.mock('../api/dlq', () => ({}))

beforeEach(() => vi.clearAllMocks())

describe('useDeployDefinition cache correctness', () => {
  it('invalidates both definition list and XML caches after deployment', async () => {
    const queryClient = makeTestQueryClient()
    queryClient.setQueryData([DEFS_KEY], [])
    queryClient.setQueryData(['definition-xml', 'hisRxReview', 'latest'], '<old/>')
    vi.mocked(deployDefinition).mockResolvedValue({
      id: 'def-2', key: 'hisRxReview', name: 'review', version: 2,
      tenantId: 'his', suspended: false, deploymentId: 'dep-2',
    })
    const wrapper = ({ children }: { children: ReactNode }) =>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    const { result } = renderHook(() => useDeployDefinition(), { wrapper })

    await act(() => result.current.mutateAsync({ name: 'review', bpmnXml: '<definitions/>' }))

    expect(queryClient.getQueryState([DEFS_KEY])?.isInvalidated).toBe(true)
    expect(queryClient.getQueryState(['definition-xml', 'hisRxReview', 'latest'])?.isInvalidated).toBe(true)
  })
})
