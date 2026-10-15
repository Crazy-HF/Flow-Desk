import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./http', () => ({
  publicHttp: { get: vi.fn(), post: vi.fn() },
}))

import { publicHttp } from './http'
import {
  MAX_ATTACHMENTS_PER_REQUEST,
  fetchAttachmentContent,
  formatFileSize,
  isUploadCanceled,
  saveBlob,
  uploadAttachment,
} from './attachments'

/**
 * 附件接口封装的契约测试（`docs/api-design.md` 7.1 / 7.2）。
 *
 * <p>这里盯住三件容易写错的事：路径是**公共前缀**（不带 `/fd/v1`）、上传只发一个 `files` 部件、
 * 下载与预览都带 `disposition` 并且要求 blob 响应——最后一条是"内容必须经后端授权代理取回"
 * 的落点，改成直链会同时丢掉令牌与授权。</p>
 */

function respondWith(payload: unknown): void {
  const envelope = { data: { code: 'OK', message: 'OK', data: payload } }
  vi.mocked(publicHttp.post).mockResolvedValue(envelope as never)
  vi.mocked(publicHttp.get).mockResolvedValue(envelope as never)
}

function file(name: string, type: string): File {
  return new File(['content'], name, { type })
}

const uploadResult = {
  id: 7,
  originalName: 'log.txt',
  contentType: 'text/plain',
  sizeBytes: 12,
  createdAt: '2026-10-14T01:00:00Z',
}

describe('附件接口封装', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    respondWith([uploadResult])
  })

  it('上传发到公共路径，只带一个 files 部件并返回第一条结果', async () => {
    const result = await uploadAttachment(file('log.txt', 'text/plain'))

    expect(result).toEqual(uploadResult)

    const [url, body] = vi.mocked(publicHttp.post).mock.calls[0]!
    // 不带 /fd/v1：这是模块级公共端点，前缀写错会 404 且与后端认证链的作用域不一致
    expect(url).toBe('/attachments/upload')
    const form = body as FormData
    expect(form.getAll('files')).toHaveLength(1)
    expect((form.get('files') as File).name).toBe('log.txt')
  })

  it('把取消信号透传给请求：不透传，界面的「取消上传」按钮就没有作用', async () => {
    const controller = new AbortController()
    await uploadAttachment(file('log.txt', 'text/plain'), { signal: controller.signal })

    expect(vi.mocked(publicHttp.post).mock.calls[0]?.[2]?.signal).toBe(controller.signal)
  })

  it('进度按 loaded/total 换算成整数百分比，并夹在 0～100', async () => {
    const onProgress = vi.fn()
    await uploadAttachment(file('log.txt', 'text/plain'), { onProgress })

    const config = vi.mocked(publicHttp.post).mock.calls[0]?.[2]
    config?.onUploadProgress?.({ loaded: 512, total: 1024 } as never)
    expect(onProgress).toHaveBeenLastCalledWith(50)

    config?.onUploadProgress?.({ loaded: 2048, total: 1024 } as never)
    expect(onProgress).toHaveBeenLastCalledWith(100)

    // 浏览器给不出总量时不回调：编一个假百分比比不显示进度更误导
    config?.onUploadProgress?.({ loaded: 512, total: 0 } as never)
    expect(onProgress).toHaveBeenCalledTimes(2)
  })

  it('响应里没有附件信息时判为失败，不返回一个假的 id', async () => {
    respondWith([])

    await expect(uploadAttachment(file('log.txt', 'text/plain'))).rejects.toThrow(
      '上传响应缺少附件信息',
    )
  })

  it('取内容用 content 端点，disposition 区分预览与下载，并要求 blob 响应', async () => {
    const blob = { type: 'application/pdf' } as Blob
    vi.mocked(publicHttp.get).mockResolvedValue({ data: blob } as never)

    await expect(fetchAttachmentContent(9, true)).resolves.toBe(blob)
    expect(publicHttp.get).toHaveBeenLastCalledWith('/attachments/9/content', {
      params: { disposition: 'inline' },
      responseType: 'blob',
    })

    await fetchAttachmentContent(9, false)
    expect(publicHttp.get).toHaveBeenLastCalledWith('/attachments/9/content', {
      params: { disposition: 'attachment' },
      responseType: 'blob',
    })
  })

  it('只把 axios 的取消错误码认成「用户取消」', () => {
    expect(isUploadCanceled({ code: 'ERR_CANCELED' })).toBe(true)
    expect(isUploadCanceled({ code: 'ERR_NETWORK' })).toBe(false)
    expect(isUploadCanceled(new Error('boom'))).toBe(false)
    expect(isUploadCanceled(undefined)).toBe(false)
  })

  it('文件大小按 B / KB / MB 渲染，非法值给占位符', () => {
    expect(formatFileSize(0)).toBe('0 B')
    expect(formatFileSize(512)).toBe('512 B')
    expect(formatFileSize(2048)).toBe('2.0 KB')
    expect(formatFileSize(5 * 1024 * 1024)).toBe('5.0 MB')
    expect(formatFileSize(Number.NaN)).toBe('-')
    expect(formatFileSize(-1)).toBe('-')
  })

  it('下载用对象 URL 触发并立即回收，链接不留在页面上', () => {
    const createObjectURL = vi.fn(() => 'blob:flowdesk')
    const revokeObjectURL = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', {
      value: createObjectURL,
      configurable: true,
      writable: true,
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      value: revokeObjectURL,
      configurable: true,
      writable: true,
    })

    const created: HTMLAnchorElement[] = []
    const originalCreateElement = document.createElement.bind(document)
    const createElementSpy = vi
      .spyOn(document, 'createElement')
      .mockImplementation((tag: string, options?: ElementCreationOptions) => {
        const element = originalCreateElement(tag, options)
        if (tag === 'a') {
          created.push(element as HTMLAnchorElement)
        }
        return element
      })
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    saveBlob(new Blob(['x']), 'log.txt')

    const anchor = created[0]
    expect(anchor?.download).toBe('log.txt')
    expect(anchor?.href).toContain('blob:flowdesk')
    expect(clickSpy).toHaveBeenCalled()
    // 用完立刻回收：不回收就是一次下载泄漏一个对象 URL，长时间使用会一直占着内存
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:flowdesk')
    expect(document.querySelector('a')).toBeNull()

    createElementSpy.mockRestore()
    clickSpy.mockRestore()
  })

  it('单次请求上限与后端默认值一致', () => {
    expect(MAX_ATTACHMENTS_PER_REQUEST).toBe(5)
  })
})
