import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Alert, App, Button, Grid, Input, Modal, Space, Table, Tag } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { PageHeader } from '../components/layout/PageHeader'
import { EmptyState, ErrorState, PageSkeleton } from '../components/common/AsyncState'
import ReviewDrawer from '../components/domain/ReviewDrawer'
import CompleteTaskDrawer from '../components/domain/CompleteTaskDrawer'
import RecentReviews from '../components/domain/RecentReviews'
import TaskCard from '../components/domain/TaskCard'
import { TaskTypeTag } from '../components/domain/TaskTypeTag'
import { PhaseLegend } from '../components/domain/PhaseLegend'
import { useClaimTask, useReassignTask, useTaskListSync, useTasks, useUnclaimTask } from '../hooks/useTasks'
import { useUiStore } from '../store/uiStore'
import { useAuthStore } from '../store/authStore'
import { useWorkbenchStore } from '../store/workbenchStore'
import { normalizeGroup } from '../auth/oidcConfig'
import { errMsg, opErrorText } from '../api/errors'
import { config } from '../config'
import type { TaskView } from '../api/types'
import { inboxCandidateGroups, inboxCopy, isReviewTask } from './taskInbox'
import { businessKeyLabel } from '../workbench/definitionLabel'
import { useWorkbenchUrl } from '../workbench/useWorkbenchUrl'

const PAGE_SIZE = 20
const EMPTY_TASKS: TaskView[] = []

export default function TasksPage() {
  const { message } = App.useApp()
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.lg
  const navigate = useNavigate()
  const { taskId } = useParams()
  const { filters, replaceFilters } = useWorkbenchUrl()
  const tenantId = useWorkbenchStore((s) => s.tenantId)
  const rememberDefinitionKeys = useWorkbenchStore((s) => s.rememberDefinitionKeys)
  const definitionKey = filters.definitionKey
  const businessKey = filters.businessKey
  const page = filters.page ?? 0
  const size = filters.size ?? PAGE_SIZE
  const authorities = useAuthStore((s) => s.authorities)
  const userId = useAuthStore((s) => s.userId)
  const candidateGroup = useMemo(
    () => inboxCandidateGroups(authorities.map(normalizeGroup), config.authEnabled),
    [authorities],
  )
  const [selected, setSelected] = useState<TaskView | null>(null)
  const [reviewOpen, setReviewOpen] = useState(false)
  const [completeOpen, setCompleteOpen] = useState(false)
  const [reassignTarget, setReassignTarget] = useState<TaskView | null>(null)
  const [assigneeDraft, setAssigneeDraft] = useState('')
  const recent = useUiStore((s) => s.recent)
  const addRecent = useUiStore((s) => s.addRecent)
  const clearRecent = useUiStore((s) => s.clearRecent)
  const copy = inboxCopy(definitionKey)

  const query = useTasks({ definitionKey, businessKey, candidateGroup, page, size })
  const sync = useTaskListSync()
  const claimMut = useClaimTask()
  const unclaimMut = useUnclaimTask()
  const reassignMut = useReassignTask()

  const rows = query.data?.items ?? EMPTY_TASKS

  useEffect(() => {
    rememberDefinitionKeys(rows.map((t) => t.processDefinitionKey))
  }, [rows, rememberDefinitionKeys])

  const openedDeepLink = useRef<string>()
  useEffect(() => {
    if (!taskId || !query.isSuccess) return
    if (openedDeepLink.current === taskId) return
    const hit = query.data?.items.find((t) => t.taskId === taskId)
    if (hit) {
      openedDeepLink.current = taskId
      openTask(hit)
    }
  }, [taskId, query.isSuccess, query.data])

  const openTask = (t: TaskView) => {
    setSelected(t)
    if (isReviewTask(t.taskDefinitionKey)) {
      setReviewOpen(true)
      setCompleteOpen(false)
    } else {
      setCompleteOpen(true)
      setReviewOpen(false)
    }
  }

  const claimActor = userId || (config.authEnabled ? undefined : 'dev')

  const doClaim = async (t: TaskView) => {
    if (!claimActor) {
      message.warning('当前会话没有用户,无法认领')
      return
    }
    try {
      await claimMut.mutateAsync({ taskId: t.taskId, userId: claimActor })
      message.success('已认领')
    } catch (e) {
      message.error(opErrorText(e))
    }
  }

  const doUnclaim = async (t: TaskView) => {
    try {
      await unclaimMut.mutateAsync(t.taskId)
      message.success('已撤回认领')
    } catch (e) {
      message.error(opErrorText(e))
    }
  }

  const doReassign = async () => {
    const assignee = assigneeDraft.trim()
    if (!reassignTarget || !assignee) return
    try {
      await reassignMut.mutateAsync({ taskId: reassignTarget.taskId, assignee })
      message.success('已转办')
      setReassignTarget(null)
      setAssigneeDraft('')
    } catch (e) {
      message.error(opErrorText(e))
    }
  }

  const columns: ColumnsType<TaskView> = [
    { title: `${businessKeyLabel(definitionKey)}(businessKey)`, dataIndex: 'businessKey', render: (v: string) => <span className="mono">{v}</span> },
    { title: '流程', dataIndex: 'processDefinitionKey' },
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
    { title: '办理人', dataIndex: 'assignee', render: (v: string | null) => v || '—' },
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
      width: 260,
      render: (_: unknown, r) => (
        <Space size={4} wrap>
          <Button type="link" onClick={() => openTask(r)}>
            办理
          </Button>
          <Button
            type="link"
            onClick={() =>
              navigate(`/process/${encodeURIComponent(r.processDefinitionKey)}?businessKey=${encodeURIComponent(r.businessKey)}`)
            }
          >
            轨迹
          </Button>
          {!r.assignee ? (
            <Button type="link" onClick={() => void doClaim(r)} loading={claimMut.isPending}>
              认领
            </Button>
          ) : (
            <>
              <Button type="link" onClick={() => void doUnclaim(r)} loading={unclaimMut.isPending}>
                撤回
              </Button>
              <Button
                type="link"
                onClick={() => {
                  setReassignTarget(r)
                  setAssigneeDraft('')
                }}
              >
                转办
              </Button>
            </>
          )}
        </Space>
      ),
    },
  ]

  const missingDeepLink = !!taskId && query.isSuccess && !rows.some((t) => t.taskId === taskId)
  const hasFilters = !!definitionKey || !!businessKey

  let body: ReactNode
  if (query.isLoading) body = <PageSkeleton />
  else if (query.isError) body = <ErrorState message={errMsg(query.error)} onRetry={() => query.refetch()} />
  else if (rows.length === 0) {
    body = (
      <EmptyState
        description={`当前租户 ${tenantId} 下没有待办。请核对租户或清除筛选。`}
        extra={
          hasFilters ? (
            <Button onClick={() => replaceFilters({ definitionKey: undefined, businessKey: undefined, page: undefined })}>
              清除筛选
            </Button>
          ) : undefined
        }
      />
    )
  } else if (isMobile)
    body = (
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {rows.map((t) => (
          <TaskCard key={t.taskId} task={t} onReview={openTask} />
        ))}
      </Space>
    )
  else
    body = (
      <Table
        rowKey="taskId"
        columns={columns}
        dataSource={rows}
        scroll={{ x: 960 }}
        pagination={{
          current: page + 1,
          pageSize: size,
          total: query.data?.total ?? 0,
          showSizeChanger: true,
          onChange: (p, s) => replaceFilters({ page: p - 1, size: s }),
        }}
      />
    )

  return (
    <>
      <PageHeader
        title="待办中心"
        description={`${copy.pageDescription} · 租户 ${tenantId} · 当前会话租户下全部流程 · 待办理 → 处理中(等业务 ACK) → 已落地`}
        extra={
          <Button icon={<ReloadOutlined />} onClick={() => query.refetch()} loading={query.isFetching}>
            刷新
          </Button>
        }
      />
      <PhaseLegend />
      {missingDeepLink && (
        <Alert type="warning" showIcon style={{ marginBottom: 16 }} message="未找到该待办或无权查看" description={`taskId ${taskId}`} />
      )}
      {recent.length > 0 && <RecentReviews items={recent} onClear={clearRecent} />}
      {body}
      <ReviewDrawer
        open={reviewOpen}
        task={selected}
        onClose={() => setReviewOpen(false)}
        onSubmitted={(r) => addRecent(r)}
        onSyncStart={() => sync.start()}
      />
      <CompleteTaskDrawer
        open={completeOpen}
        task={selected}
        onClose={() => setCompleteOpen(false)}
        onSubmitted={(r) => addRecent(r)}
        onSyncStart={() => sync.start()}
      />
      <Modal
        title="转办"
        open={!!reassignTarget}
        okText="确认转办"
        cancelText="取消"
        confirmLoading={reassignMut.isPending}
        onOk={() => void doReassign()}
        onCancel={() => setReassignTarget(null)}
      >
        <Input
          placeholder="新办理人 userId"
          value={assigneeDraft}
          onChange={(e) => setAssigneeDraft(e.target.value)}
        />
      </Modal>
    </>
  )
}
