import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  activateDefinition,
  activateInstance,
  deployDefinition,
  findDeadLetterJobs,
  findInstances,
  listDefinitions,
  retryJob,
  suspendDefinition,
  suspendInstance,
  terminateInstance,
  type FindInstancesParams,
} from '../api/admin'
import { listDlq, replayAllDlq, replayDlq } from '../api/dlq'

export const INSTANCES_KEY = 'admin-instances'
export const DLJOBS_KEY = 'dead-letter-jobs'
export const DLQ_KEY = 'dlq'
export const DEFS_KEY = 'admin-definitions'

// ---- 查询 ----
export function useInstances(params: FindInstancesParams) {
  return useQuery({
    queryKey: [INSTANCES_KEY, params],
    queryFn: () => findInstances(params),
    placeholderData: keepPreviousData,
  })
}

export function useDeadLetterJobs(limit = 100) {
  return useQuery({
    queryKey: [DLJOBS_KEY, { limit }],
    queryFn: () => findDeadLetterJobs(limit),
    placeholderData: keepPreviousData,
  })
}

export function useDlq(status: string, limit = 100) {
  return useQuery({
    queryKey: [DLQ_KEY, { status, limit }],
    queryFn: () => listDlq(status, limit),
    placeholderData: keepPreviousData,
  })
}

// ---- mutation(成功仅 invalidate 对应列表;异步操作的追一致由面板的 useBurstInvalidate 承担;不做删除式乐观更新)----
export function useSuspendInstance() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => suspendInstance(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: [INSTANCES_KEY] }),
  })
}

export function useActivateInstance() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => activateInstance(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: [INSTANCES_KEY] }),
  })
}

export function useTerminateInstance() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => terminateInstance(id, reason),
    onSuccess: () => qc.invalidateQueries({ queryKey: [INSTANCES_KEY] }),
  })
}

export function useRetryJob() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ jobId, retries }: { jobId: string; retries: number }) => retryJob(jobId, retries),
    onSuccess: () => qc.invalidateQueries({ queryKey: [DLJOBS_KEY] }),
  })
}

export function useReplayDlq() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => replayDlq(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: [DLQ_KEY] }),
  })
}

export function useReplayAllDlq() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => replayAllDlq(),
    onSuccess: () => qc.invalidateQueries({ queryKey: [DLQ_KEY] }),
  })
}

// ---- 流程定义 ----
export function useDefinitions() {
  return useQuery({
    queryKey: [DEFS_KEY],
    queryFn: () => listDefinitions(),
    placeholderData: keepPreviousData,
  })
}

export function useDeployDefinition() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ name, bpmnXml }: { name: string; bpmnXml: string }) => deployDefinition(name, bpmnXml),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: [DEFS_KEY] })
      // 最新定义发生变化；历史实例以 processInstanceId 为 key，失效后仍会取回同一准确版本。
      void qc.invalidateQueries({ queryKey: ['definition-xml'] })
    },
  })
}

export function useSuspendDefinition() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => suspendDefinition(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: [DEFS_KEY] }),
  })
}

export function useActivateDefinition() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => activateDefinition(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: [DEFS_KEY] }),
  })
}
