import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expectNoHorizontalOverflow } from './support/overflow'

/**
 * 完整工单状态机片 D（结束路径）与两阶段撤销（2026-10-08 规则变更）。
 *
 * <p>覆盖四个界面事实，全部从真实点击产生：</p>
 * <p>1. **两阶段撤销**（片 D 建立时这里是"员工在「处理中」直接撤销"，2026-10-08 规则变更后改写）：
 * 提交人只能**申请**撤销，界面出现「撤销申请待处理」块；当前负责人读到申请理由后同意，
 * 工单才进入终态「已取消」。撤销是业务终止而不是删除：负责人与此前的处理记录都保留。</p>
 * <p>2. **IT 异常关闭**：当前负责人用标准原因（这里是"超出支持范围"）关闭工单，进入「已关闭」，
 * 时间线上留下关闭方式、标准原因与说明。</p>
 * <p>3. **关闭原因为「重复工单」时的条件输入**：只有选中它，重复单号输入框才出现；
 * 改回其他原因时输入框消失——而"消失"与"请求里不带这个字段"是同一件事
 * （服务端对多传的单号返回 `400`，不是忽略）。</p>
 * <p>4. **一次完整的撤销申请往返**：申请 → 驳回（工单留在「处理中」，申请失效）→ 撤回申请后再申请 →
 * 同意。它证明"驳回不结束工单"和"提交人可以改主意"这两件事在界面上真的成立。</p>
 *
 * <p><strong>为什么这些动作不需要临时账号</strong>：`close` 只要求"当前负责人 + 处理中"，
 * 撤销申请的四个动作只要求"提交人本人"或"当前负责人"，用演示种子里的 `employee` 与 `it`
 * 就能走完整条链路；片 C 之所以要临时建第二个 IT，是因为转交的候选人恰好排除提交人与当前负责人。</p>
 *
 * <p><strong>这些用例都会向演示库写入工单</strong>（接口没有删除能力）。清理顺序与
 * `frontend/e2e/tickets.spec.ts` 文件头一致：先删 `ticket_relation` / `ticket_record` /
 * `ticket_participant`，再删 `ticket`：</p>
 *
 * ```sql
 * DELETE r FROM ticket_relation r JOIN ticket t ON t.id = r.source_ticket_id
 *  WHERE t.title LIKE 'E2E 片D%';
 * DELETE r FROM ticket_record r JOIN ticket t ON t.id = r.ticket_id
 *  WHERE t.title LIKE 'E2E 片D%';
 * DELETE p FROM ticket_participant p JOIN ticket t ON t.id = p.ticket_id
 *  WHERE t.title LIKE 'E2E 片D%';
 * DELETE FROM ticket WHERE title LIKE 'E2E 片D%';
 * ```
 */

const password = process.env.E2E_PASSWORD ?? '123456'
const employeeUsername = process.env.E2E_EMPLOYEE_USERNAME ?? 'employee'
const itUsername = process.env.E2E_IT_USERNAME ?? 'it'

/**
 * 负责人显示名默认**从当前登录身份读**，不写死常量：显示名属于演示数据，会随
 * `R__seed_demo_data.sql` 变化，而本机演示库又可能被长期手工演进（2026-10-06 CI 的 `core-e2e`
 * 就因为写死的 `IT 支持人员` 与种子里的 `演示 IT 支持人员` 不一致而失败，本机却是通过的）。
 * 确实需要指定时用 `E2E_IT_DISPLAY_NAME` 覆盖。
 */
const itDisplayNameOverride = process.env.E2E_IT_DISPLAY_NAME

/**
 * 评审产物目录：与其它视觉评审放在同一处。
 *
 * <p>用本轮的目录而不是片 D 的目录：片 D 那几张截图与 `runtime-evidence-cancel.json` 记录的是
 * **规则变更之前**的"处理中直接撤销"，属于历史证据；混在同一目录里会让"哪张是哪条规则"
 * 说不清。</p>
 */
const reviewDir = resolve(process.cwd(), '../.ui-craft/reviews/2026-10-08-slice-e-two-phase-cancel')
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

function writeEvidence(fileName: string, payload: unknown): void {
  writeFileSync(resolve(reviewDir, fileName), `${JSON.stringify(payload, null, 2)}\n`, 'utf8')
}

/** 员工建单；返回工单编号。 */
async function createTicketAsEmployee(
  page: Page,
  title: string,
  description: string,
  steps: StepEvidence[],
): Promise<string> {
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
  return ticketNo
}

/**
 * IT 从工作台领取刚建的工单：关闭要求工单处于「处理中」，撤销则是要证明"IT 已经在做了"也能撤销。
 *
 * <p>返回这名 IT 的显示名（取自顶部身份区），供"负责人保留"的断言使用——不写死演示数据里的名字。</p>
 */
async function claimAsIt(page: Page, ticketNo: string, steps: StepEvidence[]): Promise<string> {
  await signIn(page, itUsername)
  const itDisplayName =
    itDisplayNameOverride ??
    (await page.getByRole('banner').locator('.app-header__identity').innerText()).trim()
  expect(itDisplayName, '顶部栏没有展示当前 IT 身份，无法断言负责人').not.toBe('')

  await nav(page).getByRole('link', { name: 'IT 工作台', exact: true }).click()
  await page.getByLabel('工单关键词').fill(ticketNo)
  await page.getByRole('button', { name: '查询' }).click()
  const queueRow = page.locator('.admin-table tbody tr').filter({ hasText: ticketNo })
  await expect(queueRow).toHaveCount(1)
  await queueRow.locator('.ticket-cell__title').click()
  await expect(page.locator('.ticket-meta__no')).toHaveText(ticketNo)

  await page.locator('.ticket-action .el-button').filter({ hasText: '领取工单' }).click()
  const dialog = page.locator('.el-message-box')
  await expect(dialog).toBeVisible()
  const claiming = page.waitForResponse((item) => item.url().includes('/actions/claim'))
  await dialog.locator('.el-message-box__btns .el-button--primary').click()
  const claimed = await claiming
  expect(claimed.status()).toBe(200)
  record(steps, 'claim', claimed, 'IT 领取成功，工单进入「处理中」')
  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  return itDisplayName
}

/** 打开当前工单详情（换账号后直接按编号进入）。 */
async function openTicket(page: Page, ticketNo: string, title: string): Promise<void> {
  await page.goto(`/tickets/${ticketNo}`)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)
}

/** 在关闭弹窗的选择器里挑一个关闭原因（真人先展开下拉、再点那一行）。 */
async function chooseCloseReason(page: Page, label: string): Promise<void> {
  const dialog = page.locator('.el-message-box')
  await dialog.locator('.el-select').first().click()
  const items = page.locator('.el-select-dropdown__item:visible')
  await expect(items.first()).toBeVisible()
  const option = items.filter({ hasText: label }).first()
  await expect(option).toBeVisible()
  await option.click()
}

/** 关闭弹窗里的重复单号输入：条件成立时才有这个元素。 */
function duplicateInput(page: Page) {
  return page.locator('#ticket-action-conditional-text')
}

test('两阶段撤销场景一：员工申请撤销、IT 同意后工单进入已取消', async ({ page }) => {
  test.setTimeout(240_000)
  const suffix = Date.now().toString().slice(-6)
  const title = `E2E 片D撤销 ${suffix}`
  const description =
    'E2E 两阶段撤销场景一：工单已被 IT 领取（处理中），提交人申请撤销，当前负责人同意。'
  const requestReason = '问题已经自行解决，不需要 IT 再处理了。'
  const steps: StepEvidence[] = []
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.setViewportSize({ width: 1440, height: 900 })
  const ticketNo = await createTicketAsEmployee(page, title, description, steps)
  await signOut(page)

  // IT 先领取：要证明的正是"已经有人在处理"时提交人只能申请，不能单方面终止
  const itDisplayName = await claimAsIt(page, ticketNo, steps)
  await signOut(page)

  // ---------- 员工在「处理中」上申请撤销 ----------
  await signIn(page, employeeUsername)
  await openTicket(page, ticketNo, title)
  expect(await actionLabels(page)).toEqual(['申请撤销工单'])
  // 还没有申请时，这一块整块不渲染
  await expect(page.locator('.ticket-cancel-request')).toHaveCount(0)

  await page.locator('.ticket-action .el-button').filter({ hasText: '申请撤销工单' }).click()
  const requestDialog = page.locator('.el-message-box')
  await expect(requestDialog).toBeVisible()
  // 申请只写一段说明：没有要选的目标值，也没有条件输入
  await expect(requestDialog.locator('.el-select')).toHaveCount(0)
  await expect(duplicateInput(page)).toHaveCount(0)
  // 必须写明"这一下不会取消工单"，否则用户会以为按下去就结束了
  await expect(requestDialog).toContainText('不会立刻取消')
  await requestDialog.locator('textarea').fill(requestReason)

  const requesting = page.waitForResponse((item) => item.url().includes('/actions/request-cancel'))
  await requestDialog.locator('.el-message-box__btns .el-button--primary').click()
  const requested = await requesting
  expect(requested.status()).toBe(200)
  record(steps, 'request-cancel', requested, '提交人在「处理中」发起撤销申请，工单状态不变')

  // 状态与期限都不变，所以"待批准"只能由详情单独返回的 cancelRequest 画出来
  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  const pendingPanel = page.locator('.ticket-cancel-request')
  await expect(pendingPanel).toBeVisible()
  await expect(pendingPanel).toContainText('你已申请撤销这张工单')
  await expect(pendingPanel).toContainText(requestReason)
  // 本版本没有超时任务：期限必须写明"到期不自动处理"
  await expect(pendingPanel).toContainText('到期不会自动处理')
  await expect(page.locator('.ticket-action .el-button')).toHaveText(['撤回撤销申请'])

  // 时间线留下申请记录（含理由），与「待受理直接撤销」的撤销工单是两种事实
  const requestRecord = page.locator('.ticket-timeline__item').filter({ hasText: '申请撤销工单' })
  await expect(requestRecord).toHaveCount(1)
  await expect(requestRecord).toContainText(requestReason)

  await page.screenshot({
    path: resolve(reviewDir, 'cancel-request-pending-1440.png'),
    fullPage: true,
  })
  await page.setViewportSize({ width: 375, height: 812 })
  await expectNoHorizontalOverflow(page, '待批准撤销提示在 375 下被撑宽')
  await page.screenshot({
    path: resolve(reviewDir, 'cancel-request-pending-375.png'),
    fullPage: true,
  })
  // 375 下侧栏收进抽屉，"账号"入口不在可访问树里：还原视口再换账号
  await page.setViewportSize({ width: 1440, height: 900 })
  await signOut(page)

  // ---------- IT 读到申请理由后同意 ----------
  await signIn(page, itUsername)
  await openTicket(page, ticketNo, title)
  const decisionPanel = page.locator('.ticket-cancel-request')
  await expect(decisionPanel).toContainText('正在等你处理')
  await expect(decisionPanel).toContainText(requestReason)
  // 申请期间工单不冻结：原有的处理动作一个都没少，末尾多出两个决策入口
  expect(await actionLabels(page)).toEqual([
    '记录处理过程',
    '提交解决结果',
    '请求补充信息',
    '调整分类',
    '调整优先级',
    '转交工单',
    '关闭工单',
    '同意撤销',
    '驳回撤销申请',
  ])

  await page.locator('.ticket-action .el-button').filter({ hasText: '同意撤销' }).click()
  const approveDialog = page.locator('.el-message-box')
  await expect(approveDialog).toBeVisible()
  // 同意不需要理由：弹窗里不该出现输入框
  await expect(approveDialog.locator('textarea')).toHaveCount(0)

  const approving = page.waitForResponse((item) => item.url().includes('/actions/approve-cancel'))
  await approveDialog.locator('.el-message-box__btns .el-button--primary').click()
  const approved = await approving
  expect(approved.status()).toBe(200)
  record(steps, 'approve-cancel', approved, '当前负责人同意撤销，工单进入「已取消」')

  await expect(page.locator('.ticket-meta')).toContainText('已取消')
  // 撤销是业务终止不是删除：负责人保留（这里用的是登录身份读到的显示名），并落下结束时间
  await expect(page.locator('.ticket-facts')).toContainText(itDisplayName)
  await expect(page.locator('.ticket-facts')).toContainText('结束时间')
  // 终态不允许残留待决申请：这一块随之消失，动作区也不再有按钮
  await expect(page.locator('.ticket-cancel-request')).toHaveCount(0)
  await expect(page.locator('.ticket-action-panel')).toHaveCount(0)
  const approveRecord = page.locator('.ticket-timeline__item').filter({ hasText: '同意撤销申请' })
  await expect(approveRecord).toHaveCount(1)

  await page.screenshot({ path: resolve(reviewDir, 'canceled-1440.png'), fullPage: true })
  await page.setViewportSize({ width: 375, height: 812 })
  await expectNoHorizontalOverflow(page, '撤销后的详情页在 375 下被撑宽')
  await page.screenshot({ path: resolve(reviewDir, 'canceled-375.png'), fullPage: true })
  await page.setViewportSize({ width: 1440, height: 900 })
  await signOut(page)

  expect(pageErrors, '页面上出现了 JavaScript 错误').toEqual([])

  writeEvidence('runtime-evidence-two-phase-cancel.json', { ticketNo, title, steps })
})

test('片 D 场景二：IT 以「超出支持范围」关闭工单，工单进入已关闭', async ({ page }) => {
  test.setTimeout(180_000)
  const suffix = Date.now().toString().slice(-6)
  const title = `E2E 片D关闭 ${suffix}`
  const description = 'E2E 片 D 场景二：IT 用标准原因关闭一张不属于 IT 支持范围的工单。'
  const closeDescription = '门禁卡补办由行政部门负责，不属于 IT 支持范围。'
  const steps: StepEvidence[] = []
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.setViewportSize({ width: 1440, height: 900 })
  const ticketNo = await createTicketAsEmployee(page, title, description, steps)
  await signOut(page)

  const itDisplayName = await claimAsIt(page, ticketNo, steps)
  // 领取之后负责人能做的动作：关闭排在转交之后（登记表顺序），说明它确实由 allowedActions 放行
  expect(await actionLabels(page)).toEqual([
    '记录处理过程',
    '提交解决结果',
    '请求补充信息',
    '调整分类',
    '调整优先级',
    '转交工单',
    '关闭工单',
  ])

  await page.locator('.ticket-action .el-button').filter({ hasText: '关闭工单' }).click()
  const dialog = page.locator('.el-message-box')
  await expect(dialog).toBeVisible()
  // 关闭原因是契约里写死的三种；展开看一次，然后就地选中——不重新开合下拉，避免把"开关状态"
  // 也变成断言的一部分
  await dialog.locator('.el-select').first().click()
  const reasonItems = page.locator('.el-select-dropdown__item:visible')
  await expect(reasonItems.first()).toBeVisible()
  expect((await reasonItems.allInnerTexts()).map((text) => text.trim())).toEqual([
    '重复工单',
    '超出支持范围',
    '无效工单',
  ])
  // 还没选「重复工单」，没有第二个输入框
  await expect(duplicateInput(page)).toHaveCount(0)

  await reasonItems.filter({ hasText: '超出支持范围' }).first().click()
  await expect(duplicateInput(page)).toHaveCount(0)
  await dialog.locator('textarea').fill(closeDescription)

  const closing = page.waitForResponse((item) => item.url().includes('/actions/close'))
  await dialog.locator('.el-message-box__btns .el-button--primary').click()
  const closed = await closing
  expect(closed.status()).toBe(200)
  record(steps, 'close', closed, 'IT 以「超出支持范围」关闭工单，工单进入「已关闭」')

  await expect(page.locator('.ticket-meta')).toContainText('已关闭')
  // 三个终态必须可区分：已关闭带关闭方式与标准原因，且保留负责人
  const facts = page.locator('.ticket-facts')
  await expect(facts).toContainText('关闭方式')
  await expect(facts).toContainText('人工关闭')
  await expect(facts).toContainText('超出支持范围')
  await expect(facts).toContainText(itDisplayName)

  const closureRecord = page.locator('.ticket-timeline__item').filter({ hasText: '关闭工单' })
  await expect(closureRecord).toHaveCount(1)
  await expect(closureRecord).toContainText(closeDescription)
  await expect(closureRecord).toContainText('超出支持范围')
  await expect(page.locator('.ticket-action-panel')).toHaveCount(0)

  await page.screenshot({ path: resolve(reviewDir, 'closed-1440.png'), fullPage: true })
  await signOut(page)

  expect(pageErrors, '页面上出现了 JavaScript 错误').toEqual([])

  writeEvidence('runtime-evidence-close.json', { ticketNo, title, steps })
})

test('片 D 场景三：选到「重复工单」才出现重复单号输入框，改回其他原因就消失', async ({
  page,
}) => {
  test.setTimeout(180_000)
  const suffix = Date.now().toString().slice(-6)
  const title = `E2E 片D条件输入 ${suffix}`
  const description = 'E2E 片 D 场景三：只断言关闭弹窗里条件输入的出现与消失，不真正提交。'
  const steps: StepEvidence[] = []
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.setViewportSize({ width: 1440, height: 900 })
  const ticketNo = await createTicketAsEmployee(page, title, description, steps)
  await signOut(page)

  await claimAsIt(page, ticketNo, steps)

  await page.locator('.ticket-action .el-button').filter({ hasText: '关闭工单' }).click()
  const dialog = page.locator('.el-message-box')
  await expect(dialog).toBeVisible()

  // 还没选原因：没有"重复工单"这个前提，输入框不存在
  await expect(duplicateInput(page)).toHaveCount(0)

  await chooseCloseReason(page, '重复工单')
  await expect(duplicateInput(page)).toBeVisible()
  await expect(duplicateInput(page)).toHaveAttribute('maxlength', '32')
  await duplicateInput(page).fill(`FD-20261008-${suffix.slice(-3)}`)
  await page.screenshot({ path: resolve(reviewDir, 'duplicate-input-1440.png'), fullPage: true })

  await chooseCloseReason(page, '无效工单')
  // 改回其他原因：输入框消失。它同时意味着请求里不会再带这个字段——
  // 服务端对多传的单号返回 400，不是忽略，所以"隐藏"和"不发"必须是同一件事
  await expect(duplicateInput(page)).toHaveCount(0)

  // 不提交：这里验证的是界面行为，真实落库由场景二与真实栈验收脚本覆盖
  await dialog.locator('.el-message-box__btns .el-button').filter({ hasText: '取消' }).click()
  // 等遮罩真的消失再换账号：弹窗还在时顶部栏点不到
  await expect(page.locator('.el-message-box')).toHaveCount(0)
  await expect(page.locator('.ticket-meta')).toContainText('处理中')

  expect(pageErrors, '页面上出现了 JavaScript 错误').toEqual([])
  await signOut(page)

  writeEvidence('runtime-evidence-conditional.json', {
    ticketNo,
    title,
    steps,
    note: '只验证条件输入的出现与消失，未提交关闭动作',
  })
})

/**
 * 一次完整的撤销申请往返（2026-10-08 两阶段撤销）。
 *
 * <p>它证明三件在界面上容易做错、也最容易返工的事：</p>
 * <p>1. **驳回不结束工单**：工单留在「处理中」，负责人继续处理，而申请与驳回理由都进了时间线；</p>
 * <p>2. **提交人可以撤回并再次发起**：撤回后面板消失、按钮回到「申请撤销工单」，不是"用过一次就没了"；</p>
 * <p>3. **同一张单可以经历多轮申请**：每一轮都是独立记录，最后同意才进入终态。</p>
 */
test('两阶段撤销场景四：申请 → 驳回 → 再申请 → 撤回 → 再申请 → 同意', async ({ page }) => {
  test.setTimeout(300_000)
  const suffix = Date.now().toString().slice(-6)
  const title = `E2E 片D往返 ${suffix}`
  const description = 'E2E 两阶段撤销场景四：一轮完整的申请、驳回、撤回与同意往返。'
  const firstReason = '这次先问一下能不能撤销。'
  const rejectReason = '问题尚未定位，正在等供应商回复。'
  const secondReason = '问题已经自行解决，确实不需要处理了。'
  const steps: StepEvidence[] = []
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.setViewportSize({ width: 1440, height: 900 })
  const ticketNo = await createTicketAsEmployee(page, title, description, steps)
  await signOut(page)

  const itDisplayName = await claimAsIt(page, ticketNo, steps)
  await signOut(page)

  /** 提交人发起一次申请；返回后停在详情页（申请已生效）。 */
  async function requestCancelAsEmployee(reason: string): Promise<void> {
    await page.locator('.ticket-action .el-button').filter({ hasText: '申请撤销工单' }).click()
    const dialog = page.locator('.el-message-box')
    await expect(dialog).toBeVisible()
    await dialog.locator('textarea').fill(reason)
    const requesting = page.waitForResponse((item) =>
      item.url().includes('/actions/request-cancel'),
    )
    await dialog.locator('.el-message-box__btns .el-button--primary').click()
    const requested = await requesting
    expect(requested.status()).toBe(200)
    record(steps, 'request-cancel', requested, `提交人发起撤销申请：${reason}`)
    await expect(page.locator('.ticket-cancel-request')).toBeVisible()
  }

  // ---------- 第一轮：申请 → 驳回 ----------
  await signIn(page, employeeUsername)
  await openTicket(page, ticketNo, title)
  await requestCancelAsEmployee(firstReason)
  await signOut(page)

  await signIn(page, itUsername)
  await openTicket(page, ticketNo, title)
  await expect(page.locator('.ticket-cancel-request')).toContainText(firstReason)
  await page.locator('.ticket-action .el-button').filter({ hasText: '驳回撤销申请' }).click()
  const rejectDialog = page.locator('.el-message-box')
  await expect(rejectDialog).toBeVisible()
  // 驳回必须写理由：没有理由，提交人只看到"被驳回"而无从调整
  await rejectDialog.locator('textarea').fill(rejectReason)
  const rejecting = page.waitForResponse((item) => item.url().includes('/actions/reject-cancel'))
  await rejectDialog.locator('.el-message-box__btns .el-button--primary').click()
  const rejected = await rejecting
  expect(rejected.status()).toBe(200)
  record(steps, 'reject-cancel', rejected, '当前负责人驳回撤销申请，工单保留原状态')

  // 驳回不结束工单：状态不变、申请块消失、决策按钮也收回，处理动作照旧
  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  await expect(page.locator('.ticket-cancel-request')).toHaveCount(0)
  expect(await actionLabels(page)).toEqual([
    '记录处理过程',
    '提交解决结果',
    '请求补充信息',
    '调整分类',
    '调整优先级',
    '转交工单',
    '关闭工单',
  ])
  const rejectRecord = page.locator('.ticket-timeline__item').filter({ hasText: '驳回撤销申请' })
  await expect(rejectRecord).toHaveCount(1)
  await expect(rejectRecord).toContainText(rejectReason)
  await signOut(page)

  // ---------- 第二轮：再申请 → 撤回 ----------
  await signIn(page, employeeUsername)
  await openTicket(page, ticketNo, title)
  await requestCancelAsEmployee(secondReason)

  await page.locator('.ticket-action .el-button').filter({ hasText: '撤回撤销申请' }).click()
  const withdrawDialog = page.locator('.el-message-box')
  await expect(withdrawDialog).toBeVisible()
  // 撤回不需要理由（申请里那段说明仍然留在时间线上）
  await expect(withdrawDialog.locator('textarea')).toHaveCount(0)
  const withdrawing = page.waitForResponse((item) =>
    item.url().includes('/actions/withdraw-cancel-request'),
  )
  await withdrawDialog.locator('.el-message-box__btns .el-button--primary').click()
  const withdrawn = await withdrawing
  expect(withdrawn.status()).toBe(200)
  record(steps, 'withdraw-cancel-request', withdrawn, '提交人撤回自己的撤销申请')

  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  await expect(page.locator('.ticket-cancel-request')).toHaveCount(0)
  // 撤回之后不是"用过一次就没了"：还能重新发起
  expect(await actionLabels(page)).toEqual(['申请撤销工单'])
  const withdrawnRecord = page
    .locator('.ticket-timeline__item')
    .filter({ hasText: '撤回撤销申请' })
  await expect(withdrawnRecord).toHaveCount(1)

  // ---------- 第三轮：再申请 → 同意 ----------
  await requestCancelAsEmployee(secondReason)
  await signOut(page)

  await signIn(page, itUsername)
  await openTicket(page, ticketNo, title)
  await expect(page.locator('.ticket-cancel-request')).toContainText(secondReason)
  await page.locator('.ticket-action .el-button').filter({ hasText: '同意撤销' }).click()
  const approveDialog = page.locator('.el-message-box')
  await expect(approveDialog).toBeVisible()
  const approving = page.waitForResponse((item) => item.url().includes('/actions/approve-cancel'))
  await approveDialog.locator('.el-message-box__btns .el-button--primary').click()
  const approved = await approving
  expect(approved.status()).toBe(200)
  record(steps, 'approve-cancel', approved, '当前负责人同意撤销，工单进入「已取消」')

  await expect(page.locator('.ticket-meta')).toContainText('已取消')
  await expect(page.locator('.ticket-facts')).toContainText(itDisplayName)
  // 三轮申请各留一条记录：时间线是业务事实，不能被后来的动作抹平
  await expect(
    page.locator('.ticket-timeline__item').filter({ hasText: '申请撤销工单' }),
  ).toHaveCount(3)
  await expect(
    page.locator('.ticket-timeline__item').filter({ hasText: '撤回撤销申请' }),
  ).toHaveCount(1)
  await expect(
    page.locator('.ticket-timeline__item').filter({ hasText: '驳回撤销申请' }),
  ).toHaveCount(1)
  await signOut(page)

  expect(pageErrors, '页面上出现了 JavaScript 错误').toEqual([])

  writeEvidence('runtime-evidence-cancel-roundtrip.json', { ticketNo, title, steps })
})
