import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ConfigProvider, App as AntdApp } from 'antd'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { makeTestQueryClient } from '../test/renderWithProviders'
import ProcessTracePage from './ProcessTracePage'
import { getDefinitionXml } from '../api/process'
import { useProcessPhase, useTimeline } from '../hooks/useProcess'
import type { ProcessInstanceView, TimelineEntry } from '../api/types'

vi.mock('../components/bpmn/BpmnViewer', () => ({ default: () => <div>流程图</div> }))
vi.mock('../api/process', () => ({ getDefinitionXml: vi.fn() }))
vi.mock('../hooks/useProcess', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../hooks/useProcess')>()
  return { ...actual, useProcessPhase: vi.fn(), useTimeline: vi.fn() }
})

const instance: ProcessInstanceView = {
  processInstanceId: 'pi-1', tenantId: 'his', processDefinitionKey: 'hisRxReview', businessKey: 'enc-1',
  idempotencyKey: 'cycle-1', phase: 'WAITING_USER', status: 'ACTIVE', running: true, suspended: false,
}

function renderPage() {
  const queryClient = makeTestQueryClient()
  const router = createMemoryRouter([{ path: '/process/:key', element: <ProcessTracePage /> }], {
    initialEntries: ['/process/hisRxReview?businessKey=enc-1'],
  })
  return render(
    <ConfigProvider><AntdApp><QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider></AntdApp></ConfigProvider>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('ProcessTracePage correctness states', () => {
  it('shows an explicit empty state and does not load latest XML for a missing business instance', () => {
    vi.mocked(useProcessPhase).mockReturnValue({
      data: [], isSuccess: true, isLoading: false, isError: false, isFetching: false, refetch: vi.fn(),
    } as never)
    vi.mocked(useTimeline).mockReturnValue({ data: undefined, isLoading: false, isError: false } as never)

    renderPage()

    expect(screen.getByText(/未找到业务键 enc-1 的流程实例/)).toBeInTheDocument()
    expect(getDefinitionXml).not.toHaveBeenCalled()
  })

  it('surfaces timeline failure instead of silently showing an unhighlighted diagram', async () => {
    const cachedEntry: TimelineEntry = {
      activityId: 'review', activityName: '审方', activityType: 'userTask', assignee: 'alice',
      startEpochMs: 1_700_000_000_000, endEpochMs: null,
    }
    vi.mocked(useProcessPhase).mockReturnValue({
      data: [instance], isSuccess: true, isLoading: false, isError: false, isFetching: false, refetch: vi.fn(),
    } as never)
    vi.mocked(useTimeline).mockReturnValue({
      data: [cachedEntry], isLoading: false, isError: true, error: new Error('timeline failed'), refetch: vi.fn(),
    } as never)
    vi.mocked(getDefinitionXml).mockResolvedValue('<definitions/>')

    renderPage()

    expect(await screen.findByText('timeline failed')).toBeInTheDocument()
    expect(getDefinitionXml).toHaveBeenCalledWith('hisRxReview', 'pi-1')
    expect(screen.queryByText('办理轨迹')).not.toBeInTheDocument()
    expect(screen.queryByText('审方')).not.toBeInTheDocument()
  })
})
