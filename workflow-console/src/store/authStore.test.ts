import { describe, expect, it } from 'vitest'
import { canRead, isAdmin } from './authStore'

describe('组名门控(归一化后匹配 BPMN candidateGroups)', () => {
  it('PHARMACIST 可读、非管理员', () => {
    expect(canRead(['PHARMACIST'])).toBe(true)
    expect(isAdmin(['PHARMACIST'])).toBe(false)
  })

  it('ADMIN 可读且是管理员(含前缀/大小写变体)', () => {
    expect(canRead(['his_admin'])).toBe(true)
    expect(isAdmin(['org/ADMIN'])).toBe(true)
  })

  it('无关组既不可读也非管理员', () => {
    expect(canRead(['viewer', 'guest'])).toBe(false)
    expect(isAdmin([])).toBe(false)
  })
})
