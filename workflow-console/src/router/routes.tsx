import { lazy, Suspense } from 'react'
import { createBrowserRouter, Navigate } from 'react-router-dom'
import { Spin } from 'antd'
import ProtectedRoute from '../auth/ProtectedRoute'
import AppLayout from '../components/layout/AppLayout'
import CallbackPage from '../pages/CallbackPage'
import TasksPage from '../pages/TasksPage'

// 轨迹页懒加载:bpmn-js chunk 不进待办主路径首屏(评审 C2 / FINAL_PLAN §3)。
const ProcessTracePage = lazy(() => import('../pages/ProcessTracePage'))

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
    ],
  },
  { path: '*', element: <Navigate to="/tasks" replace /> },
])
