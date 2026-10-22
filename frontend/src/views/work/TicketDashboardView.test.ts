import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { AuthUser } from '@/api/auth'
import { fetchTicketDashboard } from '@/api/dashboard'
import type { TicketDashboard } from '@/api/dashboard'
import { useAuthStore } from '@/stores/auth'
import TicketDashboardView from './TicketDashboardView.vue'

vi.mock('@/api/dashboard', () => ({
  fetchTicketDashboard: vi.fn(),
}))

const itSupport: AuthUser = {
  id: 2,
  username: 'demo.it',
  displayName: '演示 IT 支持人员',
  roles: ['IT_SUPPORT'],
  permissions: ['DASHBOARD_VIEW', 'TICKET_VIEW_QUEUE', 'TICKET_VIEW_PARTICIPATED'],
}

/** 只有队列权限：用来验证"界面只列出确实具备的范围"。 */
const queueOnly: AuthUser = {
  ...itSupport,
  permissions: ['DASHBOARD_VIEW', 'TICKET_VIEW_QUEUE'],
}

const dashboard: TicketDashboard = {
  scope: 'PENDING_QUEUE',
  total: 5,
  byStatus: [
    { status: 'PENDING', count: 3 },
    { status: 'PROCESSING', count: 2 },
  ],
  byPriority: [
    { priority: 'HIGH', count: 2 },
    { priority: 'LOW', count: 3 },
  ],
  byCategory: [
    { category: { id: 7, name: '办公设备' }, count: 4 },
    { category: { id: 3, name: '网络' }, count: 1 },
  ],
  byAssignee: [{ count: 5 }],
}

const emptyDashboard: TicketDashboard = {
  scope: 'PENDING_QUEUE',
  total: 0,
  byStatus: [],
  byPriority: [],
  byCategory: [],
  byAssignee: [],
}

async function mountPage(): Promise<VueWrapper> {
  const wrapper = mount(TicketDashboardView)
  await flushPromises()
  return wrapper
}

function groupTitles(wrapper: VueWrapper): string[] {
  return wrapper.findAll('.dashboard-group__title').map((item) => item.text())
}

function groupRows(wrapper: VueWrapper, index: number): string[] {
  const group = wrapper.findAll('.dashboard-group')[index]
  return group ? group.findAll('.dashboard-group__row').map((row) => row.text()) : []
}

function buttonByText(wrapper: VueWrapper, text: string) {
  const matched = wrapper.findAll('button').find((button) => button.text() === text)
  if (!matched) {
    throw new Error(`找不到按钮：${text}`)
  }
  return matched
}

describe('TicketDashboardView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(fetchTicketDashboard).mockResolvedValue(dashboard)
  })

  it('默认停在待受理，只请求一次并渲染总数与四组', async () => {
    useAuthStore().user = itSupport
    const wrapper = await mountPage()

    expect(fetchTicketDashboard).toHaveBeenCalledTimes(1)
    expect(fetchTicketDashboard).toHaveBeenCalledWith({ scope: 'PENDING_QUEUE' })
    expect(wrapper.find('.dashboard-total__value').text()).toBe('5')
    expect(groupTitles(wrapper)).toEqual(['状态', '优先级', '分类', '负责人'])
    expect(groupRows(wrapper, 0)).toEqual(['待受理3', '处理中2'])
    expect(groupRows(wrapper, 2)).toEqual(['办公设备4', '网络1'])
    // 未分配组没有名字，界面自己给出"未分配"，而不是显示空白
    expect(groupRows(wrapper, 3)).toEqual(['未分配5'])
  })

  it('只列出当前账号具备的范围；没有队列权限时默认第一个可用范围', async () => {
    useAuthStore().user = queueOnly
    const wrapper = await mountPage()

    expect(wrapper.findAll('.ticket-scope-bar .el-radio-button').map((item) => item.text())).toEqual([
      '待受理',
    ])
    expect(fetchTicketDashboard).toHaveBeenCalledWith({ scope: 'PENDING_QUEUE' })
  })

  it('有权限码但没有 IT 身份时不发请求，并解释原因', async () => {
    useAuthStore().user = { ...itSupport, roles: ['SYSTEM_ADMIN'] }
    const wrapper = await mountPage()

    expect(fetchTicketDashboard).not.toHaveBeenCalled()
    expect(wrapper.find('.ticket-state').text()).toContain('只对 IT 支持人员开放')
    expect(wrapper.find('.dashboard-summary').exists()).toBe(false)
  })

  it('没有任何范围权限时不发请求，也不渲染统计区', async () => {
    useAuthStore().user = { ...itSupport, permissions: ['DASHBOARD_VIEW'] }
    const wrapper = await mountPage()

    expect(fetchTicketDashboard).not.toHaveBeenCalled()
    expect(wrapper.find('.ticket-state').text()).toContain('没有可统计的工单范围')
  })

  it('零结果显示 0 与明确空态，而不是四组空白', async () => {
    vi.mocked(fetchTicketDashboard).mockResolvedValue(emptyDashboard)
    useAuthStore().user = itSupport
    const wrapper = await mountPage()

    expect(wrapper.find('.dashboard-total__value').text()).toBe('0')
    expect(wrapper.find('.dashboard-groups').exists()).toBe(false)
    expect(wrapper.find('.empty-state__title').text()).toBe('待受理队列是空的')
  })

  it('请求失败时保留筛选卡并给出可重试的错误态', async () => {
    vi.mocked(fetchTicketDashboard).mockRejectedValueOnce('ACCESS_DENIED')
    useAuthStore().user = itSupport
    const wrapper = await mountPage()

    expect(wrapper.find('.admin-filter-card').exists()).toBe(true)
    expect(wrapper.find('[role="alert"]').text()).toContain('当前账号没有执行该操作的权限')

    vi.mocked(fetchTicketDashboard).mockResolvedValue(dashboard)
    await buttonByText(wrapper, '重新加载').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('.dashboard-total__value').text()).toBe('5')
  })

  it('切换范围会清掉分类筛选并重新取数', async () => {
    useAuthStore().user = itSupport
    const wrapper = await mountPage()

    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 7)
    await flushPromises()
    expect(wrapper.find('.dashboard-total__filter').text()).toContain('办公设备')

    wrapper.findComponent({ name: 'ElRadioGroup' }).vm.$emit('update:modelValue', 'ASSIGNED_TO_ME')
    await flushPromises()

    expect(fetchTicketDashboard).toHaveBeenLastCalledWith({ scope: 'ASSIGNED_TO_ME' })
    expect(wrapper.find('.dashboard-total__filter').exists()).toBe(false)
  })

  it('修改创建时间同样清掉分类，避免旧分类让新窗口看起来没有数据', async () => {
    useAuthStore().user = itSupport
    const wrapper = await mountPage()

    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 7)
    await flushPromises()

    const picker = wrapper.findComponent({ name: 'ElDatePicker' })
    picker.vm.$emit('update:modelValue', ['2026-10-01T00:00:00Z', '2026-10-21T00:00:00Z'])
    picker.vm.$emit('change')
    await flushPromises()

    await buttonByText(wrapper, '查询').trigger('click')
    await flushPromises()

    expect(fetchTicketDashboard).toHaveBeenLastCalledWith({
      scope: 'PENDING_QUEUE',
      createdFrom: '2026-10-01T00:00:00Z',
      createdTo: '2026-10-21T00:00:00Z',
    })
  })

  it('分类选项来自未按分类过滤的响应，选中后保留选项不收缩', async () => {
    useAuthStore().user = itSupport
    const wrapper = await mountPage()

    const options = () => wrapper.findComponent({ name: 'ElSelect' }).findAllComponents({ name: 'ElOption' })
    expect(options().map((option) => option.props('label'))).toEqual(['办公设备', '网络'])

    // 选中分类后的响应只剩一个分组：选项仍应保留两份，用户才能切回去
    vi.mocked(fetchTicketDashboard).mockResolvedValue({
      ...dashboard,
      total: 4,
      byCategory: [{ category: { id: 7, name: '办公设备' }, count: 4 }],
    })
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 7)
    await buttonByText(wrapper, '查询').trigger('click')
    await flushPromises()

    expect(fetchTicketDashboard).toHaveBeenLastCalledWith({ scope: 'PENDING_QUEUE', categoryId: 7 })
    expect(options().map((option) => option.props('label'))).toEqual(['办公设备', '网络'])
    expect(wrapper.find('.dashboard-total__value').text()).toBe('4')
  })

  it('重置清空时间与分类后按范围重新取数', async () => {
    useAuthStore().user = itSupport
    const wrapper = await mountPage()

    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 7)
    await flushPromises()
    await buttonByText(wrapper, '重置').trigger('click')
    await flushPromises()

    expect(fetchTicketDashboard).toHaveBeenLastCalledWith({ scope: 'PENDING_QUEUE' })
    expect(wrapper.find('.dashboard-total__filter').exists()).toBe(false)
  })
})
