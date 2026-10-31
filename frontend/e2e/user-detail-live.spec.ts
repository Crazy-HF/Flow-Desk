import { expect, test } from '@playwright/test'

import { expectNoHorizontalOverflow } from './support/overflow'

// 由 UserDetailBrowserAcceptanceManual 启动临时 MySQL/Redis 和真实后端。
test.skip(process.env.FLOWDESK_USER_DETAIL_BROWSER_ACCEPTANCE !== 'YES', '需要隔离的用户详情验收栈')

test('真实 HTTP：管理员查看详情、重载、404 与员工权限边界', async ({ page }) => {
  const userId = process.env.FLOWDESK_USER_DETAIL_EMPLOYEE_ID
  expect(userId).toBeTruthy()
  const detailPath = `/fd/v1/users/${userId}`
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))

  await page.goto('/login')
  await page.getByPlaceholder('请输入登录名').fill('admin')
  await page.getByPlaceholder('请输入密码').fill('123456')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible()

  await page.goto('/admin/users')
  const listLink = page.getByRole('link', { name: 'employee' })
  await expect(listLink).toBeVisible()
  const detailResponse = page.waitForResponse((response) =>
    response.url().endsWith(detailPath) && response.request().method() === 'GET',
  )
  await listLink.click()
  const detail = await detailResponse
  expect(detail.status(), await detail.text()).toBe(200)
  const body = (await detail.json()) as { data: { id: number; username: string; roles: { code: string }[] } }
  expect(body.data.id).toBe(Number(userId))
  expect(body.data.username).toBe('employee')
  expect(body.data.roles.map((role) => role.code)).toContain('EMPLOYEE')
  await expect(page).toHaveURL(new RegExp(`/admin/users/${userId}$`))
  await expect(page.getByRole('heading', { name: '演示员工' })).toBeVisible()
  await expect(page.getByText('普通员工')).toBeVisible()

  const refreshResponse = page.waitForResponse((response) =>
    response.url().endsWith(detailPath) && response.request().method() === 'GET',
  )
  await page.getByRole('button', { name: '刷新' }).click()
  expect((await refreshResponse).status()).toBe(200)
  await page.reload()
  await expect(page.getByRole('heading', { name: '演示员工' })).toBeVisible()
  await page.setViewportSize({ width: 375, height: 812 })
  await expectNoHorizontalOverflow(page)
  await page.getByRole('link', { name: '返回用户列表' }).click()
  await expect(page).toHaveURL(/\/admin\/users$/)

  const absentResponse = page.waitForResponse((response) =>
    response.url().endsWith('/fd/v1/users/99999999') && response.request().method() === 'GET',
  )
  await page.goto('/admin/users/99999999')
  expect((await absentResponse).status()).toBe(404)
  await expect(page.getByRole('heading', { name: '用户不存在' })).toBeVisible()

  await page.goto('/login')
  await page.getByPlaceholder('请输入登录名').fill('employee')
  await page.getByPlaceholder('请输入密码').fill('123456')
  const employeeLoginResponse = page.waitForResponse((response) =>
    response.url().endsWith('/fd/v1/auth/login') && response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '登录' }).click()
  const employeeLogin = await (await employeeLoginResponse).json() as { data: { accessToken: string } }
  const employeeToken = employeeLogin.data.accessToken
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  const forbiddenApi = await page.request.get(detailPath, {
    headers: { Authorization: `Bearer ${employeeToken}` },
  })
  expect(forbiddenApi.status(), await forbiddenApi.text()).toBe(403)
  await page.goto(`/admin/users/${userId}`)
  await expect(page).toHaveURL(/\/403$/)
  expect(errors).toEqual([])
})
