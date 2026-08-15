import { defineConfig, devices } from '@playwright/test'

// 冒烟:对 dev server(:5373)跑最小端到端。复用已运行的 vite dev(reuseExistingServer)。
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: true,
  reporter: 'list',
  use: {
    // 显式 IPv4:避免 vite 默认 localhost(::1) 与探测端 127.0.0.1 的双栈错配。
    baseURL: 'http://127.0.0.1:5373',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'pnpm exec vite --host 127.0.0.1 --port 5373',
    url: 'http://127.0.0.1:5373',
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
