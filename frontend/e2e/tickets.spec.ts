import { expect, test } from '@playwright/test'
import type { APIRequestContext, Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * 工单主链路的真实栈用例（阶段 2 `TASK-020`～`TASK-023-MVP`）。
 *
 * <p><strong>这条用例会向演示库写入工单，而且写入无法通过接口撤销</strong>：工单没有删除端点，
 * 「撤销」只是把状态改成 `CANCELED`，行还在。演示前要恢复"工单 0 行"的基线，需要手工执行
 * （顺序不能颠倒，参与者与记录先删）：</p>
 *
 * <pre>
 * DELETE FROM ticket_participant WHERE ticket_id IN (SELECT id FROM ticket WHERE title LIKE 'E2E 联调工单%');
 * DELETE FROM ticket_record      WHERE ticket_id IN (SELECT id FROM ticket WHERE title LIKE 'E2E 联调工单%');
 * DELETE FROM ticket             WHERE title LIKE 'E2E 联调工单%';
 * </pre>
 *
 * <p>标题带时间戳后缀，两次运行之间不会互相干扰，也不会碰到用户自己造的数据。</p>
 *
 * <p>为什么把桌面与窄屏放在同一条用例里：一次运行只写一张工单。窄屏断言需要一张真实存在的
 * 工单才能打开详情页，如果拆成两条用例，第二条既可能先跑（拿不到数据），又要再写一张。</p>
 */
const employeeUsername = process.env.E2E_EMPLOYEE_USERNAME ?? 'employee'
const password = process.env.E2E_PASSWORD ?? '123456'

/** 评审产物目录：与其它视觉评审放在同一处，方便和报告互相引用。 */
const reviewDir = resolve(process.cwd(), '../.ui-craft/reviews/2026-09-29-tickets')
mkdirSync(reviewDir, { recursive: true })

function nav(page: Page) {
  return page.getByRole('navigation', { name: '主导航' })
}

async function signIn(page: Page, username = employeeUsername, userPassword = password): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('请输入登录名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(userPassword)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
}

/** 第二个只持有 EMPLOYEE 角色的账号用于实际验证本人数据隔离；收尾 SQL 删除它。 */
async function createIsolationEmployee(request: APIRequestContext, suffix: string) {
  const origin = { Origin: 'http://127.0.0.1:4173' }
  const login = await request.post('/fd/v1/auth/login', {
    headers: origin,
    data: { username: process.env.E2E_ADMIN_USERNAME ?? 'admin', password },
  })
  expect(login.status()).toBe(200)
  const headers = { ...origin, Authorization: `Bearer ${(await login.json()).data.accessToken}` }
  try {
    const roles = await request.get('/fd/v1/admin/roles?keyword=EMPLOYEE', { headers })
    expect(roles.status()).toBe(200)
    const employeeRole = (await roles.json()).data.items.find(
      (role: { code: string }) => role.code === 'EMPLOYEE',
    )
    expect(employeeRole).toBeTruthy()
    const username = `E2E_ticket_isolation_${suffix}`
    const initialPassword = 'Stage2-check-123'
    const created = await request.post('/fd/v1/users', {
      headers,
      data: { username, displayName: '隔离验收员工', initialPassword, roleIds: [employeeRole.id] },
    })
    expect(created.status()).toBe(201)
    return { username, initialPassword, id: (await created.json()).data.id }
  } finally {
    const logout = await request.post('/fd/v1/auth/logout', { headers })
    expect(logout.status()).toBe(200)
  }
}

/**
 * 整页横向溢出量。
 *
 * <p>窄屏最常见的失败不是"难看"而是"整页被撑宽"：表格的 min-content 会把网格轨道顶出去。
 * 断言这个差值为 0，比肉眼看截图可靠。</p>
 */
async function horizontalOverflow(page: Page): Promise<number> {
  return page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
}

test('员工创建工单：双击只建一张、刷新后仍在、列表可回溯，1440 与 375 都不横向溢出', async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(120_000)
  const suffix = Date.now().toString().slice(-6)
  const title = `E2E 联调工单 ${suffix}`
  const description = 'E2E 自动用例创建：验证提交幂等、详情展示与列表回溯，可以安全删除。'
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.setViewportSize({ width: 1440, height: 900 })
  await signIn(page, employeeUsername)

  // ---- 权限只影响入口显隐：员工没有队列与参与范围，就看不到那两个按钮 ----
  await nav(page).getByRole('link', { name: '工单', exact: true }).click()
  await expect(page).toHaveURL(/\/tickets$/)
  const scopeButtons = page.locator('.ticket-scope-bar .el-radio-button')
  await expect(scopeButtons).toHaveCount(1)
  await expect(scopeButtons.first()).toContainText('我提交的')
  // 等列表真正取完数：骨架还亮着就截图，等于什么都没验证
  await expect(page.locator('.admin-panel__skeleton')).toHaveCount(0)
  await expect(page.locator('.empty-state, .admin-table').first()).toBeVisible()
  expect(await horizontalOverflow(page)).toBe(0)
  await page.screenshot({ path: resolve(reviewDir, 'ticket-list-1440.png'), fullPage: true })

  // ---- 新建：分类选项来自启用中的分类 ----
  await nav(page).getByRole('link', { name: '新建工单', exact: true }).click()
  await expect(page).toHaveURL(/\/tickets\/new$/)

  const submit = page.getByRole('button', { name: '提交工单' })
  await submit.click()
  await expect(page.locator('.el-form-item__error').filter({ hasText: '请输入标题' })).toBeVisible()
  await expect(page.locator('.el-form-item__error').filter({ hasText: '请输入问题描述' })).toBeVisible()

  await page.getByLabel('标题').fill(title)
  await page.getByLabel('问题描述').fill(description)

  /**
   * 优先级是三个带后果说明的选项，不是下拉。点整块选项（`<label>`）而不是内部的原生 radio：
   * Element Plus 的原生 radio 是 `opacity: 0` 的覆盖层，直接 hit-test 会被同级的
   * `.el-radio__inner` 拦下；点 label 则既命中它的子元素，又按浏览器的 label 语义勾选。
   */
  const highPriority = page.locator('.ticket-priority__option').filter({ hasText: '无法继续工作' })
  await highPriority.click()
  await expect(page.getByRole('radio', { name: /无法继续工作/ })).toBeChecked()

  const categorySelect = page.locator('.ticket-form__control')
  await categorySelect.click()
  const firstCategory = page.locator('.el-select-dropdown__item:visible').first()
  await expect(firstCategory).toBeVisible()
  await firstCategory.click()
  await page.keyboard.press('Escape')
  await page.getByRole('heading', { name: '新建工单', exact: true }).click()
  await expect(page.locator('.el-form-item__error')).toHaveCount(0)

  await page.screenshot({ path: resolve(reviewDir, 'ticket-create-1440.png'), fullPage: true })
  await page.setViewportSize({ width: 375, height: 812 })
  expect(await horizontalOverflow(page)).toBe(0)
  await page.screenshot({ path: resolve(reviewDir, 'ticket-create-375.png'), fullPage: true })
  await page.setViewportSize({ width: 1440, height: 900 })

  /**
   * 把创建请求拖慢，让"请求期间禁止重复点击"这件事有一个可观察的窗口。
   * 只拦 POST：列表的 GET 不能被拖慢，否则后面的断言会一起变慢。
   */
  const submissionKeys: string[] = []
  await page.route('**/fd/v1/tickets', async (route) => {
    if (route.request().method() === 'POST') {
      const key = route.request().postData()?.match(/"submissionKey"\s*:\s*"([^"]+)"/)?.[1]
      if (key) submissionKeys.push(key)
      await new Promise((resolveDelay) => setTimeout(resolveDelay, 800))
    }
    await route.continue()
  })

  const createdResponse = page.waitForResponse(
    (response) => response.url().endsWith('/fd/v1/tickets') && response.request().method() === 'POST',
  )
  await submit.dblclick()

  // 提交进行中：表单整体禁用，按钮同时处于 loading。
  // 这一条就是"请求期间禁止重复点击"的可观察证据——想点第二次也点不动。
  await expect(submit).toBeDisabled()

  // ---- 成功进入详情 ----
  await expect(page).toHaveURL(/\/tickets\/FD-\d{8}-\d{3}$/)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)
  const creation = await createdResponse
  expect(creation.status()).toBe(201)

  const ticketNo = (await page.locator('.ticket-meta__no').innerText()).trim()
  expect(ticketNo).toMatch(/^FD-\d{8}-\d{3}$/)

  const meta = page.locator('.ticket-meta')
  await expect(meta).toContainText('待受理')
  await expect(meta).toContainText('优先级 高')

  /**
   * 待受理 + 提交人自己：`allowedActions` 对员工是空数组，所以整块动作区都不出现。
   * 这是"按服务端返回渲染按钮，而不是前端推导"最容易观察到的证据——
   * 员工会看到 `claim` 按钮的那张工单，在服务端放行 `claim` 之前也不会出现。
   */
  await expect(page.locator('.ticket-action-panel')).toHaveCount(0)
  await expect(page.locator('.ticket-action .el-button')).toHaveCount(0)

  // 属性栏与时间线：问题正文、分类、提交人、时间都在，创建记录已写入时间线
  await expect(page.locator('.ticket-description')).toContainText('验证提交幂等')
  await expect(page.locator('.ticket-facts dt:has-text("提交人") + dd')).not.toBeEmpty()
  await expect(page.locator('.ticket-timeline__item').first()).toContainText('创建工单')
  await expect(page.locator('.ticket-timeline__item')).toHaveCount(1)
  expect(await horizontalOverflow(page)).toBe(0)
  await page.screenshot({ path: resolve(reviewDir, 'ticket-detail-1440.png'), fullPage: true })

  // ---- 刷新恢复：内存里的 Access Token 会丢，靠 HttpOnly Refresh Cookie 重新恢复身份 ----
  await page.reload()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)
  await expect(page.locator('.ticket-meta__no')).toHaveText(ticketNo)
  await expect(page.locator('.ticket-timeline__item').first()).toContainText('创建工单')

  // ---- 列表可回溯，而且用同一个标题只搜到一张：证明没有建出第二张工单 ----
  await page.getByRole('link', { name: '返回工单列表' }).first().click()
  // 用 $ 收尾：/\/tickets/ 也会匹配 /tickets/FD-...，万一没跳成也会"通过"
  await expect(page).toHaveURL(/\/tickets$/)

  await page.getByLabel('工单关键词').fill(suffix)
  await page.getByRole('button', { name: '查询' }).click()

  const rows = page.locator('.admin-table tbody tr')
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText(title)
  await expect(rows.first()).toContainText(ticketNo)

  const link = rows.first().locator('.ticket-cell__title')
  await expect(link).toHaveAttribute('href', `/tickets/${ticketNo}`)
  await page.screenshot({ path: resolve(reviewDir, 'ticket-list-searched-1440.png'), fullPage: true })

  // ---- 本人隔离：直接改地址里的 scope 越权，界面忽略它，不会给出别人的数据 ----
  await page.goto('/tickets?scope=PENDING_QUEUE')
  await expect(page.locator('.ticket-scope-bar .el-radio-button')).toHaveCount(1)
  await expect(page.locator('.ticket-scope-bar .el-radio-button').first()).toContainText('我提交的')
  // 仍然是"我提交的"：列表里出现的必须是当前账号自己的那张工单
  await expect(page.locator('.admin-table tbody tr').first()).toContainText(title)

  // ---- 375：列表与详情都不横向溢出，详情由两轨改成上下排列 ----
  await page.setViewportSize({ width: 375, height: 812 })

  expect(await horizontalOverflow(page), '工单列表在 375 下被撑宽').toBe(0)
  await page.screenshot({ path: resolve(reviewDir, 'ticket-list-375.png'), fullPage: true })

  await link.click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)

  expect(await horizontalOverflow(page), '工单详情在 375 下被撑宽').toBe(0)

  // "上下排列"不能只看截图：直接比较两个轨道的几何位置
  const mainBox = await page.locator('.ticket-detail__main').boundingBox()
  const sideBox = await page.locator('.ticket-detail__side').boundingBox()
  expect(mainBox).not.toBeNull()
  expect(sideBox).not.toBeNull()
  expect(sideBox!.y).toBeGreaterThanOrEqual(mainBox!.y + mainBox!.height - 1)
  expect(sideBox!.width).toBeCloseTo(mainBox!.width, 0)

  await page.screenshot({ path: resolve(reviewDir, 'ticket-detail-375.png'), fullPage: true })

  // 第二个纯员工账号：同一标题在本人列表为空，直接访问详情被后端统一为 404。
  const isolation = await createIsolationEmployee(request, suffix)
  const otherContext = await browser.newContext({ baseURL: 'http://127.0.0.1:4173' })
  let isolationTraceId: string | undefined
  try {
    const otherPage = await otherContext.newPage()
    await signIn(otherPage, isolation.username, isolation.initialPassword)
    await otherPage.goto('/tickets')
    await otherPage.getByLabel('工单关键词').fill(title)
    await otherPage.getByRole('button', { name: '查询' }).click()
    await expect(otherPage.locator('.admin-table tbody tr')).toHaveCount(0)
    await expect(otherPage.locator('.empty-state')).toBeVisible()
    const denied = otherPage.waitForResponse(
      (response) => response.url().endsWith(`/fd/v1/tickets/${ticketNo}`),
    )
    await otherPage.goto(`/tickets/${ticketNo}`)
    const deniedResponse = await denied
    expect(deniedResponse.status()).toBe(404)
    const deniedBody = await deniedResponse.json()
    expect(deniedBody.code).toBe('TICKET_NOT_FOUND')
    isolationTraceId = deniedResponse.headers()['x-trace-id']
    expect(isolationTraceId).toBeTruthy()
    await expect(otherPage.getByText('工单不存在或你没有查看权限', { exact: true })).toBeVisible()
    await expect(otherPage.locator('.ticket-description')).toHaveCount(0)
    await otherPage.screenshot({ path: resolve(reviewDir, 'ticket-isolation-404.png'), fullPage: true })
    await otherPage.getByRole('button', { name: '账号' }).click()
    await otherPage.getByText('退出登录').click()
    await expect(otherPage).toHaveURL(/\/login/)
  } finally {
    await otherContext.close()
  }
  expect(submissionKeys.length).toBeGreaterThanOrEqual(1)
  expect(new Set(submissionKeys).size).toBe(1)
  expect(pageErrors).toEqual([])
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  writeFileSync(resolve(reviewDir, 'runtime-evidence.json'), JSON.stringify({
    ticketNo, title, isolationUsername: isolation.username,
    creationStatus: creation.status(), creationTraceId: creation.headers()['x-trace-id'],
    creationRequests: submissionKeys.length, uniqueSubmissionKeys: new Set(submissionKeys).size,
    matchingOwnRows: 1, timelineRecords: 1, refreshed: true,
    otherEmployeeOwnRows: 0, otherEmployeeDetailStatus: 404, isolationTraceId,
    viewports: [1440, 375], horizontalOverflow: 0, pageErrors,
  }, null, 2))
})

test('分类管理：创建、改名、停用启用、删除及桌面窄屏验收', async ({ page }) => {
  const name = `E2E_STAGE2_CATEGORY_${Date.now()}`
  const updatedName = `${name}_updated`
  const checks: { action: string; status: number; traceId?: string }[] = []
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const record = async (action: string, response: Awaited<ReturnType<Page['waitForResponse']>>) => {
    expect(response.status()).toBe(action === 'create' ? 201 : 200)
    const traceId = response.headers()['x-trace-id']
    expect(traceId).toBeTruthy()
    checks.push({ action, status: response.status(), traceId })
  }
  const confirm = async () => {
    await page.locator('.el-popconfirm__action button:visible').filter({ hasText: '确定' }).click()
  }
  await page.setViewportSize({ width: 1440, height: 900 })
  await signIn(page, process.env.E2E_ADMIN_USERNAME ?? 'admin')
  await nav(page).getByRole('link', { name: '分类管理', exact: true }).click()
  await expect(page.getByRole('heading', { name: '分类管理' })).toBeVisible()
  await expect(page.getByRole('button', { name: '编辑', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '新增', exact: true }).click()
  const createDialog = page.getByRole('dialog', { name: '新建分类' })
  await createDialog.getByPlaceholder('请输入分类名称').fill(name)
  const creating = page.waitForResponse((response) =>
    response.url().endsWith('/fd/v1/admin/categories') && response.request().method() === 'POST',
  )
  await createDialog.getByRole('button', { name: '创建', exact: true }).click()
  await record('create', await creating)
  await expect(createDialog).not.toBeVisible()
  await page.locator('#category-keyword').fill(name)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const row = page.locator('.admin-table tbody tr').filter({ hasText: name })
  await expect(row).toHaveCount(1)
  await row.getByRole('button', { name: '修改分类', exact: true }).click()
  const editDialog = page.getByRole('dialog', { name: '修改分类' })
  await editDialog.getByPlaceholder('请输入分类名称').fill(updatedName)
  const updating = page.waitForResponse((response) =>
    response.url().includes('/fd/v1/admin/categories/') && response.request().method() === 'PUT',
  )
  await editDialog.getByRole('button', { name: '保存', exact: true }).click()
  await record('update', await updating)
  await expect(editDialog).not.toBeVisible()
  await expect(row).toContainText(updatedName)
  for (const action of ['disable', 'enable']) {
    await row.locator('.el-switch').click()
    const changing = page.waitForResponse((response) => response.url().endsWith(`/actions/${action}`))
    await confirm()
    await record(action, await changing)
    if (action === 'disable') await expect(row.getByRole('switch')).not.toBeChecked()
    else await expect(row.getByRole('switch')).toBeChecked()
  }
  await expect(page.locator('.el-message')).toHaveCount(0)
  expect(await horizontalOverflow(page)).toBe(0)
  await page.screenshot({ path: resolve(reviewDir, 'category-list-1440.png'), fullPage: true })
  await page.setViewportSize({ width: 375, height: 812 })
  expect(await horizontalOverflow(page)).toBe(0)
  await page.screenshot({ path: resolve(reviewDir, 'category-list-375.png'), fullPage: true })
  await row.getByRole('button', { name: '删除分类', exact: true }).click()
  const deleting = page.waitForResponse((response) =>
    response.url().includes('/fd/v1/admin/categories/') && response.request().method() === 'DELETE',
  )
  await confirm()
  await record('delete', await deleting)
  await expect(row).toHaveCount(0)
  await expect(page.locator('.empty-state')).toBeVisible()
  expect(errors).toEqual([])
  writeFileSync(resolve(reviewDir, 'category-runtime-evidence.json'),
    JSON.stringify({ name, checks, viewports: [1440, 375], horizontalOverflow: 0, pageErrors: errors }, null, 2))
})
