import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchCurrentUser, login, logout } from '@/api/auth'
import type { AuthTokenPayload, AuthUser } from '@/api/auth'
import { refreshAccessToken } from '@/api/http'
import { useAuthStore } from './auth'

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  fetchCurrentUser: vi.fn(),
}))

vi.mock('@/api/http', () => ({
  refreshAccessToken: vi.fn(),
}))

const demoUser: AuthUser = {
  id: 1,
  username: 'demo.employee',
  displayName: '演示员工',
  roles: ['EMPLOYEE'],
  permissions: ['TICKET_CREATE', 'TICKET_VIEW_OWN'],
}

function tokenPayload(accessToken: string): AuthTokenPayload {
  return { accessToken, tokenType: 'Bearer', expiresIn: 900, user: demoUser }
}

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('登录后把令牌与身份放进内存，不写入任何浏览器存储', async () => {
    vi.mocked(login).mockResolvedValue(tokenPayload('token-1'))
    const auth = useAuthStore()

    await auth.signIn('demo.employee', 'secret')

    expect(auth.accessToken).toBe('token-1')
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.user).toEqual(demoUser)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('hasPermission 只认后端下发的权限码', async () => {
    vi.mocked(login).mockResolvedValue(tokenPayload('token-1'))
    const auth = useAuthStore()

    await auth.signIn('demo.employee', 'secret')

    expect(auth.hasPermission('TICKET_CREATE')).toBe(true)
    expect(auth.hasPermission('USER_MANAGE')).toBe(false)
  })

  it('hasAnyPermission 在任一命中时为真', async () => {
    vi.mocked(login).mockResolvedValue(tokenPayload('token-1'))
    const auth = useAuthStore()

    await auth.signIn('demo.employee', 'secret')

    expect(auth.hasAnyPermission(['USER_MANAGE', 'TICKET_CREATE'])).toBe(true)
    expect(auth.hasAnyPermission(['USER_MANAGE', 'DASHBOARD_VIEW'])).toBe(false)
    expect(auth.hasAnyPermission([])).toBe(false)
  })

  it('restoreSession 用 Refresh Cookie 换新令牌并恢复身份', async () => {
    vi.mocked(refreshAccessToken).mockResolvedValue('token-2')
    vi.mocked(fetchCurrentUser).mockResolvedValue(demoUser)
    const auth = useAuthStore()

    await expect(auth.restoreSession()).resolves.toBe(true)

    expect(auth.accessToken).toBe('token-2')
    expect(auth.user).toEqual(demoUser)
  })

  it('loadCurrentUser 允许页面原位重试当前身份请求', async () => {
    vi.mocked(fetchCurrentUser).mockResolvedValue(demoUser)
    const auth = useAuthStore()

    await auth.loadCurrentUser()

    expect(fetchCurrentUser).toHaveBeenCalledTimes(1)
    expect(auth.user).toEqual(demoUser)
  })

  it('恢复失败时清空身份，不留下半登录状态', async () => {
    vi.mocked(refreshAccessToken).mockRejectedValue(new Error('session invalid'))
    const auth = useAuthStore()

    await expect(auth.restoreSession()).resolves.toBe(false)
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.accessToken).toBeNull()
  })

  it('restoreOnce 只尝试一次，避免未登录时每次跳转都打后端', async () => {
    vi.mocked(refreshAccessToken).mockRejectedValue(new Error('session invalid'))
    const auth = useAuthStore()

    await auth.restoreOnce()
    await auth.restoreOnce()

    expect(vi.mocked(refreshAccessToken)).toHaveBeenCalledTimes(1)
  })

  it('退出时即使后端调用失败也清空本地身份', async () => {
    vi.mocked(login).mockResolvedValue(tokenPayload('token-1'))
    vi.mocked(logout).mockRejectedValue(new Error('network down'))
    const auth = useAuthStore()
    await auth.signIn('demo.employee', 'secret')

    await expect(auth.signOut()).rejects.toThrow()

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.accessToken).toBeNull()
  })
})
