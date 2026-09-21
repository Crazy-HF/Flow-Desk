import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'

import App from './App.vue'
import LoginView from './views/auth/LoginView.vue'

describe('App', () => {
  it('未登录时只渲染登录页，不显示账号栏', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', name: 'login', component: LoginView }],
    })

    await router.push('/login')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [createPinia(), router],
      },
    })

    expect(wrapper.get('h1').text()).toBe('登录')
    expect(wrapper.find('.app-header').exists()).toBe(false)
  })
})
