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
  it('顶栏由品牌位与账户区两段拼成，品牌链接指向首页', async () => {
    const { wrapper } = await mountLayout()

    expect(wrapper.find('.app-shell__header').exists()).toBe(true)
    expect(wrapper.get('.app-shell__brand-link').text()).toBe('FlowDesk')
    expect(wrapper.get('.app-shell__brand-link').attributes('href')).toBe('/')
    expect(wrapper.find('.app-header__search').exists()).toBe(true)
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

    expect(wrapper.get('nav[aria-label="面包屑"]').text()).toBe('首页')
    expect(wrapper.findAll('.el-breadcrumb__item')).toHaveLength(1)
  })
})
