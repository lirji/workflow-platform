import { useQuery } from '@tanstack/react-query'
import { findProcessInstances, getTimeline } from '../api/process'
import type { ProcessInstanceView } from '../api/types'

// INCIDENT 仍可由人工处置任务推进到 COMPLETED，不能停止追踪。
const TERMINAL = new Set(['COMPLETED', 'CANCELLED'])

/** 后端按 id DESC 返回，第一条才是最新实例。集中封装，避免页面再次取反。 */
export function newestProcessInstance(list: ProcessInstanceView[] | undefined) {
  return list?.[0]
}

/** 按 businessKey 查实例阶段;未到终态则每 3s 轮询(追最终一致),终态停轮询。 */
export function useProcessPhase(definitionKey: string, businessKey: string, enabled = true) {
  return useQuery({
    queryKey: ['process-instances', definitionKey, businessKey],
    queryFn: () => findProcessInstances(definitionKey, businessKey),
    enabled,
    refetchInterval: (q) => {
      const list = q.state.data as ProcessInstanceView[] | undefined
      const latest = newestProcessInstance(list)
      return latest && TERMINAL.has(latest.phase) ? false : 3_000
    },
  })
}

/** 流程实例历史轨迹(轨迹页时间线 + 图上节点高亮)。 */
export function useTimeline(id: string | undefined) {
  return useQuery({
    queryKey: ['timeline', id],
    queryFn: () => getTimeline(id!),
    enabled: !!id,
  })
}
