import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * 工单关联（2026-10-12，完整版 backlog 2 的「轻」一侧）。
 *
 * <p>一条用例走完整链，全部从真实点击产生：员工建一张原工单 → 再建后续工单并在选择器里
 * 搜出原工单、选中 → 后续工单详情显示「原工单」→ 点编号进入原工单详情，显示「后续工单」。
 * 它证明四件事：选择器真的搜索到"我提交的"、编号随创建请求提交、服务端写回的方向在
 * 两种视角下都正确、详情页的关联块按固定四格标签渲染。</p>
 *
 * <p><strong>这些用例都会向演示库写入工单</strong>（接口没有删除能力）。清理顺序与
 * `frontend/e2e/tickets.spec.ts` 文件头一致：先删 `ticket_relation` / `ticket_record` /
 * `ticket_participant`，再删 `ticket`：</p>
 *
 * ```sql
 * DELETE r FROM ticket_relation r JOIN ticket t ON t.id = r.source_ticket_id
 *  WHERE t.title LIKE 'E2E 关联%';
 * DELETE r FROM ticket_record r JOIN ticket t ON t.id = r.ticket_id
 *  WHERE t.title LIKE 'E2E 关联%';
 * DELETE p FROM ticket_participant p JOIN ticket t ON t.id = p.ticket_id
 *  WHERE t.title LIKE 'E2E 关联%';
 * DELETE FROM ticket WHERE title LIKE 'E2E 关联%';
 * ```
 */

const password = process.env.E2E_PASSWORD ?? '123456'
const employeeUsername = process.env.E2E_EMPLOYEE_USERNAME ?? 'employee'

/** 评审产物目录：与其它 E2E 的视觉证据放在同一处（未跟踪产物）。 */
const reviewDir = resolve(process.cwd(), '../.ui-craft/reviews/2026-10-12-ticket-relations')
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

/**
 * 员工建单；`sourceTicketNo` 非空时先在「关联原工单」下拉里搜出它再选中。
 *
 * <p>搜索走 fill 而不是逐字 type：`el-select` 的远程搜索监听 input 事件，
 * `fill` 一次把值写进去并派发事件，比模拟逐键输入稳定。</p>
 */
async function createTicket(
  page: Page,
  title: string,
  description: string,
  sourceTicketNo: string | null,
  steps: StepEvidence[],
): Promise<string> {
  await nav(page).getByRole('link', { name: '新建工单', exact: true }).click()
  await expect(page).toHaveURL(/\/tickets\/new$/)
  await page.getByLabel('标题').fill(title)
  await page.getByLabel('问题描述').fill(description)

  // 分类：展开后选第一个启用分类
  await page.locator('.ticket-form__control').first().click()
  await page.locator('.el-select-dropdown__item:visible').first().click()
  await page.keyboard.press('Escape')

  if (sourceTicketNo !== null) {
    // 关联原工单：先点开下拉聚焦输入框，再写入编号触发远程搜索，最后点中那一项
    await page.locator('.ticket-form__source').click()
    await page.locator('.ticket-form__source input').fill(sourceTicketNo)
    const option = page.locator('.el-select-dropdown__item:visible').filter({ hasText: sourceTicketNo })
    await expect(option.first()).toBeVisible()
    await option.first().click()
    await page.keyboard.press('Escape')
  }

  const creating = page.waitForResponse(
    (item) => item.url().endsWith('/fd/v1/tickets') && item.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '提交工单' }).click()
  const created = await creating
  expect(created.status()).toBe(201)
  await expect(page).toHaveURL(/\/tickets\/FD-\d{8}-\d{3}$/)
  const ticketNo = (await page.locator('.ticket-hero__no').innerText()).trim()
  record(
    steps,
    sourceTicketNo === null ? 'create.original' : 'create.followUp',
    created,
    sourceTicketNo === null
      ? `${ticketNo} 作为原工单创建`
      : `${ticketNo} 引用 ${sourceTicketNo} 创建`,
  )
  return ticketNo
}

/**
 * 关联工单面板。
 *
 * <p>用 `region` 角色定位而不是 `section` + `has` 过滤：外层还有 app shell 与页面工作区两层
 * `<section>`，后者也"包含"这个标题，`section` 过滤会命中三个元素（首次运行实测）。</p>
 */
function relationsBlock(page: Page) {
  return page.getByRole('region', { name: '关联工单' })
}

test('工单关联：新建后续工单引用原工单，两侧详情按方向显示关联关系', async ({ page }) => {
  test.setTimeout(240_000)
  const suffix = Date.now().toString().slice(-6)
  const originalTitle = `E2E 关联原工单 ${suffix}`
  const followUpTitle = `E2E 关联后续工单 ${suffix}`
  const steps: StepEvidence[] = []
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.setViewportSize({ width: 1440, height: 900 })
  await signIn(page, employeeUsername)

  // 1. 先建一张「原工单」
  const originalNo = await createTicket(
    page,
    originalTitle,
    'E2E 工单关联：先提交的原工单，后续工单会引用它。',
    null,
    steps,
  )

  // 2. 再建「后续工单」：在选择器里搜出原工单并选中
  const followUpNo = await createTicket(
    page,
    followUpTitle,
    'E2E 工单关联：问题再次出现，引用之前那张工单继续处理。',
    originalNo,
    steps,
  )

  // 3. 后续工单视角：OUTGOING → 「原工单」，摘要显示对端编号、标题与状态
  const followUpBlock = relationsBlock(page)
  await expect(followUpBlock).toBeVisible()
  const outgoingRow = followUpBlock.locator('.ticket-relations__item').first()
  await expect(outgoingRow).toContainText('原工单')
  await expect(outgoingRow).toContainText(originalNo)
  await expect(outgoingRow).toContainText(originalTitle)
  await expect(outgoingRow).toContainText('待受理')
  await page.screenshot({
    path: resolve(reviewDir, 'follow-up-detail.png'),
    fullPage: true,
  })

  // 4. 从关联块里点编号进入原工单：链接真的可用，且方向翻转为 INCOMING → 「后续工单」
  await outgoingRow.locator('.ticket-relations__link').click()
  await expect(page).toHaveURL(new RegExp(`/tickets/${originalNo}$`))
  const originalBlock = relationsBlock(page)
  await expect(originalBlock).toBeVisible()
  const incomingRow = originalBlock.locator('.ticket-relations__item').first()
  await expect(incomingRow).toContainText('后续工单')
  await expect(incomingRow).toContainText(followUpNo)
  await expect(incomingRow).toContainText(followUpTitle)
  await page.screenshot({
    path: resolve(reviewDir, 'original-detail.png'),
    fullPage: true,
  })

  expect(pageErrors, '页面不应出现未捕获的脚本错误').toEqual([])

  writeFileSync(
    resolve(reviewDir, 'runtime-evidence-relations.json'),
    `${JSON.stringify(
      {
        scenario: '员工建后续工单引用原工单，两侧详情按方向显示关联',
        accounts: [employeeUsername],
        tickets: { original: originalNo, followUp: followUpNo },
        steps,
        pageErrors,
      },
      null,
      2,
    )}\n`,
    'utf8',
  )
})