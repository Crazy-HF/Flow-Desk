import { expect, test } from '@playwright/test'
import type { APIRequestContext, Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expectNoHorizontalOverflow } from './support/overflow'

/**
 * 完整工单状态机片 C：调整与转交。
 *
 * <p>覆盖四个界面事实，全部从真实点击产生：</p>
 * <p>1. **调整分类 / 调整优先级**：目标值从选项里选（分类来自启用分类接口，优先级是固定三档），
 * 提交后状态与负责人不变，时间线各留一条可追溯记录。</p>
 * <p>2. **转交**：候选人列表由服务端给出（排除提交人与当前负责人），选择后负责人立即替换，
 * 工单状态不变；界面上的动作区随之换成新负责人的动作。</p>
 * <p>3. **接手人能真的接手**：转交后被转交人登录即可看到这张工单（参与关系落库），
 * 并能继续把它做完——转交不是"改一个字段"，而是把工作交出去。</p>
 * <p>4. **候选人为空是正常状态**：没有可转交对象时，弹窗给出空态提示且不允许提交。</p>
 *
 * <p><strong>为什么这条用例要先用接口建一个 IT 账号</strong>：演示种子只有一个 IT 支持人员，
 * 而候选人恰好排除"提交人与当前负责人"，因此干净库里没有任何可转交对象；
 * 靠"点一下不报错"验证转交，等于什么都没验证。临时账号通过管理接口创建（登录名唯一），
 * 用例结束前用停用接口让它不再出现在候选人里。</p>
 *
 * <p><strong>这条用例会向演示库写入一张工单与一个已停用的临时账号</strong>，清理顺序：
 * 先按工单号删 `ticket_record` / `ticket_participant` / `ticket`（同 `tickets.spec.ts` 文件头），
 * 再删临时账号的 `iam_user_role` 与 `iam_user`（登录名前缀 `E2E_transfer_target_`）。</p>
 */

const password = process.env.E2E_PASSWORD ?? '123456'
const employeeUsername = process.env.E2E_EMPLOYEE_USERNAME ?? 'employee'
const itUsername = process.env.E2E_IT_USERNAME ?? 'it'
const adminUsername = process.env.E2E_ADMIN_USERNAME ?? 'admin'

/** 评审产物目录：与其它视觉评审放在同一处。 */
const reviewDir = resolve(process.cwd(), '../.ui-craft/reviews/2026-10-07-slice-c-adjust-transfer')
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

async function signIn(page: Page, username: string, userPassword = password): Promise<void> {
  await page.goto('/login')
  await expect(page.getByPlaceholder('请输入登录名')).toBeVisible()
  await page.getByPlaceholder('请输入登录名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(userPassword)
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

/** 管理接口登录后的请求头；调用方负责在 finally 里退出登录。 */
async function adminHeaders(request: APIRequestContext) {
  const origin = { Origin: 'http://127.0.0.1:4173' }
  const login = await request.post('/fd/v1/auth/login', {
    headers: origin,
    data: { username: adminUsername, password },
  })
  expect(login.status()).toBe(200)
  return { origin, headers: { ...origin, Authorization: `Bearer ${(await login.json()).data.accessToken}` } }
}

/**
 * 用管理接口建一个只持有 IT_SUPPORT 的临时账号，作为转交目标。
 *
 * <p>不走 SQL：登录名要能真的登录（用例第 4 步要以它身份接手工单），
 * 而口令摘要只能由创建接口生成。</p>
 */
async function createTransferTarget(request: APIRequestContext, suffix: string) {
  const { origin, headers } = await adminHeaders(request)
  const displayName = `E2E 转交目标 ${suffix}`
  const username = `E2E_transfer_target_${suffix}`
  const initialPassword = 'SliceC-check-123'

  try {
    const roles = await request.get('/fd/v1/admin/roles?keyword=IT_SUPPORT', { headers })
    expect(roles.status()).toBe(200)
    const itRole = (await roles.json()).data.items.find(
      (role: { code: string }) => role.code === 'IT_SUPPORT',
    )
    expect(itRole, '演示库里必须有内置 IT_SUPPORT 角色').toBeTruthy()

    const created = await request.post('/fd/v1/users', {
      headers,
      data: { username, displayName, initialPassword, roleIds: [itRole.id] },
    })
    expect(created.status()).toBe(201)
    const body = await created.json()
    return { id: body.data.id as number, username, password: initialPassword, displayName }
  } finally {
    const logout = await request.post('/fd/v1/auth/logout', { headers, data: {} })
    expect(logout.status()).toBe(200)
    void origin
  }
}

/** 用例收尾：停用临时账号，让它从候选人里消失（不删行：接口没有删除能力，删行要动数据库）。 */
async function disableUser(request: APIRequestContext, userId: number) {
  const { headers } = await adminHeaders(request)
  try {
    const detail = await request.get(`/fd/v1/users/${userId}`, { headers })
    expect(detail.status()).toBe(200)
    const version = (await detail.json()).data.version
    const disabled = await request.post(`/fd/v1/users/${userId}/actions/disable`, {
      headers,
      data: { version },
    })
    expect(disabled.status(), '临时转交目标应当能被停用').toBe(200)
  } finally {
    const logout = await request.post('/fd/v1/auth/logout', { headers, data: {} })
    expect(logout.status()).toBe(200)
  }
}

/**
 * 带目标值选择的动作：打开确认框 → 在选择器里挑一项 → 填原因 → 确认。
 *
 * <p>返回被选中的选项文案，调用方据此断言页面上确实出现了这个值（而不是"点成功了"）。</p>
 */
async function performSelectAction(
  page: Page,
  label: string,
  options: { optionIndex?: number; optionText?: string; reason: string; expectedUrl: string },
): Promise<{ response: Awaited<ReturnType<Page['waitForResponse']>>; chosen: string }> {
  const button = page.locator('.ticket-action .el-button').filter({ hasText: label })
  await expect(button).toBeVisible()
  await button.click()

  const dialog = page.locator('.el-message-box')
  await expect(dialog).toBeVisible()

  await dialog.locator('.el-select').first().click()
  const items = page.locator('.el-select-dropdown__item:visible')
  await expect(items.first()).toBeVisible()
  const option =
    options.optionText !== undefined
      ? items.filter({ hasText: options.optionText }).first()
      : items.nth(options.optionIndex ?? 0)
  await expect(option).toBeVisible()
  const chosen = (await option.innerText()).trim()
  await option.click()

  await dialog.locator('textarea').fill(options.reason)

  const response = page.waitForResponse(
    (item) => item.url().includes(options.expectedUrl) && item.request().method() === 'POST',
  )
  await dialog.locator('.el-message-box__btns .el-button--primary').click()
  return { response: await response, chosen }
}

/** 工单详情里事实栏的文本（分类、优先级、负责人都在这里）。 */
function facts(page: Page) {
  return page.locator('.ticket-facts')
}

test('片 C：IT 调整分类与优先级、转交给另一名 IT，接手人能把工单做完', async ({
  page,
  request,
}) => {
  test.setTimeout(300_000)
  const suffix = Date.now().toString().slice(-6)
  const title = `E2E 片C调整转交 ${suffix}`
  const description = 'E2E 片 C 用例：验证调整分类、调整优先级与转交，以及接手人能否继续推进。'
  const adjustReason = '分类选错了，按现场情况改到网络与账号'
  const priorityReason = '影响面扩大，需要优先处理'
  const transferReason = '这块由负责网络的同事接手更快'
  const steps: StepEvidence[] = []
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  // ---------- 0. 准备第二名 IT：演示种子只有一个 IT，候选人又排除提交人与当前负责人 ----------
  const target = await createTransferTarget(request, suffix)
  steps.push({
    step: 'setup.transfer-target',
    status: 201,
    detail: `${target.username}（${target.displayName}）由管理接口创建，仅用于本次转交`,
  })

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

  // ---------- 2. IT 领取，调整分类与优先级 ----------
  await signIn(page, itUsername)
  await nav(page).getByRole('link', { name: 'IT 工作台', exact: true }).click()
  await page.getByLabel('工单关键词').fill(ticketNo)
  await page.getByRole('button', { name: '查询' }).click()
  const queueRow = page.locator('.admin-table tbody tr').filter({ hasText: ticketNo })
  await expect(queueRow).toHaveCount(1)
  await queueRow.locator('.ticket-cell__title').click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)

  await page.locator('.ticket-action .el-button').filter({ hasText: '领取工单' }).click()
  const claimDialog = page.locator('.el-message-box')
  await expect(claimDialog).toBeVisible()
  const claiming = page.waitForResponse((item) => item.url().includes('/actions/claim'))
  await claimDialog.locator('.el-message-box__btns .el-button--primary').click()
  const claimed = await claiming
  expect(claimed.status()).toBe(200)
  record(steps, 'claim', claimed, 'IT 领取成功')

  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  expect(await actionLabels(page)).toEqual([
    '记录处理过程',
    '提交解决结果',
    '请求补充信息',
    '调整分类',
    '调整优先级',
    '转交工单',
  ])

  // 分类：挑一个与建单时不同的选项（建单选的是第一个，这里选最后一个）
  const categoryChange = await performSelectAction(page, '调整分类', {
    optionIndex: -1,
    reason: adjustReason,
    expectedUrl: '/actions/change-category',
  })
  expect(categoryChange.response.status()).toBe(200)
  record(
    steps,
    'change-category',
    categoryChange.response,
    `分类改为「${categoryChange.chosen}」，状态与负责人不变`,
  )
  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  await expect(facts(page)).toContainText(categoryChange.chosen)

  const priorityChange = await performSelectAction(page, '调整优先级', {
    optionText: '低',
    reason: priorityReason,
    expectedUrl: '/actions/change-priority',
  })
  expect(priorityChange.response.status()).toBe(200)
  record(
    steps,
    'change-priority',
    priorityChange.response,
    `优先级改为「${priorityChange.chosen}」，状态与负责人不变`,
  )
  await expect(facts(page)).toContainText('低')

  // 时间线：两次调整各留一条记录，且带原因
  const categoryRecord = page.locator('.ticket-timeline__item').filter({ hasText: '调整分类' })
  const priorityRecord = page.locator('.ticket-timeline__item').filter({ hasText: '调整优先级' })
  await expect(categoryRecord).toHaveCount(1)
  await expect(categoryRecord).toContainText(adjustReason)
  await expect(priorityRecord).toHaveCount(1)
  await expect(priorityRecord).toContainText(priorityReason)
  await page.screenshot({
    path: resolve(reviewDir, 'it-adjusted-1440.png'),
    fullPage: true,
  })

  // ---------- 3. 转交给第二名 IT ----------
  const transfer = await performSelectAction(page, '转交工单', {
    optionText: target.displayName,
    reason: transferReason,
    expectedUrl: '/actions/transfer',
  })
  expect(transfer.response.status()).toBe(200)
  record(steps, 'transfer', transfer.response, `负责人替换为 ${target.displayName}`)

  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  await expect(facts(page)).toContainText(target.displayName)
  const transferRecord = page.locator('.ticket-timeline__item').filter({ hasText: '转交工单' })
  await expect(transferRecord).toHaveCount(1)
  await expect(transferRecord).toContainText(transferReason)
  await page.screenshot({
    path: resolve(reviewDir, 'it-transferred-1440.png'),
    fullPage: true,
  })
  await signOut(page)

  // ---------- 4. 接手人登录：能看到工单，并继续把它做完 ----------
  await signIn(page, target.username, target.password)
  await page.goto(`/tickets/${ticketNo}`)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)
  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  expect(await actionLabels(page)).toEqual([
    '记录处理过程',
    '提交解决结果',
    '请求补充信息',
    '调整分类',
    '调整优先级',
    '转交工单',
  ])

  await page.setViewportSize({ width: 375, height: 812 })
  await expectNoHorizontalOverflow(page, '片 C 详情页在 375 下被撑宽')
  await page.screenshot({
    path: resolve(reviewDir, 'new-assignee-375.png'),
    fullPage: true,
  })
  await page.setViewportSize({ width: 1440, height: 900 })

  const resolution = '已按调整后的分类转给网络组，重配交换机端口后打印恢复正常。'
  const resolveButton = page.locator('.ticket-action .el-button').filter({ hasText: '提交解决结果' })
  await resolveButton.click()
  const resolveDialog = page.locator('.el-message-box')
  await expect(resolveDialog).toBeVisible()
  await resolveDialog.locator('textarea').fill(resolution)
  const resolving = page.waitForResponse((item) =>
    item.url().includes('/actions/submit-resolution'),
  )
  await resolveDialog.locator('.el-message-box__btns .el-button--primary').click()
  const resolved = await resolving
  expect(resolved.status()).toBe(200)
  record(steps, 'submit-resolution', resolved, '接手人提交解决结果')
  await expect(page.locator('.ticket-meta')).toContainText('待员工确认')
  await signOut(page)

  // ---------- 5. 员工确认，工单完成：转交出去的工作确实被接完了 ----------
  await signIn(page, employeeUsername)
  await page.goto(`/tickets/${ticketNo}`)
  const confirmButton = page.locator('.ticket-action .el-button').filter({ hasText: '确认已解决' })
  await confirmButton.click()
  const confirmDialog = page.locator('.el-message-box')
  await expect(confirmDialog).toBeVisible()
  const confirming = page.waitForResponse((item) =>
    item.url().includes('/actions/confirm-resolution'),
  )
  await confirmDialog.locator('.el-message-box__btns .el-button--primary').click()
  const confirmed = await confirming
  expect(confirmed.status()).toBe(200)
  record(steps, 'confirm-resolution', confirmed, '员工确认，工单进入已完成')
  await expect(page.locator('.ticket-meta')).toContainText('已完成')
  await expect(page.locator('.ticket-action-panel')).toHaveCount(0)

  // 时间线：调整与转交都能在完成后的工单上回看
  await expect(page.locator('.ticket-timeline__item').filter({ hasText: '调整分类' })).toHaveCount(1)
  await expect(page.locator('.ticket-timeline__item').filter({ hasText: '转交工单' })).toHaveCount(1)
  await page.screenshot({
    path: resolve(reviewDir, 'completed-1440.png'),
    fullPage: true,
  })

  expect(pageErrors, '页面上出现了 JavaScript 错误').toEqual([])

  // ---------- 6. 收尾：临时账号停用，避免它继续出现在其他人的候选人里 ----------
  await disableUser(request, target.id)
  steps.push({
    step: 'cleanup.disable-transfer-target',
    status: 200,
    detail: `${target.username} 已停用（演示库里会留下一行已停用账号，删行 SQL 见本文件头）`,
  })

  writeFileSync(
    resolve(reviewDir, 'runtime-evidence.json'),
    `${JSON.stringify(
      { ticketNo, title, transferTarget: target.username, steps },
      null,
      2,
    )}\n`,
    'utf8',
  )
})
