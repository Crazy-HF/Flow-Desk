import { http } from './http'
import type { ApiEnvelope } from './http'
import { pageQueryParams } from './pagination'
import type { PageQueryParams, PageResult } from './pagination'

/**
 * 完整动态 RBAC 的管理接口（`docs/api-design.md` 8.2.1，前缀 `/fd/v1/admin`）。
 *
 * <p>四组资源：角色、权限、用户角色授权、角色权限授权，全部要求 `RBAC_MANAGE`。
 * 两组授权是"一个主体 × 多个客体"的**批量增量**语义：服务端去重排序后只新增缺失关系，
 * 任一目标不存在则整批回滚，因此界面不得用循环单条 `POST` 拼出"部分成功"。</p>
 */

export interface RoleDetail {
  id: number
  code: string
  name: string
  description?: string
  createdAt?: string
  /** 已授权的权限 ID，升序。 */
  permissionIds: number[]
}

export interface PermissionDetail {
  id: number
  code: string
  name: string
  description?: string
  createdAt?: string
  /** 已引用该权限的角色 ID。 */
  roleIds: number[]
}

/** 用户角色授权关系；`grantedBy` 是授权人用户 ID，Flyway 预置关系为 `null`。 */
export interface UserRoleGrant {
  userId: number
  username: string
  roleId: number
  roleCode: string
  roleName: string
  grantedBy?: number
  grantedAt?: string
}

/** 角色权限授权关系；历史预置关系可能没有授权时间。 */
export interface RolePermissionGrant {
  roleId: number
  roleCode: string
  permissionId: number
  permissionCode: string
  permissionName: string
  grantedBy?: number
  grantedAt?: string
}

export interface RoleListParams extends PageQueryParams {
  /** 同时匹配 `code` 与 `name`。 */
  keyword?: string
}

export interface PermissionListParams extends PageQueryParams {
  /** 同时匹配 `code` 与 `name`，用于权限码搜索。 */
  keyword?: string
}

export interface CreateRolePayload {
  code: string
  name: string
  description?: string
  permissionIds?: number[]
}

export interface UpdateRolePayload {
  name: string
  description?: string
}

export interface CreatePermissionPayload {
  code: string
  name: string
  description?: string
}

export interface UpdatePermissionPayload {
  name: string
  description?: string
}

/**
 * 两组授权列表都必须给出至少一个筛选条件，否则后端返回 `400/VALIDATION_FAILED`：
 * 用户角色需要 `userId` 或 `roleId`，角色权限需要 `roleId` 或 `permissionId`。界面据此
 * 在未选择主体时走空态，而不是发一个必然失败的请求。
 */
export interface UserRoleListParams extends PageQueryParams {
  userId?: number
  roleId?: number
}

export interface RolePermissionListParams extends PageQueryParams {
  roleId?: number
  permissionId?: number
}

export async function listRoles(params: RoleListParams = {}): Promise<PageResult<RoleDetail>> {
  const { keyword, ...page } = params
  const response = await http.get<ApiEnvelope<PageResult<RoleDetail>>>('/admin/roles', {
    params: { ...pageQueryParams(page), ...(keyword ? { keyword } : {}) },
  })
  return response.data.data
}

export async function getRole(roleId: number): Promise<RoleDetail> {
  const response = await http.get<ApiEnvelope<RoleDetail>>(`/admin/roles/${roleId}`)
  return response.data.data
}

export async function createRole(payload: CreateRolePayload): Promise<RoleDetail> {
  const response = await http.post<ApiEnvelope<RoleDetail>>('/admin/roles', payload)
  return response.data.data
}

export async function updateRole(roleId: number, payload: UpdateRolePayload): Promise<RoleDetail> {
  const response = await http.put<ApiEnvelope<RoleDetail>>(`/admin/roles/${roleId}`, payload)
  return response.data.data
}

/** 受保护角色（`SYSTEM_ADMIN`）与仍被引用的角色会被后端拒绝（`409`）。 */
export async function deleteRole(roleId: number): Promise<void> {
  await http.delete<ApiEnvelope<null>>(`/admin/roles/${roleId}`)
}

export async function listPermissions(
  params: PermissionListParams = {},
): Promise<PageResult<PermissionDetail>> {
  const { keyword, ...page } = params
  const response = await http.get<ApiEnvelope<PageResult<PermissionDetail>>>('/admin/permissions', {
    params: { ...pageQueryParams(page), ...(keyword ? { keyword } : {}) },
  })
  return response.data.data
}

export async function getPermission(permissionId: number): Promise<PermissionDetail> {
  const response = await http.get<ApiEnvelope<PermissionDetail>>(
    `/admin/permissions/${permissionId}`,
  )
  return response.data.data
}

export async function createPermission(
  payload: CreatePermissionPayload,
): Promise<PermissionDetail> {
  const response = await http.post<ApiEnvelope<PermissionDetail>>('/admin/permissions', payload)
  return response.data.data
}

export async function updatePermission(
  permissionId: number,
  payload: UpdatePermissionPayload,
): Promise<PermissionDetail> {
  const response = await http.put<ApiEnvelope<PermissionDetail>>(
    `/admin/permissions/${permissionId}`,
    payload,
  )
  return response.data.data
}

/** 受保护权限（`RBAC_MANAGE`）与仍被角色引用的权限会被后端拒绝（`409`）。 */
export async function deletePermission(permissionId: number): Promise<void> {
  await http.delete<ApiEnvelope<null>>(`/admin/permissions/${permissionId}`)
}

export async function listUserRoleGrants(
  params: UserRoleListParams,
): Promise<PageResult<UserRoleGrant>> {
  const { userId, roleId, ...page } = params
  const response = await http.get<ApiEnvelope<PageResult<UserRoleGrant>>>('/admin/user-roles', {
    params: {
      ...pageQueryParams(page),
      ...(userId ? { userId } : {}),
      ...(roleId ? { roleId } : {}),
    },
  })
  return response.data.data
}

/** 一个用户 × 多个角色：只新增缺失关系，实际变化时后端撤销该用户全部会话一次。 */
export async function grantUserRoles(
  userId: number,
  roleIds: number[],
): Promise<UserRoleGrant[]> {
  const response = await http.post<ApiEnvelope<UserRoleGrant[]>>('/admin/user-roles', {
    userId,
    roleIds,
  })
  return response.data.data
}

export async function revokeUserRole(userId: number, roleId: number): Promise<void> {
  await http.delete<ApiEnvelope<null>>(`/admin/user-roles/${userId}/${roleId}`)
}

/** 批量撤销：任一关系缺失时整批失败，不会部分成功。 */
export async function revokeUserRoles(userId: number, roleIds: number[]): Promise<void> {
  await http.post<ApiEnvelope<null>>('/admin/user-roles/actions/revoke', { userId, roleIds })
}

/** 清空该用户全部角色：目标已无角色时幂等成功，零角色是合法终态。 */
export async function clearUserRoles(userId: number): Promise<void> {
  await http.delete<ApiEnvelope<null>>(`/admin/user-roles/users/${userId}`)
}

/** 一个角色 × 多个用户：只给缺少该角色的用户新增关系。 */
export async function grantRoleToUsers(
  roleId: number,
  userIds: number[],
): Promise<UserRoleGrant[]> {
  const response = await http.post<ApiEnvelope<UserRoleGrant[]>>(
    '/admin/user-roles/actions/grant-users',
    { roleId, userIds },
  )
  return response.data.data
}

export async function listRolePermissionGrants(
  params: RolePermissionListParams,
): Promise<PageResult<RolePermissionGrant>> {
  const { roleId, permissionId, ...page } = params
  const response = await http.get<ApiEnvelope<PageResult<RolePermissionGrant>>>(
    '/admin/role-permissions',
    {
      params: {
        ...pageQueryParams(page),
        ...(roleId ? { roleId } : {}),
        ...(permissionId ? { permissionId } : {}),
      },
    },
  )
  return response.data.data
}

/** 一个角色 × 多个权限：只新增缺失关系，实际变化时后端撤销该角色全部用户会话一次。 */
export async function grantRolePermissions(
  roleId: number,
  permissionIds: number[],
): Promise<RolePermissionGrant[]> {
  const response = await http.post<ApiEnvelope<RolePermissionGrant[]>>('/admin/role-permissions', {
    roleId,
    permissionIds,
  })
  return response.data.data
}

export async function revokeRolePermission(roleId: number, permissionId: number): Promise<void> {
  await http.delete<ApiEnvelope<null>>(`/admin/role-permissions/${roleId}/${permissionId}`)
}

/** 批量撤销：`SYSTEM_ADMIN` 的 `RBAC_MANAGE` 落在批次内时整批拒绝（`409`）。 */
export async function revokeRolePermissions(
  roleId: number,
  permissionIds: number[],
): Promise<void> {
  await http.post<ApiEnvelope<null>>('/admin/role-permissions/actions/revoke', {
    roleId,
    permissionIds,
  })
}

/** 清空该角色全部权限：目标已无权限时幂等成功；`SYSTEM_ADMIN` 的 `RBAC_MANAGE` 保护仍生效。 */
export async function clearRolePermissions(roleId: number): Promise<void> {
  await http.delete<ApiEnvelope<null>>(`/admin/role-permissions/roles/${roleId}`)
}
