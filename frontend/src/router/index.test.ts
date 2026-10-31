import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { refreshAccessToken } from '@/api/http'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/http', () => ({
  refreshAccessToken: vi.fn(),
}))

function signInAs(permissions: string[]): void {
  useAuthStore().user = {
    id: 1,
    username: 'demo.employee',
    displayName: '演示员工',
    roles: ['EMPLOYEE'],
    permissions,
  }
}

describe('路由守卫', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('未登录时回到登录页并记住原地址', async () => {
    vi.mocked(refreshAccessToken).mockRejectedValue(new Error('session invalid'))

    await router.push('/tickets')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/tickets')
  })

  it('具备所需权限时放行', async () => {
    signInAs(['TICKET_VIEW_OWN'])

    await router.push('/tickets')

    expect(router.currentRoute.value.name).toBe('tickets')
  })

  it('缺少权限时进入 403 而不是放行', async () => {
    signInAs(['TICKET_CREATE'])

    await router.push('/admin/users')

    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('用户详情不再是占位页，仍要求 USER_MANAGE', async () => {
    signInAs(['USER_MANAGE'])

    await router.push('/admin/users/4')

    expect(router.currentRoute.value.name).toBe('admin-user-detail')
    expect(router.currentRoute.value.meta.plannedTask).toBeUndefined()
    expect(router.currentRoute.value.meta.permission).toBe('USER_MANAGE')
  })

  it('没有 USER_MANAGE 时不能直接打开用户详情', async () => {
    await router.push('/login')
    signInAs(['TICKET_CREATE'])

    await router.push('/admin/users/4')

    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('声明多条可选权限时，任一命中即可放行', async () => {
    signInAs(['TICKET_VIEW_PARTICIPATED'])

    await router.push('/tickets')

    expect(router.currentRoute.value.name).toBe('tickets')
  })
})
