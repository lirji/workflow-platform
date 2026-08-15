import { apiClient } from './client'
import type { CompleteReviewRequest, CompleteReviewResponse, TaskSearchResult } from './types'

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
      definitionKey: params.definitionKey,
      businessKey: params.businessKey,
      candidateGroup: params.candidateGroup,
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
