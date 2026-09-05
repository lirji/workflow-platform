import { describe, expect, it } from 'vitest'
import { candidateGroupsFor, inboxCopy, resolveDefinitionKey, visibleTaskKey } from './taskInbox'

describe('taskInbox', () => {
  it('defaults to hisRxReview and keeps HIS copy', () => {
    expect(resolveDefinitionKey(null)).toBe('hisRxReview')
    expect(visibleTaskKey('hisRxReview')).toBe('pharmacistReview')
    expect(inboxCopy('hisRxReview').confirmPass).toBe('确认通过审方?')
  })

  it('maps SKU flow to reviewer group and review task', () => {
    expect(resolveDefinitionKey('benefitSkuGoLive')).toBe('benefitSkuGoLive')
    expect(visibleTaskKey('benefitSkuGoLive')).toBe('skuGoLiveReview')
    expect(candidateGroupsFor('benefitSkuGoLive', ['BENEFIT_SKU_REVIEWER'], true)).toEqual(['BENEFIT_SKU_REVIEWER'])
    expect(candidateGroupsFor('benefitSkuGoLive', [], false)).toEqual(['BENEFIT_SKU_REVIEWER'])
    expect(inboxCopy('benefitSkuGoLive').confirmPass).toBe('确认通过该待办?')
  })
})
