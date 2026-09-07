import { useMemo, type ReactNode } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Card, Input, Space, Timeline, Typography } from 'antd'
import { PageHeader } from '../components/layout/PageHeader'
import { EmptyState, ErrorState, PageSkeleton } from '../components/common/AsyncState'
import { PhaseTag } from '../components/domain/PhaseTag'
import BpmnViewer, { type BpmnHighlights } from '../components/bpmn/BpmnViewer'
import { getDefinitionXml } from '../api/process'
import { newestProcessInstance, useProcessPhase, useTimeline } from '../hooks/useProcess'
import { errMsg } from '../api/errors'
import type { TimelineEntry } from '../api/types'
import { businessKeyLabel, definitionLabel } from '../workbench/definitionLabel'
import { useWorkbenchStore } from '../store/workbenchStore'
import { useWorkbenchUrl } from '../workbench/useWorkbenchUrl'

/**
 * 流程轨迹只读页。无定义时请先选择；带 ?businessKey= 则叠加实例轨迹。
 */
export default function ProcessTracePage() {
  const { key } = useParams()
  const { filters, replaceFilters } = useWorkbenchUrl()
  const tenantId = useWorkbenchStore((s) => s.tenantId)
  const definitionKey = key
  const businessKey = filters.businessKey

  const instQuery = useProcessPhase(definitionKey ?? '', businessKey ?? '', !!definitionKey && !!businessKey)
  const instances = instQuery.data
  const latest = newestProcessInstance(instances)
  const noInstance = !!definitionKey && !!businessKey && instQuery.isSuccess && !latest
  const xmlQuery = useQuery({
    queryKey: ['definition-xml', definitionKey, latest?.processInstanceId ?? 'latest'],
    queryFn: () => getDefinitionXml(definitionKey!, latest?.processInstanceId),
    enabled: !!definitionKey && (!businessKey || (!!latest && instQuery.isSuccess)),
    staleTime: 5 * 60_000,
  })
  const timelineQuery = useTimeline(latest?.processInstanceId)
  const entries = timelineQuery.data ?? []

  const highlights: BpmnHighlights | undefined = useMemo(() => {
    if (!businessKey || entries.length === 0) return undefined
    const completed = entries.filter((e) => e.endEpochMs != null).map((e) => e.activityId)
    const active = entries.filter((e) => e.endEpochMs == null).map((e) => e.activityId)
    const isIncident = latest?.phase === 'INCIDENT'
    return { completed, active: isIncident ? [] : active, incident: isIncident ? active : [] }
  }, [businessKey, entries, latest?.phase])

  const fmt = (v: number | null) => (v ? new Date(v).toLocaleString('zh-CN') : '—')

  let body: ReactNode
  if (!definitionKey)
    body = <EmptyState description="请在工作台选择流程定义后再看轨迹" />
  else if ((businessKey && instQuery.isLoading) || xmlQuery.isLoading || (!!latest && timelineQuery.isLoading))
    body = <PageSkeleton rows={10} />
  else if (businessKey && instQuery.isError)
    body = <ErrorState message={errMsg(instQuery.error, '流程实例拉取失败')} onRetry={() => instQuery.refetch()} />
  else if (noInstance)
    body = <EmptyState description={`本租户 ${tenantId} 下未找到业务键 ${businessKey} 的流程实例`} />
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
        extra={
          definitionKey ? (
            <Input.Search
              allowClear
              style={{ width: 280 }}
              placeholder={`${businessKeyLabel(definitionKey)} 查询实例`}
              defaultValue={businessKey}
              onSearch={(v) => replaceFilters({ businessKey: v.trim() || undefined })}
            />
          ) : null
        }
        description={
          <Space size={8} wrap>
            <span>
              {definitionKey ? `流程 ${definitionLabel(definitionKey)}(只读)` : '未选择流程'} · 租户 {tenantId}
            </span>
            {businessKey && (
              <>
                <span>
                  · {businessKeyLabel(definitionKey)} {businessKey}
                </span>
                <PhaseTag phase={latest?.phase} loading={instQuery.isFetching} />
              </>
            )}
          </Space>
        }
      />
      <Card size="small" styles={{ body: { padding: 0 } }}>
        {body}
      </Card>
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
