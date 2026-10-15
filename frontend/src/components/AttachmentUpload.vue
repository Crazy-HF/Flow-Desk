<script setup lang="ts">
import { Close, Delete, Document, Refresh, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, ref } from 'vue'

import {
  MAX_ATTACHMENTS_PER_REQUEST,
  formatFileSize,
  isUploadCanceled,
  uploadAttachment,
} from '@/api/attachments'
import { describeError } from '@/api/errorMessages'

/**
 * 附件上传控件（`docs/api-design.md` 7.1 `POST /attachments/upload`）。
 *
 * <p>路径是公共的 `attachments/upload`，**不带 `/fd/v1` 前缀**，因此走 `publicHttp`——
 * 但认证语义完全相同：同样的 Bearer 注入、同样的 401 单次刷新重放。</p>
 *
 * <p>三处刻意的选择：</p>
 * <p>1. **一个文件一次请求**，不是把 5 个文件拼进同一个 multipart。后端支持一次带多个 part
 * （整体成败），但那样进度、取消与失败都是整批的：一个文件被拒，用户既看不出是哪一个，
 * 也无法只重试那一个。逐文件请求让这三件事天然按文件独立，失败文案里可以直接写出文件名。
 * 服务端真正的上限（数量、单文件大小、单次总量）仍由后端裁定，这里只是不提前制造模糊。</p>
 * <p>2. **只回传 `attachmentIds`**，不回传文件本身。附件在"先上传、后绑定"的两段式里
 * 先落库、拿到 id，再随工单提交时绑定；未绑定的附件按保留期由对账任务清理，
 * 所以用户在这里删掉一个已上传的文件不需要、也没有删除接口——它自然过期。</p>
 * <p>3. **粘贴上传**是这个控件存在的理由之一：截图没有"文件路径"，用户按 Ctrl+V 就该能传。
 * 但粘贴监听挂在控件自己身上（`tabindex="0"`），不挂 `document`——挂全局会抢掉页面上
 * 其它输入框的粘贴。</p>
 */

/** 已成功上传的附件标识；父组件把它拼进创建 / 补充请求的 `attachmentIds`。 */
const attachmentIds = defineModel<number[]>({ required: true })

/**
 * 是否还有文件在上传中。父组件用它拦住提交——附件没传完就建单，等于用户以为带上了、
 * 实际没带上，而后端只会返回一张没有附件的工单。
 */
const uploading = defineModel<boolean>('uploading', { default: false })

const props = withDefaults(
  defineProps<{
    /** 本次可上传的数量上限；默认与后端 `max-files-per-request` 一致。 */
    max?: number
    disabled?: boolean
  }>(),
  { max: MAX_ATTACHMENTS_PER_REQUEST, disabled: false },
)

type UploadStatus = 'uploading' | 'success' | 'error' | 'canceled'

/**
 * 一个文件在界面上的全部状态。
 *
 * <p>没有"排队中"：文件一进来就开始传。排队只在多文件同时上传时有意义，而这里最多 5 个，
 * 多一个状态就多一处可能与真实请求不同步的地方。</p>
 */
interface UploadTask {
  uid: number
  file: File
  status: UploadStatus
  percent: number
  /** 失败或取消的说明；成功与上传中为空。 */
  message: string
  attachmentId: number | null
  controller: AbortController | null
}

let nextUid = 0

const tasks = ref<UploadTask[]>([])
const inputRef = ref<HTMLInputElement>()
const dragging = ref(false)

/**
 * 取任务时**一律从 `tasks.value` 里现取**，不把对象存进局部变量再改。
 *
 * <p>`ref([])` 是深响应式：推进去的原始对象本身不是代理，直接改它不会触发视图更新
 * （进度条会一直停在 0%）。按 uid 现取拿到的才是代理，改它才算数。</p>
 */
function findTask(uid: number): UploadTask | undefined {
  return tasks.value.find((task) => task.uid === uid)
}

/** 已占用的名额：上传中与已成功的都算；取消与失败的不占，用户可以换个文件重试。 */
const usedSlots = computed(
  () =>
    tasks.value.filter((task) => task.status === 'uploading' || task.status === 'success').length,
)
const remainingSlots = computed(() => Math.max(0, props.max - usedSlots.value))
const full = computed(() => remainingSlots.value === 0)

function syncModelValue(): void {
  attachmentIds.value = tasks.value
    .filter((task): task is UploadTask & { attachmentId: number } => task.attachmentId !== null)
    .map((task) => task.attachmentId)
}

function syncUploading(): void {
  uploading.value = tasks.value.some((task) => task.status === 'uploading')
}

/**
 * 与文件大小同一行的状态后缀。
 *
 * <p>写成函数而不是在模板里排四个 `<template v-if>`：四个分支共用一个分隔符和一种语气，
 * 拆在模板里就变成四处各写一遍，改一次文案要改四行。</p>
 */
function statusText(task: UploadTask): string {
  switch (task.status) {
    case 'uploading':
      return `· 上传中 ${task.percent}%`
    case 'success':
      return '· 已上传'
    case 'canceled':
      return '· 已取消'
    default:
      return '· 上传失败'
  }
}

async function startUpload(uid: number): Promise<void> {
  const task = findTask(uid)
  if (!task) {
    return
  }
  task.status = 'uploading'
  task.percent = 0
  task.message = ''
  task.attachmentId = null
  const controller = new AbortController()
  task.controller = controller
  syncUploading()

  try {
    const result = await uploadAttachment(task.file, {
      signal: controller.signal,
      onProgress: (percent) => {
        const current = findTask(uid)
        if (current) {
          current.percent = percent
        }
      },
    })
    const current = findTask(uid)
    if (!current) {
      return
    }
    current.status = 'success'
    current.percent = 100
    current.attachmentId = result.id
    syncModelValue()
  } catch (error) {
    const current = findTask(uid)
    if (!current) {
      return
    }
    if (isUploadCanceled(error)) {
      current.status = 'canceled'
      current.message = '已取消上传，这个文件不会随工单一起提交。'
    } else {
      current.status = 'error'
      // 文件名写在最前面：一次选了多个文件时，用户要一眼看出是哪一个没过
      current.message = `${current.file.name}：${describeError(error, '上传没有成功，请重试')}`
    }
  } finally {
    const current = findTask(uid)
    if (current) {
      current.controller = null
    }
    syncUploading()
  }
}

function addFiles(incoming: readonly File[]): void {
  if (props.disabled || incoming.length === 0) {
    return
  }

  const accepted = incoming.slice(0, remainingSlots.value)
  const dropped = incoming.length - accepted.length
  if (dropped > 0) {
    ElMessage.warning(`一次最多上传 ${props.max} 个附件，本次忽略了 ${dropped} 个`)
  }

  for (const file of accepted) {
    const task: UploadTask = {
      uid: (nextUid += 1),
      file,
      status: 'uploading',
      percent: 0,
      message: '',
      attachmentId: null,
      controller: null,
    }
    tasks.value.push(task)
    void startUpload(task.uid)
  }
}

function openPicker(): void {
  if (props.disabled) {
    return
  }
  if (full.value) {
    ElMessage.info(`最多上传 ${props.max} 个附件，请先移除一个再添加`)
    return
  }
  inputRef.value?.click()
}

function onPick(event: Event): void {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  // 先清空再上传：不清空的话，用户删掉同一个文件再选一次不会触发 change
  input.value = ''
  addFiles(files)
}

/**
 * 粘贴上传。
 *
 * <p>只处理带文件的粘贴（截图、复制的文件）；纯文本粘贴一律放行，不 `preventDefault`，
 * 否则用户在这个控件上粘贴一段文字会什么也看不到。</p>
 */
function onPaste(event: ClipboardEvent): void {
  const files = Array.from(event.clipboardData?.files ?? [])
  if (files.length === 0) {
    return
  }
  event.preventDefault()
  addFiles(files)
}

function onDragOver(): void {
  if (!props.disabled) {
    dragging.value = true
  }
}

function onDragLeave(): void {
  dragging.value = false
}

function onDrop(event: DragEvent): void {
  dragging.value = false
  if (props.disabled) {
    return
  }
  addFiles(Array.from(event.dataTransfer?.files ?? []))
}

function cancel(uid: number): void {
  findTask(uid)?.controller?.abort()
}

function retry(uid: number): void {
  void startUpload(uid)
}

function remove(uid: number): void {
  const task = findTask(uid)
  if (!task) {
    return
  }
  task.controller?.abort()
  tasks.value = tasks.value.filter((candidate) => candidate.uid !== uid)
  syncModelValue()
  syncUploading()
}

onBeforeUnmount(() => {
  // 组件被卸载（用户离开页面）时中断在途请求：请求还挂着时把结果写进已卸载的状态没有意义
  for (const task of tasks.value) {
    task.controller?.abort()
  }
})
</script>

<template>
  <div class="attachment-upload">
    <div
      class="attachment-upload__dropzone"
      :class="{ 'attachment-upload__dropzone--active': dragging }"
      role="button"
      tabindex="0"
      :aria-disabled="disabled || full"
      @click="openPicker"
      @keydown.enter.prevent="openPicker"
      @keydown.space.prevent="openPicker"
      @paste="onPaste"
      @dragover.prevent="onDragOver"
      @dragleave="onDragLeave"
      @drop.prevent="onDrop"
    >
      <el-icon class="attachment-upload__icon">
        <UploadFilled />
      </el-icon>
      <p class="attachment-upload__title">
        点击选择文件，或把文件拖到这里
      </p>
      <p class="attachment-upload__hint">
        最多 {{ max }} 个；支持图片、PDF、纯文本与 Word / Excel 文档。
        光标停在这块区域时，可以直接按 Ctrl+V 粘贴截图。
      </p>
    </div>

    <input
      ref="inputRef"
      class="attachment-upload__input"
      type="file"
      multiple
      aria-hidden="true"
      @change="onPick"
    >

    <p
      v-if="tasks.length > 0"
      class="attachment-upload__count"
    >
      已选 {{ usedSlots }} / {{ max }}
    </p>

    <ul
      v-if="tasks.length > 0"
      class="attachment-upload__list"
    >
      <li
        v-for="task in tasks"
        :key="task.uid"
        class="attachment-upload__item"
      >
        <span
          class="attachment-upload__file-icon"
          aria-hidden="true"
        >
          <el-icon><Document /></el-icon>
        </span>

        <div class="attachment-upload__meta">
          <p class="attachment-upload__name">
            {{ task.file.name }}
          </p>
          <p class="attachment-upload__sub">
            {{ formatFileSize(task.file.size) }} {{ statusText(task) }}
          </p>
          <el-progress
            v-if="task.status === 'uploading'"
            class="attachment-upload__progress"
            :percentage="task.percent"
            :show-text="false"
          />
          <p
            v-if="task.status === 'error'"
            class="attachment-upload__error"
            role="alert"
          >
            {{ task.message }}
          </p>
          <p
            v-else-if="task.status === 'canceled'"
            class="attachment-upload__note"
          >
            {{ task.message }}
          </p>
        </div>

        <div class="attachment-upload__actions">
          <el-tooltip
            v-if="task.status === 'uploading'"
            content="取消上传"
            placement="top"
          >
            <el-button
              :icon="Close"
              circle
              plain
              type="info"
              :aria-label="`取消上传 ${task.file.name}`"
              @click="cancel(task.uid)"
            />
          </el-tooltip>

          <el-tooltip
            v-else-if="task.status === 'error' || task.status === 'canceled'"
            content="重新上传"
            placement="top"
          >
            <el-button
              :icon="Refresh"
              circle
              plain
              type="primary"
              :aria-label="`重新上传 ${task.file.name}`"
              @click="retry(task.uid)"
            />
          </el-tooltip>

          <el-tooltip
            content="移除"
            placement="top"
          >
            <el-button
              :icon="Delete"
              circle
              plain
              type="danger"
              :aria-label="`移除 ${task.file.name}`"
              @click="remove(task.uid)"
            />
          </el-tooltip>
        </div>
      </li>
    </ul>
  </div>
</template>
