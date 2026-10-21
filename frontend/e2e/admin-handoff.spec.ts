import { expect, test } from '@playwright/test'

const manager = {
  id: 3, username: 'admin', displayName: '管理员', status: 'ENABLED', version: 1,
  roles: [{ id: 3, code: 'SYSTEM_ADMIN', name: '系统管理员' }],
}
const assignee = {
  id: 4, username: 'it', displayName: '原负责人', status: 'ENABLED', version: 6,
  roles: [{ id: 2, code: 'IT_SUPPORT', name: 'IT 支持人员' }],
}
const pageOf = (items: unknown[]) => ({ items, page: 1, size: 20, totalElements: items.length, totalPages: 1 })
const reply = (data: unknown) => ({ code: 'OK', message: 'OK', data })

test('管理员停用在办负责人时逐单交接，窄屏弹窗可操作', async ({ page }, testInfo) => {
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))
  let submitted: unknown = null
  let disabled = false
  let loggedIn = false
  const authUser = { id: 3, username: 'admin', displayName: '管理员', roles: ['SYSTEM_ADMIN'],
    permissions: ['USER_MANAGE', 'RBAC_MANAGE', 'TICKET_ADMIN_HANDOFF'] }
  await page.route('**/fd/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/fd/v1', '')
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
      await route.fulfill({ json: reply({
        accessToken: 'ui-test-token', tokenType: 'Bearer', expiresIn: 900,
        user: authUser,
      }) })
      return
    }
    if (path === '/users' && request.method() === 'GET') {
      await route.fulfill({ json: reply(pageOf([manager, { ...assignee, status: disabled ? 'DISABLED' : 'ENABLED', version: disabled ? 7 : 6 }])) })
      return
    }
    if (path === '/admin/roles') {
      await route.fulfill({ json: reply(pageOf([
        { id: 2, code: 'IT_SUPPORT', name: 'IT 支持人员' },
        { id: 3, code: 'SYSTEM_ADMIN', name: '系统管理员' },
      ])) })
      return
    }
    if (path === '/admin/users/4/active-ticket-assignments') {
      await route.fulfill({ json: reply([{
        ticketNo: 'FD-2026-001', status: 'PROCESSING', assigneeId: 4,
        assigneeDisplayName: '原负责人', version: 8,
      }]) })
      return
    }
    if (path === '/admin/tickets/FD-2026-001/handoff-candidates') {
      await route.fulfill({ json: reply([{ id: 9, displayName: '接替人员' }]) })
      return
    }
    if (path === '/users/4/actions/disable' && request.method() === 'POST') {
      submitted = request.postDataJSON()
      disabled = true
      await route.fulfill({ json: reply({ ...assignee, status: 'DISABLED', version: 7 }) })
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
  const row = page.locator('tbody tr').filter({ hasText: '原负责人' })
  await expect(row).toBeVisible()
  await row.locator('.el-switch').click()
  await page.locator('.el-popconfirm__action button:visible').filter({ hasText: '确定' }).click()

  const dialog = page.getByRole('dialog', { name: '交接在办工单' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('FD-2026-001')
  await expect(dialog).toContainText('处理中')
  await dialog.getByRole('button', { name: '确认交接' }).click()
  await expect(dialog.getByText('请选择新负责人')).toBeVisible()
  await dialog.getByRole('textbox', { name: '交接原因' }).fill('岗位调整')
  await dialog.locator('.el-select').click()
  await page.getByText('接替人员', { exact: true }).click()
  await expect(dialog.getByText('请选择新负责人')).toBeHidden()
  await page.screenshot({ path: testInfo.outputPath('handoff-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 375, height: 812 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('handoff-mobile.png'), fullPage: true })
  await dialog.getByRole('button', { name: '确认交接' }).click()

  await expect(dialog).toBeHidden()
  expect(submitted).toEqual({
    version: 6, handoffReason: '岗位调整',
    handoffs: [{ ticketNo: 'FD-2026-001', ticketVersion: 8, newAssigneeId: 9 }],
  })
  await expect(row).toContainText('原负责人')
  await expect(row.getByRole('switch')).not.toBeChecked()
  expect(pageErrors).toEqual([])
})
