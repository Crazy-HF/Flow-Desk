import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getUser } from '@/api/users'
import type { UserDetail } from '@/api/users'
import UserDetailView from './UserDetailView.vue'

vi.mock('@/api/users', () => ({ getUser: vi.fn() }))

const account: UserDetail = {
  id: 4,
  username: 'employee',
  displayName: '演示员工',
  status: 'ENABLED',
  roles: [{ id: 1, code: 'EMPLOYEE', name: '普通员工' }],
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-20T08:00:00Z',
  version: 3,
}

async function mountPage(path = '/admin/users/4') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/users', name: 'admin-users', component: { template: '<div />' } },
      { path: '/admin/users/:userId', name: 'admin-user-detail', component: UserDetailView },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(UserDetailView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('UserDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getUser).mockResolvedValue(account)
  })

  it('用后端详情展示身份、状态、角色和时间，并提供返回列表入口', async () => {
    const { wrapper } = await mountPage()

    expect(getUser).toHaveBeenCalledWith(4)
    expect(wrapper.text()).toContain('演示员工')
    expect(wrapper.text()).toContain('employee')
    expect(wrapper.text()).toContain('启用')
    expect(wrapper.text()).toContain('普通员工')
    expect(wrapper.text()).toContain('创建时间')
    expect(wrapper.text()).toContain('更新时间')
    expect(wrapper.find('a[href="/admin/users"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('密码')
  })

  it('零角色明确显示无业务权限', async () => {
    vi.mocked(getUser).mockResolvedValue({ ...account, roles: [] })

    const { wrapper } = await mountPage()

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.find('.empty-state').text()).toContain('无业务权限')
  })

  it('用户不存在和非法编号不展示旧数据', async () => {
    vi.mocked(getUser).mockRejectedValueOnce({ response: { data: { code: 'USER_NOT_FOUND' } } })
    const { wrapper, router } = await mountPage()
    expect(wrapper.text()).toContain('用户不存在')

    await router.push('/admin/users/not-a-number')
    await flushPromises()
    expect(getUser).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('演示员工')
  })

  it('加载失败可重试，刷新后更新详情', async () => {
    vi.mocked(getUser).mockRejectedValueOnce(new Error('offline'))
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain('用户信息没有加载成功')

    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(getUser).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('演示员工')
  })

  it('路由参数变化时读取新用户', async () => {
    vi.mocked(getUser).mockResolvedValueOnce(account)
      .mockResolvedValueOnce({ ...account, id: 5, username: 'it', displayName: '演示 IT' })
    const { wrapper, router } = await mountPage()

    await router.push('/admin/users/5')
    await flushPromises()

    expect(getUser).toHaveBeenLastCalledWith(5)
    expect(wrapper.text()).toContain('演示 IT')
    expect(wrapper.text()).not.toContain('演示员工')
  })
})
