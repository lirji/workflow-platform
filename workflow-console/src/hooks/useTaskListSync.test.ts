import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { useTaskListSync } from './useTasks'

// setup 用 const 推断 spy 类型(避免显式标注与 invalidateQueries 泛型签名不兼容)。
function setup(opts: { totalMs?: number; intervalMs?: number }) {
  const qc = new QueryClient()
  const invalidateSpy = vi.spyOn(qc, 'invalidateQueries').mockResolvedValue(undefined)
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children)
  const view = renderHook(() => useTaskListSync(opts), { wrapper })
  return { invalidateSpy, ...view }
}

describe('useTaskListSync 爆发轮询', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('start 后按 interval 周期性 invalidate,到期自动停', () => {
    const { result, invalidateSpy } = setup({ totalMs: 6_000, intervalMs: 2_000 })
    expect(result.current.active).toBe(false)

    act(() => result.current.start())
    expect(result.current.active).toBe(true)

    act(() => vi.advanceTimersByTime(2_000))
    act(() => vi.advanceTimersByTime(2_000))
    expect(invalidateSpy).toHaveBeenCalled()

    // 超过 totalMs 后自动停止
    act(() => vi.advanceTimersByTime(4_000))
    expect(result.current.active).toBe(false)
  })

  it('stop 立即停止轮询', () => {
    const { result, invalidateSpy } = setup({ totalMs: 60_000, intervalMs: 1_000 })
    act(() => result.current.start())
    act(() => result.current.stop())
    expect(result.current.active).toBe(false)

    invalidateSpy.mockClear()
    act(() => vi.advanceTimersByTime(5_000))
    expect(invalidateSpy).not.toHaveBeenCalled()
  })
})
