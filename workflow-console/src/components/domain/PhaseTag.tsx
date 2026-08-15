import { Tag } from 'antd'

// 中台侧最终一致阶段 → 语义色(FINAL_PLAN §2 映射)。
const MAP: Record<string, { label: string; color: string }> = {
  WAITING_USER: { label: '待办理', color: 'gold' },
  WAITING_BUSINESS: { label: '处理中', color: 'processing' },
  COMPLETED: { label: '已落地', color: 'success' },
  INCIDENT: { label: '异常', color: 'error' },
  CANCELLED: { label: '已取消', color: 'default' },
}

/** 流程阶段标签。绝不出现"已完成"式误导——落地态用"已落地"。 */
export function PhaseTag({ phase, loading }: { phase?: string; loading?: boolean }) {
  if (loading && !phase) return <Tag color="processing">查询中…</Tag>
  const m = (phase && MAP[phase]) || { label: phase ?? '未知', color: 'default' }
  return <Tag color={m.color}>{m.label}</Tag>
}
