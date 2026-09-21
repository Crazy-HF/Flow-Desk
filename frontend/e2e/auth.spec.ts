import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

/** 演示账号来自 `db/demo/R__seed_demo_data.sql`，仅用于本地与 CI 的 demo 数据。 */
const username = process.env.E2E_USERNAME ?? 'employee'
const password = process.env.E2E_PASSWORD ?? '123456'

async function signIn(page: Page): Promise<void> {
  await page.getByPlaceholder('请输入登录名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
}

/** 登录成功的判据：进入受保护首页，且顶部栏展示了当前身份。 */
async function expectSignedIn(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  const identity = page.getByRole('banner').locator('.app-header__identity')
  await expect(identity).not.toHaveText('')
  const displayName = (await identity.textContent())?.trim()
  await expect(page.locator('.page__description')).toContainText(displayName ?? '')
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
  await expectSignedIn(page)
  // 角色名随 page__description 一起呈现（"显示名 · 角色名"），没有独立节点可精确匹配
  await expect(page.locator('.page__description')).toContainText('普通员工')
  await expect(page.getByRole('link', { name: 'FlowDesk' })).toBeVisible()
})

test('刷新页面后靠 Refresh Cookie 恢复身份', async ({ page }) => {
  await page.goto('/login')
  await signIn(page)
  await expectSignedIn(page)

  // Access Token 只在内存里，刷新后必须靠 HttpOnly Cookie 换新令牌再读身份
  await page.reload()

  // 先断言只有恢复成功才会出现的内容：URL 在刷新后本来就已经是 /，先断言它会失去意义
  await expectSignedIn(page)
  await expect(page).toHaveURL(/\/$/)
})

test('退出后回到登录页，且受保护页面不再可访问', async ({ page }) => {
  await page.goto('/login')
  await signIn(page)
  await expectSignedIn(page)

  await page.getByRole('button', { name: '账号' }).click()
  await page.getByText('退出登录').click()

  await expect(page).toHaveURL(/\/login/)
  await page.goto('/')
  await expect(page).toHaveURL(/\/login/)
})

test('导航只显示当前账号有权限的入口', async ({ page }) => {
  await page.goto('/login')
  await signIn(page)
  await expectSignedIn(page)

  const nav = page.getByRole('navigation', { name: '主导航' })
  await expect(nav.getByRole('link', { name: '首页', exact: true })).toBeVisible()
  await expect(nav.getByRole('link', { name: '工单', exact: true })).toBeVisible()
  await expect(nav.getByRole('link', { name: '新建工单', exact: true })).toBeVisible()
  // 员工没有队列、数据概览与管理端权限，这些入口不应出现
  await expect(nav.getByRole('link', { name: '数据概览', exact: true })).toHaveCount(0)
  await expect(nav.getByRole('link', { name: '用户管理', exact: true })).toHaveCount(0)
  await expect(nav.getByRole('link', { name: '分类管理', exact: true })).toHaveCount(0)
})

test('访问没有权限的页面会进入 403', async ({ page }) => {
  await page.goto('/login')
  await signIn(page)
  await expectSignedIn(page)

  await page.goto('/admin/users')

  await expect(page.getByRole('heading', { name: '没有访问权限' })).toBeVisible()
})

test('错误密码不会登录，只提示凭据不正确', async ({ page }) => {
  await page.goto('/login')
  await page.getByPlaceholder('请输入登录名').fill(username)
  await page.getByPlaceholder('请输入密码').fill('definitely-wrong-password')
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page.getByRole('alert')).toHaveText('用户名或密码不正确')
  await expect(page).toHaveURL(/\/login/)
})

test('空表单按字段显示校验错误且不发起登录', async ({ page }) => {
  await page.goto('/login')
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page.getByText('请输入登录名', { exact: true })).toBeVisible()
  await expect(page.getByText('请输入密码', { exact: true })).toBeVisible()
  await expect(page).toHaveURL(/\/login/)
})
