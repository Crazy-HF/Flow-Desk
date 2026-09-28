import { describe, expect, it, vi } from 'vitest'

import type { PageResult } from '@/api/pagination'
import { useAdminList } from './useAdminList'

interface Row {
  id: number
}

function pageOf(items: Row[], page = 1, totalElements = items.length): PageResult<Row> {
  return { items, page, size: 20, totalElements, totalPages: 1 }
}

describe('useAdminList', () => {
  it('首屏从骨架进入就绪，并采用服务端返回的页码与总数', async () => {
    const request = vi.fn().mockResolvedValue(pageOf([{ id: 1 }], 2, 42))
    const list = useAdminList<Row>(request)

    expect(list.phase.value).toBe('loading')

    await list.load()

    expect(list.phase.value).toBe('ready')
    expect(list.items.value).toEqual([{ id: 1 }])
    expect(list.pageNo.value).toBe(2)
    expect(list.total.value).toBe(42)
    expect(request).toHaveBeenCalledWith({ pageNo: 1, pageSize: 20 })
  })

  it('失败进入错误态并给出稳定错误码文案，重试后恢复', async () => {
    const request = vi
      .fn()
      .mockRejectedValueOnce({ response: { data: { code: 'ACCESS_DENIED' } } })
      .mockResolvedValueOnce(pageOf([{ id: 2 }]))
    const list = useAdminList<Row>(request)

    await list.load()

    expect(list.phase.value).toBe('error')
    expect(list.errorMessage.value).toBe('当前账号没有执行该操作的权限')

    await list.load()

    expect(list.phase.value).toBe('ready')
    expect(list.items.value).toEqual([{ id: 2 }])
  })

  it('静默刷新保留现有内容，不回到骨架态', async () => {
    const request = vi
      .fn()
      .mockResolvedValueOnce(pageOf([{ id: 1 }]))
      .mockResolvedValueOnce(pageOf([{ id: 1 }, { id: 2 }]))
    const list = useAdminList<Row>(request)

    await list.load()
    const silent = list.load({ silent: true })
    expect(list.phase.value).toBe('ready')
    await silent

    expect(list.phase.value).toBe('ready')
    expect(list.items.value).toHaveLength(2)
  })

  it('改变每页大小后回到第一页重新取数', async () => {
    const request = vi.fn().mockResolvedValue(pageOf([{ id: 1 }]))
    const list = useAdminList<Row>(request)

    await list.load()
    await list.changePageSize(50)

    expect(request).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 50 })
  })
})
