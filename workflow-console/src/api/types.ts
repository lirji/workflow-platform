// 与 workflow-platform-protocol 的 record 严格对齐(唯一真值源)。字段名 / 可空性照抄后端 record。

/** 待办任务视图。对应 protocol TaskView。 */
export interface TaskView {
  taskId: string
  taskDefinitionKey: string
  name: string
  processInstanceId: string
  processDefinitionKey: string
  businessKey: string
  tenantId: string
  assignee: string | null
  candidateGroups: string[]
  createTimeEpochMs: number | null
}

/** 待办分页查询结果。对应 protocol TaskSearchResult(GET /api/v1/tasks/search)。 */
export interface TaskSearchResult {
  items: TaskView[]
  total: number
  page: number
  size: number
}

/** 审方决定。 */
export type ReviewDecision = 'PASS' | 'REJECT'

/** 办理请求。对应 protocol CompleteReviewRequest。actor 三字段 shadow 模式由前端/消费方传入。 */
export interface CompleteReviewRequest {
  decision: ReviewDecision
  opinion?: string | null
  actorSub?: string | null
  actorUsername?: string | null
  actorDisplayName?: string | null
}

/** 办理响应(HTTP 202)。TaskController 返回 {actionId, status:'PENDING_BUSINESS'}——已受理,业务经 Kafka 最终一致。 */
export interface CompleteReviewResponse {
  actionId: string
  status: 'PENDING_BUSINESS'
}

/**
 * 流程实例视图(只读)。对应 protocol ProcessInstanceView。
 * phase ∈ WAITING_USER / WAITING_BUSINESS / COMPLETED / INCIDENT / CANCELLED(中台侧最终一致阶段)。
 */
export interface ProcessInstanceView {
  processInstanceId: string
  tenantId: string
  processDefinitionKey: string
  businessKey: string
  idempotencyKey: string
  phase: string
  status: string
  running: boolean
}

/** 流程轨迹条目(只读)。对应 protocol TimelineEntry(来源 Flowable HistoryService)。 */
export interface TimelineEntry {
  activityId: string
  activityName: string
  activityType: string
  assignee: string | null
  startEpochMs: number | null
  endEpochMs: number | null
}

/** 后端错误 body 约定:{error, message}(评审 C5:前端读 .message 做文案映射,不直吐后端串)。 */
export interface ApiError {
  error?: string
  message?: string
}
