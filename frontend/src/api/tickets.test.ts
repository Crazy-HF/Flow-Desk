import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./http', () => ({
  http: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

import { http } from './http'
import {
  addProcessingRecord,
  claimTicket,
  confirmResolution,
  createSubmissionKey,
  createTicket,
  getTicket,
  listTicketRecords,
  listTickets,
  requestSupplementTicket,
  submitResolution,
  supplementTicket,
} from './tickets'

/** 只回信封里的 `data`：本文件测的是请求契约，不是响应解码。 */
function respondWith(payload: unknown): void {
  const envelope = { data: { code: 'OK', message: 'OK', data: payload } }
  vi.mocked(http.get).mockResolvedValue(envelope as never)
  vi.mocked(http.post).mockResolvedValue(envelope as never)
}

/** 取第 n 次 get 调用的 params。 */
function getParams(call = 0): Record<string, unknown> {
  return (vi.mocked(http.get).mock.calls[call]?.[1]?.params ?? {}) as Record<string, unknown>
}

describe('工单接口封装', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    respondWith({ items: [], page: 1, size: 20, totalElements: 0, totalPages: 0 })
  })

  it('列表按契约使用 page/size，缺省筛选与排序都不发空键', async () => {
    await listTickets({ scope: 'PENDING_QUEUE' })

    expect(http.get).toHaveBeenCalledWith('/tickets', {
      params: { scope: 'PENDING_QUEUE', page: 1, size: 20 },
    })
  })

  it('集合筛选参数拼成逗号分隔的单个值，而不是数组', async () => {
    await listTickets({
      scope: 'ASSIGNED_TO_ME',
      pageNo: 3,
      pageSize: 50,
      status: ['PROCESSING', 'WAITING_FOR_REQUESTER'],
      priority: ['HIGH', 'LOW'],
      sort: 'CREATED_ASC',
    })

    const params = getParams()
    // axios 默认把数组序列化成 status[]=…，Spring 绑定的参数名是 status，会静默忽略整个筛选
    expect(params.status).toBe('PROCESSING,WAITING_FOR_REQUESTER')
    expect(params.priority).toBe('HIGH,LOW')
    expect(Array.isArray(params.status)).toBe(false)
    expect(params).toEqual({
      scope: 'ASSIGNED_TO_ME',
      page: 3,
      size: 50,
      status: 'PROCESSING,WAITING_FOR_REQUESTER',
      priority: 'HIGH,LOW',
      sort: 'CREATED_ASC',
    })
  })

  it('关键词去首尾空白，空白关键词与空集合都不出现在请求里', async () => {
    await listTickets({ scope: 'REQUESTED_BY_ME', keyword: '  FD-2026  ', status: [], priority: [] })

    expect(getParams().keyword).toBe('FD-2026')

    vi.clearAllMocks()
    respondWith({ items: [], page: 1, size: 20, totalElements: 0, totalPages: 0 })
    await listTickets({ scope: 'REQUESTED_BY_ME', keyword: '   ' })

    expect(getParams()).not.toHaveProperty('keyword')
    expect(getParams()).not.toHaveProperty('status')
  })

  it('分类与创建时间范围按原样传出', async () => {
    await listTickets({
      scope: 'PARTICIPATED_BY_ME',
      categoryId: 4,
      createdFrom: '2026-09-01T00:00:00+08:00',
      createdTo: '2026-09-30T23:59:59+08:00',
    })

    expect(getParams()).toMatchObject({
      categoryId: 4,
      createdFrom: '2026-09-01T00:00:00+08:00',
      createdTo: '2026-09-30T23:59:59+08:00',
    })
  })

  it('详情按外部编号定位，编号参与编码', async () => {
    await getTicket('FD-20260929-001')

    expect(http.get).toHaveBeenCalledWith('/tickets/FD-20260929-001')
  })

  it('时间线只发 page/size：契约不接受任何排序参数', async () => {
    await listTicketRecords('FD-20260929-001', { pageNo: 2, pageSize: 20 })

    expect(http.get).toHaveBeenCalledWith('/tickets/FD-20260929-001/records', {
      params: { page: 2, size: 20 },
    })
  })

  it('创建走 multipart，ticket 部件带 application/json 类型', async () => {
    await createTicket({
      submissionKey: 'key-1',
      title: '打印机无法连接',
      description: '三楼打印机连不上。',
      categoryId: 2,
      priority: 'HIGH',
    })

    const body = vi.mocked(http.post).mock.calls[0]?.[1] as FormData
    expect(vi.mocked(http.post).mock.calls[0]?.[0]).toBe('/tickets')
    expect(body).toBeInstanceOf(FormData)

    const part = body.get('ticket')
    expect(part).toBeInstanceOf(Blob)
    // 后端用 @RequestPart 接收：部件没有 application/json 会被判成不可转换
    expect((part as Blob).type).toBe('application/json')
    await expect((part as Blob).text()).resolves.toBe(
      JSON.stringify({
        submissionKey: 'key-1',
        title: '打印机无法连接',
        description: '三楼打印机连不上。',
        categoryId: 2,
        priority: 'HIGH',
      }),
    )
  })

  it('提交键是后端正则接受的 UUID 形式', () => {
    const key = createSubmissionKey()

    expect(key).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
    expect(createSubmissionKey()).not.toBe(key)
  })

  describe('工单动作', () => {
    /**
     * 四个动作共用一条路径形状（`docs/api-design.md` 6.2）：动作名就是路径末段，
     * 请求体只带版本号，需要正文的带 `content`。这份断言把形状钉住，
     * 免得某天有人给其中一个动作加了个只有它认识的字段。
     */
    it('领取只发版本号，路径末段与动作名逐字一致', async () => {
      await claimTicket('FD-20260929-001', { version: 3 })

      expect(http.post).toHaveBeenCalledWith('/tickets/FD-20260929-001/actions/claim', {
        version: 3,
      })
    })

    it('追加处理记录发版本号与正文', async () => {
      await addProcessingRecord('FD-20260929-001', { version: 4, content: '已更换证书' })

      expect(http.post).toHaveBeenCalledWith(
        '/tickets/FD-20260929-001/actions/add-processing-record',
        { version: 4, content: '已更换证书' },
      )
    })

    it('提交解决结果发版本号与正文', async () => {
      await submitResolution('FD-20260929-001', { version: 5, content: '已重新配置打印队列' })

      expect(http.post).toHaveBeenCalledWith('/tickets/FD-20260929-001/actions/submit-resolution', {
        version: 5,
        content: '已重新配置打印队列',
      })
    })

    it('员工确认只发版本号：完成方式由服务端固定', async () => {
      await confirmResolution('FD-20260929-001', { version: 6 })

      expect(http.post).toHaveBeenCalledWith(
        '/tickets/FD-20260929-001/actions/confirm-resolution',
        { version: 6 },
      )
    })

    it('动作结果原样返回最新快照摘要，供页面回写状态与版本', async () => {
      respondWith({
        ticketNo: 'FD-20260929-001',
        status: 'WAITING_FOR_CONFIRMATION',
        assignee: { id: 2, displayName: '演示 IT 支持人员' },
        actionDeadlineAt: '2026-10-06T05:00:00Z',
        version: 8,
        actionTime: '2026-09-29T05:00:00Z',
      })

      const result = await submitResolution('FD-20260929-001', { version: 7, content: '已处理' })

      expect(result.status).toBe('WAITING_FOR_CONFIRMATION')
      expect(result.version).toBe(8)
      expect(result.actionDeadlineAt).toBe('2026-10-06T05:00:00Z')
    })

    it('请求补充发版本号与正文，路径末段与动作名逐字一致', async () => {
      await requestSupplementTicket('FD-20260929-001', { version: 9, content: '请补充打印机型号' })

      expect(http.post).toHaveBeenCalledWith(
        '/tickets/FD-20260929-001/actions/request-supplement',
        { version: 9, content: '请补充打印机型号' },
      )
    })

    /**
     * 补充信息是 `multipart/form-data`（`docs/api-design.md` 6.4）：契约要求正文放在 `ticket`
     * 部件里，而该部件必须自带 `application/json`，否则 `@RequestPart` 会按部件自己的
     * Content-Type 选转换器并给出 415。附件部分本版本不发送——传了会得到 400。
     */
    it('提交补充信息走 multipart，ticket 部件带 application/json 类型', async () => {
      await supplementTicket('FD-20260929-001', { version: 10, content: '型号是 L3153' })

      const call = vi.mocked(http.post).mock.calls[0]
      expect(call?.[0]).toBe('/tickets/FD-20260929-001/actions/supplement')

      const body = call?.[1] as FormData
      expect(body).toBeInstanceOf(FormData)
      // 只发 ticket 部件：本版本带文件 part 会被服务端显式拒绝
      expect([...body.keys()]).toEqual(['ticket'])

      const part = body.get('ticket')
      expect(part).toBeInstanceOf(Blob)
      expect((part as Blob).type).toBe('application/json')
      await expect((part as Blob).text()).resolves.toBe(
        JSON.stringify({ version: 10, content: '型号是 L3153' }),
      )
    })
  })
})
