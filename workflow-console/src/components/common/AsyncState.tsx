import type { ReactNode } from 'react'
import { Button, Empty, Result, Skeleton } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'

/** 首次加载骨架。 */
export function PageSkeleton({ rows = 6 }: { rows?: number }) {
  return <Skeleton active paragraph={{ rows }} />
}

/** 错误态 + 重试。 */
export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <Result
      status="warning"
      title="加载失败"
      subTitle={message}
      extra={onRetry ? <Button icon={<ReloadOutlined />} onClick={onRetry}>重试</Button> : undefined}
    />
  )
}

/** 空态(与 idle 文案区分)。 */
export function EmptyState({ description, extra }: { description?: ReactNode; extra?: ReactNode }) {
  return <Empty description={description ?? '暂无数据'}>{extra}</Empty>
}
