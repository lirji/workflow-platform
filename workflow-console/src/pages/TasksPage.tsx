import { useMemo, useState, type ReactNode } from 'react'
import { Button, Grid, Space, Table, Tag } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { PageHeader } from '../components/layout/PageHeader'
import { EmptyState, ErrorState, PageSkeleton } from '../components/common/AsyncState'
import ReviewDrawer from '../components/domain/ReviewDrawer'
import RecentReviews from '../components/domain/RecentReviews'
import TaskCard from '../components/domain/TaskCard'
import { TaskTypeTag } from '../components/domain/TaskTypeTag'
import { useTasks, useTaskListSync } from '../hooks/useTasks'
import { useUiStore } from '../store/uiStore'
import { useAuthStore } from '../store/authStore'
import { normalizeGroup } from '../auth/oidcConfig'
import { errMsg } from '../api/errors'
import { config } from '../config'
import type { TaskView } from '../api/types'

const DEFINITION_KEY = 'hisRxReview'

/** 候选组:dev 默认药师视角;Stage 2 取当前用户归一化后的 PHARMACIST/ADMIN。 */
function useCandidateGroups(): string[] {
  const authorities = useAuthStore((s) => s.authorities)
  return useMemo(() => {
    if (!config.authEnabled) return ['PHARMACIST']
    return authorities.map(normalizeGroup).filter((g) => g === 'PHARMACIST' || g === 'ADMIN')
  }, [authorities])
}

export default function TasksPage() {
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.lg
  const candidateGroup = useCandidateGroups()
  const [selected, setSelected] = useState<TaskView | null>(null)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const recent = useUiStore((s) => s.recent)
  const addRecent = useUiStore((s) => s.addRecent)
  const clearRecent = useUiStore((s) => s.clearRecent)

  const query = useTasks({ definitionKey: DEFINITION_KEY, candidateGroup, page: 0, size: 50 })
  const sync = useTaskListSync()

  // 评审 B6:本轮只显示 pharmacistReview(manualRepair/ADMIN 默认过滤到管理员视图)。
  const rows = useMemo(
    () => (query.data?.items ?? []).filter((t) => t.taskDefinitionKey === 'pharmacistReview'),
    [query.data],
  )

  const openReview = (t: TaskView) => {
    setSelected(t)
    setDrawerOpen(true)
  }

  const columns: ColumnsType<TaskView> = [
    { title: '就诊(businessKey)', dataIndex: 'businessKey', render: (v: string) => <span className="mono">{v}</span> },
    {
      title: '任务',
      dataIndex: 'taskDefinitionKey',
      render: (_: unknown, r) => (
        <Space>
          <TaskTypeTag taskDefinitionKey={r.taskDefinitionKey} />
          {r.name}
        </Space>
      ),
    },
    { title: '候选组', dataIndex: 'candidateGroups', render: (g: string[]) => g?.map((x) => <Tag key={x}>{x}</Tag>) },
    {
      title: '创建时间',
      dataIndex: 'createTimeEpochMs',
      render: (v: number | null) => (v ? new Date(v).toLocaleString('zh-CN') : '-'),
    },
    {
      title: '操作',
      key: 'op',
      fixed: 'right',
      render: (_: unknown, r) => (
        <Button type="link" onClick={() => openReview(r)}>
          办理
        </Button>
      ),
    },
  ]

  let body: ReactNode
  if (query.isLoading) body = <PageSkeleton />
  else if (query.isError) body = <ErrorState message={errMsg(query.error)} onRetry={() => query.refetch()} />
  else if (rows.length === 0) body = <EmptyState description="暂无待办" />
  else if (isMobile)
    body = (
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {rows.map((t) => (
          <TaskCard key={t.taskId} task={t} onReview={openReview} />
        ))}
      </Space>
    )
  else body = <Table rowKey="taskId" columns={columns} dataSource={rows} pagination={false} scroll={{ x: 760 }} />

  return (
    <>
      <PageHeader
        title="待办中心"
        description={`审方待办 · 租户 ${config.workflowTenant} · 候选组 ${candidateGroup.join(' / ') || '—'}`}
        extra={
          <Button icon={<ReloadOutlined />} onClick={() => query.refetch()} loading={query.isFetching}>
            刷新
          </Button>
        }
      />
      {recent.length > 0 && <RecentReviews items={recent} definitionKey={DEFINITION_KEY} onClear={clearRecent} />}
      {body}
      <ReviewDrawer
        open={drawerOpen}
        task={selected}
        onClose={() => setDrawerOpen(false)}
        onSubmitted={(r) => addRecent(r)}
        onSyncStart={() => sync.start()}
      />
    </>
  )
}
