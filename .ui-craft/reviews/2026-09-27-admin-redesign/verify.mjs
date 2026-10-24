import { createRequire } from 'node:module'
import { writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
const require = createRequire(new URL('../../../frontend/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const out = fileURLToPath(new URL('./', import.meta.url))
const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const errors = []
const injectedErrors = []
let injectingFailure = false
const checks = []
page.on('pageerror', e => errors.push(e.message))
page.on('console', message => {
  if (message.type() === 'error') (injectingFailure ? injectedErrors : errors).push(message.text())
})
const base = 'http://localhost:5173'
const row = text => page.locator('tbody tr').filter({ hasText: text })
async function loaded() { await expect(page.locator('tbody tr').first()).toBeVisible() }
async function shot(name) { await page.screenshot({ path: out + name, fullPage: true }) }
async function query(text) {
  await page.getByPlaceholder('请输入登录名或显示名').fill(text)
  await page.getByRole('button', { name: '查询', exact: true }).click()
}
async function reset() { await page.getByRole('button', { name: '重置', exact: true }).click(); await loaded() }
await page.goto(base + '/login')
await page.getByPlaceholder('请输入登录名', { exact: true }).fill('admin')
await page.getByPlaceholder('请输入密码', { exact: true }).fill('123456')
await page.getByRole('button', { name: '登录', exact: true }).click()
await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
await page.goto(base + '/admin/users')
await loaded()
checks.push({ identity: page.url(), title: await page.title(), overlay: await page.locator('vite-error-overlay').count() })
for (const [width, height] of [[1920,1080],[1440,900],[375,812]]) {
  await page.setViewportSize({ width, height })
  await shot(width === 375 ? 'user-list-mobile-375.png' : `user-list-desktop-${width}.png`)
  const dimensions = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth, workspace: document.querySelector('.page__workspace').getBoundingClientRect().width, row: document.querySelector('tbody tr').getBoundingClientRect().height, input: document.querySelector('#user-keyword').getBoundingClientRect().height }))
  expect(dimensions.document).toBeLessThanOrEqual(width)
  checks.push({ width, ...dimensions })
}
await page.setViewportSize({ width:1440, height:900 })
await expect(page.locator('.admin-action-bar').getByRole('button', {name:'编辑',exact:true})).toBeDisabled()
await query('employee'); await expect(row('employee')).toHaveCount(1)
await reset()
await query('NO_MATCH_VISUAL_PROBE'); await expect(page.getByText('没有符合条件的用户',{exact:true})).toBeVisible()
await shot('user-list-state.png'); await reset()
if (process.env.CAPTURE_ONLY) {
  await page.getByRole('button',{name:'新增',exact:true}).click()
  await shot('user-list-create-dialog.png')
  await page.setViewportSize({width:375,height:812})
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(375)
  await shot('user-list-create-mobile.png')
  await page.setViewportSize({width:1440,height:900})
  await page.getByRole('dialog').getByRole('button',{name:'取消',exact:true}).click()
  const pending = []
  await page.route('**/fd/v1/users?*', route => pending.push(route))
  await page.getByRole('button',{name:'刷新列表'}).click()
  await expect(page.locator('[aria-busy="true"]')).toBeVisible()
  await shot('user-list-loading.png')
  for (const request of pending) await request.continue()
  await page.unroute('**/fd/v1/users?*')
  await loaded()
  injectingFailure = true
  await page.route('**/fd/v1/users?*', route => route.abort('failed'))
  await page.getByRole('button',{name:'刷新列表'}).click()
  await expect(page.getByRole('heading',{name:'列表没有加载成功'})).toBeVisible()
  await shot('user-list-error.png')
  await page.unroute('**/fd/v1/users?*')
  await page.getByRole('button',{name:'重新加载'}).click()
  await loaded()
  injectingFailure = false
  if (process.env.CHECK_PAGINATION) {
    await page.locator('.admin-pagination .el-select').click()
    await page.getByRole('option',{name:'10条/页'}).click()
    await expect(page.locator('tbody tr')).toHaveCount(10)
    await page.locator('.admin-pagination .btn-next').click()
    await expect(page.locator('.admin-pagination .el-pager .is-active')).toHaveText('2')
    await expect(page.locator('tbody tr')).toHaveCount(1)
    await page.locator('.admin-pagination .btn-prev').click()
    await expect(page.locator('tbody tr')).toHaveCount(10)
    await writeFile(out+'pagination.json', JSON.stringify({next:2,previous:1,rows:[10,1,10]}))
  }
  await writeFile(out+'capture-checks.json', JSON.stringify({checks,errors,injectedErrors},null,2))
  await browser.close()
  process.exit(0)
}
const name = 'visual_probe_20260927_' + Date.now()
await page.getByRole('button',{name:'新增',exact:true}).click()
await page.getByPlaceholder('请输入登录名',{exact:true}).fill(name)
await page.getByPlaceholder('请输入显示名称',{exact:true}).fill('视觉验收临时账号：长名称检查')
await page.getByPlaceholder('请输入初始密码').fill('VisualProbe#2026')
const created = page.waitForResponse(r => r.url().endsWith('/users') && r.request().method()==='POST')
await page.getByRole('button',{name:'创建',exact:true}).click()
expect((await created).status()).toBe(201)
await query(name); await expect(row(name)).toBeVisible(); await expect(row(name)).toContainText('无业务权限')
await row(name).getByRole('button',{name:'编辑资料'}).click()
await page.getByRole('dialog',{name:'修改资料',exact:true}).getByPlaceholder('请输入显示名称',{exact:true}).fill('视觉验收已编辑')
await page.getByRole('button',{name:'保存',exact:true}).click()
await expect(row(name)).toContainText('视觉验收已编辑')
for (const action of ['disable','enable']) {
  await row(name).locator('.el-switch').click()
  const response = page.waitForResponse(r => r.url().endsWith('/actions/'+action))
  await page.locator('.el-popconfirm__action button:visible').filter({hasText:'确定'}).click()
  expect((await response).status()).toBe(200)
  await expect(row(name).getByRole('switch')).toHaveAttribute('aria-checked', String(action==='enable'))
}
await row(name).getByRole('button',{name:'重置密码',exact:true}).click()
await page.getByPlaceholder('请输入新密码',{exact:true}).fill('VisualProbe#Reset2026')
await page.getByPlaceholder('请再次输入新密码').fill('VisualProbe#Reset2026')
const passwordResponse = page.waitForResponse(r => r.url().endsWith('/actions/reset-password'))
await page.getByRole('dialog').getByRole('button',{name:'重置',exact:true}).click()
expect((await passwordResponse).status()).toBe(200)
await row(name).getByRole('button',{name:'角色授权'}).click()
await expect(page).toHaveURL(/user-roles\?userId=/)
checks.push({ realInteractions:'查询、重置、新增零角色、编辑、停用、启用、重置密码、授权入口', temporaryUser:name })
await page.goto(base+'/admin/users'); await loaded()
await page.locator('.admin-pagination .el-select').click()
await page.getByRole('option',{name:'10条/页'}).click()
await expect(page.locator('.admin-pagination')).toContainText('10条/页')
checks.push({ pagination:'真实 pageSize=10 请求，少量数据时下一页禁用' })
await page.route('**/fd/v1/users?*', async route => { await route.abort('failed') })
await page.getByRole('button',{name:'刷新列表'}).click()
await expect(page.getByRole('heading',{name:'列表没有加载成功'})).toBeVisible()
await shot('user-list-error.png')
await page.unroute('**/fd/v1/users?*')
await page.getByRole('button',{name:'重新加载'}).click(); await loaded()
checks.push({ error:'网络故障注入后错误态显示，重试恢复真实数据' })
await writeFile(out+'checks.json', JSON.stringify({ checks, errors },null,2))
await browser.close()
