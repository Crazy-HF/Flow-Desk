import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { AuthUser } from '@/api/auth'
import { listCategoryOptions } from '@/api/categories'
import {
  addProcessingRecord,
  approveCancel,
  cancelTicket,
  changeTicketCategory,
  changeTicketPriority,
  claimTicket,
  closeTicket,
  confirmResolution,
  getTicket,
  listTicketRecords,
  listTransferCandidates,
  rejectCancel,
  requestCancelTicket,
  requestSupplementTicket,
  submitResolution,
  supplementTicket,
  transferTicket,
  withdrawCancelRequest,
} from '@/api/tickets'
import type { TicketAssigneeOption, TicketDetail, TicketRecord } from '@/api/tickets'
import { useAuthStore } from '@/stores/auth'
import { createMessageBoxHarness } from './messageBoxHarness'
import TicketDetailView from './TicketDetailView.vue'

vi.mock('@/api/tickets', () => ({
  getTicket: vi.fn(),
  listTicketRecords: vi.fn(),
  claimTicket: vi.fn(),
  addProcessingRecord: vi.fn(),
  submitResolution: vi.fn(),
  confirmResolution: vi.fn(),
  withdrawSupplementRequest: vi.fn(),
  reportUnresolved: vi.fn(),
  requestSupplementTicket: vi.fn(),
  supplementTicket: vi.fn(),
  changeTicketCategory: vi.fn(),
  changeTicketPriority: vi.fn(),
  transferTicket: vi.fn(),
  closeTicket: vi.fn(),
  cancelTicket: vi.fn(),
  requestCancelTicket: vi.fn(),
  withdrawCancelRequest: vi.fn(),
  approveCancel: vi.fn(),
  rejectCancel: vi.fn(),
  listTransferCandidates: vi.fn(),
}))

// 分类选项也是被页面真实请求的：不替身就会在用例里发出真实 HTTP 请求
vi.mock('@/api/categories', () => ({
  listCategoryOptions: vi.fn(),
}))

/** 确认框替身：真实挂载弹窗，用例按"打开 → 填内容 → 点确认"的真人顺序驱动。 */
const messageBox = createMessageBoxHarness()
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessageBox: {
      ...actual.ElMessageBox,
      confirm: (message: unknown, title?: unknown, options?: unknown) =>
        messageBox.confirm(message, title, options),
    },
  }
})

const mountedWrappers: VueWrapper[] = []

const requester: AuthUser = {
  id: 1,
  username: 'demo.employee',
  displayName: '演示员工',
  roles: ['EMPLOYEE'],
  permissions: ['TICKET_CREATE', 'TICKET_VIEW_OWN', 'TICKET_REQUESTER_ACTION'],
}

const support: AuthUser = {
  id: 2,
  username: 'demo.it',
  displayName: '演示 IT 支持人员',
  roles: ['IT_SUPPORT'],
  permissions: [
    'TICKET_VIEW_QUEUE',
    'TICKET_CLAIM',
    'TICKET_VIEW_PARTICIPATED',
    'TICKET_PROCESS',
    // 转交是独立授权（V2 里由 IT_SUPPORT 持有），不是 TICKET_PROCESS 的一部分
    'TICKET_TRANSFER',
    // 关闭又是另一条（V2 里同样由 IT_SUPPORT 持有）：后端还要叠 TICKET_PROCESS 才放行动作
    'TICKET_CLOSE',
  ],
}

const detail: TicketDetail = {
  ticketNo: 'FD-20260929-001',
  title: '办公区打印机无法连接',
  description: '从今天早上开始，三楼打印机连不上。\n已经重启过打印机和电脑。',
  category: { id: 1, name: '硬件' },
  priority: 'HIGH',
  status: 'PENDING',
  requester: { id: 1, displayName: '演示员工' },
  assignee: undefined,
  actionDeadlineAt: undefined,
  version: 0,
  completionMethod: undefined,
  closeMethod: undefined,
  closeReason: undefined,
  endedAt: undefined,
  createdAt: '2026-09-29T01:00:00Z',
  updatedAt: '2026-09-29T02:00:00Z',
  // 待受理、没有负责人的工单，服务端对任何账号都不返回动作
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

/** 动作按钮的文案，用来断言"这一屏摆出了哪几个动作"。 */
function actionLabels(wrapper: VueWrapper): string[] {
  return wrapper.findAll('.ticket-action .el-button').map((item) => item.text())
}

/**
 * 待批准的撤销申请那一块（2026-10-08 两阶段撤销）。
 *
 * <p>它只在详情返回 `cancelRequest` 时渲染——申请期间工单状态不变，所以这块是界面上
 * 唯一能看出"这张单正卡在一次撤销申请上"的地方。</p>
 */
function cancelRequestPanel(wrapper: VueWrapper) {
  return wrapper.find('.ticket-cancel-request')
}

/** 点开动作按钮，等着确认框被打开。 */
async function openActionBox(wrapper: VueWrapper, label: string): Promise<void> {
  await buttonByText(wrapper, label)?.trigger('click')
  await flushPromises()
}

/** 当前确认框里的文本框；不需要正文或原因的动作没有它。 */
function actionBoxTextarea(): HTMLTextAreaElement | null {
  return messageBox.current()?.textarea() ?? null
}

/** 条件下出现的单行输入（片 D 的重复工单编号）；条件不成立时是 null。 */
function actionBoxConditionalInput(): HTMLInputElement | null {
  return messageBox.current()?.conditionalInput() ?? null
}

/** 展开目标值下拉，看看用户到底能选到哪些（选项在下拉里，不在 wrapper 里）。 */
async function openActionBoxSelect(): Promise<string[]> {
  return (await messageBox.current()?.openSelect()) ?? []
}

/** 在确认框里选一个目标值（真人先展开、再点那一项）。 */
async function chooseInActionBox(label: string): Promise<void> {
  await messageBox.current()?.choose(label)
}

/** 确认框正文的可见文字：空态与错误说明必须真的写出来给人看。 */
function actionBoxText(): string {
  return messageBox.current()?.text() ?? ''
}

/** 选择器上显示的选中值，等同用户看到自己选了什么。 */
function actionBoxSelected(): string {
  return messageBox.current()?.select()?.textContent?.trim() ?? ''
}

/** 在确认框里写好正文或原因（真人先写、再点确认）。 */
async function typeIntoActionBox(text: string): Promise<void> {
  await messageBox.current()?.type(text)
}

/** 在条件输入里写好工单编号（真人先写、再点确认）。 */
async function typeIntoConditional(text: string): Promise<void> {
  await messageBox.current()?.typeConditional(text)
}

async function acceptActionBox(): Promise<void> {
  await messageBox.current()?.accept()
  await flushPromises()
}

async function cancelActionBox(): Promise<void> {
  await messageBox.current()?.cancel()
  await flushPromises()
}

describe('TicketDetailView', () => {
  afterEach(async () => {
    mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    await nextTick()
  })

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    useAuthStore().user = requester
    vi.mocked(getTicket).mockResolvedValue(detail)
    vi.mocked(listTicketRecords).mockResolvedValue(pageOf([createRecord]))
    // 每个用例都从这里起步：要测"没有可选项"或"取失败"的用例再各自覆盖
    vi.mocked(listCategoryOptions).mockResolvedValue([
      { id: 1, name: '硬件' },
      { id: 2, name: '软件' },
    ])
    vi.mocked(listTransferCandidates).mockResolvedValue([{ id: 5, displayName: '演示同事' }])
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

  it('allowedActions 为空时不渲染动作区：按不动的按钮比没有按钮更糟', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.find('.ticket-action-panel').exists()).toBe(false)
    expect(wrapper.findAll('.ticket-action')).toHaveLength(0)
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('IT 负责人按 allowedActions 看到领取与记录动作，并按登记表之外的顺序渲染', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 3,
      allowedActions: ['add-processing-record', 'claim'],
    })
    const { wrapper } = await mountPage()

    // `claim` 在 PROCESSING 下不该出现，但服务端万一返回了，界面按登记表顺序渲染两个都在；
    // 这里断言的是"渲染什么由 allowedActions 决定，顺序由前端定"
    expect(actionLabels(wrapper)).toEqual(['领取工单', '记录处理过程'])
  })

  it('服务端尚未放行的动作不会变成按钮：allowedActions 里没有就不渲染', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 3,
      // 有 TICKET_PROCESS 权限，但服务端没有放行 submit-resolution
      allowedActions: ['add-processing-record'],
    })
    const { wrapper } = await mountPage()

    expect(actionLabels(wrapper)).toEqual(['记录处理过程'])
    expect(buttonByText(wrapper, '提交解决结果')).toBeUndefined()
  })

  it('取详情之后被撤权时不再渲染该动作：界面二次收口，不依赖后端重算', async () => {
    // 服务端返回了 confirm-resolution，但当前账号已经不具备提交人操作权限
    useAuthStore().user = { ...requester, permissions: ['TICKET_VIEW_OWN'] }
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'WAITING_FOR_CONFIRMATION',
      actionDeadlineAt: '2026-10-06T02:00:00Z',
      allowedActions: ['confirm-resolution'],
    })
    const { wrapper } = await mountPage()

    expect(actionLabels(wrapper)).toEqual([])
  })

  it('领取工单：带当前版本、成功后重新取详情与时间线、并按新状态重算按钮', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket)
      .mockResolvedValueOnce({ ...detail, allowedActions: ['claim'] })
      .mockResolvedValueOnce({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 1,
        allowedActions: ['add-processing-record'],
      })
    vi.mocked(claimTicket).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: undefined,
      version: 1,
      actionTime: '2026-09-29T03:00:00Z',
    })

    const { wrapper } = await mountPage()
    expect(actionLabels(wrapper)).toEqual(['领取工单'])

    await openActionBox(wrapper, '领取工单')
    // 领取不需要正文本，弹窗里不该有输入框
    expect(actionBoxTextarea()).toBeNull()
    await acceptActionBox()

    expect(claimTicket).toHaveBeenCalledWith('FD-20260929-001', { version: 0 })
    // 动作成功后必须重新取详情：下一步要靠新版本做乐观锁
    expect(getTicket).toHaveBeenCalledTimes(2)
    expect(actionLabels(wrapper)).toEqual(['记录处理过程'])
    expect(wrapper.get('.ticket-meta').text()).toContain('处理中')
  })

  it('追加处理记录：正文去首尾空白后提交，取消确认框则不发请求', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 4,
      allowedActions: ['add-processing-record'],
    })
    vi.mocked(addProcessingRecord).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: undefined,
      version: 5,
      actionTime: '2026-09-29T04:00:00Z',
    })

    const { wrapper } = await mountPage()

    // 取消：什么也不该发生
    await openActionBox(wrapper, '记录处理过程')
    await cancelActionBox()
    expect(addProcessingRecord).not.toHaveBeenCalled()

    // 只填空格等于没填：提示而不是发一个注定 400 的请求，弹窗也不该关
    await openActionBox(wrapper, '记录处理过程')
    await typeIntoActionBox('   ')
    await acceptActionBox()
    expect(addProcessingRecord).not.toHaveBeenCalled()
    expect(messageBox.current()).not.toBeNull()
    await cancelActionBox()

    // 正常路径：正文去掉首尾空白后随版本号一起提交
    await openActionBox(wrapper, '记录处理过程')
    await typeIntoActionBox('  已更换打印服务器证书  ')
    await acceptActionBox()

    expect(addProcessingRecord).toHaveBeenCalledWith('FD-20260929-001', {
      version: 4,
      content: '已更换打印服务器证书',
    })
  })

  it('提交解决结果带正文与版本号', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 7,
        allowedActions: ['submit-resolution'],
      })
      .mockResolvedValue({
        ...detail,
        status: 'WAITING_FOR_CONFIRMATION',
        actionDeadlineAt: '2026-10-06T05:00:00Z',
        version: 8,
        allowedActions: [],
      })
    vi.mocked(submitResolution).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'WAITING_FOR_CONFIRMATION',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: '2026-10-06T05:00:00Z',
      version: 8,
      actionTime: '2026-09-29T05:00:00Z',
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '提交解决结果')
    await typeIntoActionBox('证书已更新，请重新连接打印机确认')
    await acceptActionBox()

    expect(submitResolution).toHaveBeenCalledWith('FD-20260929-001', {
      version: 7,
      content: '证书已更新，请重新连接打印机确认',
    })
    expect(wrapper.get('.ticket-meta').text()).toContain('待员工确认')
  })

  it('确认类动作的正文长度上限与后端契约一致', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 4,
      allowedActions: ['add-processing-record'],
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '记录处理过程')

    // 超长正文在界面层就被截断，不会走到 400
    expect(actionBoxTextarea()?.getAttribute('maxlength')).toBe('10000')
    await cancelActionBox()
  })

  it('需要正文的动作必须在确认框里渲染出输入框', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 4,
      allowedActions: ['add-processing-record'],
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '记录处理过程')

    // 输入框缺失时，界面上只会剩下一行标签文字——用户无法填写，也发不出请求
    expect(actionBoxTextarea()).not.toBeNull()
    await cancelActionBox()
  })

  it('提交人确认已解决：命中确认接口，成功后进入终态且不再有动作', async () => {
    useAuthStore().user = requester
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        status: 'WAITING_FOR_CONFIRMATION',
        actionDeadlineAt: '2026-10-06T02:00:00Z',
        version: 9,
        allowedActions: ['confirm-resolution'],
      })
      .mockResolvedValue({
        ...detail,
        status: 'COMPLETED',
        completionMethod: 'REQUESTER_CONFIRMED',
        endedAt: '2026-09-29T06:00:00Z',
        actionDeadlineAt: undefined,
        version: 10,
        allowedActions: [],
      })
    vi.mocked(confirmResolution).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'COMPLETED',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: undefined,
      version: 10,
      actionTime: '2026-09-29T06:00:00Z',
    })

    const { wrapper } = await mountPage()
    expect(actionLabels(wrapper)).toEqual(['确认已解决'])
    // 待确认时属性栏把期限解释成"我要在什么时候之前确认"
    expect(wrapper.get('.ticket-facts').text()).toContain('确认期限')
    expect(wrapper.get('.ticket-facts__hint').text()).toContain('仍需要你手动确认')

    await openActionBox(wrapper, '确认已解决')
    // 确认是终态动作：弹窗里说明清楚后果，且不需要写正文
    expect(messageBox.current()?.title).toBe('确认已解决')
    expect(actionBoxTextarea()).toBeNull()
    await acceptActionBox()

    expect(confirmResolution).toHaveBeenCalledWith('FD-20260929-001', { version: 9 })
    expect(wrapper.get('.ticket-meta').text()).toContain('已完成')
    expect(actionLabels(wrapper)).toEqual([])
  })

  // ---------- 片 B：补充往返 ----------

  /**
   * 同一个期限字段，负责人读到的不是同一句话。
   *
   * <p>待确认时负责人没有任何可做动作（确认归提交人），若提示按"有没有动作"给，负责人在这里
   * 看不到任何限定，界面就只剩一个日期在暗示"到点系统会处理"；而这句提示必须说给两种人各自的
   * 处境：提交人要做的是去确认，负责人要做的是等提交人。</p>
   */
  it('待确认时负责人看到的是"确认期限"，并写明到期要等提交人手动确认', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'WAITING_FOR_CONFIRMATION',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: '2026-10-06T02:00:00Z',
      version: 9,
      allowedActions: [],
    })

    const { wrapper } = await mountPage()

    const facts = wrapper.get('.ticket-facts')
    expect(facts.text()).toContain('确认期限')
    // 不写这一句，负责人只能猜"到点会不会自动关掉"
    expect(facts.text()).toContain('到期不会自动处理')
    expect(wrapper.get('.ticket-facts__hint').text()).toContain('需要提交人手动确认')
  })

  it('提交人补充信息：命中补充接口，成功后回到处理中并刷新时间线', async () => {
    useAuthStore().user = requester
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        status: 'WAITING_FOR_REQUESTER',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        actionDeadlineAt: '2026-10-13T02:00:00Z',
        version: 5,
        allowedActions: ['supplement'],
      })
      .mockResolvedValue({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        actionDeadlineAt: undefined,
        version: 6,
        allowedActions: [],
      })
    vi.mocked(supplementTicket).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: undefined,
      version: 6,
      actionTime: '2026-09-29T07:00:00Z',
    })

    const { wrapper } = await mountPage()
    // 提交人在待补充上只能做一件事：补充信息（不能提交解决结果，那是负责人的动作）
    expect(actionLabels(wrapper)).toEqual(['提交补充信息'])

    await openActionBox(wrapper, '提交补充信息')
    await typeIntoActionBox('  型号是 L3153，报错信息已附在描述里  ')
    await acceptActionBox()

    expect(supplementTicket).toHaveBeenCalledWith('FD-20260929-001', {
      version: 5,
      content: '型号是 L3153，报错信息已附在描述里',
    })
    expect(wrapper.get('.ticket-meta').text()).toContain('处理中')
    // 补充完成后提交人不再有任何动作
    expect(actionLabels(wrapper)).toEqual([])
  })

  it('待补充时属性栏写"补充截止时间"，并说明到期不会自动处理', async () => {
    useAuthStore().user = requester
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'WAITING_FOR_REQUESTER',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: '2026-10-13T02:00:00Z',
      version: 5,
      allowedActions: ['supplement'],
    })

    const { wrapper } = await mountPage()

    const facts = wrapper.get('.ticket-facts')
    expect(facts.text()).toContain('补充截止时间')
    // 本版本没有超时自动任务：不写这一句，界面就在暗示"到点系统会处理"
    expect(facts.text()).toContain('到期不会自动关闭工单')
    expect(wrapper.get('.ticket-facts__hint').text()).toContain('仍需提交人手动补充')
  })

  /**
   * 负责人视角同样要有这句提示。
   *
   * <p>这里刻意用「负责人」而不是「有 supplement 动作的人」来判断：负责人在待补充时唯一的动作是
   * 撤回，如果用可做动作判断，恰好是最需要看到"到点不会自动处理"的那个人看不到这句话。</p>
   */
  it('待补充时负责人看到的期限叫"补充期限"，同样说明到期不会自动处理', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'WAITING_FOR_REQUESTER',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: '2026-10-13T02:00:00Z',
      version: 5,
      allowedActions: ['withdraw-supplement-request'],
    })

    const { wrapper } = await mountPage()

    const facts = wrapper.get('.ticket-facts')
    expect(facts.text()).toContain('补充期限')
    expect(facts.text()).toContain('到期不会自动处理')
    expect(wrapper.get('.ticket-facts__hint').text()).toContain('工单会一直停在待员工补充')
  })

  it('请求补充信息：带需要补充的内容与版本号提交', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 4,
        allowedActions: ['request-supplement'],
      })
      .mockResolvedValue({
        ...detail,
        status: 'WAITING_FOR_REQUESTER',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        actionDeadlineAt: '2026-10-13T02:00:00Z',
        version: 5,
        allowedActions: ['withdraw-supplement-request'],
      })
    vi.mocked(requestSupplementTicket).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'WAITING_FOR_REQUESTER',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: '2026-10-13T02:00:00Z',
      version: 5,
      actionTime: '2026-09-29T07:00:00Z',
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '请求补充信息')
    await typeIntoActionBox('请补充打印机型号与完整报错信息')
    await acceptActionBox()

    expect(requestSupplementTicket).toHaveBeenCalledWith('FD-20260929-001', {
      version: 4,
      content: '请补充打印机型号与完整报错信息',
    })
    expect(wrapper.get('.ticket-meta').text()).toContain('待员工补充')
    // 待补充期间负责人不能再提交解决结果，只能撤回补充请求
    expect(actionLabels(wrapper)).toEqual(['撤回补充请求'])
    // 负责人视角看的是"等员工回到什么时候"，标签与提交人视角不同
    expect(wrapper.get('.ticket-facts').text()).toContain('补充期限')
  })

  it('版本过期时给出冲突说明并重新对齐版本，而不是停在旧快照上', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 2,
        allowedActions: ['add-processing-record'],
      })
      .mockResolvedValue({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 6,
        allowedActions: ['add-processing-record'],
      })
    vi.mocked(addProcessingRecord).mockRejectedValue({
      response: { data: { code: 'TICKET_CONFLICT' } },
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '记录处理过程')
    await typeIntoActionBox('先写点内容，好让请求发出去')
    await acceptActionBox()

    expect(wrapper.get('.ticket-action-error').text()).toContain('工单已被其他人更新')
    // 冲突后必须重新取详情：否则留着旧 version，用户再点一次还是同一个 409
    expect(getTicket).toHaveBeenCalledTimes(2)
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

  // ---------- 片 C：调整与转交 ----------

  it('调整分类：选项来自启用中的分类，提交带 categoryId 与原因', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 6,
        allowedActions: ['change-category'],
      })
      .mockResolvedValue({
        ...detail,
        category: { id: 2, name: '软件' },
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 7,
        allowedActions: ['change-category'],
      })
    vi.mocked(changeTicketCategory).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: undefined,
      version: 7,
      actionTime: '2026-09-29T08:00:00Z',
    })

    const { wrapper } = await mountPage()
    expect(actionLabels(wrapper)).toEqual(['调整分类'])

    await openActionBox(wrapper, '调整分类')
    // 选项来自服务端的"启用分类"接口，不是界面里写死的一份
    expect(listCategoryOptions).toHaveBeenCalledTimes(1)
    expect(await openActionBoxSelect()).toEqual(['硬件', '软件'])

    await chooseInActionBox('软件')
    expect(actionBoxSelected()).toContain('软件')
    await typeIntoActionBox('  报错来自客户端，应归到软件  ')
    await acceptActionBox()

    expect(changeTicketCategory).toHaveBeenCalledWith('FD-20260929-001', {
      version: 6,
      categoryId: 2,
      reason: '报错来自客户端，应归到软件',
    })
    // 动作成功后照旧重新取详情：属性栏的分类已经换成新的
    expect(getTicket).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.ticket-facts').text()).toContain('软件')
  })

  it('调整优先级：选项是前端的静态刻度，不为它发请求', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 4,
        allowedActions: ['change-priority'],
      })
      .mockResolvedValue({
        ...detail,
        priority: 'LOW',
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 5,
        allowedActions: ['change-priority'],
      })
    vi.mocked(changeTicketPriority).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: undefined,
      version: 5,
      actionTime: '2026-09-29T08:30:00Z',
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '调整优先级')

    // 优先级是前端已有的三级刻度：为它发一次请求既多等一次往返，也多一个失败点
    expect(listCategoryOptions).not.toHaveBeenCalled()
    expect(listTransferCandidates).not.toHaveBeenCalled()
    expect(await openActionBoxSelect()).toEqual(['高', '中', '低'])

    await chooseInActionBox('低')
    await typeIntoActionBox('影响范围缩小，可以缓一缓')
    await acceptActionBox()

    expect(changeTicketPriority).toHaveBeenCalledWith('FD-20260929-001', {
      version: 4,
      priority: 'LOW',
      reason: '影响范围缩小，可以缓一缓',
    })
    expect(wrapper.get('.ticket-meta').text()).toContain('优先级 低')
  })

  it('转交：候选人由服务端筛选，提交带 newAssigneeId 与原因', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 11,
        allowedActions: ['transfer'],
      })
      .mockResolvedValue({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 5, displayName: '演示同事二' },
        version: 12,
        allowedActions: ['transfer'],
      })
    vi.mocked(listTransferCandidates).mockResolvedValue([
      { id: 5, displayName: '演示同事二' },
      { id: 6, displayName: '演示同事三' },
    ])
    vi.mocked(transferTicket).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'PROCESSING',
      assignee: { id: 5, displayName: '演示同事二' },
      actionDeadlineAt: undefined,
      version: 12,
      actionTime: '2026-09-29T09:00:00Z',
    })

    const { wrapper } = await mountPage()
    expect(actionLabels(wrapper)).toEqual(['转交工单'])

    await openActionBox(wrapper, '转交工单')
    // 候选人要按工单问服务端：谁能接、谁被排除，都不是界面能自己算的
    expect(listTransferCandidates).toHaveBeenCalledWith('FD-20260929-001')
    expect(await openActionBoxSelect()).toEqual(['演示同事二', '演示同事三'])

    await chooseInActionBox('演示同事二')
    await typeIntoActionBox('他更熟悉这套设备')
    await acceptActionBox()

    expect(transferTicket).toHaveBeenCalledWith('FD-20260929-001', {
      version: 11,
      newAssigneeId: 5,
      reason: '他更熟悉这套设备',
    })
    // 直接转交：不需要接收方确认，属性栏的负责人立即换人
    expect(wrapper.get('.ticket-facts').text()).toContain('演示同事二')
  })

  it('没有选目标值时拦下提交：不发请求，弹窗也不关', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 6,
      allowedActions: ['change-category'],
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '调整分类')
    // 选项已经取回来了，用户只是没选
    expect(await openActionBoxSelect()).toHaveLength(2)

    await typeIntoActionBox('原因写好了，但没选分类')
    await acceptActionBox()

    expect(changeTicketCategory).not.toHaveBeenCalled()
    // 关掉再提示的话，用户得重新点按钮、重写一遍
    expect(messageBox.current()).not.toBeNull()
    await cancelActionBox()
  })

  /**
   * 服务端允许同值调整（它只看状态、身份与版本），但界面不该凭空产生一条
   * "分类从硬件改为硬件"：那对后来读时间线的人只是噪音，所以这一层由弹窗挡住。
   */
  it('选了和当前值相同的分类时拦下：不发请求，弹窗也不关', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 6,
      allowedActions: ['change-category'],
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '调整分类')
    await chooseInActionBox('硬件')
    // 选中确实生效了：拦下它的原因只能是"没有变化"，不是"没选"
    expect(actionBoxSelected()).toContain('硬件')

    await typeIntoActionBox('想重新归一次类')
    await acceptActionBox()

    expect(changeTicketCategory).not.toHaveBeenCalled()
    expect(messageBox.current()).not.toBeNull()
    await cancelActionBox()
  })

  it('原因只填空格时拦下提交：不发请求，弹窗也不关', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 4,
      allowedActions: ['change-priority'],
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '调整优先级')
    await chooseInActionBox('低')
    await typeIntoActionBox('   ')
    await acceptActionBox()

    expect(changeTicketPriority).not.toHaveBeenCalled()
    expect(messageBox.current()).not.toBeNull()
    await cancelActionBox()
  })

  it('一个候选人都没有时转交做不了：弹窗里说明原因，提交被拦下', async () => {
    useAuthStore().user = support
    vi.mocked(listTransferCandidates).mockResolvedValue([])
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 3,
      allowedActions: ['transfer'],
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '转交工单')

    // 空数组不是错误：界面必须自己把"为什么转不出去"说出来
    expect(actionBoxText()).toContain('当前没有可以接手的同事')
    expect(await openActionBoxSelect()).toEqual([])

    await typeIntoActionBox('想转给别人')
    await acceptActionBox()

    expect(transferTicket).not.toHaveBeenCalled()
    await cancelActionBox()
  })

  it('候选人没有加载成功时把原因写进弹窗，而不是留一个空下拉', async () => {
    useAuthStore().user = support
    vi.mocked(listTransferCandidates).mockRejectedValue({
      response: { data: { code: 'INTERNAL_ERROR' } },
    })
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 3,
      allowedActions: ['transfer'],
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '转交工单')

    // 失败不能静默，也不能借用"没有人可以接手"这个说法——那是另一个原因
    expect(actionBoxText()).toContain('系统暂时无法处理该请求')
    expect(actionBoxText()).not.toContain('当前没有可以接手的同事')

    await acceptActionBox()
    expect(transferTicket).not.toHaveBeenCalled()
    await cancelActionBox()
  })

  it('候选人还在取的时候弹窗里就是加载态：用户不会对着空下拉点确认', async () => {
    useAuthStore().user = support
    let release: ((candidates: TicketAssigneeOption[]) => void) | undefined
    vi.mocked(listTransferCandidates).mockReturnValue(
      new Promise<TicketAssigneeOption[]>((resolve) => {
        release = resolve
      }),
    )
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 3,
      allowedActions: ['transfer'],
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '转交工单')

    expect(actionBoxText()).toContain('正在加载可选项')
    await acceptActionBox()
    expect(transferTicket).not.toHaveBeenCalled()

    release?.([{ id: 5, displayName: '演示同事' }])
    await flushPromises()

    // 取回来之后选项出现在同一个弹窗里，不用重新打开
    expect(await openActionBoxSelect()).toEqual(['演示同事'])
    await cancelActionBox()
  })

  // ---------- 片 D：结束路径（关闭与撤销） ----------

  /**
   * 关闭工单：原因是本地静态三选一，说明走 `description`。
   *
   * <p>这里同时钉住一件容易被忽略的事：**没选「重复工单」时弹窗里没有第二个输入框**。
   * 条件输入不是"隐藏的第二个正文"，它出现与否决定请求里带不带 `duplicateTicketNo`，
   * 而服务端对多传的单号回的是 `400`，不是忽略。</p>
   */
  it('关闭工单：原因三选一、说明必填，非重复原因时不出现重复单号输入', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 6,
        allowedActions: ['close'],
      })
      .mockResolvedValue({
        ...detail,
        status: 'CLOSED',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        closeMethod: 'MANUAL',
        closeReason: 'OUT_OF_SCOPE',
        endedAt: '2026-10-08T03:00:00Z',
        version: 7,
        allowedActions: [],
      })
    vi.mocked(closeTicket).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'CLOSED',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: undefined,
      version: 7,
      actionTime: '2026-10-08T03:00:00Z',
    })

    const { wrapper } = await mountPage()
    expect(actionLabels(wrapper)).toEqual(['关闭工单'])

    await openActionBox(wrapper, '关闭工单')
    // 关闭原因是契约里写死的三种，不为它发请求
    expect(listCategoryOptions).not.toHaveBeenCalled()
    expect(listTransferCandidates).not.toHaveBeenCalled()
    expect(await openActionBoxSelect()).toEqual(['重复工单', '超出支持范围', '无效工单'])
    // 还没选原因，也就没有"重复工单"这个前提：这里不该出现第二个输入框
    expect(actionBoxConditionalInput()).toBeNull()

    await chooseInActionBox('超出支持范围')
    expect(actionBoxConditionalInput()).toBeNull()

    await typeIntoActionBox('  门禁卡补办属于行政，不在 IT 支持范围  ')
    await acceptActionBox()

    expect(closeTicket).toHaveBeenCalledWith('FD-20260929-001', {
      version: 6,
      reasonCode: 'OUT_OF_SCOPE',
      description: '门禁卡补办属于行政，不在 IT 支持范围',
    })
    // 非重复原因**不带**这个键：传空串虽然也会被服务端归一成"没传"，但那是把正确性寄托在容错上
    expect(vi.mocked(closeTicket).mock.calls[0]?.[1]).not.toHaveProperty('duplicateTicketNo')
    expect(wrapper.get('.ticket-meta').text()).toContain('已关闭')
    expect(wrapper.get('.ticket-facts').text()).toContain('超出支持范围')
  })

  /**
   * 选「重复工单」之后：条件输入出现，且**不填就不许提交**。
   *
   * <p>它是服务端解析目标工单的唯一入口，缺了必然 `400`；界面上先说，用户才不会拿到一句
   * 与自己刚做的事对不上的报错。</p>
   */
  it('关闭为重复工单：选中后才出现编号输入，没填则拦下提交', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 4,
      allowedActions: ['close'],
    })
    vi.mocked(closeTicket).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'CLOSED',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: undefined,
      version: 5,
      actionTime: '2026-10-08T03:30:00Z',
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '关闭工单')
    await chooseInActionBox('重复工单')

    // 输入框与它的触发项一起出现，上限与 CloseTicketCommand 的 @Size(max = 32) 对齐
    expect(actionBoxConditionalInput()).not.toBeNull()
    expect(actionBoxConditionalInput()?.getAttribute('maxlength')).toBe('32')
    expect(actionBoxText()).toContain('重复的工单编号')

    await typeIntoActionBox('同一个问题已经报过了')
    await acceptActionBox()

    expect(closeTicket).not.toHaveBeenCalled()
    // 关掉再提示的话，用户得重新点按钮、重写一遍
    expect(messageBox.current()).not.toBeNull()

    await typeIntoConditional('  FD-20260929-001  ')
    await acceptActionBox()

    expect(closeTicket).toHaveBeenCalledWith('FD-20260929-001', {
      version: 4,
      reasonCode: 'DUPLICATE',
      description: '同一个问题已经报过了',
      duplicateTicketNo: 'FD-20260929-001',
    })
  })

  /**
   * 选过「重复工单」又改回别的原因时，编号必须**从请求里消失**，而不是"留着但不显示"。
   *
   * <p>这是"隐藏等于不发"那个不变量的反面用例：如果提交侧按"值是否为空"判断，用户改完原因后
   * 依然会带着一个旧编号发出去，服务端回 `400`，界面上却什么都看不出来。</p>
   */
  it('从重复工单改回其他原因：输入框消失，已填的编号也不进请求', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 8,
      allowedActions: ['close'],
    })
    vi.mocked(closeTicket).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'CLOSED',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: undefined,
      version: 9,
      actionTime: '2026-10-08T03:40:00Z',
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '关闭工单')
    await chooseInActionBox('重复工单')
    await typeIntoConditional('FD-20260929-001')
    expect(actionBoxConditionalInput()).not.toBeNull()

    await chooseInActionBox('无效工单')
    expect(actionBoxConditionalInput()).toBeNull()

    await typeIntoActionBox('测试数据，没有实际问题')
    await acceptActionBox()

    const payload = vi.mocked(closeTicket).mock.calls[0]?.[1]
    expect(payload).toMatchObject({
      reasonCode: 'INVALID',
      description: '测试数据，没有实际问题',
    })
    expect(payload).not.toHaveProperty('duplicateTicketNo')
  })

  /**
   * 撤销工单：提交人的终态动作，**规则变更后只剩「待受理」**（2026-10-08 两阶段撤销）。
   *
   * <p>待受理没有负责人，不需要谁批准，所以这一格仍然一步到位。界面必须把后果写清楚：
   * 进入终态「已取消」，此前的处理记录与负责人（如果有）都保留，但不会再有人处理它，
   * 而且本版本不支持恢复。</p>
   */
  it('撤销工单（待受理）：带版本号与原因提交，成功后进入已取消且不再有动作', async () => {
    useAuthStore().user = requester
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        version: 5,
        allowedActions: ['cancel'],
      })
      .mockResolvedValue({
        ...detail,
        status: 'CANCELED',
        endedAt: '2026-10-08T04:00:00Z',
        version: 6,
        allowedActions: [],
      })
    vi.mocked(cancelTicket).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'CANCELED',
      actionDeadlineAt: undefined,
      version: 6,
      actionTime: '2026-10-08T04:00:00Z',
    })

    const { wrapper } = await mountPage()
    expect(actionLabels(wrapper)).toEqual(['撤销工单'])

    await openActionBox(wrapper, '撤销工单')
    // 撤销只要一段原因：没有要选的目标值，也没有条件输入
    expect(messageBox.current()?.select()).toBeNull()
    expect(actionBoxConditionalInput()).toBeNull()

    await typeIntoActionBox('  问题已经自行解决  ')
    await acceptActionBox()

    expect(cancelTicket).toHaveBeenCalledWith('FD-20260929-001', {
      version: 5,
      reason: '问题已经自行解决',
    })
    expect(wrapper.get('.ticket-meta').text()).toContain('已取消')
    // 终态要能看到结束时间：否则用户只知道"不动了"，不知道什么时候结束的
    expect(wrapper.get('.ticket-facts').text()).toContain('结束时间')
    expect(actionLabels(wrapper)).toEqual([])
  })

  /**
   * 申请撤销（提交人视角，2026-10-08 两阶段撤销）。
   *
   * <p>「处理中」的提交人只能**申请**：这一格同时证明两件事——申请不需要 IT 先同意就能发出去
   * （否则提交人没有出口），以及申请之后工单**状态不变**。后者意味着界面不能靠 `status` 判断，
   * 只能读详情单独返回的 `cancelRequest`，否则用户点完按钮看不到任何变化。</p>
   */
  it('申请撤销工单：写申请理由提交，刷新后出现待批准提示且状态仍是处理中', async () => {
    useAuthStore().user = requester
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 5,
        allowedActions: ['request-cancel'],
      })
      .mockResolvedValue({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 6,
        allowedActions: ['withdraw-cancel-request'],
        cancelRequest: {
          requestedAt: '2026-10-08T08:00:00Z',
          deadlineAt: '2026-10-11T08:00:00Z',
          reason: '问题已经自行解决',
        },
      })
    vi.mocked(requestCancelTicket).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: undefined,
      version: 6,
      actionTime: '2026-10-08T08:00:00Z',
    })

    const { wrapper } = await mountPage()
    expect(cancelRequestPanel(wrapper).exists()).toBe(false)
    expect(actionLabels(wrapper)).toEqual(['申请撤销工单'])

    await openActionBox(wrapper, '申请撤销工单')
    // 申请要写一段说明给负责人看：有文本框，没有要选的目标值
    expect(messageBox.current()?.select()).toBeNull()
    expect(actionBoxTextarea()).not.toBeNull()

    await typeIntoActionBox('  问题已经自行解决  ')
    await acceptActionBox()

    expect(requestCancelTicket).toHaveBeenCalledWith('FD-20260929-001', {
      version: 5,
      reason: '问题已经自行解决',
    })

    // 状态与期限都没变：待批准只能靠 cancelRequest 讲出来
    expect(wrapper.get('.ticket-meta').text()).toContain('处理中')
    const panel = cancelRequestPanel(wrapper)
    expect(panel.text()).toContain('你已申请撤销这张工单')
    expect(panel.text()).toContain('问题已经自行解决')
    // 本版本没有超时任务：期限必须写明"到期不自动处理"
    expect(panel.text()).toContain('到期不会自动处理')
    // 提交人还能撤回自己刚发出去的申请
    expect(actionLabels(wrapper)).toEqual(['撤回撤销申请'])
  })

  /**
   * 负责人视角（2026-10-08 两阶段撤销）：他先要读到**提交人写的理由**，才谈得上同意或驳回。
   *
   * <p>这一格同时钉住"申请期间工单不冻结"：关闭与同意/驳回同时摆着，谁先提交由版本条件更新裁决
   * （`docs/kickoff.md` 4.7 的既定设计）。</p>
   */
  it('负责人视角：读到提交人的理由，同意撤销只发版本号并进入已取消', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 7,
        allowedActions: ['close', 'approve-cancel', 'reject-cancel'],
        cancelRequest: {
          requestedAt: '2026-10-08T08:00:00Z',
          deadlineAt: '2026-10-11T08:00:00Z',
          reason: '这是一次重复提交',
        },
      })
      .mockResolvedValue({
        ...detail,
        status: 'CANCELED',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        endedAt: '2026-10-08T09:00:00Z',
        version: 8,
        allowedActions: [],
      })
    vi.mocked(approveCancel).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'CANCELED',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: undefined,
      version: 8,
      actionTime: '2026-10-08T09:00:00Z',
    })

    const { wrapper } = await mountPage()
    const panel = cancelRequestPanel(wrapper)
    expect(panel.exists()).toBe(true)
    expect(panel.text()).toContain('演示员工申请撤销这张工单，正在等你处理。')
    expect(panel.text()).toContain('这是一次重复提交')

    // 申请期间工单不冻结：关闭与两个决策动作同时摆着
    expect(actionLabels(wrapper)).toEqual(['关闭工单', '同意撤销', '驳回撤销申请'])

    await openActionBox(wrapper, '同意撤销')
    // 同意不需要理由：确认框里不该出现输入框
    expect(actionBoxTextarea()).toBeNull()
    await acceptActionBox()

    expect(approveCancel).toHaveBeenCalledWith('FD-20260929-001', { version: 7 })
    expect(wrapper.get('.ticket-meta').text()).toContain('已取消')
    // 终态不可能挂待决请求（数据库约束），界面这一块随之消失
    expect(cancelRequestPanel(wrapper).exists()).toBe(false)
  })

  /**
   * 待补充期间没有人在裁决（2026-10-10 用户裁决）：申请还挂在工单上，但同意与驳回两格都不在。
   *
   * <p>这一格专门防"对着负责人说'正在等待当前负责人处理'"——那句旁观者文案对提交人成立，
   * 对此刻的负责人是错的：他手上没有决策入口，能做的只有等提交人补充、或转交出去。</p>
   */
  it('负责人视角：待补充期间的申请不出现裁决入口，文案指向等提交人补充', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'WAITING_FOR_REQUESTER',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: '2026-10-15T08:00:00Z',
      version: 9,
      allowedActions: ['withdraw-supplement-request', 'transfer'],
      cancelRequest: {
        requestedAt: '2026-10-08T08:00:00Z',
        deadlineAt: '2026-10-11T08:00:00Z',
        reason: '不需要了',
      },
    })

    const { wrapper } = await mountPage()
    const panel = cancelRequestPanel(wrapper)
    expect(panel.exists()).toBe(true)
    expect(panel.text()).toContain('补充回来后由你决定')
    expect(panel.text()).not.toContain('正在等你处理')
    expect(panel.text()).not.toContain('正在等待当前负责人处理')

    const labels = actionLabels(wrapper)
    expect(labels).not.toContain('同意撤销')
    expect(labels).not.toContain('驳回撤销申请')
    // 冻结的只是裁决：转交与撤回补充请求这两条 4.11 的能力照常摆着
    expect(labels).toContain('撤回补充请求')
  })

  /**
   * 驳回必须写理由（2026-10-08 两阶段撤销）：没有理由，提交人只看到"被驳回"而无从调整。
   *
   * <p>驳回**不结束工单**，所以它不该和同意撤销一样用警示色；这也正是登记表里
   * `destructive: false` 的含义。</p>
   */
  it('驳回撤销申请必须写理由：空白不放行，写好后带版本号与理由提交', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 9,
      allowedActions: ['reject-cancel'],
      cancelRequest: {
        requestedAt: '2026-10-08T08:00:00Z',
        deadlineAt: '2026-10-11T08:00:00Z',
        reason: '不需要了',
      },
    })
    vi.mocked(rejectCancel).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      version: 10,
      actionTime: '2026-10-08T09:30:00Z',
    })

    const { wrapper } = await mountPage()
    await openActionBox(wrapper, '驳回撤销申请')
    expect(actionBoxTextarea()).not.toBeNull()

    await typeIntoActionBox('   ')
    await acceptActionBox()

    expect(rejectCancel).not.toHaveBeenCalled()
    // 关掉再提示的话，用户得重新点按钮、重写一遍
    expect(messageBox.current()).not.toBeNull()

    await typeIntoActionBox('  问题尚未定位，正在等供应商回复  ')
    await acceptActionBox()

    expect(rejectCancel).toHaveBeenCalledWith('FD-20260929-001', {
      version: 9,
      reason: '问题尚未定位，正在等供应商回复',
    })
    // 工单留在原状态：申请失效，处理继续
    expect(wrapper.get('.ticket-meta').text()).toContain('处理中')
  })

  /**
   * 撤回自己的申请（2026-10-08 两阶段撤销）：不需要理由，工单回到"没有被申请撤销"。
   *
   * <p>撤回后仍能重新发起——界面重新按服务端返回的 `allowedActions` 渲染，不缓存上一次的结论。</p>
   */
  it('撤回撤销申请：只发版本号，工单回到没有被申请撤销', async () => {
    useAuthStore().user = requester
    vi.mocked(getTicket)
      .mockResolvedValueOnce({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 6,
        allowedActions: ['withdraw-cancel-request'],
        cancelRequest: {
          requestedAt: '2026-10-08T08:00:00Z',
          deadlineAt: '2026-10-11T08:00:00Z',
          reason: '问题已经自行解决',
        },
      })
      .mockResolvedValue({
        ...detail,
        status: 'PROCESSING',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        version: 7,
        allowedActions: ['request-cancel'],
      })
    vi.mocked(withdrawCancelRequest).mockResolvedValue({
      ticketNo: detail.ticketNo,
      status: 'PROCESSING',
      assignee: { id: 2, displayName: '演示 IT 支持人员' },
      actionDeadlineAt: undefined,
      version: 7,
      actionTime: '2026-10-08T10:00:00Z',
    })

    const { wrapper } = await mountPage()
    expect(actionLabels(wrapper)).toEqual(['撤回撤销申请'])

    await openActionBox(wrapper, '撤回撤销申请')
    // 撤回不需要理由：申请里那段说明仍然留在时间线上
    expect(actionBoxTextarea()).toBeNull()
    await acceptActionBox()

    expect(withdrawCancelRequest).toHaveBeenCalledWith('FD-20260929-001', { version: 6 })
    expect(cancelRequestPanel(wrapper).exists()).toBe(false)
    expect(actionLabels(wrapper)).toEqual(['申请撤销工单'])
  })

  /**
   * 旁观者也要看得到（2026-10-08 两阶段撤销）。
   *
   * <p>没有决策权限的人（例如已经交接走的历史负责人、或刚好被撤掉处理权限的账号）
   * 动作区是空的，但"这张单正卡在一次撤销申请上"仍是事实，不能连同动作区一起消失；
   * 同时也不该指使他做任何事——所以只陈述状态，不给行动指引。</p>
   */
  it('没有决策权限的旁观者：动作区为空，待批准状态仍然可见且不指引操作', async () => {
    useAuthStore().user = support
    vi.mocked(getTicket).mockResolvedValue({
      ...detail,
      status: 'PROCESSING',
      assignee: { id: 9, displayName: '另一位同事' },
      version: 4,
      allowedActions: [],
      cancelRequest: {
        requestedAt: '2026-10-08T08:00:00Z',
        deadlineAt: '2026-10-11T08:00:00Z',
        reason: '不需要了',
      },
    })

    const { wrapper } = await mountPage()
    expect(wrapper.find('.ticket-action-panel').exists()).toBe(false)

    const panel = cancelRequestPanel(wrapper)
    expect(panel.exists()).toBe(true)
    expect(panel.text()).toContain('演示员工已申请撤销这张工单，正在等待当前负责人处理。')
    expect(panel.text()).toContain('在负责人同意或驳回之前，工单仍按当前状态继续流转。')
  })
})
