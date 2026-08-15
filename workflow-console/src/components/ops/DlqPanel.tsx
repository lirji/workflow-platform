import { useMemo, useState } from 'react'
import { Alert, App, Button, Segmented, Space, Table, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import DetailDrawer from './DetailDrawer'
import { DlqStatusTag } from './DlqStatusTag'
import { EmptyState, ErrorState, PageSkeleton } from '../common/AsyncState'
import { DLQ_KEY, useDlq, useReplayAllDlq, useReplayDlq } from '../../hooks/useOps'
import { useBurstInvalidate } from '../../hooks/useTasks'
import { errMsg, opErrorText, statusOf } from '../../api/errors'
import type { DlqRecord } from '../../api/types'

function prettyJson(s: string): string {
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}

const fmt = (v: number | null) => (v ? new Date(v).toLocaleString('zh-CN') : '-')

/** Kafka DLQ 死信:status 筛选 + 单条/批量重放(异步最终一致)+ payload 详情。全平台视角(不区分租户)。 */
export default function DlqPanel() {
  const { message, modal } = App.useApp()
  const [status, setStatus] = useState('NEW')
  const [detail, setDetail] = useState<{ title: string; content: string } | null>(null)

  const query = useDlq(status, 100)
  const burst = useBurstInvalidate([DLQ_KEY])
  const replayMut = useReplayDlq()
  const replayAllMut = useReplayAllDlq()
  const rows = useMemo(() => query.data ?? [], [query.data])

  const confirmReplay = (rec: DlqRecord) => {
    modal.confirm({
      title: '确认重放该死信?',
      content: '重放将把原始消息投回原 topic,由原监听器重新处理(幂等安全),处理经异步最终一致。',
      okText: '确认重放',
      cancelText: '取消',
      onOk: async () => {
        try {
          await replayMut.mutateAsync(rec.id)
          message.info('已重放,消息已投回原 topic(异步最终一致)')
          burst.start()
        } catch (e) {
          if (statusOf(e) === 404) message.warning('该死信不存在或已重放,已刷新')
          else message.error(opErrorText(e))
          void query.refetch()
        }
      },
    })
  }

  const confirmReplayAll = () => {
    modal.confirm({
      title: '确认批量重放当前所有待重放死信?',
      content: '将重放当前所有 NEW 死信(后端上限 500 条),集中投回原 topic 由监听幂等消费,异步最终一致。',
      okText: '确认全部重放',
      okButtonProps: { danger: true },
      cancelText: '再想想',
      onOk: async () => {
        try {
          const { replayed } = await replayAllMut.mutateAsync()
          message.info(replayed > 0 ? `已受理重放 ${replayed} 条(异步最终一致)` : '无可重放死信')
          burst.start()
        } catch (e) {
          message.error(opErrorText(e))
          void query.refetch()
        }
      },
    })
  }

  const columns: ColumnsType<DlqRecord> = [
    { title: '原始 topic', dataIndex: 'originalTopic' },
    { title: 'key', dataIndex: 'msgKey', render: (v: string | null) => <span className="mono">{v ?? '-'}</span> },
    { title: '状态', dataIndex: 'status', render: (v: string) => <DlqStatusTag status={v} /> },
    { title: '入队时间', dataIndex: 'failedAtEpochMs', render: (v: number | null) => fmt(v) },
    {
      title: '错误',
      dataIndex: 'errorMessage',
      render: (v: string | null) => (v ? <Typography.Text ellipsis style={{ maxWidth: 220 }}>{v}</Typography.Text> : '-'),
    },
    {
      title: '操作',
      key: 'op',
      fixed: 'right',
      width: 160,
      render: (_: unknown, r) => (
        <Space size={4}>
          <Button type="link" size="small" disabled={r.status !== 'NEW'} onClick={() => confirmReplay(r)}>
            重放
          </Button>
          <Button type="link" size="small" onClick={() => setDetail({ title: `payload · ${r.originalTopic}`, content: prettyJson(r.payload) })}>
            payload
          </Button>
        </Space>
      ),
    },
  ]

  let body: React.ReactNode
  if (query.isLoading) body = <PageSkeleton />
  else if (query.isError) body = <ErrorState message={errMsg(query.error)} onRetry={() => query.refetch()} />
  else if (rows.length === 0) body = <EmptyState description={status === 'NEW' ? '无待重放死信' : '无已重放死信'} />
  else body = <Table rowKey="id" columns={columns} dataSource={rows} pagination={false} scroll={{ x: 820 }} />

  return (
    <>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="DLQ 为全平台视角(不区分租户);重放/批量重放均为异步最终一致,结果以列表刷新为准。仅显示前 100 条。"
      />
      <Space style={{ marginBottom: 16 }} wrap>
        <Segmented
          value={status}
          onChange={(v) => setStatus(v as string)}
          options={[
            { label: '待重放(NEW)', value: 'NEW' },
            { label: '已重放(REPLAYED)', value: 'REPLAYED' },
          ]}
        />
        <Button danger onClick={confirmReplayAll} disabled={status !== 'NEW' || rows.length === 0} loading={replayAllMut.isPending}>
          全部重放
        </Button>
        <Button icon={<ReloadOutlined />} onClick={() => query.refetch()} loading={query.isFetching}>
          刷新
        </Button>
      </Space>
      {body}

      <DetailDrawer
        open={!!detail}
        title={detail?.title ?? ''}
        content={detail?.content ?? ''}
        onClose={() => setDetail(null)}
      />
    </>
  )
}
