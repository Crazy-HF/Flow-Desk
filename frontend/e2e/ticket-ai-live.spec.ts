import { expect, test } from '@playwright/test'

import { expectNoHorizontalOverflow } from './support/overflow'

// 仅由 TicketAiBrowserAcceptanceManual 在临时 MySQL/Redis 和模型替身下启动。
test.skip(process.env.FLOWDESK_AI_BROWSER_ACCEPTANCE !== 'YES', '需要隔离的 AI 浏览器验收栈')

test('真实 HTTP：来源预览、删敏确认、生成、持久化历史', async ({ page }) => {
  const ticketNo = process.env.FLOWDESK_AI_LIVE_TICKET_NO
  expect(ticketNo).toBeTruthy()
  const api = `/fd/v1/tickets/${ticketNo}/ai-suggestions`
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))

  await page.goto('/login')
  await page.getByPlaceholder('请输入登录名').fill('it')
  await page.getByPlaceholder('请输入密码').fill('123456')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible()
  await page.goto(`/tickets/${ticketNo}`)
  await expect(page.getByRole('heading', { name: 'AI 处理建议' })).toBeVisible()

  const previewPromise = page.waitForResponse((response) =>
    response.url().includes(`${api}/sources-preview`) && response.request().method() === 'GET',
  )
  await page.getByRole('button', { name: '生成建议' }).click()
  const preview = await previewPromise
  expect(preview.status(), await preview.text()).toBe(200)
  const previewBody = (await preview.json()) as {
    data: { sourceDigest: string; sources: { label: string; title: string; processExcerpt: string }[] }
  }
  expect(previewBody.data.sources).toHaveLength(1)
  expect(previewBody.data.sources[0]?.label).toBe('[S1]')
  expect(previewBody.data.sources[0]?.title).toBe('虚构投影仪来源')
  expect(previewBody.data.sources[0]?.processExcerpt).toBe('先核实连接线与输入源。')

  const dialog = page.getByRole('dialog', { name: '确认发送给 AI 的工单内容' })
  await expect(dialog).toContainText('先核实连接线与输入源。')
  await dialog.getByPlaceholder('请输入发送给 AI 的标题').fill('虚构投影仪报障')
  await dialog.getByPlaceholder('请输入发送给 AI 的问题描述').fill('连接后幕布无信号，原因待核实。')
  const generatePromise = page.waitForResponse((response) =>
    response.url().endsWith(api) && response.request().method() === 'POST',
  )
  await dialog.getByRole('button', { name: '确认并生成' }).click()
  const generated = await generatePromise
  expect(generated.status(), await generated.text()).toBe(200)
  const body = generated.request().postDataJSON() as Record<string, unknown>
  expect(body.sourceDigest).toBe(previewBody.data.sourceDigest)
  expect(body.title).toBe('虚构投影仪报障')
  expect(body.description).toBe('连接后幕布无信号，原因待核实。')
  expect(JSON.stringify(body)).not.toContain('13800000000')

  const result = (await generated.json()) as {
    data: { sources: { label: string; processExcerpt: string }[]; promptVersion: string }
  }
  expect(result.data.promptVersion).toBe('v5')
  expect(result.data.sources[0]?.label).toBe('[S1]')
  await expect(page.getByRole('article', { name: '已保存的 AI 建议' }))
    .toContainText('参考 [S1]')

  // 刷新页面经真实会话恢复后重新读 MySQL 历史，避免只验证内存中的生成结果。
  await page.reload()
  await expect(page.getByRole('heading', { name: 'AI 处理建议' })).toBeVisible()
  const historyPromise = page.waitForResponse((response) =>
    response.url().includes(api) && response.request().method() === 'GET',
  )
  await page.getByRole('button', { name: '查看历史' }).click()
  const history = await historyPromise
  expect(history.status(), await history.text()).toBe(200)
  const historyBody = (await history.json()) as {
    data: { totalElements: number; items: { sources: { label: string }[] }[] }
  }
  expect(historyBody.data.totalElements).toBe(1)
  expect(historyBody.data.items[0]?.sources[0]?.label).toBe('[S1]')
  await expect(page.getByRole('article', { name: '已保存的 AI 建议' }))
    .toContainText('虚构投影仪来源')
  await expectNoHorizontalOverflow(page)
  expect(errors).toEqual([])
})
