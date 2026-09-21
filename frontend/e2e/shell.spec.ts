import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

/**
 * 应用壳的行为用例。
 *
 * <p>面包屑按侧栏层级显示（首页 / 一级栏目 / 页面），这条用例锁住的就是"同级切换不能
 * 把栏目那一级顶掉"：管理员账号来自 `db/demo/R__seed_demo_data.sql` + 迁移 `V2`，
 * 它是唯一同时能看到"用户管理"和"分类管理"的角色。</p>
 */
const adminUsername = process.env.E2E_ADMIN_USERNAME ?? 'admin'
const password = process.env.E2E_PASSWORD ?? '123456'

async function signIn(page: Page, username: string): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('请输入登录名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
}

function breadcrumbItems(page: Page) {
  return page.getByRole('navigation', { name: '面包屑' }).locator('.el-breadcrumb__item')
}

test('面包屑按侧栏层级显示，同一栏目内切换页面不替换栏目', async ({ page }, testInfo) => {
  await signIn(page, adminUsername)

  const nav = page.getByRole('navigation', { name: '主导航' })

  await nav.getByRole('link', { name: '用户管理', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/users$/)
  await expect(breadcrumbItems(page)).toHaveText(['首页', '系统管理', '用户管理'])

  await nav.getByRole('link', { name: '分类管理', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/categories$/)
  await expect(breadcrumbItems(page)).toHaveText(['首页', '系统管理', '分类管理'])

  await page.screenshot({ path: testInfo.outputPath('breadcrumb-admin.png'), fullPage: true })
})
