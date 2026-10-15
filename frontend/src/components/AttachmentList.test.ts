import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { ElImage } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchAttachmentContent, saveBlob } from '@/api/attachments'
import type { TicketAttachmentSummary } from '@/api/attachments'
import AttachmentList from './AttachmentList.vue'

/**
 * 详情附件区的用例（`docs/api-design.md` 5.4 的 `attachments[]`）。
 *
 * <p>两条硬约束在这里钉住：**内容一律经后端授权代理取回**（下载与预览都走
 * `fetchAttachmentContent`，没有一条直链），以及**能不能预览由服务端的 `inlinePreviewable`
 * 决定**——前端不自己维护一份类型白名单，否则白名单一改，界面就会出现一个点了没反应的入口。</p>
 */

vi.mock('@/api/attachments', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/attachments')>()
  return { ...actual, fetchAttachmentContent: vi.fn(), saveBlob: vi.fn() }
})

function attachment(overrides: Partial<TicketAttachmentSummary> = {}): TicketAttachmentSummary {
  return {
    id: 1,
    originalName: 'log.txt',
    contentType: 'text/plain',
    sizeBytes: 2048,
    uploadedBy: { id: 9, displayName: '演示员工' },
    createdAt: '2026-10-14T01:00:00Z',
    inlinePreviewable: true,
    ...overrides,
  }
}

function blobOf(type: string, text = ''): Blob {
  return { type, text: async () => text } as unknown as Blob
}

function makeWrapper(attachments: TicketAttachmentSummary[]): VueWrapper {
  return mount(AttachmentList, { props: { attachments } })
}

function buttonByLabel(wrapper: VueWrapper, prefix: string) {
  return wrapper
    .findAll('button')
    .find((button) => (button.attributes('aria-label') ?? '').startsWith(prefix))
}

describe('工单附件区', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.defineProperty(URL, 'createObjectURL', {
      value: vi.fn(() => 'blob:flowdesk-test'),
      configurable: true,
      writable: true,
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      value: vi.fn(),
      configurable: true,
      writable: true,
    })
  })

  it('每一行都写明谁上传的、多大、什么时候', async () => {
    vi.mocked(fetchAttachmentContent).mockResolvedValue(blobOf('text/plain', 'hello'))
    const wrapper = makeWrapper([attachment()])
    await flushPromises()

    expect(wrapper.text()).toContain('log.txt')
    expect(wrapper.text()).toContain('2.0 KB')
    expect(wrapper.text()).toContain('演示员工')
    // 时间用本地化格式，这里只要求它出现在同一行里，不钉死格式
    expect(wrapper.find('.attachment-list__sub').text()).toContain('2026')
  })

  it('只有图片会额外取一次内容做缩略图，其余类型在列表里不取内容', async () => {
    vi.mocked(fetchAttachmentContent).mockResolvedValue(blobOf('image/png'))
    const wrapper = makeWrapper([
      attachment({ id: 1, originalName: 'shot.png', contentType: 'image/png' }),
      attachment({ id: 2, originalName: 'report.pdf', contentType: 'application/pdf' }),
    ])
    await flushPromises()

    expect(vi.mocked(fetchAttachmentContent)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(fetchAttachmentContent)).toHaveBeenCalledWith(1, true)

    const image = wrapper.findComponent(ElImage)
    expect(image.props('src')).toBe('blob:flowdesk-test')
    // 灯箱用的是同一个已授权取回的 blob 地址，不是一条直链
    expect(image.props('previewSrcList')).toEqual(['blob:flowdesk-test'])
  })

  it('预览入口只出现在服务端标记为可内联的类型上', async () => {
    const wrapper = makeWrapper([
      attachment({ id: 1, originalName: 'report.pdf', contentType: 'application/pdf' }),
      attachment({ id: 2, originalName: 'plan.docx', contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', inlinePreviewable: false }),
    ])
    await flushPromises()

    expect(buttonByLabel(wrapper, '预览 report.pdf')).toBeDefined()
    // Office 文档降级为下载：界面不摆一个后端一定会拒绝的入口
    expect(buttonByLabel(wrapper, '预览 plan.docx')).toBeUndefined()
    expect(buttonByLabel(wrapper, '下载 plan.docx')).toBeDefined()
  })

  it('下载走 attachment 分支，并用已授权的 blob 另存', async () => {
    const blob = blobOf('application/pdf')
    vi.mocked(fetchAttachmentContent).mockResolvedValue(blob)
    const wrapper = makeWrapper([attachment({ id: 5, originalName: 'report.pdf', contentType: 'application/pdf' })])
    await flushPromises()

    await buttonByLabel(wrapper, '下载 report.pdf')?.trigger('click')
    await flushPromises()

    expect(vi.mocked(fetchAttachmentContent)).toHaveBeenCalledWith(5, false)
    expect(saveBlob).toHaveBeenCalledWith(blob, 'report.pdf')
  })

  it('PDF 预览请求内联，并在弹窗里渲染内容', async () => {
    vi.mocked(fetchAttachmentContent).mockResolvedValue(blobOf('application/pdf'))
    const wrapper = makeWrapper([attachment({ id: 6, originalName: 'report.pdf', contentType: 'application/pdf' })])
    await flushPromises()

    await buttonByLabel(wrapper, '预览 report.pdf')?.trigger('click')
    await flushPromises()

    expect(vi.mocked(fetchAttachmentContent)).toHaveBeenCalledWith(6, true)
    expect(wrapper.find('iframe').attributes('src')).toBe('blob:flowdesk-test')
  })

  it('纯文本预览把内容直接摊开，过长时说明只显示了前一段', async () => {
    vi.mocked(fetchAttachmentContent).mockResolvedValue(blobOf('text/plain', 'a'.repeat(20_050)))
    const wrapper = makeWrapper([attachment({ id: 7, originalName: 'notes.txt', contentType: 'text/plain' })])
    await flushPromises()

    await buttonByLabel(wrapper, '预览 notes.txt')?.trigger('click')
    await flushPromises()

    expect(wrapper.find('.attachment-preview__text').text()).toHaveLength(20_000)
    expect(wrapper.text()).toContain('完整内容请下载查看')
  })

  it('取内容失败时在弹窗里说明原因，不静默留白', async () => {
    vi.mocked(fetchAttachmentContent).mockRejectedValue({
      response: { data: { code: 'ATTACHMENT_NOT_FOUND', message: 'gone' } },
    })
    const wrapper = makeWrapper([attachment({ id: 8, originalName: 'report.pdf', contentType: 'application/pdf' })])
    await flushPromises()

    await buttonByLabel(wrapper, '预览 report.pdf')?.trigger('click')
    await flushPromises()

    expect(wrapper.find('.attachment-preview__error').text()).toBe('附件不存在或不可见')
    expect(wrapper.find('iframe').exists()).toBe(false)
  })

  it('缩略图取不到就退回类型图标，不打断整页', async () => {
    vi.mocked(fetchAttachmentContent).mockRejectedValue(new Error('offline'))
    const wrapper = makeWrapper([attachment({ id: 9, originalName: 'shot.png', contentType: 'image/png' })])
    await flushPromises()

    expect(wrapper.findComponent(ElImage).exists()).toBe(false)
    expect(wrapper.find('.attachment-list__icon').exists()).toBe(true)
    // 真正要做的事仍然可用
    expect(buttonByLabel(wrapper, '下载 shot.png')).toBeDefined()
  })
})
