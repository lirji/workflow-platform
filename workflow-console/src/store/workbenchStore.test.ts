import { describe, expect, it } from 'vitest'
import { useWorkbenchStore, workbenchTenant } from './workbenchStore'
import { config } from '../config'

describe('workbenchStore', () => {
  it('keeps tenantId when definitionKey changes', () => {
    const before = workbenchTenant()
    expect(before).toBe(config.workflowTenant)
    useWorkbenchStore.getState().setFilters({ definitionKey: 'benefitSkuGoLive' })
    expect(workbenchTenant()).toBe(before)
    expect(useWorkbenchStore.getState().definitionKey).toBe('benefitSkuGoLive')
  })
})
