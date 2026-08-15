import type { ReactNode } from 'react'
import { ApartmentOutlined, ProfileOutlined, ToolOutlined } from '@ant-design/icons'

export interface NavItem {
  path: string
  label: string
  icon: ReactNode
  group: string
  /** 仅 ADMIN 可见(dev 无鉴权时放行)。 */
  adminOnly?: boolean
}

/** 侧边菜单 + 路由的单一配置源。 */
export const NAV: NavItem[] = [
  { path: '/tasks', label: '待办中心', icon: <ProfileOutlined />, group: '流程办理' },
  { path: '/process/hisRxReview', label: '流程轨迹', icon: <ApartmentOutlined />, group: '流程办理' },
  { path: '/ops', label: '运维面板', icon: <ToolOutlined />, group: '系统运维', adminOnly: true },
]

export const NAV_GROUPS = ['流程办理', '系统运维']
