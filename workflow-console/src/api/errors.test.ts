import { describe, expect, it } from 'vitest'
import { opErrorText, statusOf } from './errors'

const err = (status: number, message?: string) => ({ response: { status, data: message ? { message } : {} } })

describe('opErrorText 运维错误映射', () => {
  it('403 → 越权文案', () => {
    expect(opErrorText(err(403))).toContain('无权限')
  })
  it('5xx → 中性兜底(不吐后端串)', () => {
    expect(opErrorText(err(500, 'NullPointer at ...'))).toBe('操作失败,请刷新后重试')
  })
  it('404/409 → 读后端 message', () => {
    expect(opErrorText(err(404, '死信不存在或已重放'))).toBe('死信不存在或已重放')
    expect(opErrorText(err(409, '状态已变更'))).toBe('状态已变更')
  })
  it('statusOf 取状态码', () => {
    expect(statusOf(err(404))).toBe(404)
  })
})
