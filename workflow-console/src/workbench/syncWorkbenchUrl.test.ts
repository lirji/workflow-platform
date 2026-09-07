import { describe, expect, it } from 'vitest'
import { mergeWorkbenchSearch, readWorkbenchFromSearch } from './syncWorkbenchUrl'

describe('syncWorkbenchUrl', () => {
  it('reads and merges without dropping other params', () => {
    const current = new URLSearchParams('definitionKey=hisRxReview&businessKey=90003&tab=instances')
    const next = mergeWorkbenchSearch(current, { opsTab: 'dlq', phase: 'INCIDENT' })
    expect(next.get('definitionKey')).toBe('hisRxReview')
    expect(next.get('businessKey')).toBe('90003')
    expect(next.get('tab')).toBe('dlq')
    expect(next.get('phase')).toBe('INCIDENT')
  })

  it('treats blank definitionKey as all processes', () => {
    expect(readWorkbenchFromSearch(new URLSearchParams('')).definitionKey).toBeUndefined()
  })
})
