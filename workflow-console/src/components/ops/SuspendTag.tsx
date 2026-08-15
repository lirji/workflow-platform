import { Tag } from 'antd'

/** 挂起态标签(仅挂起时渲染;数据来自 ProcessInstanceView.suspended)。 */
export function SuspendTag({ suspended }: { suspended: boolean }) {
  if (!suspended) return null
  return <Tag color="default">已挂起</Tag>
}
