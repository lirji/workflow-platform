import { test, expect } from '@playwright/test'

/**
 * 运维面板冒烟(dev 无鉴权,AdminRoute 放行):页面加载 + 三 Tab 可见。
 * 不点任何危险按钮(终止/重放),避免污染真实数据。诚实守卫:不出现"已完成"。
 */
test('运维面板加载 + 三 Tab', async ({ page }) => {
  await page.goto('/ops')
  await expect(page.getByRole('heading', { name: '运维面板' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '实例运维' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '死信作业' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'DLQ 死信' })).toBeVisible()
  await expect(page.getByText('已完成')).toHaveCount(0)
})
