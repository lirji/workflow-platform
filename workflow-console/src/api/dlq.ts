import { apiClient } from './client'
import type { DlqRecord, DlqReplayResult } from './types'

// DLQ(Kafka 死信)运维(需 ADMIN)。跨租户全局(后端不按租户过滤)。

/** 列死信。GET /api/v1/dlq?status=&limit= */
export async function listDlq(status = 'NEW', limit = 100): Promise<DlqRecord[]> {
  const { data } = await apiClient.get<DlqRecord[]>('/api/v1/dlq', { params: { status, limit } })
  return data
}

/**
 * 单条重放(投回原 topic,由原监听幂等消费,异步最终一致)。POST /api/v1/dlq/{id}/replay
 * id 是数字,不 encode。404(不存在/已重放)由 axios reject,调用方按 statusOf===404 处理。
 */
export async function replayDlq(id: number): Promise<DlqReplayResult> {
  const { data } = await apiClient.post<DlqReplayResult>(`/api/v1/dlq/${id}/replay`)
  return data
}

/** 批量重放当前 NEW(后端上限 500)。POST /api/v1/dlq/replay-all */
export async function replayAllDlq(): Promise<{ replayed: number }> {
  const { data } = await apiClient.post<{ replayed: number }>('/api/v1/dlq/replay-all')
  return data
}
