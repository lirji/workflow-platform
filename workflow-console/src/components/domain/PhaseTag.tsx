import { Tag, Tooltip } from 'antd'

// 中台侧最终一致阶段 → 语义色(FINAL_PLAN §2 映射)。
const MAP: Record<string, { label: string; color: string; tip?: string }> = {
  WAITING_USER: { label: '待办理', color: 'gold', tip: '等人审批，对应权益侧「待审批」' },
  WAITING_BUSINESS: { label: '处理中', color: 'processing', tip: '人已通过，在等业务 ACK / 权益落地，不要终止' },
  COMPLETED: { label: '已落地', color: 'success', tip: '业务已 ACK，对应权益侧「已投放」' },
  INCIDENT: { label: '异常', color: 'error' },
  CANCELLED: { label: '已取消', color: 'default' },
}

/** 流程阶段标签。绝不出现"已完成"式误导——落地态用"已落地"。 */
export function PhaseTag({ phase, loading }: { phase?: string; loading?: boolean }) {
  if (loading && !phase) return <Tag color="processing">查询中…</Tag>
  const m = (phase && MAP[phase]) || { label: phase ?? '未知', color: 'default' }
  const tag = <Tag color={m.color}>{m.label}</Tag>
  return m.tip ? <Tooltip title={m.tip}>{tag}</Tooltip> : tag
}
