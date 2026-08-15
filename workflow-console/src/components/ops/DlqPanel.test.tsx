import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import DlqPanel from './DlqPanel'
import { listDlq, replayDlq } from '../../api/dlq'
import type { DlqRecord } from '../../api/types'

vi.mock('../../api/dlq', () => ({
  listDlq: vi.fn(),
  replayDlq: vi.fn(),
  replayAllDlq: vi.fn(),
}))
const mockedList = vi.mocked(listDlq)
const mockedReplay = vi.mocked(replayDlq)

const rec: DlqRecord = {
  id: 7,
  originalTopic: 'workflow.command.start.v1',
  msgKey: 'his|hisRxReview|90003',
  payload: '{"eventId":"e1"}',
  errorMessage: 'boom',
  status: 'NEW',
  failedAtEpochMs: 1_700_000_000_000,
  replayedAtEpochMs: null,
}

afterEach(() => vi.clearAllMocks())

describe('DlqPanel 重放流', () => {
  it('重放确认 → 调用 replayDlq(id),提示"已重放",绝不"已完成"', async () => {
    mockedList.mockResolvedValue([rec])
    mockedReplay.mockResolvedValue({ id: 7, status: 'REPLAYED' })
    const user = userEvent.setup()
    renderWithProviders(<DlqPanel />)
    await screen.findByText('workflow.command.start.v1')
    await user.click(screen.getByRole('button', { name: /^重\s*放$/ }))
    await user.click(await screen.findByRole('button', { name: /确认重放/ }))
    await waitFor(() => expect(mockedReplay).toHaveBeenCalledWith(7))
    await waitFor(() => expect(screen.getByText(/已重放,消息已投回/)).toBeInTheDocument())
    expect(document.body.textContent).not.toContain('已完成')
  })

  it('重放 404 → 友好提示"不存在或已重放"', async () => {
    mockedList.mockResolvedValue([rec])
    mockedReplay.mockRejectedValue({ response: { status: 404 } })
    const user = userEvent.setup()
    renderWithProviders(<DlqPanel />)
    await screen.findByText('workflow.command.start.v1')
    await user.click(screen.getByRole('button', { name: /^重\s*放$/ }))
    await user.click(await screen.findByRole('button', { name: /确认重放/ }))
    await waitFor(() => expect(screen.getByText(/不存在或已重放/)).toBeInTheDocument())
  })
})
