import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import { useAuthStore } from '@/stores/auth'
import HomeView from './HomeView.vue'

describe('HomeView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('只展示当前账号确实具备的能力', () => {
    const auth = useAuthStore()
    auth.user = {
      id: 1,
      username: 'demo.it',
      displayName: '演示 IT 支持人员',
      roles: ['IT_SUPPORT'],
      permissions: ['TICKET_VIEW_QUEUE', 'TICKET_PROCESS'],
    }

    const wrapper = mount(HomeView)
    const text = wrapper.text()

    expect(text).toContain('查看待受理队列')
    expect(text).toContain('处理工单')
    expect(text).not.toContain('用户管理')
    expect(text).not.toContain('创建工单')
  })

  it('没有任何能力时给出提示而不是空白列表', () => {
    const auth = useAuthStore()
    auth.user = {
      id: 2,
      username: 'demo.nobody',
      displayName: '无能力账号',
      roles: [],
      permissions: [],
    }

    const wrapper = mount(HomeView)

    expect(wrapper.text()).toContain('该账号没有任何业务能力')
  })
})
