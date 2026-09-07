import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { render } from '@testing-library/react'
import { ConfigProvider, App as AntdApp } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import TasksPage from './TasksPage'
import { findTasks } from '../api/tasks'
import type { TaskView } from '../api/types'
import { setViewport } from '../test/viewport'

vi.mock('../api/tasks', () => ({
  findTasks: vi.fn(),
  completeReview: vi.fn(),
  completeTask: vi.fn(),
  claimTask: vi.fn(),
  reassignTask: vi.fn(),
  unclaimTask: vi.fn(),
}))
vi.mock('../config', () => ({ config: { authEnabled: false, workflowTenant: 'dev-tenant' } }))

const mockedFind = vi.mocked(findTasks)

const hisTask: TaskView = {
  taskId: 't-his',
  taskDefinitionKey: 'pharmacistReview',
  name: '药师审方',
  processInstanceId: 'p-1',
  processDefinitionKey: 'hisRxReview',
  businessKey: '90003',
  tenantId: 'dev-tenant',
  assignee: null,
  candidateGroups: ['PHARMACIST'],
  createTimeEpochMs: 1_700_000_000_000,
}

const skuTask: TaskView = {
  taskId: 't-sku',
  taskDefinitionKey: 'skuGoLiveReview',
  name: 'SKU 上线审批',
  processInstanceId: 'p-2',
  processDefinitionKey: 'benefitSkuGoLive',
  businessKey: 'SKU-1',
  tenantId: 'dev-tenant',
  assignee: null,
  candidateGroups: ['BENEFIT_SKU_REVIEWER'],
  createTimeEpochMs: 1_700_000_000_000,
}

function renderTasks(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <ConfigProvider>
      <AntdApp>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[path]}>
            <Routes>
              <Route path="/tasks" element={<TasksPage />} />
              <Route path="/tasks/:taskId" element={<TasksPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>,
  )
}

describe('TasksPage', () => {
  it('lists every definition in the session tenant when definitionKey is empty', async () => {
    mockedFind.mockResolvedValue({ items: [hisTask, skuTask], total: 2, page: 0, size: 20 })
    renderTasks('/tasks')
    await waitFor(() => expect(mockedFind).toHaveBeenCalled())
    const args = mockedFind.mock.calls[0][0]
    expect(args.definitionKey).toBeUndefined()
    expect(args.candidateGroup).toBeUndefined()
    expect(await screen.findByText('90003')).toBeInTheDocument()
    expect(screen.getByText('SKU-1')).toBeInTheDocument()
  })

  it('reads definitionKey and businessKey from the URL without inventing PHARMACIST', async () => {
    mockedFind.mockResolvedValue({ items: [skuTask], total: 1, page: 0, size: 20 })
    renderTasks('/tasks?definitionKey=benefitSkuGoLive&businessKey=SKU-1')
    await waitFor(() => expect(mockedFind).toHaveBeenCalledWith(expect.objectContaining({
      definitionKey: 'benefitSkuGoLive',
      businessKey: 'SKU-1',
    })))
    expect(mockedFind.mock.calls[0][0].candidateGroup).toBeUndefined()
    expect(await screen.findByText('SKU-1')).toBeInTheDocument()
    expect(screen.queryByText('90003')).not.toBeInTheDocument()
  })

  it('opens the matching task from /tasks/:taskId', async () => {
    setViewport(true)
    mockedFind.mockResolvedValue({ items: [hisTask, skuTask], total: 2, page: 0, size: 20 })
    renderTasks('/tasks/t-sku')
    expect(await screen.findByText('办理上线审批')).toBeInTheDocument()
    setViewport(false)
  })
})
