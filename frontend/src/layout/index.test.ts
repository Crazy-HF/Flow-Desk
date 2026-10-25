import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'

import AppLayout from './index.vue'

async function mountLayout(path = '/') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<p>首页主体</p>' }, meta: { title: '首页' } },
      { path: '/tickets', name: 'tickets', component: { template: '<p>工单主体</p>' }, meta: { title: '工单' } },
    ],
  })

  await router.push(path)
  await router.isReady()

  const wrapper = mount(AppLayout, {
    global: { plugins: [createPinia(), router] },
  })

  return { wrapper, router }
}

describe('AppLayout', () => {
  it('品牌位移到侧栏顶部，顶栏只剩开关、面包屑与账户区', async () => {
    const { wrapper } = await mountLayout()

    expect(wrapper.find('.app-shell__header').exists()).toBe(true)
    // 品牌位属于侧栏：顶栏不再重复一次产品名
    expect(wrapper.get('.app-sidebar__brand-name').text()).toBe('FlowDesk')
    expect(wrapper.get('.app-sidebar__brand').attributes('href')).toBe('/')
    expect(wrapper.find('.app-header__search').exists()).toBe(true)
    expect(wrapper.find('.app-shell__tabs').exists()).toBe(true)
  })

  it('侧栏与面包屑各占一层，主体内容挂在 main 上供跳过链接定位', async () => {
    const { wrapper } = await mountLayout('/tickets')

    expect(wrapper.find('nav[aria-label="主导航"]').exists()).toBe(true)
    expect(wrapper.get('nav[aria-label="面包屑"]').text()).toContain('工单')
    expect(wrapper.get('#main-content').text()).toContain('工单主体')
    expect(wrapper.get('.app-shell__skip-link').attributes('href')).toBe('#main-content')
  })

  it('首页不重复出现"首页"面包屑，只标出当前位置', async () => {
    const { wrapper } = await mountLayout()

    // 分隔符由 Element Plus 渲染在每一级内部，只取文字层才能确认没有重复的"首页"
    expect(wrapper.get('nav[aria-label="面包屑"] .el-breadcrumb__inner').text()).toBe('首页')
    expect(wrapper.findAll('.el-breadcrumb__item')).toHaveLength(1)
  })
})
