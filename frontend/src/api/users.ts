import { http } from './http'
import type { ApiEnvelope } from './http'
import { pageQueryParams } from './pagination'
import type { PageQueryParams, PageResult } from './pagination'

/**
 * 用户与账号管理接口（`docs/api-design.md` 8.2，前缀 `/fd/v1/users`）。
 *
 * <p>领域封装与 `auth.ts` / `rbac.ts` 平级：`api/` 保持扁平，基础设施（`http` / `pagination` /
 * `errorMessages`）与领域文件靠命名区分，这是 2026-09-22 的裁定（见 `docs/modules/rbac.md` 12.3）。
 * 全部端点要求 `USER_MANAGE`；界面显隐不代替后端授权。</p>
 */

/** `iam_user.status` 的两个取值，与后端 `IamUserStatus` 一致。 */
export type UserStatus = 'ENABLED' | 'DISABLED'

export interface UserRoleSummary {
  id: number
  code: string
  name: string
}

/** 用户详情；后端不返回密码摘要。`version` 是所有写操作的乐观锁依据。 */
export interface UserDetail {
  id: number
  username: string
  displayName: string
  status: UserStatus
  roles: UserRoleSummary[]
  createdAt: string | null
  updatedAt: string | null
  version: number
}

export interface UserListParams extends PageQueryParams {
  /** 同时匹配 `username` 与 `displayName`。 */
  keyword?: string
  status?: UserStatus
  /** 只列出持有该角色的用户。 */
  roleId?: number
}

export interface CreateUserPayload {
  username: string
  displayName: string
  initialPassword: string
  /** 允许空数组或省略：零角色自 2026-09-22 起是合法终态。 */
  roleIds?: number[]
}

export interface UpdateUserPayload {
  displayName: string
  version: number
}

export interface ReplaceUserRolesPayload {
  /** 目标用户最终应当持有的全部角色；空数组表示清空。 */
  roleIds: number[]
  version: number
}

export interface ResetUserPasswordPayload {
  newPassword: string
  version: number
}

export async function listUsers(params: UserListParams = {}): Promise<PageResult<UserDetail>> {
  const { keyword, status, roleId, ...page } = params
  const response = await http.get<ApiEnvelope<PageResult<UserDetail>>>('/users', {
    params: {
      ...pageQueryParams(page),
      ...(keyword ? { keyword } : {}),
      ...(status ? { status } : {}),
      ...(roleId ? { roleId } : {}),
    },
  })
  return response.data.data
}

export async function getUser(userId: number): Promise<UserDetail> {
  const response = await http.get<ApiEnvelope<UserDetail>>(`/users/${userId}`)
  return response.data.data
}

export async function createUser(payload: CreateUserPayload): Promise<UserDetail> {
  const response = await http.post<ApiEnvelope<UserDetail>>('/users', payload)
  return response.data.data
}

export async function updateUser(userId: number, payload: UpdateUserPayload): Promise<UserDetail> {
  const response = await http.put<ApiEnvelope<UserDetail>>(`/users/${userId}`, payload)
  return response.data.data
}

export async function enableUser(userId: number, version: number): Promise<UserDetail> {
  const response = await http.post<ApiEnvelope<UserDetail>>(`/users/${userId}/actions/enable`, {
    version,
  })
  return response.data.data
}

export async function disableUser(userId: number, version: number): Promise<UserDetail> {
  const response = await http.post<ApiEnvelope<UserDetail>>(`/users/${userId}/actions/disable`, {
    version,
  })
  return response.data.data
}

/** 替换角色：携带完整角色集合，复数路径与 8.2 契约一致。 */
export async function replaceUserRoles(
  userId: number,
  payload: ReplaceUserRolesPayload,
): Promise<UserDetail> {
  const response = await http.put<ApiEnvelope<UserDetail>>(`/users/${userId}/roles`, payload)
  return response.data.data
}

export async function resetUserPassword(
  userId: number,
  payload: ResetUserPasswordPayload,
): Promise<void> {
  await http.post<ApiEnvelope<null>>(`/users/${userId}/actions/reset-password`, payload)
}
