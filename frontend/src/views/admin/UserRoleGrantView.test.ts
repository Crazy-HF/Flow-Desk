import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { grantUserRoles, listRoles, listUserRoleGrants } from '@/api/rbac'
import { getUser, listUsers } from '@/api/users'
import type { UserDetail } from '@/api/users'
import UserRoleGrantView from './UserRoleGrantView.vue'

vi.mock('@/api/rbac', () => ({
  listRoles: vi.fn(),
  listUserRoleGrants: vi.fn(),
  grantUserRoles: vi.fn(),
  revokeUserRole: vi.fn(),
  clearUserRoles: vi.fn(),
}))

vi.mock('@/api/users', () => ({
  listUsers: vi.fn(),
  getUser: vi.fn(),
}))

const employee: UserDetail = {
  id: 7,
  username: 'employee',
  displayName: '演示员工',
  status: 'ENABLED',
  roles: [],
  createdAt: '2026-09-20T08:00:00Z',
  updatedAt: null,
  version: 0,
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
      { path: '/admin/user-roles', name: 'admin-user-roles', component: { template: '<div />' } },
      {
        path: '/admin/role-permissions',
        name: 'admin-role-permissions',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push({ name: 'admin-user-roles', query })
  await router.isReady()

  const wrapper = mount(UserRoleGrantView, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('UserRoleGrantView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listRoles).mockResolvedValue(
      pageOf([{ id: 2, code: 'IT_SUPPORT', name: 'IT 支持人员', description: null, createdAt: null, permissionIds: [] }]) as never,
    )
    vi.mocked(listUsers).mockResolvedValue(pageOf([employee]) as never)
    vi.mocked(getUser).mockResolvedValue(employee as never)
    vi.mocked(listUserRoleGrants).mockResolvedValue(
      pageOf([
        {
          userId: 7,
          username: 'employee',
          roleId: 2,
          roleCode: 'IT_SUPPORT',
          roleName: 'IT 支持人员',
          grantedBy: 3,
          grantedAt: '2026-09-23T08:00:00Z',
        },
        {
          userId: 7,
          username: 'employee',
          roleId: 1,
          roleCode: 'EMPLOYEE',
          roleName: '普通员工',
          grantedBy: null,
          grantedAt: null,
        },
      ]) as never,
    )
  })

  it('未选择用户时只给空态，且不请求必然失败的授权列表', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('先选择要授权的用户')
    expect(listUserRoleGrants).not.toHaveBeenCalled()
  })

  it('从用户管理带过来的 userId 直接打开该用户的关系列表', async () => {
    const { wrapper } = await mountPage({ userId: '7' })

    expect(getUser).toHaveBeenCalledWith(7)
    expect(listUserRoleGrants).toHaveBeenCalledWith({ pageNo: 1, pageSize: 20, userId: 7 })

    const text = wrapper.text()
    expect(text).toContain('IT 支持人员')
    expect(text).toContain('IT_SUPPORT')
    expect(text).toContain('授权人 #3')
    // 迁移预置关系没有授权人，界面必须说清楚而不是留空
    expect(text).toContain('迁移预置')
  })

  it('每条关系都有独立的撤销入口', async () => {
    const { wrapper } = await mountPage({ userId: '7' })
    const rows = wrapper.findAll('.grant-row')

    expect(rows).toHaveLength(2)
    expect(rows.every((row) => row.find('button[aria-label="撤销"]').exists())).toBe(true)
  })

  it('没有选角色时提交被拦下，不发请求', async () => {
    const { wrapper } = await mountPage({ userId: '7' })

    await buttonByText(wrapper, '批量授予')?.trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('请先选择用户与至少一个角色')
    expect(grantUserRoles).not.toHaveBeenCalled()
  })

  it('用户没有任何角色时给出零角色合法的空态', async () => {
    vi.mocked(listUserRoleGrants).mockResolvedValue(pageOf([]) as never)

    const { wrapper } = await mountPage({ userId: '7' })

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.text()).toContain('该用户当前没有角色')
  })
})
