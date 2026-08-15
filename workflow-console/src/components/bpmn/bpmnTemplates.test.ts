import { describe, expect, it } from 'vitest'
import { blankTemplate, validateForDeploy } from './bpmnTemplates'

describe('blankTemplate', () => {
  it('生成含可执行 process + DI 的模板,process id 可控', () => {
    const xml = blankTemplate('hisRefundReview', '退费审核')
    expect(xml).toContain('<bpmn2:process id="hisRefundReview"')
    expect(xml).toContain('isExecutable="true"')
    expect(xml).toContain('BPMNDiagram') // 含图形段,轨迹页/画布可渲染
    const v = validateForDeploy(xml)
    expect(v.ok).toBe(true)
    expect(v.processId).toBe('hisRefundReview')
  })
})

describe('validateForDeploy', () => {
  const wrap = (inner: string) =>
    `<?xml version="1.0"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">${inner}</definitions>`

  it('无可执行 process → 阻断', () => {
    const v = validateForDeploy(wrap('<process id="p1" isExecutable="false"/>'))
    expect(v.ok).toBe(false)
    expect(v.reason).toContain('可执行流程')
  })

  it('多个可执行 process → 阻断', () => {
    const v = validateForDeploy(
      wrap('<process id="p1" isExecutable="true"/><process id="p2" isExecutable="true"/>'),
    )
    expect(v.ok).toBe(false)
    expect(v.reason).toContain('单一流程')
  })

  it('process id 为空 → 阻断', () => {
    const v = validateForDeploy(wrap('<process id="" isExecutable="true"/>'))
    expect(v.ok).toBe(false)
    expect(v.reason).toContain('id')
  })

  it('唯一可执行 process → 通过,返回 id/name', () => {
    const v = validateForDeploy(wrap('<process id="hisRx" name="药师审方" isExecutable="true"/>'))
    expect(v.ok).toBe(true)
    expect(v.processId).toBe('hisRx')
    expect(v.processName).toBe('药师审方')
  })

  it('空串 → 阻断', () => {
    expect(validateForDeploy('').ok).toBe(false)
  })
})
