<script setup lang="ts">
import { Document, Download, Files, Picture, Tickets, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { onBeforeUnmount, ref, watch } from 'vue'
import type { Component } from 'vue'

import { fetchAttachmentContent, formatFileSize, saveBlob } from '@/api/attachments'
import type { TicketAttachmentSummary } from '@/api/attachments'
import { describeError } from '@/api/errorMessages'
import { formatDateTime } from '@/utils/format'

/**
 * 工单详情里的附件区（`docs/api-design.md` 5.4 的 `attachments[]`）。
 *
 * <p>这一块把「谁上传的、多大、什么时候」和「能不能看、怎么看」放在一起：
 * 缩略图给图片、灯箱给图片全屏、PDF 与纯文本走弹窗预览、Office 文档只给下载。
 * 判断"能不能预览"用的是服务端返回的 `inlinePreviewable`，**前端不复制一份类型白名单**——
 * 白名单是安全边界（HTML / SVG 一律不内联），只在后端维护一处。</p>
 *
 * <p>关键约束：**所有内容都经后端授权代理取回，没有一条直链**。
 * `/attachments/{id}/content` 需要 Bearer 令牌，而 `<img src>` / `<iframe src>` / 新窗口
 * 都带不上自定义请求头——把 URL 直接塞进去，要么 401，要么（如果后端改成允许匿名）
 * 等于把文件公开。所以这里先取 Blob，再用 `blob:` 对象 URL 渲染；对象 URL 只在
 * 本页内存里存在，随组件卸载回收。</p>
 *
 * <p>缩略图是这一块唯一"多花请求"的地方：每张图片附件都要单独取一次内容。
 * 数量由上传上限与工单生命周期共同约束，实测量级很小；将来附件变多时，
 * 应当由后端提供一个缩略图端点，而不是在前端做并发池。</p>
 */

const props = defineProps<{
  /** 详情里的附件数组；没有附件时是空数组（不是缺键）。 */
  attachments: TicketAttachmentSummary[]
}>()

/** 单个文本预览最多渲染这么多字符：再长的正文交给下载，不在弹窗里堆 DOM。 */
const TEXT_PREVIEW_LIMIT = 20_000

/** 图片缩略图：附件 id → `blob:` 对象 URL。 */
const thumbnails = ref<Record<number, string>>({})

/** 本组件创建过的所有对象 URL，卸载时统一回收。 */
const objectUrls = new Set<string>()

function rememberObjectUrl(url: string): string {
  objectUrls.add(url)
  return url
}

function releaseObjectUrl(url: string | undefined): void {
  if (!url) {
    return
  }
  URL.revokeObjectURL(url)
  objectUrls.delete(url)
}

function isImage(contentType: string): boolean {
  return contentType.startsWith('image/')
}

function isPdf(contentType: string): boolean {
  return contentType === 'application/pdf'
}

function isText(contentType: string): boolean {
  return contentType === 'text/plain'
}

/** 列表左侧的图标：图片有缩略图，其余按类型给图标。 */
function fileIcon(contentType: string): Component {
  if (isImage(contentType)) {
    return Picture
  }
  if (isPdf(contentType)) {
    return Document
  }
  if (isText(contentType)) {
    return Tickets
  }
  return Files
}

/**
 * 缩略图的 `blob:` 地址；还没取到（或取失败）时是空串。
 *
 * <p>写成函数而不是在模板里索引 `thumbnails[item.id]`：模板里要判空、要传给 `:src`、
 * 还要拼成灯箱的 `preview-src-list`，三处各自写一次索引就得各自处理一次 `undefined`。</p>
 */
function thumbnailUrl(item: TicketAttachmentSummary): string {
  return thumbnails.value[item.id] ?? ''
}

/**
 * 弹窗预览的类型；`null` 表示这个附件只给下载。
 *
 * <p>图片不走弹窗——`el-image` 自带灯箱，那才是图片该有的交互。这里只剩 PDF 与纯文本。</p>
 */
function dialogPreviewKind(item: TicketAttachmentSummary): 'pdf' | 'text' | null {
  if (!item.inlinePreviewable) {
    return null
  }
  if (isPdf(item.contentType)) {
    return 'pdf'
  }
  if (isText(item.contentType)) {
    return 'text'
  }
  return null
}

// ---------- 缩略图 ----------

/** 缩略图取数的一趟令牌：详情刷新会重跑，旧响应不能写进新一轮的状态。 */
let thumbnailRun = 0

async function loadThumbnails(): Promise<void> {
  const run = (thumbnailRun += 1)
  const images = props.attachments.filter((item) => isImage(item.contentType))

  for (const item of images) {
    if (thumbnails.value[item.id]) {
      continue
    }
    try {
      const blob = await fetchAttachmentContent(item.id, true)
      if (run !== thumbnailRun) {
        return
      }
      thumbnails.value[item.id] = rememberObjectUrl(URL.createObjectURL(blob))
    } catch {
      // 缩略图是锦上添花：取不到就退回文件类型图标，不打断整页，也不弹提示——
      // 用户真正要做的事（下载）仍然是可用的
    }
  }
}

/** 附件列表变了：回收已经不在列表里的缩略图，再补齐新的。 */
function syncThumbnails(): void {
  const present = new Set(props.attachments.map((item) => item.id))
  for (const key of Object.keys(thumbnails.value)) {
    const id = Number(key)
    if (!present.has(id)) {
      releaseObjectUrl(thumbnails.value[id])
      delete thumbnails.value[id]
    }
  }
  void loadThumbnails()
}

watch(
  () => props.attachments,
  () => syncThumbnails(),
  { immediate: true },
)

// ---------- 下载 ----------

/** 正在下载的附件 id：只让那一个按钮转圈，不整块禁用。 */
const downloadingId = ref<number | null>(null)

async function download(item: TicketAttachmentSummary): Promise<void> {
  if (downloadingId.value !== null) {
    return
  }
  downloadingId.value = item.id
  try {
    const blob = await fetchAttachmentContent(item.id, false)
    saveBlob(blob, item.originalName)
  } catch (error) {
    ElMessage.error(describeError(error, '附件没有下载成功，请稍后重试'))
  } finally {
    downloadingId.value = null
  }
}

// ---------- 弹窗预览 ----------

const previewVisible = ref(false)
const previewItem = ref<TicketAttachmentSummary | null>(null)
const previewKind = ref<'pdf' | 'text' | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
const previewUrl = ref('')
const previewText = ref('')

async function openPreview(item: TicketAttachmentSummary): Promise<void> {
  const kind = dialogPreviewKind(item)
  if (!kind) {
    return
  }

  previewItem.value = item
  previewKind.value = kind
  previewVisible.value = true
  previewLoading.value = true
  previewError.value = ''
  previewUrl.value = ''
  previewText.value = ''

  try {
    // 即使这里请求内联，能否内联仍由后端按白名单裁决；Office 文档会被降级为下载
    const blob = await fetchAttachmentContent(item.id, true)
    if (kind === 'pdf') {
      previewUrl.value = rememberObjectUrl(URL.createObjectURL(blob))
    } else {
      previewText.value = await blob.text()
    }
  } catch (error) {
    previewError.value = describeError(error, '附件没有打开成功，请稍后重试')
  } finally {
    previewLoading.value = false
  }
}

/** 弹窗关闭动画结束后再回收：提前 revoke 会让正在渲染的预览变成空白。 */
function clearPreview(): void {
  releaseObjectUrl(previewUrl.value)
  previewUrl.value = ''
  previewText.value = ''
  previewError.value = ''
  previewItem.value = null
  previewKind.value = null
}

onBeforeUnmount(() => {
  thumbnailRun += 1
  for (const url of objectUrls) {
    URL.revokeObjectURL(url)
  }
  objectUrls.clear()
})
</script>

<template>
  <ul class="attachment-list">
    <li
      v-for="item in attachments"
      :key="item.id"
      class="attachment-list__item"
    >
      <div class="attachment-list__thumb">
        <el-image
          v-if="thumbnailUrl(item)"
          class="attachment-list__image"
          :src="thumbnailUrl(item)"
          :preview-src-list="[thumbnailUrl(item)]"
          :alt="item.originalName"
          fit="cover"
          preview-teleported
        >
          <template #error>
            <span
              class="attachment-list__icon"
              aria-hidden="true"
            >
              <el-icon><Picture /></el-icon>
            </span>
          </template>
        </el-image>
        <span
          v-else
          class="attachment-list__icon"
          aria-hidden="true"
        >
          <el-icon><component :is="fileIcon(item.contentType)" /></el-icon>
        </span>
      </div>

      <div class="attachment-list__meta">
        <p class="attachment-list__name">
          {{ item.originalName }}
        </p>
        <p class="attachment-list__sub">
          {{ formatFileSize(item.sizeBytes) }}
          · {{ item.uploadedBy?.displayName ?? '未知上传人' }}
          · {{ formatDateTime(item.createdAt) }}
        </p>
      </div>

      <div class="attachment-list__actions">
        <el-tooltip
          v-if="dialogPreviewKind(item)"
          content="预览"
          placement="top"
        >
          <el-button
            :icon="View"
            circle
            plain
            type="primary"
            :aria-label="`预览 ${item.originalName}`"
            @click="openPreview(item)"
          />
        </el-tooltip>

        <el-tooltip
          content="下载"
          placement="top"
        >
          <el-button
            :icon="Download"
            circle
            plain
            type="info"
            :loading="downloadingId === item.id"
            :aria-label="`下载 ${item.originalName}`"
            @click="download(item)"
          />
        </el-tooltip>
      </div>
    </li>
  </ul>

  <el-dialog
    v-model="previewVisible"
    class="admin-dialog attachment-preview"
    :title="previewItem?.originalName ?? '附件预览'"
    width="56rem"
    destroy-on-close
    @closed="clearPreview"
  >
    <div class="attachment-preview__body">
      <p
        v-if="previewLoading"
        class="attachment-preview__note"
      >
        正在打开附件…
      </p>
      <p
        v-else-if="previewError"
        class="attachment-preview__error"
        role="alert"
      >
        {{ previewError }}
      </p>
      <iframe
        v-else-if="previewKind === 'pdf' && previewUrl"
        class="attachment-preview__frame"
        :src="previewUrl"
        :title="previewItem?.originalName ?? 'PDF 预览'"
      />
      <template v-else-if="previewKind === 'text'">
        <pre class="attachment-preview__text">{{ previewText.slice(0, TEXT_PREVIEW_LIMIT) }}</pre>
        <p
          v-if="previewText.length > TEXT_PREVIEW_LIMIT"
          class="attachment-preview__note"
        >
          内容较长，这里只显示前 {{ TEXT_PREVIEW_LIMIT }} 个字符，完整内容请下载查看。
        </p>
      </template>
    </div>

    <template #footer>
      <el-button @click="previewVisible = false">
        关闭
      </el-button>
      <el-button
        v-if="previewItem"
        :icon="Download"
        :loading="downloadingId === previewItem.id"
        type="primary"
        plain
        @click="download(previewItem)"
      >
        下载原文件
      </el-button>
    </template>
  </el-dialog>
</template>
