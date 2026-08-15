import { Alert, Button, Space, Typography } from 'antd'
import type { RecentReview } from '../../store/uiStore'
import { useProcessPhase } from '../../hooks/useProcess'
import { PhaseTag } from './PhaseTag'

/** 单条近期办理:轮询该 businessKey 的实例阶段,诚实展示 处理中 → 已落地 / 异常。 */
function Row({ item, definitionKey }: { item: RecentReview; definitionKey: string }) {
  const q = useProcessPhase(definitionKey, item.businessKey)
  const list = q.data
  const latest = list && list[list.length - 1]
  return (
    <Space wrap size={8}>
      <Typography.Text>
        就诊 <span className="mono">{item.businessKey}</span>
      </Typography.Text>
      <Typography.Text type="secondary">{item.decision === 'PASS' ? '通过' : '驳回'}</Typography.Text>
      <PhaseTag phase={latest?.phase} loading={q.isFetching} />
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        actionId {item.actionId.slice(0, 8)}…
      </Typography.Text>
    </Space>
  )
}

/** 近期办理区:办理返回 202 后展示,追最终一致落地状态。不显示"已完成"。 */
export default function RecentReviews({
  items,
  definitionKey,
  onClear,
}: {
  items: RecentReview[]
  definitionKey: string
  onClear: () => void
}) {
  return (
    <Alert
      type="info"
      showIcon
      style={{ marginBottom: 16 }}
      message="近期办理(已受理,业务落地经异步最终一致)"
      description={
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          {items.map((it) => (
            <Row key={it.actionId} item={it} definitionKey={definitionKey} />
          ))}
        </Space>
      }
      action={
        <Button size="small" type="text" onClick={onClear}>
          清除
        </Button>
      }
    />
  )
}
