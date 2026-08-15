import { test, expect } from '@playwright/test'

/**
 * 冒烟(dev 无鉴权阶段,直连 :8300):
 * 1. 待办中心加载(列表或空态);断言页面绝不出现"已完成"字样。
 * 2. 轨迹页懒加载 bpmn Viewer,渲染出 SVG 流程图。
 * 办理→202→"已受理"的语义断言在 Vitest 组件测试(ReviewDrawer.test.tsx)中覆盖,避免冒烟消耗真实待办。
 */
test('待办中心加载 + 轨迹页渲染 bpmn', async ({ page }) => {
  await page.goto('/tasks')
  await expect(page.getByRole('heading', { name: '待办中心' })).toBeVisible()
  // 加载成功:要么有"办理"入口,要么空态"暂无待办"
  await expect(page.getByText('暂无待办').or(page.getByRole('link', { name: '办理' }).first())).toBeVisible()
  // 诚实性守卫:待办中心不得出现"已完成"误导文案
  await expect(page.getByText('已完成')).toHaveCount(0)

  await page.goto('/process/hisRxReview')
  await expect(page.getByRole('heading', { name: '流程轨迹' })).toBeVisible()
  await expect(page.locator('.bpmn-canvas svg').first()).toBeVisible({ timeout: 15_000 })
})
