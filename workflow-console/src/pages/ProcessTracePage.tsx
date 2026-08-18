import { useMemo, type ReactNode } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Card, Space, Timeline, Typography } from 'antd'
import { PageHeader } from '../components/layout/PageHeader'
import { EmptyState, ErrorState, PageSkeleton } from '../components/common/AsyncState'
import { PhaseTag } from '../components/domain/PhaseTag'
import BpmnViewer, { type BpmnHighlights } from '../components/bpmn/BpmnViewer'
import { getDefinitionXml } from '../api/process'
import { newestProcessInstance, useProcessPhase, useTimeline } from '../hooks/useProcess'
import { errMsg } from '../api/errors'
import type { TimelineEntry } from '../api/types'

/**
 * 流程轨迹只读页(懒加载)。默认渲染定义图;若带 ?businessKey= 则叠加该实例的轨迹高亮 + 时间线。
 */
export default function ProcessTracePage() {
  const { key = 'hisRxReview' } = useParams()
  const [sp] = useSearchParams()
  const businessKey = sp.get('businessKey') ?? undefined

  const instQuery = useProcessPhase(key, businessKey ?? '', !!businessKey)
  const instances = instQuery.data
  const latest = newestProcessInstance(instances)
  const noInstance = !!businessKey && instQuery.isSuccess && !latest
  const xmlQuery = useQuery({
    queryKey: ['definition-xml', key, latest?.processInstanceId ?? 'latest'],
    queryFn: () => getDefinitionXml(key, latest?.processInstanceId),
    // 带 businessKey 时先确定实例，避免先渲染最新定义再闪换成历史版本。
    enabled: !businessKey || (!!latest && instQuery.isSuccess),
    staleTime: 5 * 60_000,
  })
  const timelineQuery = useTimeline(latest?.processInstanceId)
  const entries = timelineQuery.data ?? []

  // 轨迹 → 图上高亮:已结束节点=已走;进行中(endEpochMs==null)=当前;实例 INCIDENT 时当前节点标异常。
  const highlights: BpmnHighlights | undefined = useMemo(() => {
    if (!businessKey || entries.length === 0) return undefined
    const completed = entries.filter((e) => e.endEpochMs != null).map((e) => e.activityId)
    const active = entries.filter((e) => e.endEpochMs == null).map((e) => e.activityId)
    const isIncident = latest?.phase === 'INCIDENT'
    return { completed, active: isIncident ? [] : active, incident: isIncident ? active : [] }
  }, [businessKey, entries, latest?.phase])

  const fmt = (v: number | null) => (v ? new Date(v).toLocaleString('zh-CN') : '—')

  let body: ReactNode
  if ((businessKey && instQuery.isLoading) || xmlQuery.isLoading || (!!latest && timelineQuery.isLoading))
    body = <PageSkeleton rows={10} />
  else if (businessKey && instQuery.isError)
    body = <ErrorState message={errMsg(instQuery.error, '流程实例拉取失败')} onRetry={() => instQuery.refetch()} />
  else if (noInstance) body = <EmptyState description={`未找到业务键 ${businessKey} 的流程实例`} />
  else if (xmlQuery.isError)
    body = <ErrorState message={errMsg(xmlQuery.error, '流程定义 XML 拉取失败')} onRetry={() => xmlQuery.refetch()} />
  else if (timelineQuery.isError)
    body = <ErrorState message={errMsg(timelineQuery.error, '流程办理轨迹拉取失败')} onRetry={() => timelineQuery.refetch()} />
  else if (!xmlQuery.data) body = <EmptyState description="无流程图" />
  else body = <BpmnViewer xml={xmlQuery.data} highlights={highlights} />

  return (
    <>
      <PageHeader
        title="流程轨迹"
        description={
          <Space size={8} wrap>
            <span>流程定义 {key}(只读)</span>
            {businessKey && (
              <>
                <span>· 就诊 {businessKey}</span>
                <PhaseTag phase={latest?.phase} loading={instQuery.isFetching} />
              </>
            )}
          </Space>
        }
      />
      <Card size="small" styles={{ body: { padding: 0 } }}>{body}</Card>
      {businessKey && !timelineQuery.isError && entries.length > 0 && (
        <Card size="small" title="办理轨迹" style={{ marginTop: 16 }}>
          <Timeline
            items={entries.map((e: TimelineEntry) => ({
              color: e.endEpochMs == null ? 'blue' : 'green',
              children: (
                <Space direction="vertical" size={0}>
                  <Typography.Text strong>{e.activityName || e.activityId}</Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {e.activityType}
                    {e.assignee ? ` · ${e.assignee}` : ''} · {fmt(e.startEpochMs)}
                    {e.endEpochMs == null ? ' · 进行中' : ` → ${fmt(e.endEpochMs)}`}
                  </Typography.Text>
                </Space>
              ),
            }))}
          />
        </Card>
      )}
    </>
  )
}
