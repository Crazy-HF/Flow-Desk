import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import FoundationView from './FoundationView.vue'

describe('FoundationView', () => {
  it('explains that only the engineering foundation exists', () => {
    const wrapper = mount(FoundationView)

    expect(wrapper.get('h1').text()).toBe('工程底座已启动')
    expect(wrapper.text()).toContain('业务页面将在对应纵向里程碑中逐步实现')
  })
})
