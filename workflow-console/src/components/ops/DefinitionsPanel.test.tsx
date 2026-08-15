import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import DefinitionsPanel from './DefinitionsPanel'
import { deployDefinition, listDefinitions } from '../../api/admin'

vi.mock('../../api/admin', () => ({
  listDefinitions: vi.fn(),
  deployDefinition: vi.fn(),
  suspendDefinition: vi.fn(),
  activateDefinition: vi.fn(),
  findInstances: vi.fn(),
  suspendInstance: vi.fn(),
  activateInstance: vi.fn(),
  terminateInstance: vi.fn(),
  findDeadLetterJobs: vi.fn(),
  retryJob: vi.fn(),
}))
const mockedList = vi.mocked(listDefinitions)
const mockedDeploy = vi.mocked(deployDefinition)

afterEach(() => vi.clearAllMocks())

describe('DefinitionsPanel 部署流', () => {
  it('填名称+XML 部署 → 以 (name,xml) 调用并提示已部署', async () => {
    mockedList.mockResolvedValue([])
    mockedDeploy.mockResolvedValue({
      id: 'demo:1:9', key: 'demo', name: 'demo', version: 1, tenantId: 'his', suspended: false, deploymentId: 'd1',
    })
    const user = userEvent.setup()
    renderWithProviders(<DefinitionsPanel />)
    await screen.findByText('暂无流程定义')

    await user.click(screen.getByRole('button', { name: /部署 BPMN/ }))
    await user.type(screen.getByPlaceholderText(/hisRefundReview/), 'demo')
    await user.type(screen.getByPlaceholderText(/BPMN 2.0 XML/), '<definitions/>')
    await user.click(screen.getByRole('button', { name: /^部\s*署$/ }))

    await waitFor(() => expect(mockedDeploy).toHaveBeenCalledWith('demo', '<definitions/>'))
    await waitFor(() => expect(screen.getByText(/已部署 demo v1/)).toBeInTheDocument())
  })
})
