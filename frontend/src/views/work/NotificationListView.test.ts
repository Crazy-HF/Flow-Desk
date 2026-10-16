import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from '@/api/notifications'
import type { NotificationItem } from '@/api/notifications'
import NotificationListView from './NotificationListView.vue'

vi.mock('@/api/notifications', () => ({
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn(),
}))

const unreadItem: NotificationItem = {
  id: 12,
  category: 'SUPPLEMENT_REQUESTED',
  title: '请补充设备信息',
  body: '请提供设备编号与复现步骤',
  ticketId: 5,
  ticketNo: 'FD-20261014-001',
  createdAt: '2026-10-14T01:00:00Z',
}

const readItem: NotificationItem = {
  id: 11,
  category: 'RESOLUTION_SUBMITTED',
  title: '问题已提交解决结果，等待确认',
  readAt: '2026-10-13T03:00:00Z',
  createdAt: '2026-10-13T02:00:00Z',
}

function pageOf(items: NotificationItem[], total = items.length) {
  return { items, page: 1, size: 20, totalElements: total, totalPages: total === 0 ? 0 : 1 }
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((button) => button.text().trim() === text)
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/notifications', name: 'notifications', component: { template: '<div />' } },
      { path: '/tickets/:ticketNo', name: 'ticket-detail', component: { template: '<div />' } },
    ],
  })
  await router.push('/notifications')
  await router.isReady()

  const wrapper = mount(NotificationListView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('NotificationListView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listNotifications).mockResolvedValue(pageOf([unreadItem, readItem]))
    vi.mocked(markNotificationRead).mockResolvedValue(undefined)
    vi.mocked(markAllNotificationsRead).mockResolvedValue(undefined)
  })

  it('列出通知：未读在前、类型与正文按契约渲染', async () => {
    const { wrapper } = await mountPage()

    expect(listNotifications).toHaveBeenCalledWith({ pageNo: 1, pageSize: 20 })
    expect(wrapper.text()).toContain('请补充设备信息')
    expect(wrapper.text()).toContain('请提供设备编号与复现步骤')
    expect(wrapper.text()).toContain('补充请求')
    expect(wrapper.text()).toContain('已提交解决结果')
    expect(wrapper.text()).toContain('未读')
    expect(wrapper.text()).toContain('已读')
    expect(wrapper.text()).toContain('FD-20261014-001')
  })

  it('打开「只看未读」后请求带着 unreadOnly=true', async () => {
    const { wrapper } = await mountPage()

    // 默认关闭：不发 unreadOnly
    expect(listNotifications).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 20 })

    const toggle = wrapper.findComponent({ name: 'ElSwitch' })
    toggle.vm.$emit('update:modelValue', true)
    toggle.vm.$emit('change', true)
    await flushPromises()

    expect(listNotifications).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNo: 1, pageSize: 20, unreadOnly: true }),
    )
  })

  it('单条「标记已读」调用接口并原位刷新', async () => {
    const { wrapper } = await mountPage()

    await wrapper.get('button[aria-label="标记已读"]').trigger('click')
    await flushPromises()

    expect(markNotificationRead).toHaveBeenCalledWith(12)
    // 标记后回到列表重取当前页
    expect(listNotifications).toHaveBeenCalledTimes(2)
  })

  it('「全部已读」调用接口并回到第 1 页重取', async () => {
    const { wrapper } = await mountPage()

    await buttonByText(wrapper, '全部已读')?.trigger('click')
    await flushPromises()

    expect(markAllNotificationsRead).toHaveBeenCalledTimes(1)
    expect(listNotifications).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 20 })
  })

  it('点击一条通知先标记已读、再深链到工单详情', async () => {
    const { wrapper, router } = await mountPage()

    await wrapper.findAll('tbody tr')[0]?.trigger('click')
    await flushPromises()

    expect(markNotificationRead).toHaveBeenCalledWith(12)
    expect(router.currentRoute.value.name).toBe('ticket-detail')
    expect(router.currentRoute.value.params.ticketNo).toBe('FD-20261014-001')
  })

  it('只看未读且没有未读时给出专门的空态文案', async () => {
    vi.mocked(listNotifications).mockResolvedValue(pageOf([]))
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('暂时没有通知')

    const toggle = wrapper.findComponent({ name: 'ElSwitch' })
    toggle.vm.$emit('update:modelValue', true)
    toggle.vm.$emit('change', true)
    await flushPromises()

    expect(wrapper.text()).toContain('没有未读通知')
  })

  it('加载失败进入错误态、走稳定错误码文案并可原位重试', async () => {
    vi.mocked(listNotifications)
      .mockRejectedValueOnce({ response: { data: { code: 'ACCESS_DENIED' } } })
      .mockResolvedValueOnce(pageOf([unreadItem]))
    const { wrapper } = await mountPage()

    expect(wrapper.get('[role="alert"]').text()).toContain('当前账号没有执行该操作的权限')

    await buttonByText(wrapper, '重新加载')?.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('请补充设备信息')
  })
})
