import { apiClient } from './client'
import type { ProcessInstanceView, TimelineEntry } from './types'

/** 按 businessKey 查流程实例(只读,含最终一致 phase)。GET /api/v1/process-instances */
export async function findProcessInstances(definitionKey: string, businessKey: string): Promise<ProcessInstanceView[]> {
  const { data } = await apiClient.get<ProcessInstanceView[]>('/api/v1/process-instances', {
    params: { definitionKey, businessKey },
  })
  return data
}

/** 流程历史轨迹(用于轨迹页时间线 + 节点高亮)。GET /api/v1/process-instances/{id}/timeline */
export async function getTimeline(id: string): Promise<TimelineEntry[]> {
  const { data } = await apiClient.get<TimelineEntry[]>(`/api/v1/process-instances/${encodeURIComponent(id)}/timeline`)
  return data
}

/** 取某 key 最新版本 BPMN XML(含 DI),供 bpmn-js 只读渲染。GET /api/v1/definitions/{key}/xml */
export async function getDefinitionXml(key: string): Promise<string> {
  const { data } = await apiClient.get(`/api/v1/definitions/${encodeURIComponent(key)}/xml`, {
    responseType: 'text',
    headers: { Accept: 'application/xml' },
    transformResponse: (d) => d, // 保持原始 XML 字符串,勿被 JSON 解析
  })
  return data as string
}
