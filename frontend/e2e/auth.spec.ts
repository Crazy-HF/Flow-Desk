import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

/** 演示账号来自 `db/demo/R__seed_demo_data.sql`，仅用于本地与 CI 的 demo 数据。 */
const username = process.env.E2E_USERNAME ?? 'demo.employee'
const password = process.env.E2E_PASSWORD ?? 'Demo#FlowDesk2026'

async function signIn(page: Page): Promise<void> {
  await page.getByPlaceholder('登录名').fill(username)
  await page.getByPlaceholder('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
}

test('未登录访问受保护页面会回到登录页', async ({ page }) => {
  await page.goto('/')

  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByRole('heading', { name: '登录' })).toBeVisible()
})

test('登录后进入首页并显示当前身份', async ({ page }) => {
  await page.goto('/login')
  await signIn(page)

  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('heading', { name: '演示员工' })).toBeVisible()
  await expect(page.getByText('EMPLOYEE')).toBeVisible()
  await expect(page.getByRole('link', { name: 'FlowDesk' })).toBeVisible()
})

test('刷新页面后靠 Refresh Cookie 恢复身份', async ({ page }) => {
  await page.goto('/login')
  await signIn(page)
  await expect(page.getByRole('heading', { name: '演示员工' })).toBeVisible()

  // Access Token 只在内存里，刷新后必须靠 HttpOnly Cookie 换新令牌再读身份
  await page.reload()

  // 先断言只有恢复成功才会出现的内容：URL 在刷新后本来就已经是 /，先断言它会失去意义
  await expect(page.getByRole('heading', { name: '演示员工' })).toBeVisible()
  await expect(page).toHaveURL(/\/$/)
})

test('退出后回到登录页，且受保护页面不再可访问', async ({ page }) => {
  await page.goto('/login')
  await signIn(page)
  await expect(page.getByRole('heading', { name: '演示员工' })).toBeVisible()

  await page.getByRole('button', { name: '账号' }).click()
  await page.getByText('退出登录').click()

  await expect(page).toHaveURL(/\/login/)
  await page.goto('/')
  await expect(page).toHaveURL(/\/login/)
})

test('错误密码不会登录，只提示凭据不正确', async ({ page }) => {
  await page.goto('/login')
  await page.getByPlaceholder('登录名').fill(username)
  await page.getByPlaceholder('密码').fill('definitely-wrong-password')
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page.getByRole('alert')).toHaveText('用户名或密码不正确')
  await expect(page).toHaveURL(/\/login/)
})
