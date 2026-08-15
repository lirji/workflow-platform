import { apiClient } from './client'
import type { DeadLetterJobView, ProcessDefinitionView, ProcessInstanceView } from './types'

// 运维实例/作业(需 ADMIN,鉴权启用时)。租户头由 apiClient 单点注入,勿在此手动加。

export interface FindInstancesParams {
  definitionKey?: string
  /** WAITING_USER/WAITING_BUSINESS/COMPLETED/INCIDENT/CANCELLED;'INCIDENT' 即 incident 快捷。 */
  phase?: string
  limit?: number
}

/** 列实例。GET /api/v1/admin/instances */
export async function findInstances(params: FindInstancesParams): Promise<ProcessInstanceView[]> {
  const { data } = await apiClient.get<ProcessInstanceView[]>('/api/v1/admin/instances', {
    params: { definitionKey: params.definitionKey, phase: params.phase, limit: params.limit ?? 100 },
  })
  return data
}

/** 挂起实例(可逆)。POST /api/v1/admin/instances/{id}/suspend */
export async function suspendInstance(id: string): Promise<void> {
  await apiClient.post(`/api/v1/admin/instances/${encodeURIComponent(id)}/suspend`)
}

/** 恢复实例(可逆)。POST /api/v1/admin/instances/{id}/activate */
export async function activateInstance(id: string): Promise<void> {
  await apiClient.post(`/api/v1/admin/instances/${encodeURIComponent(id)}/activate`)
}

/** 终止实例(不可逆,置 CANCELLED)。POST /api/v1/admin/instances/{id}/terminate?reason= */
export async function terminateInstance(id: string, reason?: string): Promise<void> {
  await apiClient.post(`/api/v1/admin/instances/${encodeURIComponent(id)}/terminate`, null, {
    params: { reason },
  })
}

/** 列 Flowable 死信作业。GET /api/v1/admin/jobs/dead-letter */
export async function findDeadLetterJobs(limit = 100): Promise<DeadLetterJobView[]> {
  const { data } = await apiClient.get<DeadLetterJobView[]>('/api/v1/admin/jobs/dead-letter', {
    params: { limit },
  })
  return data
}

/** 重试死信作业(移回可执行队列,异步重执行)。POST /api/v1/admin/jobs/{jobId}/retry?retries= */
export async function retryJob(jobId: string, retries = 3): Promise<void> {
  await apiClient.post(`/api/v1/admin/jobs/${encodeURIComponent(jobId)}/retry`, null, {
    params: { retries },
  })
}

// ---- 流程定义部署/版本管理(Option B) ----

/** 列流程定义。GET /api/v1/admin/definitions */
export async function listDefinitions(): Promise<ProcessDefinitionView[]> {
  const { data } = await apiClient.get<ProcessDefinitionView[]>('/api/v1/admin/definitions')
  return data
}

/** 部署 BPMN XML。POST /api/v1/admin/definitions/deploy */
export async function deployDefinition(name: string, bpmnXml: string): Promise<ProcessDefinitionView> {
  const { data } = await apiClient.post<ProcessDefinitionView>('/api/v1/admin/definitions/deploy', { name, bpmnXml })
  return data
}

/** 挂起定义(该定义不可再发起新实例)。POST /api/v1/admin/definitions/{id}/suspend */
export async function suspendDefinition(id: string): Promise<void> {
  await apiClient.post(`/api/v1/admin/definitions/${encodeURIComponent(id)}/suspend`)
}

/** 恢复定义。POST /api/v1/admin/definitions/{id}/activate */
export async function activateDefinition(id: string): Promise<void> {
  await apiClient.post(`/api/v1/admin/definitions/${encodeURIComponent(id)}/activate`)
}
