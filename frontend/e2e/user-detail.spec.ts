import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { expect, test } from '@playwright/test'

const authUser = {
  id: 3,
  username: 'admin',
  displayName: '演示管理员',
  roles: ['SYSTEM_ADMIN'],
  permissions: ['USER_MANAGE'],
}
const employee = {
  id: 4,
  username: 'employee',
  displayName: '演示员工',
  status: 'ENABLED',
  roles: [{ id: 1, code: 'EMPLOYEE', name: '普通员工' }],
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-20T08:00:00Z',
  version: 3,
}
const reply = (data: unknown) => ({ code: 'OK', message: 'OK', data })

test('从用户列表进入详情、刷新并返回，桌面和窄屏不溢出', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))
  let loggedIn = false
  let detailRequests = 0
  await page.route('**/fd/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/fd/v1', '')
    if (path === '/auth/refresh') {
      await route.fulfill(loggedIn
        ? { json: reply({ accessToken: 'ui-test-token', tokenType: 'Bearer', expiresIn: 900 }) }
        : { status: 401, json: { code: 'AUTH_REQUIRED', message: '', data: null } })
      return
    }
    if (path === '/auth/me') {
      await route.fulfill({ json: reply(authUser) })
      return
    }
    if (path === '/auth/login') {
      loggedIn = true
      await route.fulfill({ json: reply({ accessToken: 'ui-test-token', tokenType: 'Bearer', expiresIn: 900, user: authUser }) })
      return
    }
    if (path === '/users') {
      await route.fulfill({ json: reply({ items: [employee], page: 1, size: 20, totalElements: 1, totalPages: 1 }) })
      return
    }
    if (path === '/users/4') {
      detailRequests += 1
      await route.fulfill({ json: reply(employee) })
      return
    }
    if (path === '/users/999') {
      await route.fulfill({ status: 404, json: { code: 'USER_NOT_FOUND', message: '', data: null } })
      return
    }
    await route.fulfill({ status: 404, json: { code: 'RESOURCE_NOT_FOUND', message: '', data: null } })
  })

  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/login')
  await page.getByPlaceholder('请输入登录名').fill('admin')
  await page.getByPlaceholder('请输入密码').fill('ui-test-password')
  await page.getByRole('button', { name: '登录' }).click()
  await page.goto('/admin/users')
  await page.getByRole('link', { name: 'employee' }).click()

  await expect(page).toHaveURL(/\/admin\/users\/4$/)
  await expect(page.getByRole('heading', { name: '演示员工' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '当前角色' })).toBeVisible()
  await expect(page.getByText('普通员工')).toBeVisible()
  await page.screenshot({ path: join(tmpdir(), 'flowdesk-user-detail-desktop.png'), fullPage: true })

  await page.getByRole('button', { name: '刷新' }).click()
  await expect.poll(() => detailRequests).toBe(2)
  await page.setViewportSize({ width: 375, height: 812 })
  await expect(page.getByRole('heading', { name: '演示员工' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: join(tmpdir(), 'flowdesk-user-detail-mobile.png'), fullPage: true })
  await page.getByRole('link', { name: '返回用户列表' }).click()
  await expect(page).toHaveURL(/\/admin\/users$/)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/admin/users/999')
  await expect(page.getByRole('heading', { name: '用户不存在' })).toBeVisible()
  await page.screenshot({ path: join(tmpdir(), 'flowdesk-user-detail-absent.png'), fullPage: true })
  expect(pageErrors).toEqual([])
})
