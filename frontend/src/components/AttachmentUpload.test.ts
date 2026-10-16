import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

import {
  ACCEPTED_ATTACHMENT_EXTENSIONS,
  MAX_ATTACHMENT_FILE_SIZE_BYTES,
  MAX_ATTACHMENTS_PER_REQUEST,
  deleteAttachment,
  uploadAttachment,
} from '@/api/attachments'
import type { AttachmentUploadResult, UploadAttachmentOptions } from '@/api/attachments'
import AttachmentUpload from './AttachmentUpload.vue'

/**
 * 上传控件的用例（`docs/api-design.md` 7.1）。
 *
 * <p>这一组盯的是"逐文件"这条设计：进度、取消、失败都按文件独立，失败文案里带文件名，
 * 数量上限只拦超出部分而不是整批拒绝。它们正是"把 5 个文件拼进一个请求"做不到的部分，
 * 所以每条都值得单独钉住。</p>
 *
 * <p>另外两条钉住"上传前就失败"和"移除即释放"：本地预检只判大小（类型白名单只在后端一处），
 * 拦下的文件根本不发请求；移除一个已上传成功的文件会调 `DELETE /attachments/{id}`，
 * 失败（含已绑定的 409）不打扰用户。</p>
 */

vi.mock('@/api/attachments', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/attachments')>()
  return { ...actual, uploadAttachment: vi.fn(), deleteAttachment: vi.fn() }
})

interface PendingUpload {
  file: File
  options: UploadAttachmentOptions | undefined
  resolve: (value: AttachmentUploadResult) => void
  reject: (reason: unknown) => void
}

const pending: PendingUpload[] = []

/** 让每个上传请求都停在"在途"，由用例决定它成功、失败还是被取消。 */
function holdUploads(): void {
  vi.mocked(uploadAttachment).mockImplementation(
    (file, options) =>
      new Promise<AttachmentUploadResult>((resolve, reject) => {
        pending.push({ file, options, resolve, reject })
        options?.signal?.addEventListener('abort', () => {
          const canceled = new Error('canceled') as Error & { code: string }
          // axios 取消错误的形状：判定只认这个码，见 isUploadCanceled
          canceled.code = 'ERR_CANCELED'
          reject(canceled)
        })
      }),
  )
}

function file(name: string, type: string): File {
  return new File(['content'], name, { type })
}

function uploadResult(id: number, name: string, type: string): AttachmentUploadResult {
  return { id, originalName: name, contentType: type, sizeBytes: 7, createdAt: '2026-10-14T01:00:00Z' }
}

function makeWrapper(): VueWrapper {
  return mount(AttachmentUpload, { props: { modelValue: [] } })
}

/** 走真实的 input 事件：控件靠 `change` 取文件，直接调内部函数测不到这条路径。 */
async function selectFiles(wrapper: VueWrapper, files: File[]): Promise<void> {
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  await input.trigger('change')
}

function buttonByLabel(wrapper: VueWrapper, prefix: string) {
  return wrapper
    .findAll('button')
    .find((button) => (button.attributes('aria-label') ?? '').startsWith(prefix))
}

function lastModelValue(wrapper: VueWrapper): number[] | undefined {
  const emitted = wrapper.emitted('update:modelValue')
  return emitted?.at(-1)?.[0] as number[] | undefined
}

describe('附件上传控件', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    pending.length = 0
    vi.spyOn(ElMessage, 'warning').mockReturnValue({ close: () => {} } as never)
    vi.spyOn(ElMessage, 'info').mockReturnValue({ close: () => {} } as never)
    vi.spyOn(ElMessage, 'error').mockReturnValue({ close: () => {} } as never)
  })

  it('一个文件一次请求，进度按文件独立，全部成功后按顺序回传 attachmentIds', async () => {
    holdUploads()
    const wrapper = makeWrapper()

    await selectFiles(wrapper, [file('a.png', 'image/png'), file('b.pdf', 'application/pdf')])

    expect(vi.mocked(uploadAttachment)).toHaveBeenCalledTimes(2)
    expect(pending.map((item) => item.file.name)).toEqual(['a.png', 'b.pdf'])

    // 只推第一个文件的进度：第二行必须还是 0%，否则进度就不是"按文件独立"的
    pending[0]?.options?.onProgress?.(40)
    await nextTick()
    expect(wrapper.text()).toContain('上传中 40%')

    pending[0]?.resolve(uploadResult(11, 'a.png', 'image/png'))
    await flushPromises()
    pending[1]?.resolve(uploadResult(12, 'b.pdf', 'application/pdf'))
    await flushPromises()

    expect(lastModelValue(wrapper)).toEqual([11, 12])
    expect(wrapper.text()).toContain('已上传')
  })

  it('失败逐条显示且文案带文件名，成功的那些仍然被回传', async () => {
    holdUploads()
    const wrapper = makeWrapper()

    await selectFiles(wrapper, [
      file('payload.exe', 'application/octet-stream'),
      file('ok.txt', 'text/plain'),
    ])

    pending[1]?.resolve(uploadResult(21, 'ok.txt', 'text/plain'))
    pending[0]?.reject({
      response: { data: { code: 'ATTACHMENT_TYPE_UNSUPPORTED', message: 'unsupported' } },
    })
    await flushPromises()

    // 一次选多个文件时，光有"不支持的文件类型"指不出是哪一个没过
    expect(wrapper.text()).toContain('payload.exe')
    expect(wrapper.text()).toContain('不支持的文件类型')
    expect(lastModelValue(wrapper)).toEqual([21])
  })

  it('取消上传后这个文件不随工单提交，并说明这一点', async () => {
    holdUploads()
    const wrapper = makeWrapper()

    await selectFiles(wrapper, [file('big.zip', 'application/zip')])
    await buttonByLabel(wrapper, '取消上传')?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('已取消上传')
    // 没有发出过任何 id：取消的文件不能混进提交里
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('超过上限时只上传允许的数量，并提示忽略了几个', async () => {
    holdUploads()
    const wrapper = makeWrapper()

    await selectFiles(
      wrapper,
      Array.from({ length: 7 }, (_, index) => file(`f${index}.txt`, 'text/plain')),
    )

    expect(vi.mocked(uploadAttachment)).toHaveBeenCalledTimes(MAX_ATTACHMENTS_PER_REQUEST)
    expect(ElMessage.warning).toHaveBeenCalledTimes(1)
  })

  it('移除已上传的文件会把它的 id 从模型里去掉', async () => {
    holdUploads()
    const wrapper = makeWrapper()

    await selectFiles(wrapper, [file('a.png', 'image/png')])
    pending[0]?.resolve(uploadResult(31, 'a.png', 'image/png'))
    await flushPromises()
    expect(lastModelValue(wrapper)).toEqual([31])

    await buttonByLabel(wrapper, '移除')?.trigger('click')
    await flushPromises()

    expect(lastModelValue(wrapper)).toEqual([])
  })

  it('粘贴带文件的剪贴板会直接上传，纯文本粘贴不拦截', async () => {
    holdUploads()
    const wrapper = makeWrapper()
    const zone = wrapper.find('.attachment-upload__dropzone')

    const textOnly = new Event('paste', { bubbles: true, cancelable: true }) as Event & {
      clipboardData: unknown
    }
    Object.defineProperty(textOnly, 'clipboardData', { value: { files: [] } })
    zone.element.dispatchEvent(textOnly)
    await nextTick()
    // 放行：在这个控件上粘贴一段文字时，用户不该什么也看不到
    expect(textOnly.defaultPrevented).toBe(false)
    expect(vi.mocked(uploadAttachment)).not.toHaveBeenCalled()

    const withFile = new Event('paste', { bubbles: true, cancelable: true }) as Event & {
      clipboardData: unknown
    }
    Object.defineProperty(withFile, 'clipboardData', {
      value: { files: [file('screenshot.png', 'image/png')] },
    })
    zone.element.dispatchEvent(withFile)
    await flushPromises()

    expect(withFile.defaultPrevented).toBe(true)
    expect(vi.mocked(uploadAttachment)).toHaveBeenCalledTimes(1)
    expect(pending[0]?.file.name).toBe('screenshot.png')
  })

  it('上传中把 uploading 置为 true，结束后置回 false', async () => {
    holdUploads()
    const wrapper = makeWrapper()

    await selectFiles(wrapper, [file('a.png', 'image/png')])
    expect(wrapper.emitted('update:uploading')?.at(-1)?.[0]).toBe(true)

    pending[0]?.resolve(uploadResult(41, 'a.png', 'image/png'))
    await flushPromises()

    expect(wrapper.emitted('update:uploading')?.at(-1)?.[0]).toBe(false)
  })

  it('文件选择框用 accept 做软过滤，但不自己维护类型白名单', () => {
    const wrapper = makeWrapper()

    expect(wrapper.find('input[type="file"]').attributes('accept')).toBe(
      ACCEPTED_ATTACHMENT_EXTENSIONS,
    )
  })

  it('超过单文件上限的文件不发请求，并在列表里说明原因', async () => {
    holdUploads()
    const wrapper = makeWrapper()
    const oversized = new File([new Uint8Array(MAX_ATTACHMENT_FILE_SIZE_BYTES + 1)], 'huge.pdf', {
      type: 'application/pdf',
    })

    await selectFiles(wrapper, [oversized, file('ok.txt', 'text/plain')])

    // 10 MiB 要传很久，传完再收到 413 是最差的顺序：本地先拦下，只有正常的那个发请求
    expect(vi.mocked(uploadAttachment)).toHaveBeenCalledTimes(1)
    expect(pending[0]?.file.name).toBe('ok.txt')
    expect(wrapper.text()).toContain('huge.pdf')
    expect(wrapper.text()).toContain('未上传')
    expect(wrapper.text()).toContain('超过单个附件')
  })

  it('移除已上传成功的文件会顺手释放服务端存储', async () => {
    holdUploads()
    vi.mocked(deleteAttachment).mockResolvedValue()
    const wrapper = makeWrapper()

    await selectFiles(wrapper, [file('a.png', 'image/png')])
    pending[0]?.resolve(uploadResult(51, 'a.png', 'image/png'))
    await flushPromises()

    await buttonByLabel(wrapper, '移除')?.trigger('click')
    await flushPromises()

    expect(vi.mocked(deleteAttachment)).toHaveBeenCalledWith(51)
    expect(lastModelValue(wrapper)).toEqual([])
  })

  it('释放失败（例如附件已随工单提交）不打扰用户，移除照样生效', async () => {
    holdUploads()
    vi.mocked(deleteAttachment).mockRejectedValue({
      response: { data: { code: 'ATTACHMENT_ALREADY_BOUND' } },
    })
    const wrapper = makeWrapper()

    await selectFiles(wrapper, [file('a.png', 'image/png')])
    pending[0]?.resolve(uploadResult(52, 'a.png', 'image/png'))
    await flushPromises()

    await buttonByLabel(wrapper, '移除')?.trigger('click')
    await flushPromises()

    expect(lastModelValue(wrapper)).toEqual([])
    // 已绑定的附件本来就该留着，其余失败由服务端对账兜底：都不需要弹提示
    expect(ElMessage.error).not.toHaveBeenCalled()
  })

  it('移除还没上传完成的文件不会去调删除接口', async () => {
    holdUploads()
    const wrapper = makeWrapper()

    await selectFiles(wrapper, [file('a.png', 'image/png')])
    await buttonByLabel(wrapper, '移除')?.trigger('click')
    await flushPromises()

    expect(vi.mocked(deleteAttachment)).not.toHaveBeenCalled()
  })
})
