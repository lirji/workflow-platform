import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// dev :5373 / 同源反代 /api -> workflow-platform-server :8300(免 CORS)。
// Casdoor(:8000)不代理:authority 直连,保持 issuer 一致。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  return {
    plugins: [react()],
    server: {
      port: 5373,
      proxy: {
        '/api': {
          target: env.VITE_API_TARGET || 'http://localhost:8300',
          changeOrigin: true,
        },
      },
    },
    build: {
      chunkSizeWarningLimit: 1200,
      rollupOptions: {
        output: {
          manualChunks: {
            react: ['react', 'react-dom', 'react-router-dom'],
            antd: ['antd', '@ant-design/icons'],
            oidc: ['oidc-client-ts', 'react-oidc-context'],
            query: ['@tanstack/react-query'],
            // bpmn 单独成块;真正不进首屏靠 BpmnViewer 的 React.lazy 动态 import
            bpmn: ['bpmn-js'],
          },
        },
      },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      // 只跑 src 下的单元/组件测试;e2e/(Playwright)由 playwright 自己驱动,勿被 vitest 收集。
      include: ['src/**/*.{test,spec}.{ts,tsx}'],
    },
  }
})
