import type { ReactNode } from 'react'
import { Typography } from 'antd'

/** 页面标题区:title / description / extra。不取业务数据。 */
export function PageHeader({ title, description, extra }: { title: string; description?: ReactNode; extra?: ReactNode }) {
  return (
    <div style={{ marginBottom: 16, display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
      <div>
        <Typography.Title level={4} style={{ margin: 0 }}>
          {title}
        </Typography.Title>
        {description && (
          <Typography.Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
            {description}
          </Typography.Paragraph>
        )}
      </div>
      {extra && <div>{extra}</div>}
    </div>
  )
}
