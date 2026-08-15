import { lazy, Suspense } from 'react'
import { createBrowserRouter, Navigate } from 'react-router-dom'
import { Spin } from 'antd'
import ProtectedRoute from '../auth/ProtectedRoute'
import AdminRoute from '../auth/AdminRoute'
import AppLayout from '../components/layout/AppLayout'
import CallbackPage from '../pages/CallbackPage'
import TasksPage from '../pages/TasksPage'

// 懒加载:轨迹页 bpmn chunk / 运维面板 admin chunk 不进待办首屏。
const ProcessTracePage = lazy(() => import('../pages/ProcessTracePage'))
const OpsPage = lazy(() => import('../pages/OpsPage'))
const DesignerPage = lazy(() => import('../pages/DesignerPage'))

const lazyFallback = (
  <div style={{ minHeight: '60vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
    <Spin size="large" tip="加载流程图..." />
  </div>
)

// 数据式路由表。/callback 公开;其余在 ProtectedRoute(Stage 2 未登录跳 Casdoor)+ AppLayout 下。
export const router = createBrowserRouter([
  { path: '/callback', element: <CallbackPage /> },
  {
    path: '/',
    element: (
      <ProtectedRoute>
        <AppLayout />
      </ProtectedRoute>
    ),
    children: [
      { index: true, element: <Navigate to="/tasks" replace /> },
      { path: 'tasks', element: <TasksPage /> },
      { path: 'tasks/:taskId', element: <TasksPage /> },
      { path: 'process/:key', element: <Suspense fallback={lazyFallback}><ProcessTracePage /></Suspense> },
      { path: 'ops', element: <AdminRoute><Suspense fallback={lazyFallback}><OpsPage /></Suspense></AdminRoute> },
      { path: 'designer', element: <AdminRoute><Suspense fallback={lazyFallback}><DesignerPage /></Suspense></AdminRoute> },
    ],
  },
  { path: '*', element: <Navigate to="/tasks" replace /> },
])
