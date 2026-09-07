import { apiClient } from './client'
import type { CompleteReviewRequest, CompleteReviewResponse, CompleteTaskRequest, CompleteTaskResponse, TaskSearchResult } from './types'

export interface FindTasksParams {
  definitionKey?: string
  businessKey?: string
  candidateGroup?: string[]
  page?: number
  size?: number
}

/**
 * 待办分页查询(候选组服务端过滤 + 分页)。GET /api/v1/tasks/search
 * candidateGroup 为可重复参数(?candidateGroup=PHARMACIST&candidateGroup=ADMIN),需 axios 以重复键序列化。
 */
export async function findTasks(params: FindTasksParams): Promise<TaskSearchResult> {
  const { data } = await apiClient.get<TaskSearchResult>('/api/v1/tasks/search', {
    params: {
      ...(params.definitionKey ? { definitionKey: params.definitionKey } : {}),
      ...(params.businessKey ? { businessKey: params.businessKey } : {}),
      ...(params.candidateGroup?.length ? { candidateGroup: params.candidateGroup } : {}),
      page: params.page ?? 0,
      size: params.size ?? 20,
    },
    // indexes:null → 数组序列化为 candidateGroup=a&candidateGroup=b(而非 candidateGroup[0]=a)。
    paramsSerializer: { indexes: null },
  })
  return data
}

/**
 * 办理审方。POST /api/v1/tasks/{taskId}/complete-review
 * 恒返回 202 + {actionId, status:'PENDING_BUSINESS'} —— 已受理,业务落地经 Kafka 最终一致,不代表已完成。
 */
export async function completeReview(taskId: string, body: CompleteReviewRequest): Promise<CompleteReviewResponse> {
  const { data } = await apiClient.post<CompleteReviewResponse>(
    `/api/v1/tasks/${encodeURIComponent(taskId)}/complete-review`,
    body,
  )
  return data
}

/** 通用办理。POST /api/v1/tasks/{taskId}/complete，202 + PENDING_BUSINESS。 */
export async function completeTask(taskId: string, body: CompleteTaskRequest): Promise<CompleteTaskResponse> {
  const { data } = await apiClient.post<CompleteTaskResponse>(
    `/api/v1/tasks/${encodeURIComponent(taskId)}/complete`,
    body,
  )
  return data
}

/** 认领。POST /api/v1/tasks/{taskId}/claim?userId= */
export async function claimTask(taskId: string, userId: string): Promise<void> {
  await apiClient.post(`/api/v1/tasks/${encodeURIComponent(taskId)}/claim`, null, { params: { userId } })
}

/** 转办。POST /api/v1/tasks/{taskId}/reassign?assignee= */
export async function reassignTask(taskId: string, assignee: string): Promise<void> {
  await apiClient.post(`/api/v1/tasks/${encodeURIComponent(taskId)}/reassign`, null, { params: { assignee } })
}

/** 撤回认领。POST /api/v1/tasks/{taskId}/unclaim */
export async function unclaimTask(taskId: string): Promise<void> {
  await apiClient.post(`/api/v1/tasks/${encodeURIComponent(taskId)}/unclaim`)
}
