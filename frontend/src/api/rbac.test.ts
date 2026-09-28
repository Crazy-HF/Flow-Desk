import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./http', () => ({
  http: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

import { http } from './http'
import {
  clearRolePermissions,
  clearUserRoles,
  grantRolePermissions,
  grantRoleToUsers,
  grantUserRoles,
  listPermissions,
  listRolePermissionGrants,
  listRoles,
  listUserRoleGrants,
  revokeUserRole,
} from './rbac'

const page = { items: [], page: 1, size: 20, totalElements: 0, totalPages: 0 }

function respondWith(payload: unknown): void {
  vi.mocked(http.get).mockResolvedValue({
    data: { code: 'OK', message: 'OK', data: payload },
  } as never)
  vi.mocked(http.post).mockResolvedValue({
    data: { code: 'OK', message: 'OK', data: payload },
  } as never)
}

describe('RBAC 管理接口封装', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    respondWith(page)
  })

  it('角色与权限列表把 keyword 交给远程检索', async () => {
    await listRoles({ pageNo: 1, pageSize: 20, keyword: 'ADMIN' })
    await listPermissions({ pageNo: 1, pageSize: 50, keyword: 'TICKET' })

    expect(http.get).toHaveBeenCalledWith('/admin/roles', {
      params: { pageNo: 1, pageSize: 20, keyword: 'ADMIN' },
    })
    expect(http.get).toHaveBeenCalledWith('/admin/permissions', {
      params: { pageNo: 1, pageSize: 50, keyword: 'TICKET' },
    })
  })

  it('两组授权列表只发送实际给出的筛选条件', async () => {
    await listUserRoleGrants({ userId: 7, pageNo: 1, pageSize: 20 })
    await listRolePermissionGrants({ roleId: 3, pageNo: 1, pageSize: 20 })

    expect(http.get).toHaveBeenCalledWith('/admin/user-roles', {
      params: { pageNo: 1, pageSize: 20, userId: 7 },
    })
    expect(http.get).toHaveBeenCalledWith('/admin/role-permissions', {
      params: { pageNo: 1, pageSize: 20, roleId: 3 },
    })
  })

  it('批量授予用一个请求提交一个主体 × 多个客体，不循环单条 POST', async () => {
    await grantUserRoles(7, [1, 2, 3])
    await grantRolePermissions(3, [10, 11])
    await grantRoleToUsers(3, [7, 8])

    expect(http.post).toHaveBeenCalledTimes(3)
    expect(http.post).toHaveBeenCalledWith('/admin/user-roles', { userId: 7, roleIds: [1, 2, 3] })
    expect(http.post).toHaveBeenCalledWith('/admin/role-permissions', {
      roleId: 3,
      permissionIds: [10, 11],
    })
    expect(http.post).toHaveBeenCalledWith('/admin/user-roles/actions/grant-users', {
      roleId: 3,
      userIds: [7, 8],
    })
  })

  it('单条撤销走路径参数，清空全部走批量端点', async () => {
    await revokeUserRole(7, 2)
    await clearUserRoles(7)
    await clearRolePermissions(3)

    expect(http.delete).toHaveBeenCalledWith('/admin/user-roles/7/2')
    expect(http.delete).toHaveBeenCalledWith('/admin/user-roles/users/7')
    expect(http.delete).toHaveBeenCalledWith('/admin/role-permissions/roles/3')
  })
})
