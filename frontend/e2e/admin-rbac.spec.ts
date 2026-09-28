import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

/**
 * RBAC 管理端页面的真实闭环用例（`TASK-060` / `TASK-062`）。
 *
 * <p>这些用例<strong>会写演示库</strong>：用例自己创建临时角色、授予、再逐层撤销并删除，
 * 并断言清理干净（列表里不再出现该角色）。角色编码带时间戳后缀，两次运行之间不会互相污染，
 * 也不会碰到迁移预置的三种内置角色。</p>
 */
const adminUsername = process.env.E2E_ADMIN_USERNAME ?? 'admin'
const password = process.env.E2E_PASSWORD ?? '123456'

async function signIn(page: Page): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('请输入登录名').fill(adminUsername)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
}

function nav(page: Page) {
  return page.getByRole('navigation', { name: '主导航' })
}

async function openEntry(page: Page, label: string): Promise<void> {
  await nav(page).getByRole('link', { name: label, exact: true }).click()
}

function rowWith(page: Page, text: string) {
  return page.locator('tbody tr').filter({ hasText: text })
}

/**
 * 在下拉里选一项（按控件自己的可访问名定位它所在的筛选字段）。
 *
 * <p>点 select 的根元素而不是内部的 input：内部 input 被占位符覆盖，Playwright 会判定
 * "pointer events 被拦截"。选完用 `toContainText` 断言命中的确实是目标项——否则选错
 * 下拉会静默变成"没选"，表现为后面那句成功提示永远等不到。</p>
 */
async function pickOption(page: Page, fieldLabel: string, query: string): Promise<void> {
  const select = page.locator(`.admin-filter-field:has([aria-label="${fieldLabel}"]) .el-select`)
  await select.click()
  await page.keyboard.type(query)
  const option = page.locator('.el-select-dropdown__item:visible').first()
  await expect(option).toContainText(query)
  await option.click()
  await page.keyboard.press('Escape')
  await expect(select).toContainText(query)
}

/** popconfirm 的确认按钮渲染在挂载到 body 的弹层里，只取当前可见的那个。 */
async function confirmPopconfirm(page: Page): Promise<void> {
  await page.locator('.el-popconfirm__action button:visible').filter({ hasText: '确定' }).click()
}

/**
 * 点「批量授予」，并直接断言批量端点的响应。
 *
 * <p>比对提示文案更可靠：提示只活 3 秒，超时后什么都看不到；这里拿到的是状态码与
 * `data` 长度，失败时能直接看到后端说了什么。</p>
 */
async function submitGrant(page: Page, endpoint: string, expectedNew: number): Promise<void> {
  const [response] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        candidate.url().endsWith(endpoint) && candidate.request().method() === 'POST',
    ),
    page.getByRole('button', { name: '批量授予' }).click(),
  ])
  expect(response.status(), await response.text()).toBe(200)
  const body = (await response.json()) as { data: unknown[] }
  expect(body.data).toHaveLength(expectedNew)
}

test('角色与权限闭环：建角色 → 授权限 → 清空并删除，清理干净', async ({ page }, testInfo) => {
  const suffix = Date.now().toString().slice(-6)
  const roleCode = `E2E_AGENT_${suffix}`
  const roleName = `E2E 临时角色 ${suffix}`

  await signIn(page)

  await openEntry(page, '角色管理')
  await expect(page).toHaveURL(/\/admin\/roles$/)
  await expect(rowWith(page, 'SYSTEM_ADMIN').first()).toBeVisible()

  await page.getByRole('button', { name: '新增', exact: true }).click()
  await page.getByPlaceholder('请输入角色编码', { exact: true }).fill(roleCode)
  await page.getByPlaceholder('请输入角色名称', { exact: true }).fill(roleName)
  await page.getByRole('button', { name: '创建', exact: true }).click()

  await expect(page.getByText('角色已创建')).toBeVisible()
  await expect(rowWith(page, roleCode)).toBeVisible()

  // 授权限：从角色行进入角色权限授权页，角色已被带过去（新角色还没有任何权限）
  await rowWith(page, roleCode).getByRole('button', { name: '维护角色权限' }).click()
  await expect(page).toHaveURL(/\/admin\/role-permissions\?roleId=\d+/)
  await expect(page.getByText('该角色当前没有权限')).toBeVisible()

  await pickOption(page, '要授予的权限', 'TICKET_CREATE')
  await submitGrant(page, '/admin/role-permissions', 1)
  await expect(page.locator('.grant-row').filter({ hasText: 'TICKET_CREATE' })).toBeVisible()

  await page.screenshot({ path: testInfo.outputPath('admin-role-permissions.png'), fullPage: true })

  // 清理：先清空该角色全部权限，再删除角色
  await page.getByRole('button', { name: '清空全部权限' }).click()
  await confirmPopconfirm(page)
  await expect(page.getByText('已清空该角色的全部权限')).toBeVisible()

  await openEntry(page, '角色管理')
  await rowWith(page, roleCode).getByRole('button', { name: '删除角色' }).click()
  await confirmPopconfirm(page)
  await expect(page.getByText('角色已删除')).toBeVisible()

  await expect(rowWith(page, roleCode)).toHaveCount(0)
})

test('用户角色闭环：给员工授临时角色 → 撤销，并保护内置管理员的删除入口', async ({ page }, testInfo) => {
  const suffix = Date.now().toString().slice(-6)
  const roleCode = `E2E_USER_${suffix}`
  const roleName = `E2E 用户角色 ${suffix}`

  await signIn(page)

  // 先建一个临时角色，供本用例授予用户
  await openEntry(page, '角色管理')
  await page.getByRole('button', { name: '新增', exact: true }).click()
  await page.getByPlaceholder('请输入角色编码', { exact: true }).fill(roleCode)
  await page.getByPlaceholder('请输入角色名称', { exact: true }).fill(roleName)
  await page.getByRole('button', { name: '创建', exact: true }).click()
  await expect(page.getByText('角色已创建')).toBeVisible()

  // 用户管理：内置管理员行带保护标记，受保护角色不可删除
  await openEntry(page, '用户管理')
  await expect(page).toHaveURL(/\/admin\/users$/)
  await expect(rowWith(page, 'admin').first()).toContainText('管理员保护')
  await page.screenshot({ path: testInfo.outputPath('admin-users.png'), fullPage: true })

  await rowWith(page, 'employee').first().getByRole('button', { name: '角色授权' }).click()
  await expect(page).toHaveURL(/\/admin\/user-roles\?userId=\d+/)

  // 路由跳转先于预选用户的异步初始化；初始化会清空角色选择，等关系加载后再操作。
  await expect(page.locator('.grant-row').filter({ hasText: 'employee' }).first()).toBeVisible()

  await pickOption(page, '要授予的角色', roleCode)
  await submitGrant(page, '/admin/user-roles', 1)

  const granted = page.locator('.grant-row').filter({ hasText: roleCode })
  await expect(granted).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('admin-user-roles.png'), fullPage: true })

  // 清理：撤销这条关系，再删除临时角色
  await granted.getByRole('button', { name: '撤销' }).click()
  await confirmPopconfirm(page)
  await expect(page.getByText(/已撤销/)).toBeVisible()
  await expect(page.locator('.grant-row').filter({ hasText: roleCode })).toHaveCount(0)

  await openEntry(page, '角色管理')
  await rowWith(page, roleCode).getByRole('button', { name: '删除角色' }).click()
  await confirmPopconfirm(page)
  await expect(rowWith(page, roleCode)).toHaveCount(0)
})

test('入口按权限显隐：管理员看到五页，受保护对象的删除入口被禁用', async ({ page }, testInfo) => {
  await signIn(page)

  for (const label of ['用户管理', '角色管理', '权限管理', '用户角色授权', '角色权限授权']) {
    await expect(nav(page).getByRole('link', { name: label, exact: true })).toBeVisible()
  }

  await openEntry(page, '权限管理')
  await expect(page.getByRole('heading', { name: '权限管理' })).toBeVisible()

  const protectedRow = rowWith(page, 'RBAC_MANAGE').first()
  await expect(protectedRow).toContainText('内置')
  await expect(protectedRow.getByRole('button', { name: '删除权限' })).toBeDisabled()
  await page.screenshot({ path: testInfo.outputPath('admin-permissions.png'), fullPage: true })

  await openEntry(page, '角色管理')
  await expect(page.getByRole('heading', { name: '角色管理' })).toBeVisible()
  await expect(rowWith(page, 'SYSTEM_ADMIN').first()).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('admin-roles.png'), fullPage: true })

  // 员工没有任何管理权限：五页入口都不出现
  await page.getByRole('button', { name: '账号' }).click()
  await page.getByText('退出登录').click()
  await expect(page).toHaveURL(/\/login/)

  await page.getByPlaceholder('请输入登录名').fill(process.env.E2E_USERNAME ?? 'employee')
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()

  for (const label of ['用户管理', '角色管理', '权限管理', '用户角色授权', '角色权限授权']) {
    await expect(nav(page).getByRole('link', { name: label, exact: true })).toHaveCount(0)
  }
})

/** 窄屏（≤52rem）是六态之一：到这里为止它只有 CSS，没有实测证据。 */
test.describe('窄屏', () => {
  test.use({ viewport: { width: 768, height: 900 } })

  test('窄屏下页面不横向溢出，关系行竖排并隐藏关系轴', async ({ page }, testInfo) => {
    await signIn(page)

    // 窄屏下侧栏收进抽屉，入口不可点，直接按地址进入
    await page.goto('/admin/users')
    await expect(page.locator('tbody tr').first()).toBeVisible()
    await expect(page.locator('.admin-filter-fields')).toBeVisible()
    expect(await documentOverflow(page)).toBeLessThanOrEqual(0)
    await page.screenshot({ path: testInfo.outputPath('admin-users-narrow.png'), fullPage: true })

    await page.goto('/admin/roles')
    await rowWith(page, 'SYSTEM_ADMIN').first().getByRole('button', { name: '维护角色权限' }).click()
    await expect(page).toHaveURL(/\/admin\/role-permissions\?roleId=\d+/)
    await expect(page.locator('.grant-row').first()).toBeVisible()
    await expect(page.locator('.grant-row__axis').first()).toBeHidden()
    expect(await documentOverflow(page)).toBeLessThanOrEqual(0)
  })
})

/** 文档级横向滚动量：>0 表示页面在窄屏下横向溢出。 */
async function documentOverflow(page: Page): Promise<number> {
  return page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
}
