import { Drawer, Grid } from 'antd'

/** 通用详情抽屉:等宽全文(payload / 异常栈)。移动端底部全屏,pre-wrap + break-all 不撑破页面。 */
export default function DetailDrawer({
  open,
  title,
  content,
  onClose,
}: {
  open: boolean
  title: string
  content: string
  onClose: () => void
}) {
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.lg
  return (
    <Drawer
      title={title}
      open={open}
      onClose={onClose}
      placement={isMobile ? 'bottom' : 'right'}
      width={isMobile ? '100%' : 640}
      height={isMobile ? '100%' : undefined}
      destroyOnClose
    >
      <pre className="mono" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: 0 }}>
        {content}
      </pre>
    </Drawer>
  )
}
