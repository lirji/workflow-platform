import { test, expect } from '@playwright/test'

/**
 * 流程设计器冒烟(dev 无鉴权,AdminRoute 放行)。新建空白流程无需后端:
 * 画布渲染 + 属性面板出现 + 导出 XML 非空。**不点部署**(后端无 delete 端点,避免留痕)。
 * 诚实守卫:不出现"已完成/已生效"。
 */
test('设计器加载 + 画布/属性面板渲染 + 导出 XML 非空', async ({ page }) => {
  await page.goto('/designer')
  await expect(page.getByRole('heading', { name: '流程设计器' })).toBeVisible()

  // bpmn-js Modeler 真实渲染:画布 svg + 左侧 palette。
  await expect(page.locator('.bpmn-canvas svg').first()).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.djs-palette').first()).toBeVisible({ timeout: 15_000 })
  // 属性面板容器渲染。
  await expect(page.locator('.bpmn-props').first()).toBeVisible()

  // 导出 XML → 抽屉内出现非空 BPMN。
  await page.getByRole('button', { name: /导出/ }).click()
  const xml = page.locator('.ant-drawer-body pre')
  await expect(xml).toBeVisible()
  await expect(xml).toContainText('bpmn')
  await expect(xml).toContainText('isExecutable')

  // 诚实守卫:全程不误导为已完成/已生效。
  await expect(page.getByText('已完成')).toHaveCount(0)
  await expect(page.getByText('已生效')).toHaveCount(0)
})
