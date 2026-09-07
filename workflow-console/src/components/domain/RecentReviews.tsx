import { Alert, Button, Space, Typography } from 'antd'
import type { RecentReview } from '../../store/uiStore'
import { newestProcessInstance, useProcessPhase } from '../../hooks/useProcess'
import { PhaseTag } from './PhaseTag'
import { businessKeyLabel } from '../../workbench/definitionLabel'

/** 单条近期办理:按该条自己的 processDefinitionKey 查阶段。 */
function Row({ item }: { item: RecentReview }) {
  const q = useProcessPhase(item.processDefinitionKey, item.businessKey)
  const latest = newestProcessInstance(q.data)
  const label = businessKeyLabel(item.processDefinitionKey)
  const decisionText = item.decision === 'PASS' || item.decision === 'APPROVE' ? '通过' : item.decision === 'REJECT' ? '驳回' : item.decision
  return (
    <Space wrap size={8}>
      <Typography.Text>
        {label} <span className="mono">{item.businessKey}</span>
      </Typography.Text>
      <Typography.Text type="secondary">{decisionText}</Typography.Text>
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
  onClear,
}: {
  items: RecentReview[]
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
            <Row key={it.actionId} item={it} />
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
