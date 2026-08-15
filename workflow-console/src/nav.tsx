import type { ReactNode } from 'react'
import { ApartmentOutlined, ProfileOutlined } from '@ant-design/icons'

export interface NavItem {
  path: string
  label: string
  icon: ReactNode
  group: string
}

/** 侧边菜单 + 路由的单一配置源。本轮两页:待办中心(核心)+ 流程轨迹(只读)。 */
export const NAV: NavItem[] = [
  { path: '/tasks', label: '待办中心', icon: <ProfileOutlined />, group: '流程办理' },
  { path: '/process/hisRxReview', label: '流程轨迹', icon: <ApartmentOutlined />, group: '流程办理' },
]

export const NAV_GROUPS = ['流程办理']
