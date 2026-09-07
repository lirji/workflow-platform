import { Space, Typography } from 'antd'
import { PhaseTag } from './PhaseTag'

const STEPS = ['WAITING_USER', 'WAITING_BUSINESS', 'COMPLETED'] as const

/** 阶段图例：待办理 → 处理中(等 ACK) → 已落地。 */
export function PhaseLegend() {
  return (
    <Space wrap size={8} style={{ marginBottom: 16 }}>
      <Typography.Text type="secondary">阶段</Typography.Text>
      {STEPS.map((phase, i) => (
        <Space key={phase} size={4}>
          {i > 0 && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              →
            </Typography.Text>
          )}
          <PhaseTag phase={phase} />
        </Space>
      ))}
    </Space>
  )
}
