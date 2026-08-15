import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { DlqStatusTag } from './DlqStatusTag'
import { SuspendTag } from './SuspendTag'

describe('DlqStatusTag', () => {
  it('NEW → 待重放,REPLAYED → 已重放,不出现误导词', () => {
    const { container: c1 } = render(<DlqStatusTag status="NEW" />)
    expect(screen.getByText('待重放')).toBeInTheDocument()
    const { container: c2 } = render(<DlqStatusTag status="REPLAYED" />)
    expect(screen.getByText('已重放')).toBeInTheDocument()
    expect(c1.textContent).not.toContain('已完成')
    expect(c2.textContent).not.toContain('已完成')
  })
})

describe('SuspendTag', () => {
  it('挂起时渲染,未挂起不渲染', () => {
    const { container } = render(<SuspendTag suspended={false} />)
    expect(container.textContent).toBe('')
    render(<SuspendTag suspended={true} />)
    expect(screen.getByText('已挂起')).toBeInTheDocument()
  })
})
