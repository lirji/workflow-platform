import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import InstancesPanel from './InstancesPanel'
import { findInstances, terminateInstance } from '../../api/admin'
import type { ProcessInstanceView } from '../../api/types'

vi.mock('../../api/admin', () => ({
  findInstances: vi.fn(),
  suspendInstance: vi.fn(),
  activateInstance: vi.fn(),
  terminateInstance: vi.fn(),
  findDeadLetterJobs: vi.fn(),
  retryJob: vi.fn(),
}))
const mockedFind = vi.mocked(findInstances)
const mockedTerminate = vi.mocked(terminateInstance)

const row: ProcessInstanceView = {
  processInstanceId: 'pi-1',
  tenantId: 'his',
  processDefinitionKey: 'hisRxReview',
  businessKey: '90003',
  idempotencyKey: 'cyc-1',
  phase: 'WAITING_USER',
  status: 'ACTIVE',
  running: true,
  suspended: false,
}

afterEach(() => vi.clearAllMocks())

describe('InstancesPanel 终止流', () => {
  it('取消确认 → 不调用 terminate', async () => {
    mockedFind.mockResolvedValue([row])
    const user = userEvent.setup()
    renderWithProviders(<InstancesPanel />)
    await screen.findByText('90003')
    await user.click(screen.getByRole('button', { name: /^终\s*止$/ }))
    await screen.findByText('终止不可逆')
    await user.click(screen.getByRole('button', { name: /再想想/ }))
    expect(mockedTerminate).not.toHaveBeenCalled()
  })

  it('reason 未填 → 就地校验拦截,不调用 terminate', async () => {
    mockedFind.mockResolvedValue([row])
    const user = userEvent.setup()
    renderWithProviders(<InstancesPanel />)
    await screen.findByText('90003')
    await user.click(screen.getByRole('button', { name: /^终\s*止$/ }))
    await screen.findByText('终止不可逆')
    await user.click(screen.getByRole('button', { name: /确认终止/ }))
    await waitFor(() => expect(screen.getByText('请填写终止原因')).toBeInTheDocument())
    expect(mockedTerminate).not.toHaveBeenCalled()
  })

  it('填 reason 确认 → 以 (pid,reason) 调用,且不显示"已完成"', async () => {
    mockedFind.mockResolvedValue([row])
    mockedTerminate.mockResolvedValue()
    const user = userEvent.setup()
    renderWithProviders(<InstancesPanel />)
    await screen.findByText('90003')
    await user.click(screen.getByRole('button', { name: /^终\s*止$/ }))
    await screen.findByText('终止不可逆')
    await user.type(screen.getByPlaceholderText(/必填/), '误发起')
    await user.click(screen.getByRole('button', { name: /确认终止/ }))
    await waitFor(() => expect(mockedTerminate).toHaveBeenCalledWith('pi-1', '误发起'))
    expect(document.body.textContent).not.toContain('已完成')
  })
})
