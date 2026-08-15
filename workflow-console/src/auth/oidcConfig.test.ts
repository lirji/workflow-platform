import { describe, expect, it } from 'vitest'
import { groupsFromToken, normalizeGroup } from './oidcConfig'

describe('normalizeGroup', () => {
  it('大写化并去掉路径段与 <org>_ 前缀', () => {
    expect(normalizeGroup('PHARMACIST')).toBe('PHARMACIST')
    expect(normalizeGroup('pharmacist')).toBe('PHARMACIST')
    expect(normalizeGroup('org/PHARMACIST')).toBe('PHARMACIST')
    expect(normalizeGroup('his_PHARMACIST')).toBe('PHARMACIST')
    expect(normalizeGroup('casdoor/his_admin')).toBe('ADMIN')
  })
})

describe('groupsFromToken', () => {
  const makeToken = (payload: object) => {
    // btoa 输出标准 base64;groupsFromToken 内部做 url-safe 还原 + 补齐,ASCII 载荷安全。
    const b64 = btoa(JSON.stringify(payload))
    return `header.${b64}.sig`
  }

  it('从 JWT payload 解出 groups 并取路径末段', () => {
    const token = makeToken({ groups: ['built-in/PHARMACIST', 'ADMIN'] })
    expect(groupsFromToken(token)).toEqual(['PHARMACIST', 'ADMIN'])
  })

  it('无 token 或无 groups 返回空数组', () => {
    expect(groupsFromToken(undefined)).toEqual([])
    expect(groupsFromToken(makeToken({}))).toEqual([])
  })
})
