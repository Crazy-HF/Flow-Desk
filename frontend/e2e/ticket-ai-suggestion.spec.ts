import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

import { expectNoHorizontalOverflow } from './support/overflow'

const ticketNo = 'FD-20261023-001'
const itUser = {
  id: 4,
  username: 'it',
  displayName: '演示 IT 支持人员',
  roles: ['IT_SUPPORT'],
  permissions: ['TICKET_VIEW_PARTICIPATED', 'TICKET_PROCESS', 'TICKET_AI_ASSIST'],
}
const reply = (data: unknown) => ({ code: 'OK', message: 'OK', data })
const ticket = {
  ticketNo,
  title: '三楼投影仪显示员工手机号 13800000000',
  description: '投影仪无信号，报障人电话 13800000000。',
  category: { id: 1, name: '办公设备' },
  priority: 'MEDIUM',
  status: 'PROCESSING',
  requester: { id: 1, displayName: '演示员工' },
  assignee: { id: 4, displayName: '演示 IT 支持人员' },
  version: 0,
  createdAt: '2026-10-23T01:00:00Z',
  updatedAt: '2026-10-23T01:00:00Z',
  allowedActions: ['add-processing-record'],
  relations: [],
  attachments: [],
}

const suggestion = {
  id: 12,
  ticketNo,
  requestId: '11111111-2222-4333-8444-555555555555',
  input: {
    title: '三楼投影仪无信号',
    description: '连接后幕布显示无信号。',
    category: '办公设备',
    priority: 'MEDIUM',
    status: 'PROCESSING',
  },
  summary: '三楼投影仪连接后无信号，原因待核实。',
  questions: ['输入源是否与连接接口一致？'],
  suggestedSteps: ['检查线缆与输入源设置。'],
  model: 'deepseek-flash',
  promptVersion: 'v1',
  inputTokens: 120,
  outputTokens: 85,
  createdAt: '2026-10-23T02:00:00Z',
}

async function mockApi(page: Page, onGenerate: (body: Record<string, unknown>) => void): Promise<void> {
  let saved = false
  await page.route('**/fd/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/fd/v1', '')
    if (path === '/auth/refresh') {
      await route.fulfill({ json: reply({ accessToken: 'ai-ui-token', tokenType: 'Bearer', expiresIn: 900 }) })
      return
    }
    if (path === '/auth/me') {
      await route.fulfill({ json: reply(itUser) })
      return
    }
    if (path === '/auth/login') {
      await route.fulfill({ json: reply({ accessToken: 'ai-ui-token', tokenType: 'Bearer', expiresIn: 900, user: itUser }) })
      return
    }
    if (path === `/tickets/${ticketNo}`) {
      await route.fulfill({ json: reply(ticket) })
      return
    }
    if (path === `/tickets/${ticketNo}/records`) {
      await route.fulfill({ json: reply({ items: [], page: 1, size: 20, totalElements: 0, totalPages: 0 }) })
      return
    }
    if (path === `/tickets/${ticketNo}/ai-suggestions` && request.method() === 'POST') {
      onGenerate(request.postDataJSON() as Record<string, unknown>)
      saved = true
      await route.fulfill({ json: reply(suggestion) })
      return
    }
    if (path === `/tickets/${ticketNo}/ai-suggestions` && request.method() === 'GET') {
      await route.fulfill({ json: reply({
        items: saved ? [suggestion] : [],
        page: 1,
        size: 10,
        totalElements: saved ? 1 : 0,
        totalPages: saved ? 1 : 0,
      }) })
      return
    }
    await route.fulfill({ status: 404, json: { code: 'RESOURCE_NOT_FOUND', message: '', data: null } })
  })
}

async function openTicket(page: Page): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('请输入登录名').fill('it')
  await page.getByPlaceholder('请输入密码').fill('test')
  await page.getByRole('button', { name: '登录' }).click()
  // 窄屏侧栏折叠为菜单按钮；顶栏账号入口在两种视口都可见。
  await expect(page.getByRole('button', { name: '账号' })).toBeVisible()
  await page.goto(`/tickets/${ticketNo}`)
  await expect(page.getByRole('heading', { name: 'AI 处理建议' })).toBeVisible()
}

test('负责人先删敏再生成，保存历史并只预填处理记录', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const sent: Record<string, unknown>[] = []
  await mockApi(page, (body) => sent.push(body))
  await openTicket(page)

  await page.getByRole('button', { name: '生成建议' }).click()
  const dialog = page.getByRole('dialog', { name: '确认发送给 AI 的工单内容' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('附件和处理记录不会发送')
  await dialog.getByPlaceholder('请输入发送给 AI 的标题').fill('三楼投影仪无信号')
  await dialog.getByPlaceholder('请输入发送给 AI 的问题描述').fill('连接后幕布显示无信号。')
  await dialog.getByRole('button', { name: '确认并生成' }).click()

  await expect(dialog).not.toBeVisible()
  await expect(page.getByRole('article', { name: '已保存的 AI 建议' })).toContainText('原因待核实')
  expect(sent).toHaveLength(1)
  expect(sent[0]).toMatchObject({
    version: 0,
    title: '三楼投影仪无信号',
    description: '连接后幕布显示无信号。',
  })
  expect(String(sent[0]?.requestId)).toMatch(/^[0-9a-f-]{36}$/)
  expect(JSON.stringify(sent[0])).not.toContain('13800000000')
  expect(JSON.stringify(sent[0])).not.toContain('category')

  await page.getByRole('button', { name: '填入处理记录草稿' }).click()
  const recordDialog = page.locator('.el-message-box')
  await expect(recordDialog).toBeVisible()
  await expect(recordDialog.locator('textarea')).toHaveValue(/AI 草稿：以下内容尚未执行/)
  await expect(recordDialog.locator('textarea')).toHaveValue(/输入源是否与连接接口一致/)
  await recordDialog.getByRole('button', { name: '取消' }).click()
  await expect(recordDialog).not.toBeVisible()
  await expect(page.locator('.ticket-timeline__item')).toHaveCount(0)

  await expectNoHorizontalOverflow(page)
  const imagePath = testInfo.outputPath('ai-ticket-desktop.png')
  await page.screenshot({ path: imagePath, fullPage: true })
  await testInfo.attach('ai-ticket-desktop', { path: imagePath })
})

test('375 窄屏可查看历史且页面不横向溢出', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 375, height: 812 })
  await mockApi(page, () => {})
  await openTicket(page)
  await page.getByRole('button', { name: '查看历史' }).click()
  await expect(page.getByText('还没有 AI 建议')).toBeVisible()
  await expectNoHorizontalOverflow(page)
  const imagePath = testInfo.outputPath('ai-ticket-mobile.png')
  await page.screenshot({ path: imagePath, fullPage: true })
  await testInfo.attach('ai-ticket-mobile', { path: imagePath })
})
