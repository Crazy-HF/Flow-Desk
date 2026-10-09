import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { {{API_IMPORTS}} } from '{{API_MODULE}}'
import type { {{TYPE_DETAIL}} } from '{{API_MODULE}}'
import type { AuthUser } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import {{CLASS}}View from './{{CLASS}}View.vue'

vi.mock('{{API_MODULE}}', () => ({
{{MOCK_FACTORY}}
}))

/**
 * 身份夹具：页面的写操作入口按权限显隐，因此每个用例都必须先给出"当前是谁、有什么权限"，
 * 否则 `v-if` 把按钮整块摘掉，用例会以"找不到按钮"的方式失败而不是报权限问题。
 */
const operator: AuthUser = {
  id: 1,
  username: 'demo.admin',
  displayName: '演示管理员',
  roles: ['SYSTEM_ADMIN'],
  permissions: ['{{PERMISSION}}'],
}

/**
 * 夹具自己写：用例不依赖后端，也不依赖演示库的漂移。
 * 字段按 `{{API_MODULE}}` 的 `{{TYPE_DETAIL}}` 定义补齐。
 */
const sample: {{TYPE_DETAIL}} = {{FIXTURE}}

function pageOf(items: {{TYPE_DETAIL}}[]) {
  return { items, page: 1, size: 20, totalElements: items.length, totalPages: 1 }
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((button) => button.text().trim() === text)
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', name: 'home', component: { template: '<div />' } }],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount({{CLASS}}View, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('{{CLASS}}View', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    useAuthStore().user = operator
    vi.mocked({{API_LIST}}).mockResolvedValue(pageOf([sample]) as never)
  })

  it('渲染列表返回的行', async () => {
    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('{{FIXTURE_ASSERT}}')
  })

  it('没有数据时给空态而不是空表格', async () => {
    vi.mocked({{API_LIST}}).mockResolvedValue(pageOf([]) as never)

    const wrapper = await mountPage()

    expect(wrapper.find('.empty-state').exists()).toBe(true)
  })

  it('关键词进入查询条件', async () => {
    const wrapper = await mountPage()

    await wrapper.get('input[aria-label="{{TITLE}}关键词"]').setValue('abc')
    await buttonByText(wrapper, '查询')?.trigger('click')
    await flushPromises()

    expect(vi.mocked({{API_LIST}})).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: 'abc', pageNo: 1 }),
    )
  })

  it('加载失败时给出稳定错误码文案并可重试', async () => {
    vi.mocked({{API_LIST}})
      .mockRejectedValueOnce({ response: { data: { code: 'INTERNAL_ERROR' } } })
      .mockResolvedValueOnce(pageOf([sample]) as never)

    const wrapper = await mountPage()

    expect(wrapper.get('[role="alert"]').text()).toContain('稍后重试')

    await buttonByText(wrapper, '重新加载')?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('{{FIXTURE_ASSERT}}')
  })

  it('新建成功后关闭弹窗并刷新列表', async () => {
    vi.mocked({{API_CREATE}}).mockResolvedValue(sample as never)

    const wrapper = await mountPage()
    const callsAfterMount = vi.mocked({{API_LIST}}).mock.calls.length

    await buttonByText(wrapper, '新建')?.trigger('click')
    await flushPromises()

    // 筛选区的关键词输入与弹窗里的名称输入 placeholder 相同，必须限定在弹窗内取，
    // 否则会把值填进筛选框、提交一个空表单
    await wrapper.get('.el-dialog input[placeholder="请输入名称"]').setValue('新的一条')
    await buttonByText(wrapper, '确定')?.trigger('click')
    await flushPromises()

    expect(vi.mocked({{API_CREATE}})).toHaveBeenCalledWith({ name: '新的一条' })
    // 写操作成功后必须重新取数，否则列表停在旧数据上
    expect(vi.mocked({{API_LIST}}).mock.calls.length).toBeGreaterThan(callsAfterMount)
  })

  it('没有选中行时删除入口禁用，接口不会被调用', async () => {
    const wrapper = await mountPage()

    const deleteButton = buttonByText(wrapper, '删除')

    expect(deleteButton?.attributes('disabled')).toBeDefined()
    expect(vi.mocked({{API_DELETE}})).not.toHaveBeenCalled()
  })
})
