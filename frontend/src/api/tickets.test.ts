import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./http', () => ({
  http: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

import { http } from './http'
import {
  createSubmissionKey,
  createTicket,
  getTicket,
  listTicketRecords,
  listTickets,
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
})
