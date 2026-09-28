import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./http', () => ({
  http: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

import { http } from './http'
import {
  createUser,
  disableUser,
  enableUser,
  listUsers,
  replaceUserRoles,
  resetUserPassword,
  updateUser,
} from './users'

/** 只回信封里的 `data`：本文件测的是请求契约，不是响应解码。 */
function respondWith(payload: unknown): void {
  vi.mocked(http.get).mockResolvedValue({
    data: { code: 'OK', message: 'OK', data: payload },
  } as never)
  vi.mocked(http.post).mockResolvedValue({
    data: { code: 'OK', message: 'OK', data: payload },
  } as never)
  vi.mocked(http.put).mockResolvedValue({
    data: { code: 'OK', message: 'OK', data: payload },
  } as never)
}

describe('用户管理接口封装', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    respondWith({ items: [], page: 1, size: 20, totalElements: 0, totalPages: 0 })
  })

  it('分页与筛选参数按后端契约组装，未给筛选时不发空键', async () => {
    await listUsers({ pageNo: 2, pageSize: 10, keyword: 'ali', status: 'ENABLED', roleId: 3 })

    expect(http.get).toHaveBeenCalledWith('/users', {
      params: { pageNo: 2, pageSize: 10, keyword: 'ali', status: 'ENABLED', roleId: 3 },
    })

    vi.clearAllMocks()
    await listUsers({})

    expect(http.get).toHaveBeenCalledWith('/users', { params: { pageNo: 1, pageSize: 20 } })
  })

  it('替换角色使用契约的复数路径 /roles', async () => {
    await replaceUserRoles(7, { roleIds: [2, 1], version: 4 })

    expect(http.put).toHaveBeenCalledWith('/users/7/roles', { roleIds: [2, 1], version: 4 })
  })

  it('启停、重置密码与改资料各自带上版本', async () => {
    await enableUser(7, 4)
    await disableUser(7, 5)
    await resetUserPassword(7, { newPassword: 'new-pass-1234', version: 6 })
    await updateUser(7, { displayName: '新名字', version: 7 })

    expect(http.post).toHaveBeenCalledWith('/users/7/actions/enable', { version: 4 })
    expect(http.post).toHaveBeenCalledWith('/users/7/actions/disable', { version: 5 })
    expect(http.post).toHaveBeenCalledWith('/users/7/actions/reset-password', {
      newPassword: 'new-pass-1234',
      version: 6,
    })
    expect(http.put).toHaveBeenCalledWith('/users/7', { displayName: '新名字', version: 7 })
  })

  it('创建用户允许携带空角色集合', async () => {
    await createUser({
      username: 'new.user',
      displayName: '新用户',
      initialPassword: 'initial-1234',
      roleIds: [],
    })

    expect(http.post).toHaveBeenCalledWith('/users', {
      username: 'new.user',
      displayName: '新用户',
      initialPassword: 'initial-1234',
      roleIds: [],
    })
  })
})
