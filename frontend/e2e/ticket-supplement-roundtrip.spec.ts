import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expectNoHorizontalOverflow } from './support/overflow'

/**
 * 完整工单状态机片 B：补充往返。
 *
 * <p>覆盖四个界面事实，全部从真实点击产生，不用 SQL 造状态：</p>
 * <p>1. **请求补充**：IT 负责人从「处理中」把工单推进到「待员工补充」，并拿到服务端算出的期限；
 * 进入该状态后负责人**不能再提交解决结果**（`docs/kickoff.md` 4.11 末两条规则），只剩撤回入口。</p>
 * <p>2. **期限的读法按视角不同**：负责人看「补充期限」，提交人看「补充截止时间」；两者都要看到
 * 「到期不会自动处理」这句——本版本没有超时自动任务，界面不能暗示"到点系统会关单"。</p>
 * <p>3. **提交补充信息**：提交人补充后工单回到「处理中」，期限从属性栏消失，原负责人继续处理。</p>
 * <p>4. **时间线不可变**：请求补充与提交补充各留下一条记录，顺序与正文都能在页面上读到。</p>
 *
 * <p><strong>这条用例会向演示库写入一张工单且无法通过接口撤销</strong>，清理顺序与 SQL 见
 * `frontend/e2e/tickets.spec.ts` 文件头（先删 `ticket_participant` 与 `ticket_record`，再删 `ticket`）。</p>
 */

const password = process.env.E2E_PASSWORD ?? '123456'
const employeeUsername = process.env.E2E_EMPLOYEE_USERNAME ?? 'employee'
const itUsername = process.env.E2E_IT_USERNAME ?? 'it'

/** 评审产物目录：与其它视觉评审放在同一处。 */
const reviewDir = resolve(process.cwd(), '../.ui-craft/reviews/2026-10-07-slice-b-supplement-roundtrip')
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

test('片 B：IT 请求补充、员工补充，工单回到处理中且 IT 可继续处理', async ({ page }) => {
  test.setTimeout(180_000)
  const suffix = Date.now().toString().slice(-6)
  const title = `E2E 片B补充 ${suffix}`
  const description = 'E2E 片 B 用例：验证处理中 → 待员工补充 → 处理中的往返，以及两个视角下的期限说明。'
  const requestContent = '请补充打印机型号，以及打印时弹出的完整报错文字。'
  const supplementContent = '型号是 L3153，报错是"打印机未响应"，重启后仍然出现。'
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

  // ---------- 2. IT 领取并请求补充 ----------
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

  const requested = await performAction(
    page,
    '请求补充信息',
    requestContent,
    '/actions/request-supplement',
  )
  expect(requested.status()).toBe(200)
  record(steps, 'request-supplement', requested, '进入待员工补充并生成补充期限')

  await expect(page.locator('.ticket-meta')).toContainText('待员工补充')
  // 待补充期间负责人不能再提交解决结果（docs/kickoff.md 4.11），剩下的入口是撤回，
  // 外加片 C 的调整分类、调整优先级与转交
  expect(await actionLabels(page)).toEqual([
    '撤回补充请求',
    '调整分类',
    '调整优先级',
    '转交工单',
  ])
  // 负责人视角：这是"等员工回到什么时候"
  const itFacts = page.locator('.ticket-facts')
  await expect(itFacts).toContainText('补充期限')
  await expect(itFacts).toContainText('到期不会自动处理')
  await page.screenshot({
    path: resolve(reviewDir, 'it-waiting-for-supplement-1440.png'),
    fullPage: true,
  })
  await signOut(page)

  // ---------- 3. 员工看请求与截止时间，再补充 ----------
  await signIn(page, employeeUsername)
  await page.goto(`/tickets/${ticketNo}`)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)
  await expect(page.locator('.ticket-meta')).toContainText('待员工补充')
  // 提交人在待补充上只能做一件事：补充信息
  expect(await actionLabels(page)).toEqual(['提交补充信息'])

  const employeeFacts = page.locator('.ticket-facts')
  await expect(employeeFacts).toContainText('补充截止时间')
  await expect(employeeFacts).toContainText('到期不会自动关闭工单')
  // 请求内容进入时间线，员工能读到"需要补充什么"
  const requestRecord = page
    .locator('.ticket-timeline__item')
    .filter({ hasText: '请求补充信息' })
  await expect(requestRecord).toHaveCount(1)
  await expect(requestRecord).toContainText(requestContent)
  await page.screenshot({
    path: resolve(reviewDir, 'employee-waiting-for-supplement-1440.png'),
    fullPage: true,
  })

  const supplemented = await performAction(
    page,
    '提交补充信息',
    supplementContent,
    '/actions/supplement',
  )
  expect(supplemented.status()).toBe(200)
  record(steps, 'supplement', supplemented, '员工补充后回到处理中')

  // ---------- 4. 回到处理中：期限消失、员工侧无动作、时间线两条新记录 ----------
  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  await expect(page.locator('.ticket-action-panel')).toHaveCount(0)
  await expect(employeeFacts).not.toContainText('补充截止时间')
  const supplementRecord = page
    .locator('.ticket-timeline__item')
    .filter({ hasText: '提交补充信息' })
  await expect(supplementRecord).toHaveCount(1)
  await expect(supplementRecord).toContainText(supplementContent)
  await page.screenshot({
    path: resolve(reviewDir, 'employee-back-to-processing-1440.png'),
    fullPage: true,
  })

  // ---------- 5. 补充之后 IT 仍能继续处理 ----------
  await signOut(page)
  await signIn(page, itUsername)
  await page.goto(`/tickets/${ticketNo}`)
  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  expect(await actionLabels(page)).toEqual([
    '记录处理过程',
    '提交解决结果',
    '请求补充信息',
    '调整分类',
    '调整优先级',
    '转交工单',
  ])

  const processed = await performAction(
    page,
    '记录处理过程',
    '已按补充的型号重新安装驱动，继续观察打印队列。',
    '/actions/add-processing-record',
  )
  expect(processed.status()).toBe(200)
  record(steps, 'add-processing-record', processed, '补充后可继续处理')

  // ---------- 6. 窄屏不溢出 ----------
  await page.setViewportSize({ width: 375, height: 812 })
  await expectNoHorizontalOverflow(page, '片 B 详情页在 375 下被撑宽')
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
