import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { AuthUser } from '@/api/auth'
import { uploadAttachment } from '@/api/attachments'
import { listCategoryOptions } from '@/api/categories'
import { createSubmissionKey, createTicket, listTickets } from '@/api/tickets'
import { useAuthStore } from '@/stores/auth'
import TicketCreateView from './TicketCreateView.vue'

vi.mock('@/api/categories', () => ({
  listCategoryOptions: vi.fn(),
}))

/**
 * 附件接口：上传控件会真实发出上传请求。
 *
 * <p>只替网络出口，`formatFileSize` / `isUploadCanceled` / 数量上限仍用真实实现——
 * 它们是界面文案与判定的一部分。</p>
 */
vi.mock('@/api/attachments', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/attachments')>()
  return {
    ...actual,
    uploadAttachment: vi.fn(),
    fetchAttachmentContent: vi.fn(),
    saveBlob: vi.fn(),
  }
})

vi.mock('@/api/tickets', () => ({
  createTicket: vi.fn(),
  createSubmissionKey: vi.fn(),
  listTickets: vi.fn(),
}))

const mountedWrappers: VueWrapper[] = []

const ElInputStub = defineComponent({
  name: 'ElInput',
  inheritAttrs: false,
  props: {
    modelValue: { type: String, default: '' },
    type: { type: String, default: 'text' },
    autosize: { type: Object, default: undefined },
    showWordLimit: Boolean,
    maxlength: { type: String, default: undefined },
  },
  emits: ['update:modelValue'],
  setup(props, { attrs, emit }) {
    return () =>
      h(props.type === 'textarea' ? 'textarea' : 'input', {
        ...attrs,
        value: props.modelValue,
        onInput: (event: Event) =>
          emit('update:modelValue', (event.target as HTMLInputElement).value),
      })
  },
})

const employee: AuthUser = {
  id: 1,
  username: 'demo.employee',
  displayName: '演示员工',
  roles: ['EMPLOYEE'],
  permissions: ['TICKET_CREATE', 'TICKET_VIEW_OWN'],
}

const createdTicket = {
  ticketNo: 'FD-20260929-001',
  status: 'PENDING' as const,
  version: 0,
  createdAt: '2026-09-29T01:00:00Z',
}

async function mountPage(user: AuthUser = employee) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tickets/new', name: 'ticket-new', component: { template: '<div />' } },
      { path: '/tickets', name: 'tickets', component: { template: '<div />' } },
      { path: '/tickets/:ticketNo', name: 'ticket-detail', component: { template: '<div />' } },
    ],
  })
  await router.push('/tickets/new')
  await router.isReady()

  useAuthStore().user = user
  const wrapper = mount(TicketCreateView, {
    global: { plugins: [router], stubs: { ElInput: ElInputStub } },
  })
  mountedWrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

async function fillValidForm(wrapper: VueWrapper): Promise<void> {
  await wrapper.get('input[aria-label="标题"]').setValue('办公区打印机无法连接')
  await wrapper.get('textarea[aria-label="问题描述"]').setValue('从早上开始，三楼打印机连不上。')
  // 分类是下拉，直接给组件抛模型更新比在 jsdom 里模拟弹层选择更稳
  wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 1)
  await flushPromises()
}

async function clickSubmit(wrapper: VueWrapper): Promise<void> {
  const button = wrapper.findAll('button').find((item) => item.text().includes('提交工单'))
  expect(button, '提交按钮必须存在').toBeTruthy()
  await button?.trigger('click')
  await flushPromises()
}

/** 取第 n 次提交发出的请求体。 */
function submittedPayload(call: number) {
  return vi.mocked(createTicket).mock.calls[call]?.[0]
}

describe('TicketCreateView', () => {
  afterEach(async () => {
    mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    await nextTick()
  })

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listCategoryOptions).mockResolvedValue([{ id: 1, name: '硬件' }])
    vi.mocked(createSubmissionKey).mockReturnValue('key-1')
  })

  it('打开页面后创建权限被撤：不渲染表单，也不去请求分类选项', async () => {
    const { wrapper } = await mountPage({ ...employee, permissions: ['TICKET_VIEW_OWN'] })

    expect(wrapper.text()).toContain('当前账号不能创建工单')
    expect(wrapper.find('form').exists()).toBe(false)
    // 明知会 403 的请求不发：它只会在控制台留下一条误导人的失败
    expect(listCategoryOptions).not.toHaveBeenCalled()
  })

  it('分类加载失败时给出可重试的错误态，重试成功后回到表单', async () => {
    vi.mocked(listCategoryOptions).mockRejectedValueOnce(new Error('offline'))
    const { wrapper } = await mountPage()

    expect(wrapper.get('[role="alert"]').text()).toContain('分类没有加载成功')

    vi.mocked(listCategoryOptions).mockResolvedValueOnce([{ id: 1, name: '硬件' }])
    await wrapper.get('[role="alert"]').findAll('button')[0]?.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('form').exists()).toBe(true)
  })

  it('没有启用中的分类时不渲染表单，并说明该找谁开通', async () => {
    vi.mocked(listCategoryOptions).mockResolvedValue([])
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('还没有可用的分类')
    expect(wrapper.text()).toContain('分类管理')
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('优先级用三个带后果说明的选项，默认「中」', async () => {
    const { wrapper } = await mountPage()
    const text = wrapper.text()

    expect(text).toContain('无法继续工作，需要尽快处理')
    expect(text).toContain('影响部分工作，可以暂时绕过')
    expect(text).toContain('不影响当前工作，方便时处理')

    const options = wrapper.findAll('.ticket-priority__option')
    expect(options).toHaveLength(3)
    expect(options[1]?.classes()).toContain('is-checked')
  })

  it('必填项为空时不创建提交键，也不发请求', async () => {
    const { wrapper } = await mountPage()
    await clickSubmit(wrapper)

    expect(createTicket).not.toHaveBeenCalled()
    expect(createSubmissionKey).not.toHaveBeenCalled()
  })

  it('提交成功后进入刚创建的工单详情', async () => {
    vi.mocked(createTicket).mockResolvedValue(createdTicket)
    const { wrapper, router } = await mountPage()
    await fillValidForm(wrapper)
    await clickSubmit(wrapper)

    expect(createTicket).toHaveBeenCalledWith({
      submissionKey: 'key-1',
      title: '办公区打印机无法连接',
      description: '从早上开始，三楼打印机连不上。',
      categoryId: 1,
      priority: 'MEDIUM',
    })
    expect(router.currentRoute.value.name).toBe('ticket-detail')
    expect(router.currentRoute.value.params.ticketNo).toBe('FD-20260929-001')
  })

  /** 工单关联（2026-10-12）：原工单搜索只查「我提交的」范围，选中的编号进请求体。 */
  it('原工单下拉按关键词搜索「我提交的」范围', async () => {
    vi.mocked(listTickets).mockResolvedValue({
      items: [],
      page: 1,
      size: 10,
      totalElements: 0,
      totalPages: 0,
    })
    const { wrapper } = await mountPage()

    const remoteMethod = wrapper.findAllComponents({ name: 'ElSelect' })[1]?.props('remoteMethod') as
      | ((keyword: string) => void)
      | undefined
    expect(remoteMethod, '关联原工单下拉必须支持远程搜索').toBeTruthy()
    remoteMethod?.('打印机')
    await flushPromises()

    expect(listTickets).toHaveBeenCalledWith({
      scope: 'REQUESTED_BY_ME',
      keyword: '打印机',
      pageSize: 10,
    })
  })

  it('选择了原工单：请求体带上 sourceTicketNo', async () => {
    vi.mocked(createTicket).mockResolvedValue(createdTicket)
    const { wrapper } = await mountPage()
    await fillValidForm(wrapper)

    // 关联下拉是第二个 ElSelect（第一个是分类）；直接抛模型更新，不在 jsdom 里模拟弹层选择
    wrapper.findAllComponents({ name: 'ElSelect' })[1]?.vm.$emit(
      'update:modelValue',
      'FD-20260928-002',
    )
    await flushPromises()
    await clickSubmit(wrapper)

    expect(submittedPayload(0)?.sourceTicketNo).toBe('FD-20260928-002')
  })

  it('提交失败后保留原请求：重试复用同一个提交编号与同一份内容', async () => {
    vi.mocked(createTicket)
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce(createdTicket)
    const { wrapper, router } = await mountPage()
    await fillValidForm(wrapper)

    await clickSubmit(wrapper)
    expect(createTicket).toHaveBeenCalledTimes(1)
    // 结果不确定时必须显式告诉用户"再点一次不会建出第二张工单"，他才敢重试
    expect(wrapper.text()).toContain('提交编号 key-1')
    expect(wrapper.get('[role="alert"]').text()).toContain('提交没有完成')

    await clickSubmit(wrapper)

    expect(createTicket).toHaveBeenCalledTimes(2)
    expect(submittedPayload(1)).toEqual(submittedPayload(0))
    expect(router.currentRoute.value.name).toBe('ticket-detail')
  })

  it('请求期间重复点击不会发出第二次请求', async () => {
    let resolveCreate: (value: typeof createdTicket) => void = () => {}
    vi.mocked(createTicket).mockReturnValue(
      new Promise((resolve) => {
        resolveCreate = resolve
      }) as never,
    )
    const { wrapper } = await mountPage()
    await fillValidForm(wrapper)

    await clickSubmit(wrapper)
    await clickSubmit(wrapper)

    expect(createTicket).toHaveBeenCalledTimes(1)

    resolveCreate(createdTicket)
    await flushPromises()
  })

  it('失败后改动内容会换新的提交编号：新内容不能被旧键命中而静默丢弃', async () => {
    vi.mocked(createSubmissionKey).mockReturnValueOnce('key-1').mockReturnValueOnce('key-2')
    vi.mocked(createTicket)
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce(createdTicket)
    const { wrapper } = await mountPage()
    await fillValidForm(wrapper)

    await clickSubmit(wrapper)
    expect(submittedPayload(0)?.submissionKey).toBe('key-1')

    await wrapper.get('input[aria-label="标题"]').setValue('改过的标题')
    // 内容变了就不再是"同一次提交"，界面上那句话也该消失
    expect(wrapper.text()).not.toContain('提交编号 key-1')

    await clickSubmit(wrapper)

    expect(submittedPayload(1)?.submissionKey).toBe('key-2')
    expect(submittedPayload(1)?.title).toBe('改过的标题')
  })

  /** 附件走"先上传后绑定"：上传拿到的 id 进 `attachmentIds`，没有附件时**不带这个键**。 */
  it('上传成功的附件随创建请求一起绑定', async () => {
    vi.mocked(uploadAttachment).mockResolvedValue({
      id: 55,
      originalName: 'log.txt',
      contentType: 'text/plain',
      sizeBytes: 8,
      createdAt: '2026-10-14T01:00:00Z',
    })
    vi.mocked(createTicket).mockResolvedValue(createdTicket)
    const { wrapper } = await mountPage()
    await fillValidForm(wrapper)

    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['content'], 'log.txt', { type: 'text/plain' })],
      configurable: true,
    })
    await input.trigger('change')
    await flushPromises()

    await clickSubmit(wrapper)

    expect(submittedPayload(0)?.attachmentIds).toEqual([55])
  })

  /**
   * 附件没传完就建单，用户以为带上了、实际没带上，而后端只会安静地返回一张没有附件的工单。
   * 所以提交按钮必须在有文件在途时禁用，并在页面上说明原因。
   */
  it('附件还在上传时提交按钮禁用，并说明要等传完', async () => {
    // 永远不 resolve：模拟"正在上传"
    vi.mocked(uploadAttachment).mockImplementation(() => new Promise(() => {}))
    const { wrapper } = await mountPage()
    await fillValidForm(wrapper)

    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['content'], 'big.zip', { type: 'application/zip' })],
      configurable: true,
    })
    await input.trigger('change')
    await flushPromises()

    const submitButton = wrapper.findAll('button').find((item) => item.text().includes('提交工单'))
    expect(submitButton?.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('附件正在上传，传完之后才能提交')

    await clickSubmit(wrapper)
    expect(createTicket).not.toHaveBeenCalled()
  })
})
