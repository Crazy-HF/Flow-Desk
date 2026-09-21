import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { AuthUser } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import HomeView from './HomeView.vue'

const supportUser: AuthUser = {
  id: 1,
  username: 'demo.it',
  displayName: '演示 IT 支持人员',
  roles: ['IT_SUPPORT'],
  permissions: ['TICKET_VIEW_QUEUE', 'TICKET_PROCESS'],
}

function mountHome() {
  return mount(HomeView, {
    global: {
      stubs: {
        RouterLink: {
          template: '<a><slot /></a>',
        },
      },
    },
  })
}

describe('HomeView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('只展示当前账号确实具备的工作入口', () => {
    const auth = useAuthStore()
    auth.user = supportUser

    const wrapper = mountHome()
    const text = wrapper.text()

    expect(text).not.toContain('普通员工')
    expect(text).toContain('IT 支持人员')
    expect(text).toContain('工单')
    expect(text).not.toContain('用户管理')
    expect(text).not.toContain('新建工单')
  })

  it('没有任何入口时复用空态组件并说明恢复方式', () => {
    const auth = useAuthStore()
    auth.user = {
      id: 2,
      username: 'demo.nobody',
      displayName: '无能力账号',
      roles: [],
      permissions: [],
    }

    const wrapper = mountHome()

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂无可用入口')
    expect(wrapper.text()).toContain('请联系管理员分配角色')
  })

  it('身份尚未恢复时展示与最终布局一致的加载骨架', async () => {
    const auth = useAuthStore()
    let completeLoad!: () => void
    vi.spyOn(auth, 'loadCurrentUser').mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          completeLoad = () => {
            auth.user = supportUser
            resolve()
          }
        }),
    )

    const wrapper = mountHome()

    expect(wrapper.find('[aria-busy="true"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('正在恢复你的身份与工作入口')

    completeLoad()
    await flushPromises()
    expect(wrapper.text()).toContain('你的工作入口')
  })

  it('身份加载失败时可原位重试', async () => {
    const auth = useAuthStore()
    vi.spyOn(auth, 'loadCurrentUser')
      .mockRejectedValueOnce(new Error('network down'))
      .mockImplementationOnce(async () => {
        auth.user = supportUser
      })

    const wrapper = mountHome()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('身份信息加载失败')

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('你的工作入口')
  })
})
