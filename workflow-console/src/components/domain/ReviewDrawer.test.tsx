import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import ReviewDrawer from './ReviewDrawer'
import type { TaskView } from '../../api/types'
import { completeReview } from '../../api/tasks'

vi.mock('../../api/tasks', () => ({ completeReview: vi.fn() }))
const mockedComplete = vi.mocked(completeReview)

const task: TaskView = {
  taskId: 't-1',
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

const noop = () => {}
afterEach(() => vi.clearAllMocks())

describe('ReviewDrawer 办理', () => {
  it('驳回未填意见被就地拦截,不调用 API', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <ReviewDrawer open task={task} onClose={noop} onSubmitted={noop} onSyncStart={noop} />,
    )
    await user.click(screen.getByText('驳回'))
    await user.click(screen.getByRole('button', { name: /提\s*交/ }))
    await waitFor(() => expect(screen.getByText('驳回必须填写意见')).toBeInTheDocument())
    expect(mockedComplete).not.toHaveBeenCalled()
  })

  it('通过办理成功:显示"已受理",绝不显示"已完成"', async () => {
    mockedComplete.mockResolvedValue({ actionId: 'act-123456789', status: 'PENDING_BUSINESS' })
    const onSubmitted = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(
      <ReviewDrawer open task={task} onClose={noop} onSubmitted={onSubmitted} onSyncStart={noop} />,
    )
    await user.click(screen.getByRole('button', { name: /提\s*交/ }))
    const confirmBtn = await screen.findByRole('button', { name: /确认提交/ })
    await user.click(confirmBtn)

    await waitFor(() =>
      expect(mockedComplete).toHaveBeenCalledWith('t-1', expect.objectContaining({ decision: 'PASS' })),
    )
    await waitFor(() => expect(screen.getByText(/已受理/)).toBeInTheDocument())
    expect(document.body.textContent).not.toContain('已完成')
    expect(onSubmitted).toHaveBeenCalledWith(
      expect.objectContaining({ businessKey: '90003', decision: 'PASS' }),
    )
  })

  it('409 冲突给出友好提示', async () => {
    mockedComplete.mockRejectedValue({ response: { status: 409 } })
    const user = userEvent.setup()
    renderWithProviders(
      <ReviewDrawer open task={task} onClose={noop} onSubmitted={noop} onSyncStart={noop} />,
    )
    await user.click(screen.getByRole('button', { name: /提\s*交/ }))
    const confirmBtn = await screen.findByRole('button', { name: /确认提交/ })
    await user.click(confirmBtn)
    await waitFor(() => expect(screen.getByText(/已被处理或状态已变更/)).toBeInTheDocument())
  })
})
