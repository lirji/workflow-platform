import { Tag } from 'antd'

// DLQ 状态 → antd 预设色名(与 PhaseTag 同风格:待处理=warning,终态=default)。
const MAP: Record<string, { label: string; color: string }> = {
  NEW: { label: '待重放', color: 'warning' },
  REPLAYED: { label: '已重放', color: 'default' },
}

export function DlqStatusTag({ status }: { status: string }) {
  const m = MAP[status] ?? { label: status, color: 'default' }
  return <Tag color={m.color}>{m.label}</Tag>
}
