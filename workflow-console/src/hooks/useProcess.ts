import { useQuery } from '@tanstack/react-query'
import { findProcessInstances, getTimeline } from '../api/process'
import type { ProcessInstanceView } from '../api/types'

const TERMINAL = new Set(['COMPLETED', 'INCIDENT', 'CANCELLED'])

/** 按 businessKey 查实例阶段;未到终态则每 3s 轮询(追最终一致),终态停轮询。 */
export function useProcessPhase(definitionKey: string, businessKey: string, enabled = true) {
  return useQuery({
    queryKey: ['process-instances', definitionKey, businessKey],
    queryFn: () => findProcessInstances(definitionKey, businessKey),
    enabled,
    refetchInterval: (q) => {
      const list = q.state.data as ProcessInstanceView[] | undefined
      const latest = list && list[list.length - 1]
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
