import { http } from './http'
import type { ApiEnvelope } from './http'

/** 最小身份信息，对应后端 `AuthUserBO`；只用于界面展示与路由判断，不代替后端授权。 */
export interface AuthUser {
  id: number
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
}

/** 登录与刷新的响应数据。Refresh Token 只存在于 HttpOnly Cookie，不会出现在这里。 */
export interface AuthTokenPayload {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: AuthUser
}

export async function login(username: string, password: string): Promise<AuthTokenPayload> {
  const response = await http.post<ApiEnvelope<AuthTokenPayload>>('/auth/login', { username, password })
  return response.data.data
}

/** 当前身份：页面刷新后用它恢复菜单与路由所需的角色与权限。 */
export async function fetchCurrentUser(): Promise<AuthUser> {
  const response = await http.get<ApiEnvelope<AuthUser>>('/auth/me')
  return response.data.data
}

export async function logout(): Promise<void> {
  await http.post<ApiEnvelope<null>>('/auth/logout')
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await http.post<ApiEnvelope<null>>('/auth/change-password', { currentPassword, newPassword })
}
