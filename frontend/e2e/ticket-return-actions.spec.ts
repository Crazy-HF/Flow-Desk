import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expectNoHorizontalOverflow } from './support/overflow'

/**
 * 完整工单状态机片 A：员工反馈「问题仍未解决」，工单从「待员工确认」退回「处理中」。
 *
 * <p>本片有两个动作，这里只覆盖能从界面自然到达的那一个：`report-unresolved`（提交人在待确认
 * 状态反馈未解决）。另一个 `withdraw-supplement-request`（当前负责人撤回补充请求）需要工单先处于
 * 「待员工补充」；片 B 之前只能靠 SQL 置位，片 B 的 `request-supplement` 落地后已由
 * `ticket-supplement-roundtrip.spec.ts` 从界面自然覆盖，本文件因此不再单独跑它。</p>
 *
 * <p>这条用例证明的是界面上的三件事：动作按钮确实由详情的 `allowedActions` 驱动（提交人在待确认
 * 时同时看到「确认已解决」和「问题仍未解决」）；反馈未解决后状态真的回到处理中且员工侧不再有动作；
 * 退回之后 IT 还能继续处理——往返是闭环的，不是把工单推进死胡同。</p>
 *
 * <p><strong>这条用例会向演示库写入一张工单且无法通过接口撤销</strong>，清理顺序与 SQL 见
 * `frontend/e2e/tickets.spec.ts` 文件头（先删 `ticket_participant` 与 `ticket_record`，再删 `ticket`）。</p>
 */

const password = process.env.E2E_PASSWORD ?? '123456'
const employeeUsername = process.env.E2E_EMPLOYEE_USERNAME ?? 'employee'
const itUsername = process.env.E2E_IT_USERNAME ?? 'it'

/** 评审产物目录：与其它视觉评审放在同一处。 */
const reviewDir = resolve(process.cwd(), '../.ui-craft/reviews/2026-10-06-slice-a-return-actions')
mkdirSync(reviewDir, { recursive: true })

interface StepEvidence {
  step: string
  status: number
  traceId?: string
  detail: string
}

function nav(page: Page) {
  return page.getByRole('navigation', { name: '主导航' })
}

async function signIn(page: Page, username: string): Promise<void> {
  await page.goto('/login')
  await expect(page.getByPlaceholder('请输入登录名')).toBeVisible()
  await page.getByPlaceholder('请输入登录名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(nav(page)).toBeVisible()
}

async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: '账号' }).click()
  await page.getByText('退出登录').click()
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByPlaceholder('请输入登录名')).toBeVisible()
}

/** 动作区当前摆出的按钮文案；没有动作区时是空数组。 */
async function actionLabels(page: Page): Promise<string[]> {
  return page.locator('.ticket-action .el-button').allInnerTexts()
}

/** 点动作按钮 → 在确认框里写内容 → 确认；返回动作接口的响应。 */
async function performAction(
  page: Page,
  label: string,
  content: string | undefined,
  expectedUrl: string,
): Promise<Awaited<ReturnType<Page['waitForResponse']>>> {
  const button = page.locator('.ticket-action .el-button').filter({ hasText: label })
  await expect(button).toBeVisible()
  await button.click()

  const dialog = page.locator('.el-message-box')
  await expect(dialog).toBeVisible()
  if (content !== undefined) {
    await dialog.locator('textarea').fill(content)
  }

  const response = page.waitForResponse(
    (item) => item.url().includes(expectedUrl) && item.request().method() === 'POST',
  )
  await dialog.locator('.el-message-box__btns .el-button--primary').click()
  return await response
}

function record(
  steps: StepEvidence[],
  step: string,
  response: { status(): number; headers(): Record<string, string> },
  detail: string,
) {
  const traceId = response.headers()['x-trace-id']
  expect(traceId, `${step} 缺少 X-Trace-Id`).toBeTruthy()
  steps.push({ step, status: response.status(), traceId, detail })
}

test('片 A：员工反馈问题仍未解决，工单退回处理中且 IT 可以继续处理', async ({ page }) => {
  test.setTimeout(180_000)
  const suffix = Date.now().toString().slice(-6)
  const title = `E2E 片A未解决 ${suffix}`
  const description = 'E2E 片 A 用例：验证待确认 → 问题未解决 → 处理中的往返，以及退回后 IT 仍可继续处理。'
  const unresolvedReason = '按说明重启后仍然打印乱码，问题没有解决。'
  const steps: StepEvidence[] = []
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  // ---------- 1. 员工建单 ----------
  await page.setViewportSize({ width: 1440, height: 900 })
  await signIn(page, employeeUsername)
  await nav(page).getByRole('link', { name: '新建工单', exact: true }).click()
  await expect(page).toHaveURL(/\/tickets\/new$/)
  await page.getByLabel('标题').fill(title)
  await page.getByLabel('问题描述').fill(description)
  await page.locator('.ticket-priority__option').filter({ hasText: '无法继续工作' }).click()
  await page.locator('.ticket-form__control').click()
  await page.locator('.el-select-dropdown__item:visible').first().click()
  await page.keyboard.press('Escape')

  const creating = page.waitForResponse(
    (item) => item.url().endsWith('/fd/v1/tickets') && item.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '提交工单' }).click()
  const created = await creating
  expect(created.status()).toBe(201)
  await expect(page).toHaveURL(/\/tickets\/FD-\d{8}-\d{3}$/)
  const ticketNo = (await page.locator('.ticket-meta__no').innerText()).trim()
  record(steps, 'create', created, `${ticketNo} 由员工通过界面创建`)
  await signOut(page)

  // ---------- 2. IT 领取并提交解决结果 ----------
  await signIn(page, itUsername)
  await nav(page).getByRole('link', { name: 'IT 工作台', exact: true }).click()
  await page.getByLabel('工单关键词').fill(ticketNo)
  await page.getByRole('button', { name: '查询' }).click()
  const queueRow = page.locator('.admin-table tbody tr').filter({ hasText: ticketNo })
  await expect(queueRow).toHaveCount(1)
  await queueRow.locator('.ticket-cell__title').click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)

  const claimed = await performAction(page, '领取工单', undefined, '/actions/claim')
  expect(claimed.status()).toBe(200)
  record(steps, 'claim', claimed, 'IT 领取成功')

  const resolved = await performAction(
    page,
    '提交解决结果',
    '已更换打印服务器证书，请重启后确认是否恢复。',
    '/actions/submit-resolution',
  )
  expect(resolved.status()).toBe(200)
  record(steps, 'submit-resolution', resolved, '进入待员工确认并生成确认期限')
  await expect(page.locator('.ticket-meta')).toContainText('待员工确认')

  /**
   * 片 A 的关键断言：提交人此时应该同时看到「确认已解决」与「问题仍未解决」。
   * 两个动作的前置条件完全相同（待确认 + 本人是提交人 + TICKET_REQUESTER_ACTION），
   * 所以顺序只由前端登记表决定，与后端拼接顺序无关。
   *
   * <p>片 D 起这一格还多一个撤销入口，2026-10-08 两阶段撤销后它叫「申请撤销工单」：
   * 待确认已经有人负责，提交人只能发起申请，由当前负责人同意或驳回，所以排在最后。</p>
   */
  await signOut(page)
  await signIn(page, employeeUsername)
  await page.goto(`/tickets/${ticketNo}`)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)
  await expect(page.locator('.ticket-meta')).toContainText('待员工确认')
  expect(await actionLabels(page)).toEqual(['确认已解决', '问题仍未解决', '申请撤销工单'])
  await page.screenshot({
    path: resolve(reviewDir, 'employee-waiting-confirmation-1440.png'),
    fullPage: true,
  })

  // ---------- 3. 反馈问题未解决 ----------
  const unresolved = await performAction(
    page,
    '问题仍未解决',
    unresolvedReason,
    '/actions/report-unresolved',
  )
  expect(unresolved.status()).toBe(200)
  record(steps, 'report-unresolved', unresolved, '工单退回处理中')

  // 状态回到处理中：员工不是负责人，处理动作一个都不出现；留下的只有他自己那一格申请撤销
  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  expect(await actionLabels(page)).toEqual(['申请撤销工单'])
  // 未解决反馈进入时间线，解决结果仍在（不可变）
  await expect(page.locator('.ticket-timeline__item').last()).toContainText('反馈问题仍未解决')
  await expect(page.locator('.ticket-timeline__item').filter({ hasText: '提交解决结果' })).toHaveCount(1)
  await page.screenshot({
    path: resolve(reviewDir, 'employee-back-to-processing-1440.png'),
    fullPage: true,
  })

  // ---------- 4. 退回之后 IT 仍能继续处理 ----------
  await signOut(page)
  await signIn(page, itUsername)
  await page.goto(`/tickets/${ticketNo}`)
  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  // 处理中 + 本人负责人：七个动作（片 B 起「请求补充信息」、片 C 起调整与转交、片 D 起关闭都在这里）
  expect(await actionLabels(page)).toEqual([
    '记录处理过程',
    '提交解决结果',
    '请求补充信息',
    '调整分类',
    '调整优先级',
    '转交工单',
    '关闭工单',
  ])

  const processed = await performAction(
    page,
    '记录处理过程',
    '已确认证书更换生效，补充驱动为最新版本，继续观察打印队列。',
    '/actions/add-processing-record',
  )
  expect(processed.status()).toBe(200)
  record(steps, 'add-processing-record', processed, '退回后可继续处理')

  // ---------- 5. 窄屏不溢出 ----------
  await page.setViewportSize({ width: 375, height: 812 })
  await expectNoHorizontalOverflow(page, '片 A 详情页在 375 下被撑宽')
  await page.screenshot({
    path: resolve(reviewDir, 'it-detail-375.png'),
    fullPage: true,
  })

  expect(pageErrors, '页面上出现了 JavaScript 错误').toEqual([])

  writeFileSync(
    resolve(reviewDir, 'runtime-evidence.json'),
    `${JSON.stringify({ ticketNo, title, steps }, null, 2)}\n`,
    'utf8',
  )
})
