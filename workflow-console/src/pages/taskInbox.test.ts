import { describe, expect, it } from 'vitest'
import { inboxCandidateGroups, inboxCopy, isReviewTask } from './taskInbox'

describe('taskInbox', () => {
  it('does not invent a default HIS definition or PHARMACIST group', () => {
    expect(inboxCandidateGroups([], false)).toBeUndefined()
    expect(inboxCandidateGroups(['PHARMACIST'], false)).toBeUndefined()
    expect(inboxCopy(undefined).businessKeyLabel).toBe('业务键')
  })

  it('maps review tasks and authenticated group union', () => {
    expect(isReviewTask('pharmacistReview')).toBe(true)
    expect(isReviewTask('skuGoLiveReview')).toBe(true)
    expect(isReviewTask('manualRepair')).toBe(false)
    expect(inboxCandidateGroups(['PHARMACIST', 'BENEFIT_SKU_REVIEWER'], true)).toEqual([
      'PHARMACIST',
      'BENEFIT_SKU_REVIEWER',
    ])
    expect(inboxCopy('benefitSkuGoLive').confirmPass).toBe('确认通过该待办?')
    expect(inboxCopy('hisRxReview').confirmPass).toBe('确认通过审方?')
  })
})
