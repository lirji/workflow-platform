import { useMemo, useState } from 'react'
import { App, Button, InputNumber, Modal, Space, Table, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import DetailDrawer from './DetailDrawer'
import { EmptyState, ErrorState, PageSkeleton } from '../common/AsyncState'
import { DLJOBS_KEY, useDeadLetterJobs, useRetryJob } from '../../hooks/useOps'
import { useBurstInvalidate } from '../../hooks/useTasks'
import { errMsg, opErrorText } from '../../api/errors'
import type { DeadLetterJobView } from '../../api/types'

/** Flowable 死信作业:列出 + 重试(移回可执行队列,异步)+ 异常详情。 */
export default function DeadLetterPanel() {
  const { message } = App.useApp()
  const [retryTarget, setRetryTarget] = useState<DeadLetterJobView | null>(null)
  const [retries, setRetries] = useState(3)
  const [detail, setDetail] = useState<{ title: string; content: string } | null>(null)

  const query = useDeadLetterJobs(100)
  const burst = useBurstInvalidate([DLJOBS_KEY])
  const retryMut = useRetryJob()
  const rows = useMemo(() => query.data ?? [], [query.data])

  const submitRetry = async () => {
    if (!retryTarget) return
    try {
      await retryMut.mutateAsync({ jobId: retryTarget.jobId, retries })
      message.info('已受理重试,作业已移回执行队列(结果稍后刷新查看)')
      burst.start()
      setRetryTarget(null)
    } catch (e) {
      message.error(opErrorText(e))
      void query.refetch()
    }
  }

  const columns: ColumnsType<DeadLetterJobView> = [
    { title: '作业(jobId)', dataIndex: 'jobId', render: (v: string) => <span className="mono">{v}</span> },
    { title: '实例', dataIndex: 'processInstanceId', render: (v: string) => <span className="mono">{v}</span> },
    { title: '节点', dataIndex: 'elementId' },
    { title: '剩余重试', dataIndex: 'retries', render: (v: number) => <Tag color={v <= 0 ? 'error' : 'warning'}>{v}</Tag> },
    {
      title: '异常',
      dataIndex: 'exceptionMessage',
      render: (v: string | null) =>
        v ? (
          <Space>
            <Typography.Text ellipsis style={{ maxWidth: 260 }}>{v}</Typography.Text>
            <Button type="link" size="small" onClick={() => setDetail({ title: '异常栈', content: v })}>详情</Button>
          </Space>
        ) : (
          '-'
        ),
    },
    {
      title: '操作',
      key: 'op',
      fixed: 'right',
      width: 100,
      render: (_: unknown, r) => (
        <Button type="link" size="small" onClick={() => { setRetries(3); setRetryTarget(r) }}>
          重试
        </Button>
      ),
    },
  ]

  let body: React.ReactNode
  if (query.isLoading) body = <PageSkeleton />
  else if (query.isError) body = <ErrorState message={errMsg(query.error)} onRetry={() => query.refetch()} />
  else if (rows.length === 0) body = <EmptyState description="无死信作业" />
  else body = <Table rowKey="jobId" columns={columns} dataSource={rows} pagination={false} scroll={{ x: 900 }} />

  return (
    <>
      <Space style={{ marginBottom: 16 }} wrap>
        <Typography.Text type="secondary">全平台视角(不区分租户);仅显示前 100 条</Typography.Text>
        <Button icon={<ReloadOutlined />} onClick={() => query.refetch()} loading={query.isFetching}>刷新</Button>
      </Space>
      {body}

      <Modal
        title="重试死信作业"
        open={!!retryTarget}
        okText="确认重试"
        cancelText="取消"
        confirmLoading={retryMut.isPending}
        onOk={submitRetry}
        onCancel={() => setRetryTarget(null)}
        destroyOnClose
      >
        <Typography.Paragraph type="secondary">
          将作业移回可执行队列,由 Flowable 异步重执行——结果稍后在列表刷新查看(重试成功则该作业消失)。
        </Typography.Paragraph>
        <Space>
          <span>重试次数</span>
          <InputNumber min={1} max={10} value={retries} onChange={(v) => setRetries(v ?? 3)} />
        </Space>
      </Modal>

      <DetailDrawer
        open={!!detail}
        title={detail?.title ?? ''}
        content={detail?.content ?? ''}
        onClose={() => setDetail(null)}
      />
    </>
  )
}
