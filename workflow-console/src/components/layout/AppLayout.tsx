import { useState } from 'react'
import { Avatar, Breadcrumb, Button, Drawer, Dropdown, Grid, Layout, Menu, Space, Tag } from 'antd'
import {
  DeploymentUnitOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MenuOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import { NAV, NAV_GROUPS } from '../../nav'
import { config } from '../../config'
import { useAuthStore } from '../../store/authStore'
import { colors } from '../../theme/colors'

export default function AppLayout() {
  const location = useLocation()
  const navigate = useNavigate()
  const auth = useAuth()
  const username = useAuthStore((s) => s.username)
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.lg
  const [collapsed, setCollapsed] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  // /tasks/:id、/process/:key 归到对应菜单项(前缀匹配)。
  const current = NAV.find((n) => location.pathname === n.path || location.pathname.startsWith(n.path + '/'))

  const menuItems = NAV_GROUPS.map((g) => ({
    type: 'group' as const,
    key: g,
    label: g,
    children: NAV.filter((n) => n.group === g).map((n) => ({ key: n.path, icon: n.icon, label: n.label })),
  }))

  const menu = (afterClick?: () => void) => (
    <Menu
      mode="inline"
      selectedKeys={[current?.path ?? location.pathname]}
      items={menuItems}
      onClick={(e) => {
        navigate(e.key)
        afterClick?.()
      }}
      style={{ borderInlineEnd: 0 }}
    />
  )

  const brand = (
    <div className="brand">
      <DeploymentUnitOutlined style={{ color: colors.primary }} />
      {!collapsed && '流程审批中台'}
    </div>
  )

  const userArea = config.authEnabled ? (
    <Dropdown
      menu={{
        items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }],
        onClick: () => void auth.signoutRedirect(),
      }}
    >
      <Button type="text" style={{ height: 'auto', paddingBlock: 4 }}>
        <Space>
          <Avatar size="small" icon={<UserOutlined />} />
          {username ?? '未登录'}
        </Space>
      </Button>
    </Dropdown>
  ) : (
    <Tag color="orange">开发模式 · 未鉴权</Tag>
  )

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {!isMobile && (
        <Layout.Sider
          theme="light"
          width={224}
          collapsedWidth={72}
          collapsible
          collapsed={collapsed}
          trigger={null}
          style={{ borderInlineEnd: `1px solid ${colors.border}` }}
        >
          {brand}
          {menu()}
        </Layout.Sider>
      )}

      <Layout>
        <Layout.Header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderBottom: `1px solid ${colors.border}` }}>
          <Space>
            <Button
              type="text"
              aria-label={isMobile ? '打开菜单' : collapsed ? '展开菜单' : '收起菜单'}
              icon={isMobile ? <MenuOutlined /> : collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => (isMobile ? setDrawerOpen(true) : setCollapsed(!collapsed))}
            />
            <Breadcrumb items={[{ title: '中台' }, { title: current?.label ?? '' }]} />
          </Space>
          {userArea}
        </Layout.Header>
        <Layout.Content>
          <div className="app-content">
            <Outlet />
          </div>
        </Layout.Content>
      </Layout>

      <Drawer
        placement="left"
        width={224}
        open={isMobile && drawerOpen}
        onClose={() => setDrawerOpen(false)}
        styles={{ body: { padding: 0 } }}
        title={
          <Space>
            <DeploymentUnitOutlined style={{ color: colors.primary }} />
            流程审批中台
          </Space>
        }
      >
        {menu(() => setDrawerOpen(false))}
      </Drawer>
    </Layout>
  )
}
