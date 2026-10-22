import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

import { expectNoHorizontalOverflow } from './support/overflow'

/**
 * 数据概览页的浏览器验收（`docs/api-design.md` 8.5）。
 *
 * <p>接口用路由拦截模拟，因此这条用例**不需要后端**：它验证的是页面自己的行为
 * （范围切换、分类选项保留、空态与失败态）与版式（1440 / 375 无整页横向溢出）。
 * 统计口径与授权由后端测试负责，这里不重复。</p>
 *
 * <p>截图落在 `.ui-craft/reviews/2026-10-21-ticket-dashboard/`，与其它阶段的证据同一处。</p>
 */

const reviewDir = resolve(process.cwd(), '../.ui-craft/reviews/2026-10-21-ticket-dashboard')
mkdirSync(reviewDir, { recursive: true })

const itUser = {
  id: 4,
  username: 'it',
  displayName: '演示 IT 支持人员',
  status: 'ENABLED',
  roles: ['IT_SUPPORT'],
  permissions: ['DASHBOARD_VIEW', 'TICKET_VIEW_QUEUE', 'TICKET_VIEW_PARTICIPATED'],
}

const reply = (data: unknown) => ({ code: 'OK', message: 'OK', data })

/** 三个范围给不同总数，切换范围后数字必须真的变——否则"重新取数"没有被验证。 */
const dashboards: Record<string, unknown> = {
  PENDING_QUEUE: {
    scope: 'PENDING_QUEUE',
    total: 7,
    byStatus: [{ status: 'PENDING', count: 7 }],
    byPriority: [
      { priority: 'HIGH', count: 2 },
      { priority: 'MEDIUM', count: 3 },
      { priority: 'LOW', count: 2 },
    ],
    byCategory: [
      { category: { id: 7, name: '办公设备' }, count: 4 },
      { category: { id: 3, name: '网络与账号' }, count: 2 },
      { category: { id: 9, name: '已停用的旧分类' }, count: 1 },
    ],
    byAssignee: [{ count: 7 }],
  },
  ASSIGNED_TO_ME: {
    scope: 'ASSIGNED_TO_ME',
    total: 3,
    byStatus: [
      { status: 'PROCESSING', count: 2 },
      { status: 'COMPLETED', count: 1 },
    ],
    byPriority: [
      { priority: 'HIGH', count: 1 },
      { priority: 'MEDIUM', count: 2 },
    ],
    byCategory: [{ category: { id: 7, name: '办公设备' }, count: 3 }],
    byAssignee: [{ assignee: { id: 4, displayName: '演示 IT 支持人员' }, count: 3 }],
  },
  PARTICIPATED_BY_ME: {
    scope: 'PARTICIPATED_BY_ME',
    total: 12,
    byStatus: [
      { status: 'PROCESSING', count: 4 },
      { status: 'WAITING_FOR_REQUESTER', count: 2 },
      { status: 'COMPLETED', count: 5 },
      { status: 'CLOSED', count: 1 },
    ],
    byPriority: [
      { priority: 'HIGH', count: 4 },
      { priority: 'MEDIUM', count: 5 },
      { priority: 'LOW', count: 3 },
    ],
    byCategory: [
      { category: { id: 3, name: '网络与账号' }, count: 6 },
      { category: { id: 7, name: '办公设备' }, count: 4 },
      { category: { id: 11, name: '门禁与考勤' }, count: 2 },
    ],
    byAssignee: [
      { assignee: { id: 4, displayName: '演示 IT 支持人员' }, count: 5 },
      { assignee: { id: 9, displayName: '另一位 IT 同事' }, count: 4 },
      { count: 3 },
    ],
  },
}

/** 登录态与概览接口都走模拟；未命中的请求显式 404，避免"悄悄走真实后端"。 */
async function mockApi(page: Page, options: { failDashboard?: boolean } = {}): Promise<void> {
  await page.route('**/fd/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/fd/v1', '')

    if (path === '/auth/refresh') {
      await route.fulfill({ json: reply({ accessToken: 'ui-test-token', tokenType: 'Bearer', expiresIn: 900 }) })
      return
    }
    if (path === '/auth/me') {
      await route.fulfill({ json: reply(itUser) })
      return
    }
    if (path === '/auth/login') {
      await route.fulfill({
        json: reply({ accessToken: 'ui-test-token', tokenType: 'Bearer', expiresIn: 900, user: itUser }),
      })
      return
    }
    if (path === '/dashboard/tickets') {
      if (options.failDashboard) {
        await route.fulfill({
          status: 403,
          json: { code: 'ACCESS_DENIED', message: '', data: null },
        })
        return
      }
      const scope = url.searchParams.get('scope') ?? 'PENDING_QUEUE'
      await route.fulfill({ json: reply(dashboards[scope]) })
      return
    }
    await route.fulfill({ status: 404, json: { code: 'RESOURCE_NOT_FOUND', message: '', data: null } })
  })
}

async function openDashboard(page: Page): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('请输入登录名').fill('it')
  await page.getByPlaceholder('请输入密码').fill('ui-test-password')
  await page.getByRole('button', { name: '登录' }).click()
  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { level: 1, name: '数据概览' })).toBeVisible()
}

test('数据概览：总数与四组对齐，切换范围重新取数并保留分类选项', async ({ page }, testInfo) => {
  test.setTimeout(120_000)
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.setViewportSize({ width: 1440, height: 900 })
  await mockApi(page)
  await openDashboard(page)

  await expect(page.locator('.dashboard-total__value')).toHaveText('7')
  await expect(page.locator('.dashboard-group__title')).toHaveText(['状态', '优先级', '分类', '负责人'])
  // 未分配组没有名字：页面自己写"未分配"，而不是留白
  await expect(page.locator('.dashboard-group').nth(3)).toContainText('未分配')
  // 停用但仍挂着历史工单的分类出现在选项里
  await expect(page.locator('.dashboard-group').nth(2)).toContainText('已停用的旧分类')

  await expectNoHorizontalOverflow(page, '1440 下数据概览出现整页横向溢出')
  await page.screenshot({
    path: resolve(reviewDir, 'dashboard-desktop-1440.png'),
    fullPage: true,
  })
  testInfo.attach('dashboard-desktop-1440', { path: resolve(reviewDir, 'dashboard-desktop-1440.png') })

  // 选择分类后再查询：选项不收缩，仍能切回其它分类。
  // 点 `.el-select__wrapper` 而不是内部 input：Element Plus 的占位层会挡住 input 的点击。
  await page.locator('.admin-filter-field:has(#dashboard-category) .el-select__wrapper').click()
  await page.getByRole('option', { name: '办公设备' }).click()
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.locator('.dashboard-total__filter')).toContainText('分类 办公设备')

  // 切换范围：分类条件被清掉，数字换成新范围的总数。
  // 点 `.el-radio-button` 标签而不是原生 radio：EP 的原生输入是视觉隐藏的，不可点击。
  await page.locator('.ticket-scope-bar .el-radio-button', { hasText: '我负责的' }).click()
  await expect(page.locator('.dashboard-total__value')).toHaveText('3')
  await expect(page.locator('.dashboard-total__filter')).toHaveCount(0)
  await expect(page.locator('.dashboard-group').nth(3)).toContainText('演示 IT 支持人员')

  await page.locator('.ticket-scope-bar .el-radio-button', { hasText: '我参与的' }).click()
  await expect(page.locator('.dashboard-total__value')).toHaveText('12')
  await expect(page.locator('.dashboard-group').nth(3)).toContainText('未分配')

  expect(pageErrors).toEqual([])
})

test('数据概览：375 窄屏纵向排列且无整页横向溢出', async ({ page }, testInfo) => {
  test.setTimeout(120_000)
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.setViewportSize({ width: 375, height: 812 })
  await mockApi(page)
  await openDashboard(page)

  await expect(page.locator('.dashboard-total__value')).toHaveText('7')
  await expectNoHorizontalOverflow(page, '375 下数据概览出现整页横向溢出')
  await page.screenshot({
    path: resolve(reviewDir, 'dashboard-mobile-375.png'),
    fullPage: true,
  })
  testInfo.attach('dashboard-mobile-375', { path: resolve(reviewDir, 'dashboard-mobile-375.png') })

  expect(pageErrors).toEqual([])
})

test('数据概览：请求失败时给出可重试的错误态', async ({ page }) => {
  test.setTimeout(120_000)
  await page.setViewportSize({ width: 1440, height: 900 })
  await mockApi(page, { failDashboard: true })
  await openDashboard(page)

  const alert = page.getByRole('alert')
  await expect(alert).toContainText('数据概览没有加载成功')
  await expect(alert).toContainText('当前账号没有执行该操作的权限')
  // 筛选卡仍在：失败不该把整页变成空白
  await expect(page.getByRole('radio', { name: '待受理' })).toBeVisible()
})
