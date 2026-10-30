import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { grantUserRoles, listRoles, listUserRoleGrants } from '@/api/rbac'
import { disableUser, getActiveTicketAssignments, getHandoffCandidates, getUser, listUsers, replaceUserRoles } from '@/api/users'
import type { UserDetail } from '@/api/users'
import { useAuthStore } from '@/stores/auth'
import UserListView from './UserListView.vue'

vi.mock('@/api/users', () => ({
  listUsers: vi.fn(),
  getUser: vi.fn(),
  createUser: vi.fn(),
  updateUser: vi.fn(),
  enableUser: vi.fn(),
  disableUser: vi.fn(),
  getActiveTicketAssignments: vi.fn(),
  getHandoffCandidates: vi.fn(),
  replaceUserRoles: vi.fn(),
  resetUserPassword: vi.fn(),
}))

vi.mock('@/api/rbac', () => ({
  listRoles: vi.fn(),
  listUserRoleGrants: vi.fn(),
  grantUserRoles: vi.fn(),
  revokeUserRole: vi.fn(),
  clearUserRoles: vi.fn(),
}))

const adminAccount: UserDetail = {
  id: 3,
  username: 'admin',
  displayName: '演示管理员',
  status: 'ENABLED',
  roles: [{ id: 3, code: 'SYSTEM_ADMIN', name: '系统管理员' }],
  createdAt: '2026-09-23T08:00:00Z',
  updatedAt: '2026-09-23T08:00:00Z',
  version: 1,
}

const employeeAccount: UserDetail = {
  id: 4,
  username: 'employee',
  displayName: '演示员工',
  status: 'ENABLED',
  roles: [],
  createdAt: undefined,
  updatedAt: undefined,
  version: 0,
}

function pageOf(items: UserDetail[]) {
  return { items, page: 1, size: 20, totalElements: items.length, totalPages: 1 }
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((button) => button.text().trim() === text)
}

function signIn(permissions: string[]): void {
  useAuthStore().user = {
    id: 1,
    username: 'demo.admin',
    displayName: '演示管理员',
    roles: ['SYSTEM_ADMIN'],
    permissions,
  }
}

async function mountPage(query: Record<string, string> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/users', name: 'admin-users', component: { template: '<div />' } },
      { path: '/admin/user-roles', name: 'admin-user-roles', component: { template: '<div />' } },
    ],
  })
  await router.push({ name: 'admin-users', query })
  await router.isReady()

  const wrapper = mount(UserListView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('UserListView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listRoles).mockResolvedValue(pageOf([]) as never)
    vi.mocked(listUserRoleGrants).mockResolvedValue(pageOf([]) as never)
    vi.mocked(listUsers).mockResolvedValue(pageOf([adminAccount, employeeAccount]) as never)
  })

  it('列出账号与角色，零角色写明没有业务权限', async () => {
    signIn(['USER_MANAGE', 'RBAC_MANAGE'])

    const { wrapper } = await mountPage()
    const text = wrapper.text()

    expect(text).toContain('admin')
    expect(text).toContain('系统管理员')
    expect(text).toContain('无业务权限')
    // 状态用开关表达，开关自带读屏说明（谁的状态、当前是启用还是停用）
    expect(wrapper.html()).toContain('演示管理员的账号状态，当前启用')
    expect(wrapper.findAll('[role="switch"]')).toHaveLength(2)
  })

  it('从授权页带 userId 进入时打开该用户的角色替换弹窗', async () => {
    signIn(['USER_MANAGE', 'RBAC_MANAGE', 'TICKET_ADMIN_HANDOFF'])
    vi.mocked(getUser).mockResolvedValue({
      ...employeeAccount,
      roles: [{ id: 2, code: 'IT_SUPPORT', name: 'IT 支持人员' }],
    } as never)

    const { wrapper } = await mountPage({ userId: '4' })
    await vi.waitFor(() => expect(getUser).toHaveBeenCalledWith(4))
    await flushPromises()
    expect(wrapper.html()).toContain('调整用户角色')
    expect(wrapper.html()).toContain('演示员工（employee）的角色将替换')
  })

  it('启用的管理员行带上受保护标记与原因', async () => {
    signIn(['USER_MANAGE'])

    const { wrapper } = await mountPage()
    const rows = wrapper.findAll('tbody tr')
    const adminRow = rows.find((row) => row.text().includes('演示管理员'))
    const employeeRow = rows.find((row) => row.text().includes('演示员工'))

    expect(adminRow?.text()).toContain('管理员保护')
    expect(adminRow?.text()).toContain('至少保留一个启用管理员')
    expect(employeeRow?.text()).not.toContain('管理员保护')
  })

  it('没有账号时用空态，而不是空白表格', async () => {
    signIn(['USER_MANAGE'])
    vi.mocked(listUsers).mockResolvedValue(pageOf([]) as never)

    const { wrapper } = await mountPage()

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.text()).toContain('还没有账号')
    expect(wrapper.find('tbody').exists()).toBe(false)
  })

  it('加载失败时给出稳定错误码文案，并可原位重试', async () => {
    signIn(['USER_MANAGE'])
    vi.mocked(listUsers)
      .mockRejectedValueOnce({ response: { data: { code: 'ACCESS_DENIED' } } })
      .mockResolvedValueOnce(pageOf([employeeAccount]) as never)

    const { wrapper } = await mountPage()

    expect(wrapper.get('[role="alert"]').text()).toContain('当前账号没有执行该操作的权限')

    await buttonByText(wrapper, '重新加载')?.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('演示员工')
  })

  it('行内「分配角色」打开抽屉，按用户取授权并在保存后刷新列表', async () => {
    signIn(['USER_MANAGE', 'RBAC_MANAGE'])
    vi.mocked(listRoles).mockResolvedValue({
      items: [{ id: 2, code: 'IT_SUPPORT', name: 'IT 支持人员', permissionIds: [] }],
      page: 1,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    } as never)
    vi.mocked(getUser).mockResolvedValue(employeeAccount as never)
    vi.mocked(grantUserRoles).mockResolvedValue([
      { userId: 4, roleId: 2, roleName: 'IT 支持人员', roleCode: 'IT_SUPPORT' },
    ] as never)

    const { wrapper } = await mountPage()
    const row = wrapper.findAll('tbody tr').find((item) => item.text().includes('演示员工'))

    await row?.find('[aria-label="分配角色"]').trigger('click')
    await flushPromises()

    expect(getUser).toHaveBeenCalledWith(4)
    expect(listUserRoleGrants).toHaveBeenCalledWith(expect.objectContaining({ userId: 4 }))
    expect(wrapper.text()).toContain('当前角色')

    await wrapper.get('.grant-choice input').setValue(true)
    await buttonByText(wrapper, '保存')?.trigger('click')
    await flushPromises()

    expect(grantUserRoles).toHaveBeenCalledWith(4, [2])
    // 保存后回到同一列表：筛选与页码不重置，只重新取数
    expect(listUsers).toHaveBeenCalledTimes(2)
  })

  it('查询把筛选条件交给接口，重置后回到无筛选', async () => {
    signIn(['USER_MANAGE'])

    const { wrapper } = await mountPage()
    await wrapper.get('input').setValue('ali')
    await buttonByText(wrapper, '搜索')?.trigger('click')
    await flushPromises()

    expect(listUsers).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: 'ali', pageNo: 1, pageSize: 20 }),
    )

    await buttonByText(wrapper, '重置')?.trigger('click')
    await flushPromises()

    expect(listUsers).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 20 })
  })

  it('只有 USER_MANAGE 时不请求角色下拉，并说明角色由谁授予', async () => {
    signIn(['USER_MANAGE'])

    const { wrapper } = await mountPage()

    expect(listRoles).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('请选择角色')
  })

  it('有管理性交接权限时，停用在办负责人先展示逐单接替方案', async () => {
    signIn(['USER_MANAGE', 'TICKET_ADMIN_HANDOFF'])
    vi.mocked(getActiveTicketAssignments).mockResolvedValue([{
      ticketNo: 'FD-1', status: 'PROCESSING', assigneeId: 4,
      assigneeDisplayName: '演示员工', version: 2,
    }])
    vi.mocked(getHandoffCandidates).mockResolvedValue([{ id: 9, displayName: '接替人' }])

    const { wrapper } = await mountPage()
    const row = wrapper.findAll('tbody tr').find((item) => item.text().includes('演示员工'))
    const confirm = row?.findComponent({ name: 'ElPopconfirm' })
    expect(confirm?.exists()).toBe(true)
    confirm?.vm.$emit('confirm')
    await flushPromises()

    expect(getActiveTicketAssignments).toHaveBeenCalledWith(4)
    expect(getHandoffCandidates).toHaveBeenCalledWith('FD-1')
    expect(disableUser).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('交接在办工单')
    expect(wrapper.text()).toContain('FD-1')
  })

  it('移除 IT 角色且有在办工单时先准备交接，不提前提交角色替换', async () => {
    signIn(['USER_MANAGE', 'RBAC_MANAGE', 'TICKET_ADMIN_HANDOFF'])
    const itAccount: UserDetail = {
      ...employeeAccount,
      roles: [{ id: 2, code: 'IT_SUPPORT', name: 'IT 支持人员' }],
    }
    vi.mocked(listUsers).mockResolvedValue(pageOf([itAccount]) as never)
    vi.mocked(listRoles).mockResolvedValue({
      items: [{ id: 2, code: 'IT_SUPPORT', name: 'IT 支持人员' }],
      page: 1, size: 20, totalElements: 1, totalPages: 1,
    } as never)
    vi.mocked(getActiveTicketAssignments).mockResolvedValue([{
      ticketNo: 'FD-2', status: 'WAITING_FOR_CONFIRMATION', assigneeId: 4,
      assigneeDisplayName: '演示员工', version: 3,
    }])
    vi.mocked(getHandoffCandidates).mockResolvedValue([{ id: 9, displayName: '接替人' }])

    const { wrapper } = await mountPage()
    const vm = wrapper.vm as unknown as {
      openRoleEdit: (row: UserDetail) => void
      roleSelection: number[]
      submitRoles: () => Promise<void>
    }
    vm.openRoleEdit(itAccount)
    vm.roleSelection = []
    await vm.submitRoles()
    await flushPromises()

    expect(getActiveTicketAssignments).toHaveBeenCalledWith(4)
    expect(replaceUserRoles).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('交接在办工单')
  })
})
