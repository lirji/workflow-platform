import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { render } from '@testing-library/react'
import { ConfigProvider, App as AntdApp } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import TasksPage from './TasksPage'
import { findTasks } from '../api/tasks'
import type { TaskView } from '../api/types'

vi.mock('../api/tasks', () => ({
  findTasks: vi.fn(),
  completeReview: vi.fn(),
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
  tenantId: 'his',
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
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>,
  )
}

describe('TasksPage', () => {
  it('defaults to hisRxReview and hides SKU tasks', async () => {
    mockedFind.mockResolvedValue({ items: [hisTask, skuTask], total: 2, page: 0, size: 50 })
    renderTasks('/tasks')
    await waitFor(() => expect(mockedFind).toHaveBeenCalledWith(expect.objectContaining({
      definitionKey: 'hisRxReview',
      candidateGroup: ['PHARMACIST'],
    })))
    expect(await screen.findByText('90003')).toBeInTheDocument()
    expect(screen.queryByText('SKU-1')).not.toBeInTheDocument()
    expect(screen.getByText(/审方待办/)).toBeInTheDocument()
  })

  it('reads definitionKey and businessKey from the URL for SKU go-live', async () => {
    mockedFind.mockResolvedValue({ items: [hisTask, skuTask], total: 2, page: 0, size: 50 })
    renderTasks('/tasks?definitionKey=benefitSkuGoLive&businessKey=SKU-1')
    await waitFor(() => expect(mockedFind).toHaveBeenCalledWith(expect.objectContaining({
      definitionKey: 'benefitSkuGoLive',
      businessKey: 'SKU-1',
      candidateGroup: ['BENEFIT_SKU_REVIEWER'],
    })))
    expect(await screen.findByText('SKU-1')).toBeInTheDocument()
    expect(screen.queryByText('90003')).not.toBeInTheDocument()
    expect(screen.getByText(/SKU 上线待办/)).toBeInTheDocument()
  })
})
