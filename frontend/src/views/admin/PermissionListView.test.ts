import { flushPromises, mount } from '@vue/test-utils'
import type { DOMWrapper, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { listPermissions } from '@/api/rbac'
import type { PermissionDetail } from '@/api/rbac'
import PermissionListView from './PermissionListView.vue'

vi.mock('@/api/rbac', () => ({
  listPermissions: vi.fn(),
  createPermission: vi.fn(),
  updatePermission: vi.fn(),
  deletePermission: vi.fn(),
}))

const rbacPermission: PermissionDetail = {
  id: 15,
  code: 'RBAC_MANAGE',
  name: '角色与权限管理',
  description: '维护角色、权限与授权关系',
  createdAt: '2026-09-21T08:00:00Z',
  roleIds: [3],
}

const ticketPermission: PermissionDetail = {
  id: 1,
  code: 'TICKET_CREATE',
  name: '创建工单',
  description: undefined,
  createdAt: '2026-09-20T08:00:00Z',
  roleIds: [1, 3],
}

function pageOf(items: PermissionDetail[]) {
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
  const wrapper = mount(PermissionListView)
  await flushPromises()
  return wrapper
}

describe('PermissionListView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listPermissions).mockResolvedValue(
      pageOf([rbacPermission, ticketPermission]) as never,
    )
  })

  it('列出权限编码、名称与引用角色数', async () => {
    const wrapper = await mountPage()
    const text = wrapper.text()

    expect(text).toContain('RBAC_MANAGE')
    expect(text).toContain('角色与权限管理')
    expect(text).toContain('TICKET_CREATE')
    expect(text).toContain('未填写')
  })

  it('受保护权限带内置标记，且删除入口被禁用', async () => {
    const wrapper = await mountPage()
    const rows = wrapper.findAll('tbody tr')
    const protectedRow = rows.find((row) => row.text().includes('RBAC_MANAGE'))
    const normalRow = rows.find((row) => row.text().includes('TICKET_CREATE'))

    expect(protectedRow?.text()).toContain('内置')
    expect(protectedRow?.text()).toContain('不能删除')

    const protectedDelete = protectedRow ? buttonByLabel(protectedRow, '删除权限') : undefined
    const normalDelete = normalRow ? buttonByLabel(normalRow, '删除权限') : undefined

    expect(protectedDelete?.attributes('disabled')).toBeDefined()
    expect(normalDelete?.attributes('disabled')).toBeUndefined()
  })

  it('按权限码检索时把 keyword 交给远程接口', async () => {
    const wrapper = await mountPage()
    await wrapper.get('input').setValue('TICKET')
    await buttonByText(wrapper, '查询')?.trigger('click')
    await flushPromises()

    expect(listPermissions).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: 'TICKET', pageNo: 1, pageSize: 20 }),
    )
  })

  it('没有权限时用空态说明新增数据不会自动产生访问能力', async () => {
    vi.mocked(listPermissions).mockResolvedValue(pageOf([]) as never)

    const wrapper = await mountPage()

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.text()).toContain('还没有权限')
  })
})
