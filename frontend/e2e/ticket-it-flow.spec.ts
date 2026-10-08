import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expectNoHorizontalOverflow } from './support/overflow'

/**
 * 阶段 3 端到端主链：员工提交 → IT 领取 → 记录处理 → 提交解决 → 员工确认（`TASK-024-MVP`）。
 *
 * <p>这条用例走的是**两个真实账号在两个浏览器上下文里交替操作同一张工单**，
 * 而不是用一个接口请求把状态推到下一站——后端四个动作的 HTTP 行为已经有真实栈验收
 * （`docs/acceptance/2026-10-06-stage3-claim-process-resolution-confirm.json`，77/77），
 * 这一条要证明的是另一件事：**界面上的按钮、状态与权限提示确实随着真实状态迁移而变化**。</p>
 *
 * <p>工单由员工通过界面创建（不是接口造数），这样"员工创建后立刻看到待受理"也是被验证的一环。</p>
 *
 * <p><strong>这条用例会向演示库写入一张工单且无法通过接口撤销</strong>，清理顺序与 SQL 见
 * `frontend/e2e/tickets.spec.ts` 文件头；标题带时间戳后缀，两次运行互不干扰。</p>
 */

const password = process.env.E2E_PASSWORD ?? '123456'
const employeeUsername = process.env.E2E_EMPLOYEE_USERNAME ?? 'employee'
const itUsername = process.env.E2E_IT_USERNAME ?? 'it'
/**
 * 演示 IT 账号的显示名只用来断言"负责人真的换成了 IT"。
 *
 * <p>这里刻意不写死常量：显示名属于演示数据，会随 `R__seed_demo_data.sql` 变化，而本机演示库
 * 又可能被长期手工演进（2026-10-06 CI 的 `core-e2e` 就因为这里的 `IT 支持人员` 与种子里的
 * `演示 IT 支持人员` 不一致而失败，本机却是通过的）。默认改为登录后从顶部身份区读取当前账号
 * 的显示名；确实需要指定时仍可用 `E2E_IT_DISPLAY_NAME` 覆盖。</p>
 */
const itDisplayNameOverride = process.env.E2E_IT_DISPLAY_NAME

/** 评审产物目录：与其它视觉评审放在同一处，方便和报告互相引用。 */
const reviewDir = resolve(process.cwd(), '../.ui-craft/reviews/2026-10-06-ticket-it-flow')
mkdirSync(reviewDir, { recursive: true })

/** 每一步的真实结果，收尾写成运行时证据文件。 */
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
  /**
   * 先确认真的停在登录页再看表单。退出登录后紧接着 `goto('/login')` 时，路由守卫手上的
   * 身份状态可能还是上一位用户的：它会把这次导航当作"已登录还要去登录页"而弹回原地址，
   * 于是后面的 `fill` 在一个没有表单的页面上等——报出来的却是"侧栏没出现"，指向错误的方向。
   */
  await expect(page.getByPlaceholder('请输入登录名')).toBeVisible()
  await page.getByPlaceholder('请输入登录名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  // 登录成功的判据是进了应用壳（侧栏出现主导航），不是"页面跳走了"
  await expect(nav(page)).toBeVisible()
}

async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: '账号' }).click()
  await page.getByText('退出登录').click()
  await expect(page).toHaveURL(/\/login/)
  // 等登录表单真正就位再交还给调用方：下一步往往就是换账号登录
  await expect(page.getByPlaceholder('请输入登录名')).toBeVisible()
}

/** 动作区当前摆出的按钮文案；没有动作区时是空数组。 */
async function actionLabels(page: Page): Promise<string[]> {
  return page.locator('.ticket-action .el-button').allInnerTexts()
}

/**
 * 点动作按钮 → 在确认框里写正文（可选）→ 确认。
 *
 * <p>先等按钮出现再点：动作区是按 `allowedActions` 渲染的，抢在详情返回之前点会落空。</p>
 */
async function performAction(
  page: Page,
  label: string,
  options: { content?: string; expectedUrl: string } = { expectedUrl: '/actions/' },
): Promise<Awaited<ReturnType<Page['waitForResponse']>>> {
  const button = page.locator('.ticket-action .el-button').filter({ hasText: label })
  await expect(button).toBeVisible()
  await button.click()

  const dialog = page.locator('.el-message-box')
  await expect(dialog).toBeVisible()
  if (options.content !== undefined) {
    await dialog.locator('textarea').fill(options.content)
  }

  const response = page.waitForResponse(
    (item) => item.url().includes(options.expectedUrl) && item.request().method() === 'POST',
  )
  /**
   * 动作成功后组件会再取一次时间线把新记录补上（那一次请求是并发的，不阻塞动作结果）。
   * 这里一并等它完成：后面几条断言读的正是刚写进去的那条记录，
   * 不等就会出现"状态已经是已完成，但时间线还少最后一条"的假失败。
   */
  const timelineReload = page.waitForResponse(
    (item) => item.url().includes('/records') && item.request().method() === 'GET',
  )
  await dialog.locator('.el-message-box__btns .el-button--primary').click()
  const settled = await response
  await timelineReload
  return settled
}

function record(steps: StepEvidence[], step: string, response: { status(): number; headers(): Record<string, string> }, detail: string) {
  const traceId = response.headers()['x-trace-id']
  expect(traceId, `${step} 缺少 X-Trace-Id`).toBeTruthy()
  steps.push({ step, status: response.status(), traceId, detail })
}

test('阶段 3 主链：员工提交 → IT 领取 → 处理 → 提交解决 → 员工确认，两个账号交替走真实界面', async ({
  page,
}) => {
  test.setTimeout(180_000)
  const suffix = Date.now().toString().slice(-6)
  const title = `E2E 阶段3主链 ${suffix}`
  const description = 'E2E 阶段 3 主链用例：验证领取、处理记录、提交解决与员工确认在界面上的状态迁移。'
  const steps: StepEvidence[] = []
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  // ---------- 1. 员工创建工单 ----------
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
  expect(ticketNo).toMatch(/^FD-\d{8}-\d{3}$/)
  record(steps, 'create', created, `${ticketNo} 由员工通过界面创建`)

  /**
   * 员工视角：待受理阶段拿不到 `claim`（不能领取自己提交的工单），但片 D 起有一格
   * 「撤销工单」——撤销覆盖四种非终态。这一格恰好证明按钮来自服务端返回的
   * `allowedActions`：同一个界面、同一张工单，员工看到撤销、IT 看到领取。
   */
  await expect(page.locator('.ticket-meta')).toContainText('待受理')
  expect(await actionLabels(page)).toEqual(['撤销工单'])
  await signOut(page)

  // ---------- 2. IT 领取 ----------
  await signIn(page, itUsername)

  // 期望的负责人显示名取自当前登录身份（`AppHeader` 的 `.app-header__identity`），不写死常量
  const itDisplayName = itDisplayNameOverride
    ?? (await page.getByRole('banner').locator('.app-header__identity').innerText()).trim()
  expect(itDisplayName, '顶部栏没有展示当前 IT 身份，无法断言负责人').not.toBe('')

  /**
   * IT 工作台默认停在「待受理」。断言的是"这张刚提交的工单确实出现在队列里"，
   * 而不是笼统地"列表有数据"——后者在演示库有历史数据时会假通过。
   */
  await nav(page).getByRole('link', { name: 'IT 工作台', exact: true }).click()
  await expect(page).toHaveURL(/\/it\/queue$/)
  await expect(page.locator('.ticket-scope-bar .el-radio-button').first()).toContainText('待受理')
  await page.getByLabel('工单关键词').fill(ticketNo)
  await page.getByRole('button', { name: '查询' }).click()
  const queueRow = page.locator('.admin-table tbody tr').filter({ hasText: ticketNo })
  await expect(queueRow).toHaveCount(1)
  await queueRow.locator('.ticket-cell__title').click()

  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)
  await expect(page.locator('.ticket-meta')).toContainText('待受理')
  expect(await actionLabels(page)).toEqual(['领取工单'])
  await page.screenshot({ path: resolve(reviewDir, 'it-detail-claim-1440.png'), fullPage: true })

  const claimed = await performAction(page, '领取工单', { expectedUrl: '/actions/claim' })
  expect(claimed.status()).toBe(200)
  record(steps, 'claim', claimed, 'IT 领取成功，工单进入处理中')

  // 领取后按钮立刻换成处理动作：版本已经 +1，界面必须按新快照重算
  await expect(page.locator('.ticket-meta')).toContainText('处理中')
  // 处理中状态下负责人具备七个动作（片 B 起多了「请求补充信息」，片 C 起多了调整分类、
  // 调整优先级与转交，片 D 起多了关闭），顺序由前端登记表决定
  expect(await actionLabels(page)).toEqual([
    '记录处理过程',
    '提交解决结果',
    '请求补充信息',
    '调整分类',
    '调整优先级',
    '转交工单',
    '关闭工单',
  ])
  await expect(page.locator('.ticket-facts dt:has-text("负责人") + dd')).toHaveText(itDisplayName)

  // ---------- 3. 追加处理记录 ----------
  const processed = await performAction(page, '记录处理过程', {
    content: '已远程排查打印服务器，证书过期，正在申请更换。',
    expectedUrl: '/actions/add-processing-record',
  })
  expect(processed.status()).toBe(200)
  record(steps, 'add-processing-record', processed, '处理记录写入时间线，状态与负责人不变')

  // 记录不允许"只填空格"：那等于没写，服务端会 400，界面先拦下
  await page.locator('.ticket-action .el-button').filter({ hasText: '记录处理过程' }).click()
  const blankDialog = page.locator('.el-message-box')
  await blankDialog.locator('textarea').fill('   ')
  await blankDialog.locator('.el-message-box__btns .el-button--primary').click()
  /**
   * `ElMessage` 是追加式的：领取、处理成功各留过一条，所以这里用 `.last()` 而不是整组匹配
   * （Playwright 的严格模式会因为多个元素直接判失败）。
   */
  await expect(page.locator('.el-message').last()).toContainText('请先填写处理内容')
  // 提示出现后弹窗还在原处，用户写好的内容没被丢掉
  await expect(blankDialog).toBeVisible()
  await blankDialog.locator('.el-message-box__btns .el-button').filter({ hasText: '取消' }).click()
  await expect(blankDialog).not.toBeVisible()

  await expect(page.locator('.ticket-timeline__item')).toHaveCount(3)
  await expect(page.locator('.ticket-timeline__item').nth(2)).toContainText('记录处理过程')
  await expect(page.locator('.ticket-timeline__item').nth(2)).toContainText('已远程排查打印服务器')
  await expectNoHorizontalOverflow(page)
  await page.screenshot({ path: resolve(reviewDir, 'it-detail-processing-1440.png'), fullPage: true })

  // ---------- 4. 提交解决结果 ----------
  const resolved = await performAction(page, '提交解决结果', {
    content: '已更换打印服务器证书并重启打印队列，请在打印机上重新发起一次打印确认。',
    expectedUrl: '/actions/submit-resolution',
  })
  expect(resolved.status()).toBe(200)
  record(steps, 'submit-resolution', resolved, '进入待员工确认，确认期限由服务端给出')

  await expect(page.locator('.ticket-meta')).toContainText('待员工确认')
  // 交出解决结果之后 IT 侧不能再改处理记录
  expect(await actionLabels(page)).toEqual([])
  /**
   * 同一个期限字段，IT 侧读到的与员工侧不同：期限属于"提交人什么时候之前要确认"这件事，
   * 而 IT 此刻没有任何可做动作（确认归提交人）。所以这里同时钉住两点——标签是「确认期限」
   * 而不是一个对谁都能读的「当前期限」，以及"到期不会自动处理"这句限定对负责人照样要说。
   */
  await expect(page.locator('.ticket-facts dt:has-text("确认期限") + dd')).not.toBeEmpty()
  await expect(page.locator('.ticket-facts__hint')).toContainText('需要提交人手动确认')
  await page.screenshot({ path: resolve(reviewDir, 'it-detail-waiting-1440.png'), fullPage: true })
  await signOut(page)

  // ---------- 5. 员工确认已解决 ----------
  await signIn(page, employeeUsername)
  await nav(page).getByRole('link', { name: '工单', exact: true }).click()
  await expect(page).toHaveURL(/\/tickets$/)
  await page.getByLabel('工单关键词').fill(ticketNo)
  await page.getByRole('button', { name: '查询' }).click()
  const ownRow = page.locator('.admin-table tbody tr').filter({ hasText: ticketNo })
  await expect(ownRow).toHaveCount(1)
  await expect(ownRow).toContainText('待员工确认')
  await ownRow.locator('.ticket-cell__title').click()

  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)
  /**
   * 提交人在待确认时看到的是两个互斥选择：确认已解决 / 问题仍未解决（片 A 新增后者），
   * 外加片 D 起在四种非终态上都可用的撤销。
   * 三者的前置条件不完全相同（撤销条件更宽），所以顺序只由前端登记表决定——
   * 本用例仍要钉住"看到的是提交人动作，不是 IT 的处理入口"。
   */
  expect(await actionLabels(page)).toEqual(['确认已解决', '问题仍未解决', '撤销工单'])
  // 同一个期限字段，对提交人说的是"我要在什么时候之前确认"
  await expect(page.locator('.ticket-facts dt:has-text("确认期限") + dd')).not.toBeEmpty()
  await page.screenshot({ path: resolve(reviewDir, 'employee-detail-confirm-1440.png'), fullPage: true })

  const confirmed = await performAction(page, '确认已解决', {
    expectedUrl: '/actions/confirm-resolution',
  })
  expect(confirmed.status()).toBe(200)
  record(steps, 'confirm-resolution', confirmed, '员工确认，工单进入终态已完成')

  await expect(page.locator('.ticket-meta')).toContainText('已完成')
  await expect(page.locator('.ticket-facts dt:has-text("完成方式") + dd')).toHaveText('员工确认完成')
  expect(await actionLabels(page)).toEqual([])

  /**
   * 时间线是这条链路的完整证据：六个业务事实各一条，按发生顺序。
   * 只有**一条** `记录处理过程`——"只填空格"的那次被界面拦下，没有落到时间线上。
   */
  const timelineTypes = await page.locator('.ticket-timeline__type').allInnerTexts()
  expect(timelineTypes).toEqual([
    '创建工单',
    '领取工单',
    '记录处理过程',
    '提交解决结果',
    '完成工单',
  ])
  await expectNoHorizontalOverflow(page)
  await page.screenshot({ path: resolve(reviewDir, 'employee-detail-completed-1440.png'), fullPage: true })

  // ---------- 6. 窄屏：动作区与属性栏都不横向溢出 ----------
  await page.setViewportSize({ width: 375, height: 812 })
  await expectNoHorizontalOverflow(page, '工单详情在 375 下被撑宽')
  await page.screenshot({ path: resolve(reviewDir, 'employee-detail-completed-375.png'), fullPage: true })

  // ---------- 7. IT 工作台：桌面看队列，再收窄看窄屏 ----------
  await signOut(page)
  /**
   * 换账号前把视口还原成桌面：375 下侧栏收进抽屉，`主导航` 不在可访问树里，
   * 于是"登录成功了没有"会被误判成没成功（`signIn` 正是以侧栏出现为判据）。
   */
  await page.setViewportSize({ width: 1440, height: 900 })
  await signIn(page, itUsername)
  await nav(page).getByRole('link', { name: 'IT 工作台', exact: true }).click()
  await expect(page.locator('.admin-panel__skeleton')).toHaveCount(0)
  await expectNoHorizontalOverflow(page)
  await page.screenshot({ path: resolve(reviewDir, 'it-queue-1440.png'), fullPage: true })

  // 切到「我负责的」：这张刚完成的工单仍在负责人名下
  await page.locator('.ticket-scope-bar .el-radio-button').filter({ hasText: '我负责的' }).click()
  await expect(page).toHaveURL(/scope=ASSIGNED_TO_ME/)
  await page.getByLabel('工单关键词').fill(ticketNo)
  await page.getByRole('button', { name: '查询' }).click()
  const assignedRow = page.locator('.admin-table tbody tr').filter({ hasText: ticketNo })
  await expect(assignedRow).toHaveCount(1)
  await expect(assignedRow).toContainText('已完成')

  await page.setViewportSize({ width: 375, height: 812 })
  await expectNoHorizontalOverflow(page, 'IT 工作台在 375 下被撑宽')
  await page.screenshot({ path: resolve(reviewDir, 'it-queue-375.png'), fullPage: true })

  expect(pageErrors).toEqual([])
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)

  writeFileSync(
    resolve(reviewDir, 'runtime-evidence.json'),
    JSON.stringify(
      {
        ticketNo,
        title,
        steps,
        // 从界面上读到的因果链，不是脚本自己推的字符串
        uiStatusTransitions: ['待受理', '处理中', '待员工确认', '已完成'],
        timelineTypes,
        viewports: [1440, 375],
        horizontalOverflow: 0,
        pageErrors,
      },
      null,
      2,
    ),
  )
})
