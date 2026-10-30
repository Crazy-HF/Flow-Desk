import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it } from 'vitest'

import { usePageTabs } from './usePageTabs'

/**
 * 页面标签带的状态机。
 *
 * <p>这些用例锁的是"标签只来自真实访问过的路由"：刷新、深链、403、登出都不能留下
 * 错误标签；关闭当前标签要落到左邻而不是把视图留在空处。</p>
 */
const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'home', component: { template: '<p />' }, meta: { title: '首页' } },
    { path: '/tickets', name: 'tickets', component: { template: '<p />' }, meta: { title: '工单' } },
    {
      path: '/tickets/:ticketNo',
      name: 'ticket-detail',
      component: { template: '<p />' },
      meta: { title: '工单详情' },
    },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: { template: '<p />' },
      meta: { title: '用户管理' },
    },
    { path: '/403', name: 'forbidden', component: { template: '<p />' }, meta: { title: '无访问权限' } },
    { path: '/login', name: 'login', component: { template: '<p />' }, meta: { public: true, title: '登录' } },
  ],
})

async function visit(path: string) {
  await router.push(path)
  await router.isReady()
  return router.currentRoute.value
}

describe('usePageTabs', () => {
  const tabs = usePageTabs()

  beforeEach(() => {
    tabs.reset()
  })

  it('初始只有固定的首页标签', () => {
    expect(tabs.tabs.value.map((tab) => tab.title)).toEqual(['首页'])
    expect(tabs.tabs.value[0]?.affix).toBe(true)
  })

  it('深链打开时得到「首页 + 当前页」，而不是只显示当前页', async () => {
    tabs.sync(await visit('/admin/users'))

    expect(tabs.tabs.value.map((tab) => tab.title)).toEqual(['首页', '用户管理'])
  })

  it('同一路由重复访问只更新标题，不重复插入', async () => {
    tabs.sync(await visit('/admin/users'))
    tabs.sync(await visit('/admin/users'))

    expect(tabs.tabs.value).toHaveLength(2)
  })

  it('错误页与登录页不进入标签带', async () => {
    tabs.sync(await visit('/403'))
    tabs.sync(await visit('/login'))

    expect(tabs.tabs.value.map((tab) => tab.title)).toEqual(['首页'])
  })

  it('工单详情按路径各自成标签，查询串不拆分标签', async () => {
    tabs.sync(await visit('/tickets/FD-1'))
    tabs.sync(await visit('/tickets/FD-2'))
    tabs.sync(await visit('/admin/users?userId=3'))

    expect(tabs.tabs.value.map((tab) => tab.path)).toEqual([
      '/',
      '/tickets/FD-1',
      '/tickets/FD-2',
      '/admin/users',
    ])
  })

  it('关闭非当前标签只移除它，返回 null 表示不需要跳转', async () => {
    tabs.sync(await visit('/admin/users'))
    tabs.sync(await visit('/tickets'))

    expect(tabs.close('/admin/users', '/tickets')).toBeNull()
    expect(tabs.tabs.value.map((tab) => tab.title)).toEqual(['首页', '工单'])
  })

  it('关闭当前标签返回左邻路径；左邻不存在时回首页', async () => {
    tabs.sync(await visit('/admin/users'))
    tabs.sync(await visit('/tickets'))

    expect(tabs.close('/tickets', '/tickets')).toBe('/admin/users')
    expect(tabs.close('/admin/users', '/admin/users')).toBe('/')
  })

  it('固定标签不可关闭', () => {
    expect(tabs.close('/', '/')).toBeNull()
    expect(tabs.tabs.value).toHaveLength(1)
  })

  it('关闭其他标签保留首页与指定标签', async () => {
    tabs.sync(await visit('/admin/users'))
    tabs.sync(await visit('/tickets'))

    tabs.closeOthers('/tickets')

    expect(tabs.tabs.value.map((tab) => tab.title)).toEqual(['首页', '工单'])
  })

  it('关闭全部标签回到首页', async () => {
    tabs.sync(await visit('/admin/users'))
    tabs.sync(await visit('/tickets'))

    expect(tabs.closeAll()).toBe('/')
    expect(tabs.tabs.value.map((tab) => tab.title)).toEqual(['首页'])
  })

  it('超过上限时淘汰最早的非固定标签，固定标签与最新页保留', async () => {
    for (let index = 1; index <= 13; index += 1) {
      tabs.sync(await visit(`/tickets/FD-${index}`))
    }

    const paths = tabs.tabs.value.map((tab) => tab.path)
    expect(tabs.tabs.value.length).toBeLessThanOrEqual(12)
    expect(paths[0]).toBe('/')
    expect(paths).toContain('/tickets/FD-13')
    expect(paths).not.toContain('/tickets/FD-1')
  })

  it('登出重置后不保留上一个会话的标签', async () => {
    tabs.sync(await visit('/admin/users'))

    tabs.reset()

    expect(tabs.tabs.value.map((tab) => tab.title)).toEqual(['首页'])
  })
})
