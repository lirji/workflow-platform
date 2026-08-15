import { Button, Card, Space, Tag, Typography } from 'antd'
import type { TaskView } from '../../api/types'
import { TaskTypeTag } from './TaskTypeTag'

/** 小屏卡片(表格→卡片堆叠);主操作按钮全宽 ≥44px 触屏友好。 */
export default function TaskCard({ task, onReview }: { task: TaskView; onReview: (t: TaskView) => void }) {
  return (
    <Card size="small">
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Space wrap>
          <TaskTypeTag taskDefinitionKey={task.taskDefinitionKey} />
          <Typography.Text strong>{task.name}</Typography.Text>
        </Space>
        <Typography.Text type="secondary">
          就诊 <span className="mono">{task.businessKey}</span>
        </Typography.Text>
        <Space wrap>{task.candidateGroups?.map((g) => <Tag key={g}>{g}</Tag>)}</Space>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {task.createTimeEpochMs ? new Date(task.createTimeEpochMs).toLocaleString('zh-CN') : '-'}
        </Typography.Text>
        <Button type="primary" block style={{ minHeight: 44 }} onClick={() => onReview(task)}>
          办理
        </Button>
      </Space>
    </Card>
  )
}
