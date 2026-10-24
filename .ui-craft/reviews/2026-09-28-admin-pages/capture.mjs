/**
 * 管理端四页截图与自查探针（本次改版的证据采集脚本，非产品代码）。
 *
 * 依赖：仓库根 .env 指向的真实栈（后端 8081 + MySQL/Redis）与 `vite preview`（4173）。
 * 用法：node .ui-craft/reviews/2026-09-28-admin-pages/capture.mjs
 *
 * 只读取数据：不点任何提交按钮，页面点击范围限定在"打开弹窗 / 打开下拉 / 选中对象"。
 */
import { mkdir, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// `@playwright/test` 只装在 frontend/node_modules 下，而 ESM 的裸标识符是按"导入文件的位置"
// 解析的（不是 cwd），所以这里显式以 frontend 为基准解析，脚本才能留在 review 目录里。
const here = dirname(fileURLToPath(import.meta.url))
const frontendRequire = createRequire(resolve(here, '../../../frontend/package.json'))
const { chromium } = frontendRequire('@playwright/test')

const baseURL = 'http://127.0.0.1:4173'
const admin = { username: 'admin', password: '123456' }

/** 四页的地址与"数据已就绪"判据。 */
const pages = [
  { key: 'roles', path: '/admin/roles', ready: 'tbody tr' },
  { key: 'permissions', path: '/admin/permissions', ready: 'tbody tr' },
  { key: 'user-roles', path: '/admin/user-roles', ready: '.empty-state' },
  { key: 'role-permissions', path: '/admin/role-permissions', ready: '.empty-state' },
]

const checks = []

async function signIn(page) {
  await page.goto(`${baseURL}/login`)
  await page.getByPlaceholder('请输入登录名').fill(admin.username)
  await page.getByPlaceholder('请输入密码').fill(admin.password)
  await page.getByRole('button', { name: '登录' }).click()
  await page.getByRole('heading', { name: '欢迎回来' }).waitFor()
}

/** 文档级横向溢出量与主要的盒模型读数。 */
async function measure(page, label) {
  const data = await page.evaluate(() => {
    const doc = document.documentElement
    const box = (selector) => {
      const el = document.querySelector(selector)
      if (!el) return null
      const rect = el.getBoundingClientRect()
      return {
        width: Math.round(rect.width),
        left: Math.round(rect.left),
        right: Math.round(rect.right),
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
      }
    }
    return {
      overflow: doc.scrollWidth - doc.clientWidth,
      viewport: doc.clientWidth,
      filterCard: box('.admin-filter-card'),
      dataPanel: box('[class*="admin-data"], .user-list-data'),
      table: box('.admin-table'),
      pagination: box('.admin-pagination'),
      grantList: box('.grant-list'),
      firstRow: box('tbody tr, .grant-row'),
    }
  })
  checks.push({ label, ...data })
  return data
}

/** 页面是否残留 Element Plus 默认蓝（项目主色是自定义蓝，不是 #409eff）。 */
async function hasDefaultBlue(page) {
  return page.evaluate(() => {
    const offenders = []
    for (const el of document.querySelectorAll('.el-button, .el-switch, .el-pagination')) {
      const bg = getComputedStyle(el).backgroundColor
      if (bg === 'rgb(64, 158, 255)') offenders.push(el.className)
    }
    return offenders
  })
}

/**
 * 弹窗有淡入动画：不等待就截图会拍到半透明的一帧，看上去像"弹窗没渲染完"。
 *
 * 页面上所有 `el-dialog` 都常驻 DOM（关闭的只是隐藏），所以这里限定**可见**的那一个，
 * 不能只写 `.el-dialog` —— 否则会同时匹配到页面上另外两个隐藏弹窗。
 */
async function openDialog(page, buttonName) {
  await page.getByRole('button', { name: buttonName, exact: true }).click()
  const dialog = page.locator('.el-overlay-dialog:visible .el-dialog')
  await dialog.waitFor({ state: 'visible' })
  await page.waitForTimeout(600)
  await dialog.screenshot({ path: resolve(here, `dialog-${buttonName}.png`) })
  return dialog
}

const browser = await chromium.launch()
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
const jsErrors = []
page.on('pageerror', (error) => jsErrors.push(String(error)))
page.on('console', (message) => {
  if (message.type() === 'error') jsErrors.push(`console: ${message.text()}`)
})

await signIn(page)
await mkdir(here, { recursive: true })

// ---------- 1440 桌面：四页正常态 ----------
for (const entry of pages) {
  await page.goto(`${baseURL}${entry.path}`)
  await page.locator(entry.ready).first().waitFor()
  await page.screenshot({ path: resolve(here, `${entry.key}-desktop-1440.png`), fullPage: true })
  const m = await measure(page, `${entry.key} @1440`)
  checks.push({ label: `${entry.key} default-blue`, value: await hasDefaultBlue(page) })
  console.log(`${entry.key} @1440 overflow=${m.overflow} filter=${m.filterCard?.width} data=${m.dataPanel?.width}`)
}

// ---------- 已选主体的授权页：关系行 + 撤销入口 ----------
// 演示库真实 id：employee=1、it=2、admin=3；角色 EMPLOYEE=1、IT_SUPPORT=2、SYSTEM_ADMIN=3。
await page.goto(`${baseURL}/admin/user-roles?userId=1`)
await page.locator('.grant-row').first().waitFor()
await page.screenshot({ path: resolve(here, 'user-roles-granted-1440.png'), fullPage: true })
await measure(page, 'user-roles-granted @1440')

await page.goto(`${baseURL}/admin/role-permissions?roleId=3`)
await page.locator('.grant-row').first().waitFor()
await page.screenshot({ path: resolve(here, 'role-permissions-granted-1440.png'), fullPage: true })
await measure(page, 'role-permissions-granted @1440')

// ---------- 异常态：加载失败 + 原位重试 ----------
await page.route('**/fd/v1/admin/roles**', (route) => route.abort('failed'))
await page.goto(`${baseURL}/admin/roles`)
await page.locator('.admin-panel__error').waitFor()
await page.screenshot({ path: resolve(here, 'roles-error.png'), fullPage: true })
await measure(page, 'roles-error @1440')
await page.unroute('**/fd/v1/admin/roles**')

// 权限页错误态同样截一张：两页的错误容器与重试按钮需要一起自查
await page.route('**/fd/v1/admin/permissions**', (route) => route.abort('failed'))
await page.goto(`${baseURL}/admin/permissions`)
await page.locator('.admin-panel__error').waitFor()
await page.screenshot({ path: resolve(here, 'permissions-error.png'), fullPage: true })
await page.unroute('**/fd/v1/admin/permissions**')

// ---------- 弹窗态：等淡入结束再截，否则拍到的是半透明的一帧 ----------
await page.goto(`${baseURL}/admin/roles`)
await page.locator('tbody tr').first().waitFor()
await openDialog(page, '新增')
await page.screenshot({ path: resolve(here, 'roles-create-dialog.png'), fullPage: false })
await measure(page, 'roles-dialog @1440')
await page.keyboard.press('Escape')

await page.goto(`${baseURL}/admin/permissions`)
await page.locator('tbody tr').first().waitFor()
await openDialog(page, '新增')
await page.screenshot({ path: resolve(here, 'permissions-create-dialog.png'), fullPage: false })
await page.keyboard.press('Escape')

// ---------- 受保护对象的禁用与下拉展开 ----------
await page.goto(`${baseURL}/admin/roles`)
await page.locator('tbody tr').first().waitFor()
const protectedRow = page.locator('tbody tr').filter({ hasText: 'SYSTEM_ADMIN' }).first()
await protectedRow.getByRole('button', { name: '删除角色' }).hover()
await page.screenshot({ path: resolve(here, 'roles-protected-row.png'), fullPage: true })
checks.push({
  label: 'protected-delete-disabled',
  value: await protectedRow.getByRole('button', { name: '删除角色' }).isDisabled(),
})

// ---------- 375 窄屏：四页 ----------
const narrow = await browser.newContext({ viewport: { width: 375, height: 812 } })
const narrowPage = await narrow.newPage()
narrowPage.on('pageerror', (error) => jsErrors.push(`narrow: ${error}`))
await signIn(narrowPage)
for (const entry of pages) {
  await narrowPage.goto(`${baseURL}${entry.path}`)
  await narrowPage.locator(entry.ready).first().waitFor()
  await narrowPage.screenshot({ path: resolve(here, `${entry.key}-mobile-375.png`), fullPage: true })
  const m = await measure(narrowPage, `${entry.key} @375`)
  console.log(`${entry.key} @375 overflow=${m.overflow}`)
}

await narrowPage.goto(`${baseURL}/admin/user-roles?userId=1`)
await narrowPage.locator('.grant-row').first().waitFor()
await narrowPage.screenshot({ path: resolve(here, 'user-roles-granted-mobile-375.png'), fullPage: true })
await measure(narrowPage, 'user-roles-granted @375')

// 窄屏弹窗是否超出视口
await narrowPage.goto(`${baseURL}/admin/permissions`)
await narrowPage.locator('tbody tr').first().waitFor()
await openDialog(narrowPage, '新增')
await narrowPage.screenshot({ path: resolve(here, 'permissions-dialog-mobile-375.png'), fullPage: false })
const dialogBox = await narrowPage.locator('.el-overlay-dialog:visible .el-dialog').boundingBox()
checks.push({ label: 'dialog @375', value: dialogBox, viewportWidth: 375, viewportHeight: 812 })

await browser.close()

console.log('\n=== overflow 汇总 ===')
for (const check of checks.filter((item) => typeof item.overflow === 'number')) {
  console.log(`${check.label}: overflow=${check.overflow}, viewport=${check.viewport}`)
}
console.log('\n=== 默认蓝残留 ===')
for (const check of checks.filter((item) => item.label.endsWith('default-blue'))) {
  console.log(`${check.label}: ${JSON.stringify(check.value)}`)
}
console.log(`\nJS 错误：${jsErrors.length === 0 ? '无' : JSON.stringify(jsErrors, null, 2)}`)
console.log(`\n窄屏弹窗框：${JSON.stringify(checks.at(-1))}`)

await writeFile(resolve(here, 'checks.json'), JSON.stringify({ checks, jsErrors }, null, 2), 'utf8')
