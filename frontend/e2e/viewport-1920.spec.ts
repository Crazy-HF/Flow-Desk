import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { expectNoHorizontalOverflow } from './support/overflow'

/**
 * 1920 宽屏自查（阶段 4 MVP 收口）。
 *
 * <p>阶段 1～3 的视觉与溢出证据都只覆盖 1440 与 375；1920 是最常见的办公显示器宽度，
 * 表格、筛选面板与分页在更宽的容器里会换一套排版（`--fd-admin-list-max` 放开后的表现
 * 只能在宽屏上看到），所以这里把演示要用到的页面在 1920×1080 下逐个走一遍，
 * 断言整页横向溢出为 0 并留截图。</p>
 *
 * <p><strong>这条用例是只读的</strong>：不建工单、不建用户、不改任何数据，也不依赖演示库里
 * 有没有工单——CI 的 `core-e2e` 跑在干净的 demo 库上，任何"依赖某张历史工单"的写法都会
 * 在那里失败（阶段 3 的 E2E 就吃过这个亏）。因此工单详情页不在本用例范围内。</p>
 */

const password = process.env.E2E_PASSWORD ?? '123456'
const employeeUsername = process.env.E2E_EMPLOYEE_USERNAME ?? 'employee'
const itUsername = process.env.E2E_IT_USERNAME ?? 'it'
const adminUsername = process.env.E2E_ADMIN_USERNAME ?? 'admin'

/** 证据目录：与本次收口的其它产物放在同一处。 */
const reviewDir = resolve(process.cwd(), '../.ui-craft/reviews/2026-10-06-mvp-closeout')
mkdirSync(reviewDir, { recursive: true })

const WIDTH = 1920
const HEIGHT = 1080

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

/** 页面稳定后再量溢出并截图；等的是"这一页真的画完了"，不是固定时长。 */
async function capture(page: Page, name: string): Promise<void> {
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await page.waitForLoadState('networkidle')
  await expectNoHorizontalOverflow(page, `${name} 在 1920 下出现整页横向溢出`)
  await page.screenshot({
    path: resolve(reviewDir, `${name}-1920.png`),
    fullPage: true,
  })
}

test('1920 宽屏：员工、IT 与管理页均无整页横向溢出', async ({ page }) => {
  test.setTimeout(180_000)
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.setViewportSize({ width: WIDTH, height: HEIGHT })

  // ---------- 未登录：登录页 ----------
  await page.goto('/login')
  await capture(page, 'login')

  // ---------- 员工侧：首页、工单列表、新建工单 ----------
  await signIn(page, employeeUsername)
  await capture(page, 'employee-home')

  await nav(page).getByRole('link', { name: '工单', exact: true }).click()
  await expect(page).toHaveURL(/\/tickets$/)
  await capture(page, 'ticket-list')

  await nav(page).getByRole('link', { name: '新建工单', exact: true }).click()
  await expect(page).toHaveURL(/\/tickets\/new$/)
  await capture(page, 'ticket-create')

  await signOut(page)

  // ---------- IT 侧：工作台默认停在待受理 ----------
  await signIn(page, itUsername)
  await nav(page).getByRole('link', { name: 'IT 工作台', exact: true }).click()
  await expect(page).toHaveURL(/\/it\/queue$/)
  await capture(page, 'it-queue')

  await signOut(page)

  // ---------- 管理端：六页 ----------
  await signIn(page, adminUsername)
  const adminPages: Array<{ link: string; url: RegExp; name: string }> = [
    { link: '用户管理', url: /\/admin\/users$/, name: 'admin-users' },
    { link: '角色管理', url: /\/admin\/roles$/, name: 'admin-roles' },
    { link: '权限管理', url: /\/admin\/permissions$/, name: 'admin-permissions' },
    { link: '用户角色授权', url: /\/admin\/user-roles$/, name: 'admin-user-roles' },
    { link: '角色权限授权', url: /\/admin\/role-permissions$/, name: 'admin-role-permissions' },
    { link: '分类管理', url: /\/admin\/categories$/, name: 'admin-categories' },
  ]

  for (const item of adminPages) {
    await nav(page).getByRole('link', { name: item.link, exact: true }).click()
    await expect(page).toHaveURL(item.url)
    await capture(page, item.name)
  }

  expect(pageErrors, '页面上出现了 JavaScript 错误').toEqual([])
})
