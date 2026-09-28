import { flushPromises, mount } from '@vue/test-utils'
import type { DOMWrapper, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { listPermissions, listRoles } from '@/api/rbac'
import type { RoleDetail } from '@/api/rbac'
import RoleListView from './RoleListView.vue'

vi.mock('@/api/rbac', () => ({
  listRoles: vi.fn(),
  listPermissions: vi.fn(),
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
  description: null,
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

  it('行内「权限」把角色带进角色权限授权页', async () => {
    const { wrapper, router } = await mountPage()
    const rows = wrapper.findAll('tbody tr')
    const supportRow = rows.find((row) => row.text().includes('IT_SUPPORT'))

    await (supportRow ? buttonByLabel(supportRow, '维护角色权限') : undefined)?.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-role-permissions')
    expect(router.currentRoute.value.query.roleId).toBe('2')
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
