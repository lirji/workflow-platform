import { useCallback, useEffect, useRef, useState } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { completeReview, findTasks, type FindTasksParams } from '../api/tasks'
import type { CompleteReviewRequest } from '../api/types'

export const TASKS_KEY = 'tasks'

/** 待办列表查询(候选组服务端过滤)。 */
export function useTasks(params: FindTasksParams) {
  return useQuery({
    queryKey: [TASKS_KEY, params],
    queryFn: () => findTasks(params),
    placeholderData: keepPreviousData,
  })
}

/** 办理 mutation:成功后 invalidate 待办列表(不做删除式乐观更新,避免伪装已完成)。 */
export function useCompleteReview() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ taskId, body }: { taskId: string; body: CompleteReviewRequest }) => completeReview(taskId, body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: [TASKS_KEY] })
    },
  })
}

/**
 * 办理后爆发轮询:短时间内高频 invalidate 待办列表以追最终一致(Kafka),到期停止;
 * 页面不可见(document.hidden)时跳过该次 invalidate(载荷退避,评审 B8)。
 */
export function useTaskListSync(opts?: { totalMs?: number; intervalMs?: number }) {
  const qc = useQueryClient()
  const [active, setActive] = useState(false)
  const timer = useRef<number | null>(null)
  const deadline = useRef(0)
  const totalMs = opts?.totalMs ?? 20_000
  const intervalMs = opts?.intervalMs ?? 2_500

  const stop = useCallback(() => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current)
      timer.current = null
    }
    setActive(false)
  }, [])

  const tick = useCallback(() => {
    if (Date.now() >= deadline.current) {
      stop()
      return
    }
    if (document.visibilityState === 'visible') {
      void qc.invalidateQueries({ queryKey: [TASKS_KEY] })
    }
    timer.current = window.setTimeout(tick, intervalMs)
  }, [qc, stop, intervalMs])

  const start = useCallback(() => {
    deadline.current = Date.now() + totalMs
    setActive(true)
    if (timer.current !== null) window.clearTimeout(timer.current)
    timer.current = window.setTimeout(tick, intervalMs)
  }, [tick, totalMs, intervalMs])

  useEffect(() => () => stop(), [stop])
  return { active, start, stop }
}
