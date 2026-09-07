import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ConfigProvider, App as AntdApp } from 'antd'
import { QueryClientProvider } from '@tanstack/react-query'
import { makeTestQueryClient } from '../../test/renderWithProviders'
import RecentReviews from './RecentReviews'
import { useProcessPhase } from '../../hooks/useProcess'
import type { ProcessInstanceView } from '../../api/types'

vi.mock('../../hooks/useProcess', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../hooks/useProcess')>()
  return { ...actual, useProcessPhase: vi.fn() }
})

const hisInst: ProcessInstanceView = {
  processInstanceId: 'pi-1',
  tenantId: 'dev-tenant',
  processDefinitionKey: 'hisRxReview',
  businessKey: '90003',
  idempotencyKey: 'c1',
  phase: 'WAITING_BUSINESS',
  status: 'ACTIVE',
  running: true,
  suspended: false,
}

const skuInst: ProcessInstanceView = {
  processInstanceId: 'pi-2',
  tenantId: 'dev-tenant',
  processDefinitionKey: 'benefitSkuGoLive',
  businessKey: 'SKU-1',
  idempotencyKey: 'c2',
  phase: 'COMPLETED',
  status: 'COMPLETED',
  running: false,
  suspended: false,
}

describe('RecentReviews', () => {
  it('queries phase with each row processDefinitionKey', () => {
    vi.mocked(useProcessPhase).mockImplementation((definitionKey: string) => {
      const data = definitionKey === 'benefitSkuGoLive' ? [skuInst] : [hisInst]
      return { data, isFetching: false } as never
    })
    render(
      <ConfigProvider>
        <AntdApp>
          <QueryClientProvider client={makeTestQueryClient()}>
            <RecentReviews
              items={[
                { businessKey: '90003', actionId: 'act-his-1', decision: 'PASS', processDefinitionKey: 'hisRxReview', at: 1 },
                { businessKey: 'SKU-1', actionId: 'act-sku-1', decision: 'PASS', processDefinitionKey: 'benefitSkuGoLive', at: 2 },
              ]}
              onClear={() => {}}
            />
          </QueryClientProvider>
        </AntdApp>
      </ConfigProvider>,
    )
    expect(useProcessPhase).toHaveBeenCalledWith('hisRxReview', '90003')
    expect(useProcessPhase).toHaveBeenCalledWith('benefitSkuGoLive', 'SKU-1')
    expect(screen.getByText('处理中')).toBeInTheDocument()
    expect(screen.getByText('已落地')).toBeInTheDocument()
  })
})
