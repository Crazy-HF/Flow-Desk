import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  getRole,
  grantRolePermissions,
  listPermissions,
  listRolePermissionGrants,
  listRoles,
} from '@/api/rbac'
import type { RoleDetail } from '@/api/rbac'
import RolePermissionGrantView from './RolePermissionGrantView.vue'

vi.mock('@/api/rbac', () => ({
  listRoles: vi.fn(),
  getRole: vi.fn(),
  listPermissions: vi.fn(),
  listRolePermissionGrants: vi.fn(),
  grantRolePermissions: vi.fn(),
  revokeRolePermission: vi.fn(),
  clearRolePermissions: vi.fn(),
}))

const adminRole: RoleDetail = {
  id: 3,
  code: 'SYSTEM_ADMIN',
  name: '系统管理员',
  description: undefined,
  createdAt: undefined,
  permissionIds: [15],
}

function pageOf<T>(items: T[]) {
  return { items, page: 1, size: 20, totalElements: items.length, totalPages: 1 }
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((button) => button.text().trim() === text)
}

async function mountPage(query: Record<string, string> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/role-permissions',
        name: 'admin-role-permissions',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push({ name: 'admin-role-permissions', query })
  await router.isReady()

  const wrapper = mount(RolePermissionGrantView, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('RolePermissionGrantView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listRoles).mockResolvedValue(pageOf([adminRole]) as never)
    vi.mocked(getRole).mockResolvedValue(adminRole as never)
    vi.mocked(listPermissions).mockResolvedValue(pageOf([]) as never)
    vi.mocked(listRolePermissionGrants).mockResolvedValue(
      pageOf([
        {
          roleId: 3,
          roleCode: 'SYSTEM_ADMIN',
          permissionId: 15,
          permissionCode: 'RBAC_MANAGE',
          permissionName: '角色与权限管理',
          grantedBy: null,
          grantedAt: null,
        },
        {
          roleId: 3,
          roleCode: 'SYSTEM_ADMIN',
          permissionId: 10,
          permissionCode: 'USER_MANAGE',
          permissionName: '用户管理',
          grantedBy: 3,
          grantedAt: '2026-09-23T08:00:00Z',
        },
      ]) as never,
    )
  })

  it('未选择角色时只给空态，且不请求授权列表', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('先选择要授权的角色')
    expect(listRolePermissionGrants).not.toHaveBeenCalled()
  })

  it('带 roleId 打开时按该角色取关系，并渲染授权来源', async () => {
    const { wrapper } = await mountPage({ roleId: '3' })

    expect(listRolePermissionGrants).toHaveBeenCalledWith({ pageNo: 1, pageSize: 20, roleId: 3 })
    const text = wrapper.text()
    expect(text).toContain('角色与权限管理')
    expect(text).toContain('RBAC_MANAGE')
    expect(text).toContain('授权人 #3')
    expect(text).toContain('迁移预置')
  })

  it('SYSTEM_ADMIN 的 RBAC_MANAGE 授权不可撤销，清空入口也被禁用', async () => {
    const { wrapper } = await mountPage({ roleId: '3' })
    const rows = wrapper.findAll('.grant-row')
    const protectedRow = rows.find((row) => row.text().includes('RBAC_MANAGE'))
    const normalRow = rows.find((row) => row.text().includes('USER_MANAGE'))

    const protectedRevoke = protectedRow?.find('button[aria-label="撤销"]')
    const normalRevoke = normalRow?.find('button[aria-label="撤销"]')

    expect(protectedRevoke?.attributes('disabled')).toBeDefined()
    expect(normalRevoke?.attributes('disabled')).toBeUndefined()
    expect(buttonByText(wrapper, '清空全部权限')?.attributes('disabled')).toBeDefined()
  })

  it('没有选权限时提交被拦下，不发请求', async () => {
    const { wrapper } = await mountPage({ roleId: '3' })

    await buttonByText(wrapper, '批量授予')?.trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('请先选择角色与至少一个权限')
    expect(grantRolePermissions).not.toHaveBeenCalled()
  })
})
