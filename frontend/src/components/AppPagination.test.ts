import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import AppPagination from './AppPagination.vue'

/**
 * 分页条的契约有两条最容易在改动中被弄坏：
 * ① 显示条件（没数据、还在加载、没选主体时都不该出现）；
 * ② 换每页条数必须回到第 1 页——不回第 1 页会停在一个按新页大小算并不存在的页上。
 * 这两条都是"看起来正常、实际会出错"的那类，所以用用例钉住。
 */
function mountPagination(props: Record<string, unknown> = {}) {
  return mount(AppPagination, {
    props: {
      page: 1,
      pageSize: 20,
      totalElements: 40,
      phase: 'ready',
      ...props,
    },
  })
}

describe('AppPagination', () => {
  it('就绪且有条数时渲染分页', () => {
    expect(mountPagination().find('.admin-pagination').exists()).toBe(true)
  })

  it.each([
    ['还在加载', { phase: 'loading' }],
    ['加载失败', { phase: 'error' }],
    ['一条数据都没有', { totalElements: 0 }],
    ['授权页还没选主体', { hasSubject: false }],
  ])('%s 时不渲染分页', (_label, props) => {
    expect(mountPagination(props).find('.admin-pagination').exists()).toBe(false)
  })

  it('翻页只上报页码，不动每页条数', () => {
    const wrapper = mountPagination()

    wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('current-change', 3)

    expect(wrapper.emitted('change')).toEqual([[{ page: 3, pageSize: 20 }]])
  })

  it('换每页条数时回到第 1 页', () => {
    const wrapper = mountPagination({ page: 4 })

    wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('size-change', 50)

    expect(wrapper.emitted('change')).toEqual([[{ page: 1, pageSize: 50 }]])
  })

  it('重复上报同一个页码不会重复取数', () => {
    const wrapper = mountPagination({ page: 2 })

    wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('current-change', 2)

    expect(wrapper.emitted('change')).toBeUndefined()
  })

  it('窄屏用收敛版 layout，宽屏用完整 layout', async () => {
    // jsdom 没有 matchMedia：useCompactPagination 会退回宽屏，先钉住宽屏那一支
    const wideWrapper = mountPagination()
    const wideLayout = wideWrapper.findComponent({ name: 'ElPagination' }).props('layout') as string

    expect(wideLayout).toContain('sizes')
    expect(wideLayout).toContain('jumper')

    // 窄屏：matchMedia 必须在挂载之前就位（useCompactPagination 在 setup 阶段取它一次），
    // 而且它的同步发生在 onMounted，所以挂载后要等一次刷新再读 layout
    vi.stubGlobal('matchMedia', () => ({
      matches: true,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    }))

    try {
      const compactWrapper = mountPagination()
      await nextTick()
      const compactLayout = compactWrapper
        .findComponent({ name: 'ElPagination' })
        .props('layout') as string

      expect(compactLayout).toBe('total, prev, pager, next')
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
