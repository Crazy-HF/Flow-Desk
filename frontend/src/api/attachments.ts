import { publicHttp } from './http'
import type { ApiEnvelope } from './http'

/**
 * 附件接口（公共路径 `attachments/xx`，**不带 `/fd/v1` 前缀**，见 `docs/api-design.md` 7.1/7.2）。
 *
 * <p>下载与预览都走「后端授权 → 后端代理内容」这条路：前端带令牌取回 Blob，
 * 绝不把 `/attachments/{id}/content` 直接塞进 `<img src>` 或 `<iframe src>`——
 * 那既带不上 `Authorization` 头，也等于把授权交给 URL。</p>
 */

/** 后端单文件上传结果（`POST /attachments/upload` 返回数组，逐文件一条）。 */
export interface AttachmentUploadResult {
  id: number
  originalName: string
  contentType: string
  sizeBytes: number
  createdAt: string
}

/** 工单详情里的附件摘要（`GET /tickets/{ticketNo}` 的 `attachments[]`）。 */
export interface TicketAttachmentSummary {
  id: number
  originalName: string
  contentType: string
  sizeBytes: number
  uploadedBy: { id: number; displayName: string } | null
  createdAt: string
  inlinePreviewable: boolean
}

export interface UploadAttachmentOptions {
  /** 0～100 的整数进度；浏览器无法给出总量时不会回调。 */
  onProgress?: (percent: number) => void
  /** 取消上传（用户点「取消」时 abort）。 */
  signal?: AbortSignal
}

/** 单次请求的附件数量上限，与后端 `flowdesk.attachment.max-files-per-request` 默认值一致。 */
export const MAX_ATTACHMENTS_PER_REQUEST = 5

/**
 * 单文件大小上限，与后端 `flowdesk.attachment.max-file-size` 默认值一致（10 MiB）。
 *
 * <p>**这是唯一一处被复制到前端的限额**，用途只有一个：在发请求之前拦掉必然被
 * `413/ATTACHMENT_TOO_LARGE` 拒绝的文件，不让用户白等一次上传。真正的边界始终在后端，
 * 前端拦截失败也只是"少提示一次"，不会放行任何后端不接受的内容。</p>
 */
export const MAX_ATTACHMENT_FILE_SIZE_BYTES = 10 * 1024 * 1024

/**
 * 文件选择框的 `accept` 值：**软过滤，不是安全边界**。
 *
 * <p>它只影响系统文件选择框默认列出的条目，拖放与粘贴都能绕过；真正决定"能不能存"的
 * 是后端对「扩展名 + 声明类型 + 服务端识别结果」的三重一致校验。这里刻意不复制白名单判定
 * 逻辑（例如"是不是真的 OOXML 容器"），类型裁决只在后端一处，避免两份规则漂移。</p>
 */
export const ACCEPTED_ATTACHMENT_EXTENSIONS = '.jpg,.jpeg,.png,.pdf,.txt,.docx,.xlsx'

/**
 * 上传单个附件。
 *
 * <p>批量由调用方**逐文件调用**本函数：这样进度、取消与失败都天然按文件独立，
 * 失败能精确到文件名；后端同样支持一次带多个 part（整体成败），保留给其它调用方。</p>
 */
export async function uploadAttachment(
  file: File,
  options: UploadAttachmentOptions = {},
): Promise<AttachmentUploadResult> {
  const form = new FormData()
  form.append('files', file)
  const response = await publicHttp.post<ApiEnvelope<AttachmentUploadResult[]>>(
    '/attachments/upload',
    form,
    {
      signal: options.signal,
      onUploadProgress: (event) => {
        if (!options.onProgress || !event.total) {
          return
        }
        options.onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
      },
    },
  )
  const results = response.data.data
  const first = results?.[0]
  if (!first) {
    // 上传成功却没有回执：不能返回一个假的结果让调用方以为拿到了 id
    throw new Error('上传响应缺少附件信息')
  }
  return first
}

/**
 * 取附件内容为 Blob。
 *
 * @param inline 请求内联；**能否内联由后端按白名单裁决**（Office 文档会降级为下载）。
 */
export async function fetchAttachmentContent(id: number, inline: boolean): Promise<Blob> {
  const response = await publicHttp.get(`/attachments/${id}/content`, {
    params: { disposition: inline ? 'inline' : 'attachment' },
    responseType: 'blob',
  })
  return response.data as Blob
}

/**
 * 删除一个**尚未绑定**的附件（`DELETE /attachments/{id}`）。
 *
 * <p>只对「上传成功、还没随工单提交」的附件有效。已绑定到工单记录的附件会得到
 * `409/ATTACHMENT_ALREADY_BOUND`——那种情况下文件本来就该留下，调用方按正常路径处理，
 * 不要当成失败提示给用户。</p>
 */
export async function deleteAttachment(id: number): Promise<void> {
  await publicHttp.delete(`/attachments/${id}`)
}

/** 用已授权的 Blob 触发浏览器下载/另存；不用直链，因此不会丢掉令牌。 */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    link.rel = 'noopener'
    document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    // 立即回收：链接已经触发，Blob 不再需要长期占用内存。
    URL.revokeObjectURL(url)
  }
}

/**
 * 这次失败是不是"用户自己点了取消"。
 *
 * <p>必须与真失败分开：取消是用户的选择，界面该说"已取消"；真失败要说明原因，
 * 并且失败文案必须带上文件名——用户一次选了多个文件时，"不支持的文件类型"这种句子
 * 本身指不出是哪一个没过。</p>
 *
 * <p>判定依据是 axios 的取消错误码 `ERR_CANCELED`（`AbortController.abort()` 触发），
 * 不按文案或 `instanceof` 判断：打包后 `CanceledError` 的类身份不可靠，而错误码是契约。</p>
 */
export function isUploadCanceled(error: unknown): boolean {
  return (error as { code?: string } | null | undefined)?.code === 'ERR_CANCELED'
}

/** 人类可读的文件大小（附件区展示用）。 */
export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '-'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
