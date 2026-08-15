import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PhaseTag } from './PhaseTag'

describe('PhaseTag', () => {
  it('阶段映射到诚实文案', () => {
    render(<PhaseTag phase="WAITING_BUSINESS" />)
    expect(screen.getByText('处理中')).toBeInTheDocument()
  })

  it('COMPLETED 呈现"已落地"而非"已完成"(不误导)', () => {
    const { container } = render(<PhaseTag phase="COMPLETED" />)
    expect(screen.getByText('已落地')).toBeInTheDocument()
    expect(container.textContent).not.toContain('已完成')
  })

  it('INCIDENT 呈现"异常"', () => {
    render(<PhaseTag phase="INCIDENT" />)
    expect(screen.getByText('异常')).toBeInTheDocument()
  })
})
