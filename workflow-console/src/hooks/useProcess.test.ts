import { describe, expect, it } from 'vitest'
import { newestProcessInstance } from './useProcess'
import type { ProcessInstanceView } from '../api/types'

const instance = (id: string): ProcessInstanceView => ({
  processInstanceId: id,
  tenantId: 'his',
  processDefinitionKey: 'hisRxReview',
  businessKey: 'enc-1',
  idempotencyKey: `cycle-${id}`,
  phase: 'WAITING_USER',
  status: 'ACTIVE',
  running: true,
  suspended: false,
})

describe('newestProcessInstance', () => {
  it('uses the first row because backend returns id DESC', () => {
    expect(newestProcessInstance([instance('newest'), instance('oldest')])?.processInstanceId)
      .toBe('newest')
  })

  it('returns undefined for an empty result', () => {
    expect(newestProcessInstance([])).toBeUndefined()
  })
})
