import { expect, test } from '@playwright/test'
import type { APIRequestContext, Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'

// This test uses the CI demo stack. Its temporary principals live only in that isolated database.
const password = process.env.E2E_PASSWORD ?? '123456'
const origin = process.env.E2E_ORIGIN ?? 'http://127.0.0.1:4173'

interface Envelope<T> { data: T }
interface Role { id: number; code: string }
interface User { id: number; username: string; status: string; version: number; roles: Role[] }
interface Assignment { ticketNo: string; version: number; assigneeId: number }

async function data<T>(response: Awaited<ReturnType<APIRequestContext['get']>>, status = 200): Promise<T> {
  expect(response.status(), await response.text()).toBe(status)
  return ((await response.json()) as Envelope<T>).data
}

async function token(request: APIRequestContext, username: string, secret: string): Promise<string> {
  const result = await data<{ accessToken: string }>(await request.post('/fd/v1/auth/login', {
    headers: { Origin: origin }, data: { username, password: secret },
  }))
  return result.accessToken
}

function auth(accessToken: string) { return { Authorization: `Bearer ${accessToken}`, Origin: origin } }

async function findUser(page: Page, username: string) {
  await page.getByLabel('登录名').fill(username)
  await page.getByRole('region', { name: '用户查询条件' }).getByRole('button', { name: '搜索' }).click()
  const row = page.locator('tbody tr').filter({ hasText: username })
  await expect(row).toHaveCount(1)
  return row
}

test('真实后端：停用与移除 IT 角色均逐单交接', async ({ page, request }) => {
  const stamp = Date.now().toString(36)
  const firstName = `ah-ui-${stamp}-a`
  const secondName = `ah-ui-${stamp}-b`
  const firstDisplay = `交接源 ${stamp}`
  const secondDisplay = `接替人 ${stamp}`
  const tempPassword = 'Handoff#2026'
  const adminToken = await token(request, process.env.E2E_ADMIN_USERNAME ?? 'admin', password)
  const employeeToken = await token(request, process.env.E2E_EMPLOYEE_USERNAME ?? 'employee', password)
  const roles = await data<{ items: Role[] }>(await request.get('/fd/v1/admin/roles?page=1&size=100', {
    headers: auth(adminToken),
  }))
  const itRole = roles.items.find((role) => role.code === 'IT_SUPPORT')
  expect(itRole).toBeDefined()
  const categoryOptions = await data<{ id: number }[]>(await request.get('/fd/v1/categories/options', {
    headers: auth(employeeToken),
  }))
  expect(categoryOptions.length).toBeGreaterThan(0)

  async function createUser(username: string, displayName: string): Promise<User> {
    return data<User>(await request.post('/fd/v1/users', {
      headers: auth(adminToken), data: {
        username, displayName, initialPassword: tempPassword, roleIds: [itRole!.id],
      },
    }), 201)
  }
  const first = await createUser(firstName, firstDisplay)
  const second = await createUser(secondName, secondDisplay)
  const firstToken = await token(request, firstName, tempPassword)
  const ticket = await data<{ ticketNo: string }>(await request.post('/fd/v1/tickets', {
    headers: auth(employeeToken),
    multipart: {
      ticket: {
        name: 'ticket.json', mimeType: 'application/json',
        buffer: Buffer.from(JSON.stringify({
          submissionKey: randomUUID(), title: `管理交接页面 ${stamp}`,
          description: '真实栈页面交接用例', categoryId: categoryOptions[0]!.id, priority: 'MEDIUM',
        })),
      },
    },
  }), 201)
  await data(await request.post(`/fd/v1/tickets/${ticket.ticketNo}/actions/claim`, {
    headers: auth(firstToken), data: { version: 0 },
  }))

  await page.goto('/login')
  await page.getByPlaceholder('请输入登录名').fill(process.env.E2E_ADMIN_USERNAME ?? 'admin')
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  await page.goto('/admin/users')

  const firstRow = await findUser(page, firstName)
  await firstRow.locator('.el-switch').click()
  await page.locator('.el-popconfirm__action button:visible').filter({ hasText: '确定' }).click()
  const dialog = page.getByRole('dialog', { name: '交接在办工单' })
  await expect(dialog).toContainText(ticket.ticketNo)
  await dialog.getByRole('textbox', { name: '交接原因' }).fill('真实栈停用交接')
  await dialog.locator(`.el-select:has([aria-label="${ticket.ticketNo}的新负责人"])`).click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: secondDisplay }).click()
  const staleAssignments = await data<Assignment[]>(await request.get(
    `/fd/v1/admin/users/${first.id}/active-ticket-assignments`, { headers: auth(adminToken) },
  ))
  await data(await request.post(`/fd/v1/tickets/${ticket.ticketNo}/actions/add-processing-record`, {
    headers: auth(firstToken), data: { version: staleAssignments[0]!.version, content: '触发真实版本冲突' },
  }))
  const [conflict] = await Promise.all([
    page.waitForResponse((response) => response.url().endsWith(`/fd/v1/users/${first.id}/actions/disable`) && response.request().method() === 'POST'),
    dialog.getByRole('button', { name: '确认交接' }).click(),
  ])
  expect(conflict.status(), await conflict.text()).toBe(409)
  await expect(dialog.getByRole('alert')).toContainText('交接期间数据发生变化')
  await dialog.getByRole('button', { name: '取消' }).click()
  await expect(dialog).toBeHidden()
  await firstRow.locator('.el-switch').click()
  await page.locator('.el-popconfirm__action button:visible').filter({ hasText: '确定' }).click()
  await expect(dialog).toContainText(ticket.ticketNo)
  await dialog.getByRole('textbox', { name: '交接原因' }).fill('真实栈停用交接')
  await dialog.locator(`.el-select:has([aria-label="${ticket.ticketNo}的新负责人"])`).click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: secondDisplay }).click()
  const [disabled] = await Promise.all([
    page.waitForResponse((response) => response.url().endsWith(`/fd/v1/users/${first.id}/actions/disable`) && response.request().method() === 'POST'),
    dialog.getByRole('button', { name: '确认交接' }).click(),
  ])
  expect(disabled.status(), await disabled.text()).toBe(200)
  await expect(dialog).toBeHidden()
  const afterDisable = await data<User>(await request.get(`/fd/v1/users/${first.id}`, { headers: auth(adminToken) }))
  expect(afterDisable.status).toBe('DISABLED')
  let assignments = await data<Assignment[]>(await request.get(`/fd/v1/admin/users/${second.id}/active-ticket-assignments`, {
    headers: auth(adminToken),
  }))
  expect(assignments.map((item) => item.ticketNo)).toContain(ticket.ticketNo)

  const candidates = await data<{ id: number; displayName: string }[]>(await request.get(
    `/fd/v1/admin/tickets/${ticket.ticketNo}/handoff-candidates`, { headers: auth(adminToken) },
  ))
  const finalCandidate = candidates.find((item) => item.id !== second.id)
  expect(finalCandidate).toBeDefined()

  const secondRow = await findUser(page, secondName)
  await secondRow.locator('.el-checkbox').click()
  await page.getByRole('button', { name: '调整角色' }).click()
  const rolesDialog = page.getByRole('dialog', { name: '调整用户角色' })
  await rolesDialog.locator('.el-tag__close').click()
  await rolesDialog.getByRole('button', { name: '保存角色' }).click()
  await expect(dialog).toContainText(ticket.ticketNo)
  await dialog.getByRole('textbox', { name: '交接原因' }).fill('真实栈角色交接')
  await dialog.locator(`.el-select:has([aria-label="${ticket.ticketNo}的新负责人"])`).click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: finalCandidate!.displayName }).click()
  const [replaced] = await Promise.all([
    page.waitForResponse((response) => response.url().endsWith(`/fd/v1/users/${second.id}/roles`) && response.request().method() === 'PUT'),
    dialog.getByRole('button', { name: '确认交接' }).click(),
  ])
  expect(replaced.status(), await replaced.text()).toBe(200)
  await expect(dialog).toBeHidden()
  const afterRoles = await data<User>(await request.get(`/fd/v1/users/${second.id}`, { headers: auth(adminToken) }))
  expect(afterRoles.roles.some((role) => role.code === 'IT_SUPPORT')).toBe(false)
  assignments = await data<Assignment[]>(await request.get(`/fd/v1/admin/users/${second.id}/active-ticket-assignments`, {
    headers: auth(adminToken),
  }))
  expect(assignments).toHaveLength(0)
})
