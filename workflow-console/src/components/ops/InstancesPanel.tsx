import { useMemo, useState } from 'react'
import { Alert, App, Button, Descriptions, Dropdown, Form, Grid, Input, Modal, Select, Space, Table, Tag, Tooltip } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate } from 'react-router-dom'
import { PhaseTag } from '../domain/PhaseTag'
import { PhaseLegend } from '../domain/PhaseLegend'
import { SuspendTag } from './SuspendTag'
import { EmptyState, ErrorState, PageSkeleton } from '../common/AsyncState'
import {
  INSTANCES_KEY,
  useActivateInstance,
  useInstances,
  useSuspendInstance,
  useTerminateInstance,
} from '../../hooks/useOps'
import { useBurstInvalidate } from '../../hooks/useTasks'
import { errMsg, opErrorText } from '../../api/errors'
import type { ProcessInstanceView } from '../../api/types'
import { businessKeyLabel } from '../../workbench/definitionLabel'
import { useWorkbenchStore } from '../../store/workbenchStore'
import { useWorkbenchUrl } from '../../workbench/useWorkbenchUrl'

const PHASES = [
  { value: 'WAITING_USER', label: '待办理' },
  { value: 'WAITING_BUSINESS', label: '处理中' },
  { value: 'COMPLETED', label: '已落地' },
  { value: 'INCIDENT', label: '异常' },
  { value: 'CANCELLED', label: '已取消' },
]

/** 实例运维:读工作台 context;终止必填原因;小屏禁用终止。 */
export default function InstancesPanel() {
  const { message, modal } = App.useApp()
  const navigate = useNavigate()
  const screens = Grid.useBreakpoint()
  const isMobile = screens.lg === false
  const { filters, replaceFilters } = useWorkbenchUrl()
  const tenantId = useWorkbenchStore((s) => s.tenantId)
  const definitionKey = filters.definitionKey
  const phase = filters.phase
  const [form] = Form.useForm<{ reason: string }>()
  const [terminateTarget, setTerminateTarget] = useState<ProcessInstanceView | null>(null)

  const query = useInstances({ definitionKey, phase, limit: 100 })
  const burst = useBurstInvalidate([INSTANCES_KEY])
  const suspendMut = useSuspendInstance()
  const activateMut = useActivateInstance()
  const terminateMut = useTerminateInstance()
  const rows = useMemo(() => query.data ?? [], [query.data])
  const keyLabel = businessKeyLabel(definitionKey)

  const confirmSuspendToggle = (row: ProcessInstanceView) => {
    const suspend = !row.suspended
    modal.confirm({
      title: suspend ? '确认挂起该实例?' : '确认恢复该实例?',
      content: suspend ? '挂起后该实例的作业/任务暂停,可随时恢复。' : '恢复后该实例继续运行。',
      okText: '确认',
      cancelText: '取消',
      onOk: async () => {
        try {
          if (suspend) await suspendMut.mutateAsync(row.processInstanceId)
          else await activateMut.mutateAsync(row.processInstanceId)
          message.success(suspend ? '已挂起' : '已恢复')
        } catch (e) {
          message.error(opErrorText(e))
          void query.refetch()
        }
      },
    })
  }

  const submitTerminate = async () => {
    const v = await form.validateFields().catch(() => null)
    if (!v || !terminateTarget) return
    try {
      await terminateMut.mutateAsync({ id: terminateTarget.processInstanceId, reason: v.reason })
      message.success('已终止,实例标记为已取消')
      burst.start()
      setTerminateTarget(null)
      form.resetFields()
    } catch (e) {
      message.error(opErrorText(e))
      void query.refetch()
    }
  }

  const columns: ColumnsType<ProcessInstanceView> = [
    { title: `${keyLabel}(businessKey)`, dataIndex: 'businessKey', render: (v: string) => <span className="mono">{v}</span> },
    { title: '流程定义', dataIndex: 'processDefinitionKey' },
    {
      title: '阶段',
      dataIndex: 'phase',
      render: (_: unknown, r) => (
        <Space>
          <PhaseTag phase={r.phase} />
          <SuspendTag suspended={r.suspended} />
        </Space>
      ),
    },
    { title: '运行中', dataIndex: 'running', render: (v: boolean) => (v ? <Tag color="processing">是</Tag> : <Tag>否</Tag>) },
    {
      title: '操作',
      key: 'op',
      fixed: 'right',
      width: 280,
      render: (_: unknown, r) => {
        const terminated = r.phase === 'CANCELLED' || r.phase === 'COMPLETED' || !r.running
        return (
          <Space size={4} wrap>
            {r.phase === 'WAITING_USER' && (
              <Button
                type="link"
                size="small"
                onClick={() =>
                  navigate(
                    `/tasks?definitionKey=${encodeURIComponent(r.processDefinitionKey)}&businessKey=${encodeURIComponent(r.businessKey)}`,
                  )
                }
              >
                打开待办
              </Button>
            )}
            <Button
              type="link"
              size="small"
              onClick={() => navigate(`/process/${encodeURIComponent(r.processDefinitionKey)}?businessKey=${encodeURIComponent(r.businessKey)}`)}
            >
              轨迹
            </Button>
            <Button type="link" size="small" disabled={terminated} onClick={() => confirmSuspendToggle(r)}>
              {r.suspended ? '恢复' : '挂起'}
            </Button>
            <Dropdown
              menu={{
                items: [
                  {
                    key: 'terminate',
                    label: isMobile ? '终止(请在桌面执行)' : '终止',
                    danger: true,
                    disabled: terminated || isMobile,
                  },
                ],
                onClick: ({ key }) => {
                  if (key === 'terminate' && !isMobile) setTerminateTarget(r)
                },
              }}
            >
              <Tooltip title={isMobile ? '请在桌面执行' : undefined}>
                <Button type="text" size="small">
                  更多
                </Button>
              </Tooltip>
            </Dropdown>
          </Space>
        )
      },
    },
  ]

  let body: React.ReactNode
  if (query.isLoading) body = <PageSkeleton />
  else if (query.isError) body = <ErrorState message={errMsg(query.error)} onRetry={() => query.refetch()} />
  else if (rows.length === 0)
    body = (
      <EmptyState
        description={
          phase === 'INCIDENT'
            ? '当前无异常实例'
            : `当前租户 ${tenantId}${definitionKey ? ` / 流程 ${definitionKey}` : ''} 下暂无实例。`
        }
      />
    )
  else body = <Table rowKey="processInstanceId" columns={columns} dataSource={rows} pagination={false} scroll={{ x: 820 }} />

  return (
    <>
      <Space style={{ marginBottom: 16 }} wrap>
        <Select
          allowClear
          placeholder="阶段筛选(全部)"
          style={{ minWidth: 180 }}
          value={phase}
          onChange={(v) => replaceFilters({ phase: v })}
          options={PHASES}
        />
        <Button danger={phase !== 'INCIDENT'} onClick={() => replaceFilters({ phase: 'INCIDENT' })}>
          只看异常
        </Button>
        <Button icon={<ReloadOutlined />} onClick={() => query.refetch()} loading={query.isFetching}>
          刷新
        </Button>
      </Space>
      <PhaseLegend />
      {body}

      <Modal
        title="终止实例（危险操作）"
        open={!!terminateTarget}
        okText="确认终止"
        okButtonProps={{ danger: true }}
        cancelText="再想想"
        confirmLoading={terminateMut.isPending}
        onOk={submitTerminate}
        onCancel={() => {
          setTerminateTarget(null)
          form.resetFields()
        }}
        destroyOnClose
      >
        <Alert
          type="warning"
          showIcon
          message="终止不可逆"
          description="实例将被删除并标记为已取消(CANCELLED),未落地业务不会继续。处理中(等业务 ACK)的实例不要终止。"
          style={{ marginBottom: 16 }}
        />
        <Descriptions column={1} size="small" style={{ marginBottom: 12 }}>
          <Descriptions.Item label={keyLabel}>
            <span className="mono">{terminateTarget?.businessKey}</span>
          </Descriptions.Item>
          <Descriptions.Item label="实例">
            <span className="mono">{terminateTarget?.processInstanceId}</span>
          </Descriptions.Item>
        </Descriptions>
        <Form form={form} layout="vertical">
          <Form.Item name="reason" label="终止原因" rules={[{ required: true, message: '请填写终止原因' }]}>
            <Input.TextArea rows={3} maxLength={200} showCount placeholder="必填:说明终止原因" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
