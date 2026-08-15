import { describe, expect, it, vi } from 'vitest'

// 隔离重量级/浏览器态依赖,只测本模块纯逻辑(getGroups 形状 + read/write)。
vi.mock('@bpmn-io/properties-panel', () => ({
  TextFieldEntry: () => null,
  isTextFieldEntryEdited: () => false,
}))
vi.mock('bpmn-js-properties-panel', () => ({ useService: () => ({}) }))
vi.mock('bpmn-js/lib/util/ModelUtil', () => ({
  is: (el: { businessObject?: { $instanceOf?: (t: string) => boolean } }, type: string) =>
    el?.businessObject?.$instanceOf?.(type) ?? false,
}))

import { getFlowableGroups, readAttr, writeAttr } from './flowablePropertiesProvider'

// 伪造 businessObject.$type,复刻 bpmn-js is() 的判定(is 走 $type / 继承;此处任务类型直接匹配)。
const fakeElement = (type: string, attrs: Record<string, string> = {}) => ({
  businessObject: {
    $type: type,
    $instanceOf: (t: string) => t === type,
    get: (k: string) => attrs[k],
  },
})

describe('getFlowableGroups', () => {
  it('UserTask → 候选组分组(candidateGroups/assignee)', () => {
    const groups = getFlowableGroups(fakeElement('bpmn:UserTask'))
    expect(groups).toHaveLength(1)
    expect(groups[0].id).toBe('flowable-userTask')
    const entryIds = groups[0].entries.map((e: { id: string }) => e.id)
    expect(entryIds).toContain('flowable-candidateGroups')
    expect(entryIds).toContain('flowable-assignee')
  })

  it('ServiceTask → delegateExpression 分组', () => {
    const groups = getFlowableGroups(fakeElement('bpmn:ServiceTask'))
    expect(groups).toHaveLength(1)
    expect(groups[0].entries[0].id).toBe('flowable-delegateExpression')
  })

  it('StartEvent → 无 Flowable 分组', () => {
    expect(getFlowableGroups(fakeElement('bpmn:StartEvent'))).toHaveLength(0)
  })
})

describe('readAttr / writeAttr', () => {
  it('readAttr 取 businessObject 上的 flowable 属性', () => {
    const el = fakeElement('bpmn:UserTask', { 'flowable:candidateGroups': 'PHARMACIST' })
    expect(readAttr(el, 'flowable:candidateGroups')).toBe('PHARMACIST')
    expect(readAttr(el, 'flowable:assignee')).toBe('') // 缺省→空串
  })

  it('writeAttr 有值 → updateProperties 写入', () => {
    const modeling = { updateProperties: vi.fn() }
    const el = fakeElement('bpmn:UserTask')
    writeAttr(modeling, el, 'flowable:candidateGroups', 'ADMIN')
    expect(modeling.updateProperties).toHaveBeenCalledWith(el, { 'flowable:candidateGroups': 'ADMIN' })
  })

  it('writeAttr 空串 → 写 undefined(删除属性,不留空)', () => {
    const modeling = { updateProperties: vi.fn() }
    const el = fakeElement('bpmn:UserTask')
    writeAttr(modeling, el, 'flowable:candidateGroups', '')
    expect(modeling.updateProperties).toHaveBeenCalledWith(el, { 'flowable:candidateGroups': undefined })
  })
})
