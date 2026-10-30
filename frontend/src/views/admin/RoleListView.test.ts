import { flushPromises, mount } from '@vue/test-utils'
import type { DOMWrapper, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getRole, listPermissions, listRolePermissionGrants, listRoles } from '@/api/rbac'
import type { RoleDetail } from '@/api/rbac'
import RoleListView from './RoleListView.vue'

vi.mock('@/api/rbac', () => ({
  listRoles: vi.fn(),
  listPermissions: vi.fn(),
  getRole: vi.fn(),
  listRolePermissionGrants: vi.fn(),
  grantRolePermissions: vi.fn(),
  revokeRolePermission: vi.fn(),
  clearRolePermissions: vi.fn(),
  createRole: vi.fn(),
  updateRole: vi.fn(),
  deleteRole: vi.fn(),
}))

const adminRole: RoleDetail = {
  id: 3,
  code: 'SYSTEM_ADMIN',
  name: '系统管理员',
  description: '内置角色',
  createdAt: '2026-09-20T08:00:00Z',
  permissionIds: [10, 11, 12, 15],
}

const supportRole: RoleDetail = {
  id: 2,
  code: 'IT_SUPPORT',
  name: 'IT 支持人员',
  description: undefined,
  createdAt: '2026-09-20T08:00:00Z',
  permissionIds: [4, 5],
}

function pageOf(items: RoleDetail[]) {
  return { items, page: 1, size: 20, totalElements: items.length, totalPages: 1 }
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((button) => button.text().trim() === text)
}

/** 操作列是图标按钮：可访问名走 aria-label，因此用例按标签定位。 */
function buttonByLabel(wrapper: VueWrapper | DOMWrapper<Element>, label: string) {
  return wrapper.find(`button[aria-label="${label}"]`)
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/roles', name: 'admin-roles', component: { template: '<div />' } },
      {
        path: '/admin/role-permissions',
        name: 'admin-role-permissions',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push('/admin/roles')
  await router.isReady()

  const wrapper = mount(RoleListView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('RoleListView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listRoles).mockResolvedValue(pageOf([adminRole, supportRole]) as never)
    vi.mocked(listPermissions).mockResolvedValue(pageOf([]) as never)
    vi.mocked(getRole).mockResolvedValue(supportRole as never)
    vi.mocked(listRolePermissionGrants).mockResolvedValue(pageOf([]) as never)
  })

  it('列出角色、描述与已授权限数量', async () => {
    const { wrapper } = await mountPage()
    const text = wrapper.text()

    expect(text).toContain('SYSTEM_ADMIN')
    expect(text).toContain('IT_SUPPORT')
    expect(text).toContain('未填写')
    expect(text).toContain('4')
  })

  it('受保护角色带内置标记，且删除入口被禁用', async () => {
    const { wrapper } = await mountPage()
    const rows = wrapper.findAll('tbody tr')
    const protectedRow = rows.find((row) => row.text().includes('SYSTEM_ADMIN'))
    const normalRow = rows.find((row) => row.text().includes('IT_SUPPORT'))

    expect(protectedRow?.text()).toContain('内置')
    expect(protectedRow?.text()).toContain('不能删除')

    const protectedDelete = protectedRow ? buttonByLabel(protectedRow, '删除角色') : undefined
    const normalDelete = normalRow ? buttonByLabel(normalRow, '删除角色') : undefined

    expect(protectedDelete?.attributes('disabled')).toBeDefined()
    expect(normalDelete?.attributes('disabled')).toBeUndefined()
  })

  it('行内「分配权限」打开抽屉，并保留完整授权页的深链', async () => {
    const { wrapper } = await mountPage()
    const rows = wrapper.findAll('tbody tr')
    const supportRow = rows.find((row) => row.text().includes('IT_SUPPORT'))

    await (supportRow ? buttonByLabel(supportRow, '分配权限') : undefined)?.trigger('click')
    await flushPromises()

    // 抽屉打开时按角色 id 重新取详情与授权关系，不信任行上的旧快照
    expect(getRole).toHaveBeenCalledWith(2)
    expect(listRolePermissionGrants).toHaveBeenCalledWith(
      expect.objectContaining({ roleId: 2 }),
    )
    expect(wrapper.text()).toContain('当前权限')
    expect(wrapper.text()).toContain('添加权限')

    // 原授权页与深链入口仍在：抽屉里直接给出带角色 id 的链接
    expect(wrapper.get('.grant-drawer__full-link').attributes('href')).toBe(
      '/admin/role-permissions?roleId=2',
    )
  })

  it('SYSTEM_ADMIN 的 RBAC_MANAGE 授权在抽屉里不可撤销，清空入口也禁用', async () => {
    vi.mocked(getRole).mockResolvedValue(adminRole as never)
    vi.mocked(listRolePermissionGrants).mockResolvedValue({
      items: [
        {
          roleId: 3,
          roleCode: 'SYSTEM_ADMIN',
          permissionId: 15,
          permissionName: '角色与权限管理',
          permissionCode: 'RBAC_MANAGE',
        },
      ],
      page: 1,
      size: 100,
      totalElements: 1,
      totalPages: 1,
    } as never)

    const { wrapper } = await mountPage()
    const row = wrapper.findAll('tbody tr').find((item) => item.text().includes('SYSTEM_ADMIN'))

    await row?.find('[aria-label="分配权限"]').trigger('click')
    await flushPromises()

    const revoke = wrapper
      .findAll('.grant-drawer__row button')
      .find((button) => button.text().trim() === '撤销')
    expect(revoke?.attributes('disabled')).toBeDefined()

    const clear = buttonByText(wrapper, '清空全部')
    expect(clear?.attributes('disabled')).toBeDefined()
  })

  it('没有角色时用空态说明内置角色的来源', async () => {
    vi.mocked(listRoles).mockResolvedValue(pageOf([]) as never)

    const { wrapper } = await mountPage()

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.text()).toContain('还没有自定义角色')
  })

  it('加载失败时给出稳定错误码文案并可重试', async () => {
    vi.mocked(listRoles)
      .mockRejectedValueOnce({ response: { data: { code: 'RBAC_CONFLICT' } } })
      .mockResolvedValueOnce(pageOf([supportRole]) as never)

    const { wrapper } = await mountPage()

    expect(wrapper.get('[role="alert"]').text()).toContain('已拒绝')

    await buttonByText(wrapper, '重新加载')?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('IT_SUPPORT')
  })
})
