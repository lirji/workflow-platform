import { afterEach, describe, expect, it, vi } from 'vitest'
import { useWorkbenchStore } from '../store/workbenchStore'
import { config } from '../config'
import { apiClient } from './client'

describe('apiClient tenant header', () => {
  afterEach(() => {
    useWorkbenchStore.getState().clearFilters()
    apiClient.defaults.adapter = undefined
  })

  it('sends workbench tenant and does not change it when definitionKey changes', async () => {
    const tenant = config.workflowTenant
    useWorkbenchStore.getState().setFilters({ definitionKey: 'benefitSkuGoLive' })
    expect(useWorkbenchStore.getState().tenantId).toBe(tenant)

    const adapter = vi.fn(async (cfg) => ({
      data: {},
      status: 200,
      statusText: 'OK',
      headers: {},
      config: cfg,
    }))
    apiClient.defaults.adapter = adapter
    await apiClient.get('/health-probe')
    expect(adapter).toHaveBeenCalled()
    const hdrs = adapter.mock.calls[0][0].headers
    expect(hdrs['X-Workflow-Tenant']).toBe(tenant)
  })
})
