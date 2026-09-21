import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import { describe, expect, it } from 'vitest'

import AppBreadcrumb from './AppBreadcrumb.vue'

const routes = [
  { path: '/', name: 'home', component: { template: '<p />' }, meta: { title: '首页' } },
  { path: '/tickets', name: 'tickets', component: { template: '<p />' }, meta: { title: '工单' } },
  {
    path: '/tickets/:ticketNo',
    name: 'ticket-detail',
    component: { template: '<p />' },
    meta: { title: '工单详情' },
  },
  { path: '/admin/users', name: 'admin-users', component: { template: '<p />' }, meta: { title: '用户管理' } },
  {
    path: '/admin/users/:userId',
    name: 'admin-user-detail',
    component: { template: '<p />' },
    meta: { title: '用户详情' },
  },
  {
    path: '/admin/categories',
    name: 'admin-categories',
    component: { template: '<p />' },
    meta: { title: '分类管理' },
  },
  { path: '/403', name: 'forbidden', component: { template: '<p />' }, meta: { title: '无访问权限' } },
]

/** 面包屑的层级来自 `constants/authorization.ts`，这里用真实路由名驱动它。 */
async function mountBreadcrumb(path: string): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({ history: createMemoryHistory(), routes })

  await router.push(path)
  await router.isReady()

  const wrapper = mount(AppBreadcrumb, { global: { plugins: [router] } })

  return { wrapper, router }
}

function crumbLabels(wrapper: VueWrapper): string[] {
  return wrapper.findAll('.el-breadcrumb__item').map((item) => item.text())
}

function linkLabels(wrapper: VueWrapper): string[] {
  return wrapper.findAll('.el-breadcrumb__inner.is-link').map((item) => item.text())
}

describe('AppBreadcrumb', () => {
  it('首页只有一级，且不是可点击的块', async () => {
    const { wrapper } = await mountBreadcrumb('/')

    expect(crumbLabels(wrapper)).toEqual(['首页'])
    expect(linkLabels(wrapper)).toEqual([])
  })

  it('栏目下的页面显示「首页 / 栏目 / 页面」，栏目名与侧栏同一份声明', async () => {
    const { wrapper, router } = await mountBreadcrumb('/tickets')

    expect(crumbLabels(wrapper)).toEqual(['首页', '业务工作', '工单'])
    expect(wrapper.get('.app-breadcrumb__group').text()).toBe('业务工作')
    // 栏目没有目标页，所以只有"首页"是可点的
    expect(linkLabels(wrapper)).toEqual(['首页'])

    await wrapper.get('.el-breadcrumb__inner.is-link').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('home')
  })

  it('同一栏目内切换页面只换末级，栏目保持不变', async () => {
    const { wrapper, router } = await mountBreadcrumb('/admin/users')

    expect(crumbLabels(wrapper)).toEqual(['首页', '系统管理', '用户管理'])

    await router.push('/admin/categories')
    await flushPromises()

    expect(crumbLabels(wrapper)).toEqual(['首页', '系统管理', '分类管理'])
    expect(wrapper.findAll('.app-breadcrumb__group')).toHaveLength(1)
  })

  it('页面下的子路由补出可点回列表页的上一级', async () => {
    const { wrapper } = await mountBreadcrumb('/admin/users/7')

    expect(crumbLabels(wrapper)).toEqual(['首页', '系统管理', '用户管理', '用户详情'])
    expect(linkLabels(wrapper)).toEqual(['首页', '用户管理'])
  })

  it('不在导航声明里的页面退回「首页 / 当前页」', async () => {
    const { wrapper } = await mountBreadcrumb('/403')

    expect(crumbLabels(wrapper)).toEqual(['首页', '无访问权限'])
    expect(wrapper.find('.app-breadcrumb__group').exists()).toBe(false)
  })
})
