import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { getTicket, listTicketRecords } from '@/api/tickets'
import type { TicketDetail, TicketRecord } from '@/api/tickets'
import TicketDetailView from './TicketDetailView.vue'

vi.mock('@/api/tickets', () => ({
  getTicket: vi.fn(),
  listTicketRecords: vi.fn(),
}))

const mountedWrappers: VueWrapper[] = []

const detail: TicketDetail = {
  ticketNo: 'FD-20260929-001',
  title: '办公区打印机无法连接',
  description: '从今天早上开始，三楼打印机连不上。\n已经重启过打印机和电脑。',
  category: { id: 1, name: '硬件' },
  priority: 'HIGH',
  status: 'PENDING',
  requester: { id: 1, displayName: '演示员工' },
  assignee: null,
  actionDeadlineAt: null,
  version: 0,
  completionMethod: null,
  closeMethod: null,
  closeReason: null,
  endedAt: null,
  createdAt: '2026-09-29T01:00:00Z',
  updatedAt: '2026-09-29T02:00:00Z',
  // 阶段 2 没有已实现的工单动作，后端固定返回空数组
  allowedActions: [],
}

const createRecord: TicketRecord = {
  sequenceNo: 1,
  recordType: 'CREATE',
  actorType: 'USER',
  actor: { id: 1, displayName: '演示员工' },
  createdAt: '2026-09-29T01:00:00Z',
  context: { toStatus: 'PENDING', categoryId: 5, toPriority: 'HIGH' },
}

function pageOf(items: TicketRecord[], page = 1, total = items.length) {
  return {
    items,
    page,
    size: 20,
    totalElements: total,
    totalPages: total === 0 ? 0 : 1,
  }
}

const notFound = { response: { data: { code: 'TICKET_NOT_FOUND' } } }

async function mountPage(ticketNo = detail.ticketNo) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tickets', name: 'tickets', component: { template: '<div />' } },
      { path: '/tickets/:ticketNo', name: 'ticket-detail', component: { template: '<div />' } },
    ],
  })
  await router.push(`/tickets/${ticketNo}`)
  await router.isReady()

  const wrapper = mount(TicketDetailView, { global: { plugins: [router] } })
  mountedWrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text().includes(text))
}

describe('TicketDetailView', () => {
  afterEach(async () => {
    mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    await nextTick()
  })

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getTicket).mockResolvedValue(detail)
    vi.mocked(listTicketRecords).mockResolvedValue(pageOf([createRecord]))
  })

  it('标题用问题本身，编号、状态与优先级组成标识条', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.get('h1').text()).toBe('办公区打印机无法连接')
    expect(wrapper.get('.ticket-meta__no').text()).toBe('FD-20260929-001')

    const marks = wrapper.findAll('.ticket-meta .ticket-mark')
    expect(marks).toHaveLength(2)
    expect(marks[0]?.text()).toContain('待受理')
    expect(marks[0]?.get('.ticket-mark__dot').classes()).toContain('ticket-mark__dot--waiting')
    expect(marks[1]?.text()).toContain('高')
    expect(marks[1]?.get('.ticket-mark__dot').classes()).toContain('ticket-mark__dot--high')
  })

  it('主体展示问题正文与时间线，侧栏展示分类、人员、状态与时间', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.get('.ticket-description').text()).toContain('三楼打印机连不上')
    expect(wrapper.get('.ticket-facts').text()).toContain('硬件')
    expect(wrapper.get('.ticket-facts').text()).toContain('演示员工')
    // 待受理没有负责人，属性栏要写清楚而不是留空
    expect(wrapper.get('.ticket-facts').text()).toContain('未分配')
    expect(wrapper.get('.ticket-facts').text()).toContain('创建时间')

    expect(wrapper.get('.ticket-timeline__item').text()).toContain('创建工单')
  })

  it('allowedActions 为空时不渲染任何动作按钮', async () => {
    const { wrapper } = await mountPage()

    // 没有已实现的工单动作就不摆按钮：按不动的按钮比没有按钮更糟
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('时间线只渲染可公开的人工字段，内部主键不进界面', async () => {
    const { wrapper } = await mountPage()

    const facts = wrapper.get('.ticket-timeline__facts').text()
    expect(facts).toContain('状态')
    expect(facts).toContain('待受理')
    expect(facts).toContain('优先级')
    // context.categoryId = 5 是数据库主键，界面上出现"分类 5"没有任何意义
    expect(facts).not.toContain('5')
  })

  it('无权与不存在都按契约给出同一种解释，且不再请求时间线', async () => {
    vi.mocked(getTicket).mockRejectedValue(notFound)
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('工单不存在或你没有查看权限')
    expect(listTicketRecords).not.toHaveBeenCalled()
    expect(wrapper.get('.ticket-state__link').attributes('href')).toBe('/tickets')
  })

  it('详情加载失败时给出可重试的错误态，重试后恢复', async () => {
    vi.mocked(getTicket)
      .mockRejectedValueOnce({ response: { data: { code: 'INTERNAL_ERROR' } } })
      .mockResolvedValueOnce(detail)
    const { wrapper } = await mountPage()

    expect(wrapper.get('[role="alert"]').text()).toContain('系统暂时无法处理该请求')

    await buttonByText(wrapper, '重新加载')?.trigger('click')
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('办公区打印机无法连接')
    expect(wrapper.get('.ticket-timeline__item').text()).toContain('创建工单')
  })

  it('时间线加载失败只影响时间线，详情主体照常显示', async () => {
    vi.mocked(listTicketRecords).mockRejectedValue({ response: { data: { code: 'INTERNAL_ERROR' } } })
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('处理记录没有加载成功')
    expect(wrapper.get('h1').text()).toBe('办公区打印机无法连接')
    expect(wrapper.get('.ticket-facts').text()).toContain('硬件')

    vi.mocked(listTicketRecords).mockResolvedValue(pageOf([createRecord]))
    await buttonByText(wrapper, '重新加载')?.trigger('click')
    await flushPromises()

    expect(wrapper.get('.ticket-timeline__item').text()).toContain('创建工单')
  })

  it('时间线分页按序号向后追加，不重排已读到的记录', async () => {
    const claim: TicketRecord = {
      sequenceNo: 2,
      recordType: 'CLAIM',
      actorType: 'USER',
      actor: { id: 2, displayName: '演示 IT 支持人员' },
      createdAt: '2026-09-29T03:00:00Z',
      context: { assigneeId: 2, fromStatus: 'PENDING', toStatus: 'PROCESSING' },
    }
    vi.mocked(listTicketRecords)
      .mockResolvedValueOnce({
        items: [createRecord],
        page: 1,
        size: 20,
        totalElements: 2,
        totalPages: 2,
      })
      .mockResolvedValueOnce({ items: [claim], page: 2, size: 20, totalElements: 2, totalPages: 2 })

    const { wrapper } = await mountPage()
    expect(wrapper.findAll('.ticket-timeline__item')).toHaveLength(1)

    await buttonByText(wrapper, '加载更多记录')?.trigger('click')
    await flushPromises()

    expect(listTicketRecords).toHaveBeenLastCalledWith('FD-20260929-001', { pageNo: 2 })
    const items = wrapper.findAll('.ticket-timeline__item')
    expect(items).toHaveLength(2)
    expect(items[0]?.text()).toContain('创建工单')
    expect(items[1]?.text()).toContain('领取工单')
    expect(items[1]?.text()).toContain('待受理 → 处理中')
    // 全部记录都已读到，加载更多入口消失
    expect(wrapper.find('.ticket-timeline__more').exists()).toBe(false)
  })
})
