import { test, expect } from '@playwright/test'

/**
 * 登录页冒烟(dev 无鉴权 authEnabled=false)。此时按设计不发起 SSO,展示"开发模式 · 免登录"入口。
 * 诚实守卫:dev 不误导为已鉴权。真实 Casdoor SSO 往返不入 CI(需 authEnabled=true + Casdoor)。
 */
test('登录页加载 + 开发模式免登录进控制台', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '欢迎登录' })).toBeVisible()
  await expect(page.getByText(/开发模式 · 免登录/)).toBeVisible()

  // dev 不发起 SSO,点"进入控制台"直达待办中心。
  await page.getByRole('button', { name: '进入控制台' }).click()
  await expect(page).toHaveURL(/\/tasks$/)
})
