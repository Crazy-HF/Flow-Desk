import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { AuthUser } from '@/api/auth'
import { listCategoryOptions } from '@/api/categories'
import { listTickets } from '@/api/tickets'
import type { TicketListItem } from '@/api/tickets'
import { useAuthStore } from '@/stores/auth'
import TicketListView from './TicketListView.vue'

vi.mock('@/api/tickets', () => ({
  listTickets: vi.fn(),
}))

vi.mock('@/api/categories', () => ({
  listCategoryOptions: vi.fn(),
}))

const employee: AuthUser = {
  id: 1,
  username: 'demo.employee',
  displayName: '演示员工',
  roles: ['EMPLOYEE'],
  permissions: ['TICKET_CREATE', 'TICKET_VIEW_OWN'],
}

const support: AuthUser = {
  id: 2,
  username: 'demo.it',
  displayName: '演示 IT 支持人员',
  roles: ['IT_SUPPORT'],
  permissions: ['TICKET_VIEW_QUEUE', 'TICKET_PROCESS', 'TICKET_VIEW_PARTICIPATED'],
}

const ticket: TicketListItem = {
  ticketNo: 'FD-20260929-001',
  title: '办公区打印机无法连接',
  category: { id: 1, name: '硬件' },
  priority: 'HIGH',
  status: 'PENDING',
  requester: { id: 1, displayName: '演示员工' },
  assignee: null,
  actionDeadlineAt: null,
  createdAt: '2026-09-29T01:00:00Z',
  updatedAt: '2026-09-29T02:00:00Z',
  version: 0,
}

function pageOf(items: TicketListItem[], total = items.length) {
  return { items, page: 1, size: 20, totalElements: total, totalPages: total === 0 ? 0 : 1 }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tickets', name: 'tickets', component: { template: '<div />' } },
      { path: '/tickets/new', name: 'ticket-new', component: { template: '<div />' } },
      { path: '/tickets/:ticketNo', name: 'ticket-detail', component: { template: '<div />' } },
    ],
  })
  await router.push('/tickets')
  await router.isReady()

  const wrapper = mount(TicketListView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

function fieldLabels(wrapper: VueWrapper): string[] {
  return wrapper.findAll('.admin-filter-field').map((field) => field.text())
}

/** 通过范围控件的模型事件切换，避免依赖弹层与原生 radio 的点击细节。 */
async function switchScope(wrapper: VueWrapper, value: string): Promise<void> {
  wrapper.findComponent({ name: 'ElRadioGroup' }).vm.$emit('update:modelValue', value)
  await flushPromises()
}

describe('TicketListView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listTickets).mockResolvedValue(pageOf([ticket]))
    vi.mocked(listCategoryOptions).mockResolvedValue([{ id: 1, name: '硬件' }])
  })

  it('只列出当前账号具备权限的范围，并默认取第一个可用范围', async () => {
    useAuthStore().user = employee
    const { wrapper } = await mountPage()

    expect(wrapper.findAll('.ticket-scope-bar .el-radio-button').map((item) => item.text())).toEqual([
      '我提交的',
    ])
    expect(listTickets).toHaveBeenCalledWith(expect.objectContaining({ scope: 'REQUESTED_BY_ME' }))
  })

  it('IT 支持人员看到队列与参与范围，但看不到"我提交的"', async () => {
    useAuthStore().user = support
    const { wrapper } = await mountPage()

    expect(wrapper.findAll('.ticket-scope-bar .el-radio-button').map((item) => item.text())).toEqual([
      '待受理',
      '我负责的',
      '我参与的',
    ])
    expect(listTickets).toHaveBeenCalledWith(expect.objectContaining({ scope: 'PENDING_QUEUE' }))
  })

  it('完全没有工单范围权限时不发请求，并说明该找谁处理', async () => {
    useAuthStore().user = {
      id: 9,
      username: 'demo.nobody',
      displayName: '无工单权限账号',
      roles: [],
      permissions: ['DASHBOARD_VIEW'],
    }
    const { wrapper } = await mountPage()

    expect(listTickets).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前账号没有可查看的工单范围')
  })

  it('列表主列承担层级：标题是指向详情的链接，编号在其下方', async () => {
    useAuthStore().user = employee
    const { wrapper } = await mountPage()

    const title = wrapper.get('.ticket-cell__title')
    expect(title.text()).toBe('办公区打印机无法连接')
    expect(title.attributes('href')).toBe('/tickets/FD-20260929-001')
    expect(wrapper.get('.ticket-cell__no').text()).toBe('FD-20260929-001')
    // 待受理工单没有负责人，界面不能显示成一个空白单元格
    expect(wrapper.text()).toContain('未分配')
  })

  it('队列范围隐藏状态筛选：后端在队列上固定 status=PENDING，条件只会筛出空列表', async () => {
    useAuthStore().user = support
    const { wrapper } = await mountPage()

    // IT 账号的默认范围是队列，先切到有状态筛选的范围，再切回来
    await switchScope(wrapper, 'ASSIGNED_TO_ME')
    expect(fieldLabels(wrapper).some((text) => text.startsWith('状态'))).toBe(true)
    expect(vi.mocked(listTickets).mock.calls.filter((call) => call[0]?.scope === 'ASSIGNED_TO_ME')).toHaveLength(1)

    await switchScope(wrapper, 'PENDING_QUEUE')

    expect(fieldLabels(wrapper).some((text) => text.startsWith('状态'))).toBe(false)
    expect(listTickets).toHaveBeenLastCalledWith(expect.objectContaining({ scope: 'PENDING_QUEUE' }))
    // 一次切换只发一次请求：地址里的 scope 与本地范围一致时，route 的 watch 必须直接返回
    expect(vi.mocked(listTickets).mock.calls.filter((call) => call[0]?.scope === 'PENDING_QUEUE')).toHaveLength(2)
    expect(wrapper.find('#ticket-status').exists()).toBe(false)
  })

  it('空结果按当前范围给不同的说明，而不是笼统的"暂无数据"', async () => {
    useAuthStore().user = support
    vi.mocked(listTickets).mockResolvedValue(pageOf([]))
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('待受理队列是空的')

    await switchScope(wrapper, 'ASSIGNED_TO_ME')
    expect(wrapper.text()).toContain('当前没有由你负责的工单')
  })

  it('加载失败进入错误态，可原位重试', async () => {
    useAuthStore().user = employee
    vi.mocked(listTickets)
      .mockRejectedValueOnce({ response: { data: { code: 'ACCESS_DENIED' } } })
      .mockResolvedValueOnce(pageOf([ticket]))
    const { wrapper } = await mountPage()

    expect(wrapper.get('[role="alert"]').text()).toContain('当前账号没有执行该操作的权限')

    const retry = wrapper.findAll('button').find((item) => item.text().includes('重新加载'))
    await retry?.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('.ticket-cell__title').text()).toBe('办公区打印机无法连接')
  })

  it('分类选项不可用时只隐藏分类筛选，列表本身照常工作', async () => {
    useAuthStore().user = employee
    vi.mocked(listCategoryOptions).mockRejectedValue(new Error('forbidden'))
    const { wrapper } = await mountPage()

    expect(fieldLabels(wrapper).some((text) => text.startsWith('分类'))).toBe(false)
    expect(wrapper.get('.ticket-cell__title').text()).toBe('办公区打印机无法连接')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
})
