<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { describeError } from '@/api/errorMessages'
import { errorCode } from '@/api/http'
import {
  generateTicketAiSuggestion,
  listTicketAiSuggestions,
  previewTicketAiSources,
} from '@/api/ticketAi'
import type { TicketAiSourcesPreview, TicketAiSuggestion } from '@/api/ticketAi'
import { createSubmissionKey } from '@/api/tickets'
import type { TicketDetail } from '@/api/tickets'
import EmptyState from '@/components/EmptyState.vue'
import { ticketPriorityLabel, ticketStatusLabel } from '@/constants/tickets'
import { formatDateTime } from '@/utils/format'

const props = defineProps<{
  ticket: TicketDetail
  canGenerate: boolean
  canFillRecord: boolean
}>()

const emit = defineEmits<{
  fillRecord: [content: string]
  refreshDetail: []
}>()

const previewOpen = ref(false)
const sourcesPreview = ref<TicketAiSourcesPreview | null>(null)
const previewPhase = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const previewError = ref('')
let previewSequence = 0
const confirmedTitle = ref('')
const confirmedDescription = ref('')
const titleError = ref('')
const descriptionError = ref('')
const generating = ref(false)
const generationError = ref('')
const selected = ref<TicketAiSuggestion | null>(null)

const historyOpened = ref(false)
const historyPhase = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const historyError = ref('')
const historyItems = ref<TicketAiSuggestion[]>([])
const historyPage = ref(1)
const historyTotalPages = ref(0)
const historyTotal = ref(0)
const loadingMore = ref(false)

const hasMoreHistory = computed(() => historyPage.value < historyTotalPages.value)

/** 详情变化或关闭弹窗会废弃飞行中的预览，旧响应不能重新启用确认。 */
watch(() => [props.ticket.ticketNo, props.ticket.version], () => {
  previewOpen.value = false
  invalidatePreview()
})

watch(previewOpen, (open) => {
  if (!open) {
    invalidatePreview()
  }
})

function invalidatePreview(): void {
  previewSequence += 1
  sourcesPreview.value = null
  previewPhase.value = 'idle'
  previewError.value = ''
}

/** 只接受当前弹窗、当前工单版本的预览；失败时保持确认禁用。 */
async function loadSourcesPreview(): Promise<void> {
  const sequence = ++previewSequence
  const ticketNo = props.ticket.ticketNo
  const version = props.ticket.version
  sourcesPreview.value = null
  previewPhase.value = 'loading'
  previewError.value = ''
  try {
    const result = await previewTicketAiSources(ticketNo, version)
    if (sequence !== previewSequence || !previewOpen.value
      || ticketNo !== props.ticket.ticketNo || version !== props.ticket.version) {
      return
    }
    sourcesPreview.value = result
    previewPhase.value = 'ready'
  } catch (error) {
    if (sequence !== previewSequence || !previewOpen.value) {
      return
    }
    previewPhase.value = 'error'
    previewError.value = describeError(error, '参考来源加载失败，请重试')
    if (errorCode(error) === 'TICKET_CONFLICT') {
      emit('refreshDetail')
    }
  }
}

function openPreview(): void {
  if (!props.canGenerate || generating.value) {
    return
  }
  confirmedTitle.value = props.ticket.title
  confirmedDescription.value = props.ticket.description
  titleError.value = ''
  descriptionError.value = ''
  generationError.value = ''
  previewOpen.value = true
  void loadSourcesPreview()
}

function validateTitle(): boolean {
  const title = confirmedTitle.value.trim()
  titleError.value = !title ? '请输入标题' : title.length > 200 ? '标题最多 200 字' : ''
  return titleError.value === ''
}

function validateDescription(): boolean {
  const description = confirmedDescription.value.trim()
  descriptionError.value = !description
    ? '请输入问题描述'
    : description.length > 10_000 ? '描述最多 10000 字' : ''
  return descriptionError.value === ''
}

/** 每次确认生成一个新 requestId；同一点击在飞行期间不允许再次提交。 */
async function generate(): Promise<void> {
  if (!props.canGenerate || generating.value || !previewOpen.value
    || previewPhase.value !== 'ready' || !sourcesPreview.value) {
    return
  }

  const validTitle = validateTitle()
  const validDescription = validateDescription()
  if (!validTitle || !validDescription) {
    return
  }

  const title = confirmedTitle.value.trim()
  const description = confirmedDescription.value.trim()

  const ticketNo = props.ticket.ticketNo
  const version = props.ticket.version
  const sourceDigest = sourcesPreview.value.sourceDigest
  generating.value = true
  generationError.value = ''
  try {
    const suggestion = await generateTicketAiSuggestion(ticketNo, {
      requestId: createSubmissionKey(),
      version,
      title,
      description,
      sourceDigest,
    })
    selected.value = suggestion
    previewOpen.value = false
    // 服务端已经保存；历史再取失败时，当前成功结果仍可在面板中查看。
    void loadHistory(1, false)
  } catch (error) {
    generationError.value = describeError(error, '建议没有生成成功，请稍后重试')
    if (errorCode(error) === 'TICKET_CONFLICT') {
      emit('refreshDetail')
    } else if (errorCode(error) === 'AI_SOURCES_CHANGED') {
      // 拒绝的请求没有占额；刷新摘录后必须由负责人再次点击确认，不自动重发 POST。
      void loadSourcesPreview()
    }
  } finally {
    generating.value = false
  }
}

/** 历史只在负责人主动打开时读取；失败不清除已经看过的记录。 */
async function loadHistory(page: number, append: boolean): Promise<void> {
  historyOpened.value = true
  if (append) {
    loadingMore.value = true
  } else {
    historyPhase.value = 'loading'
  }
  historyError.value = ''

  try {
    const result = await listTicketAiSuggestions(props.ticket.ticketNo, {
      pageNo: page,
      pageSize: 10,
    })
    historyItems.value = append ? [...historyItems.value, ...result.items] : result.items
    historyPage.value = result.page
    historyTotalPages.value = result.totalPages
    historyTotal.value = result.totalElements
    historyPhase.value = 'ready'
    if (!append) {
      selected.value = result.items.find((item) => item.id === selected.value?.id)
        ?? result.items[0]
        ?? null
    }
  } catch (error) {
    historyError.value = describeError(error, 'AI 建议历史没有加载成功，请稍后重试')
    if (errorCode(error) === 'AI_HISTORY_FORBIDDEN'
      || errorCode(error) === 'TICKET_NOT_FOUND') {
      historyItems.value = []
      selected.value = null
    }
    if (!append) {
      historyPhase.value = 'error'
    }
  } finally {
    loadingMore.value = false
  }
}

/** 把三部分建议作为待编辑的草稿，明确区分建议与已完成的处理事实。 */
function fillProcessingRecord(suggestion: TicketAiSuggestion): void {
  if (!props.canFillRecord) {
    return
  }
  const content = [
    'AI 草稿：以下内容尚未执行，请核实并改写为实际处理情况。',
    '',
    '工单摘要',
    suggestion.summary,
    '',
    '待核实问题',
    ...suggestion.questions.map((item, index) => `${index + 1}. ${item}`),
    '',
    '建议处理步骤',
    ...suggestion.suggestedSteps.map((item, index) => `${index + 1}. ${item}`),
  ].join('\n')
  if (content.length > 10_000) {
    generationError.value = '草稿超过处理记录的 10000 字上限，请手动摘取需要的内容。'
    return
  }
  emit('fillRecord', content)
}
</script>

<template>
  <section
    class="ticket-panel ticket-ai"
    aria-labelledby="ticket-ai-heading"
  >
    <header class="ticket-panel__header ticket-ai__header">
      <div>
        <h2 id="ticket-ai-heading">
          AI 处理建议
        </h2>
        <p class="ticket-panel__count">
          供负责人参考；核实、修改后才能写入处理记录
        </p>
      </div>
      <div class="ticket-ai__actions">
        <el-button
          v-if="canGenerate"
          type="primary"
          :loading="generating"
          @click="openPreview"
        >
          生成建议
        </el-button>
        <el-button
          v-if="!historyOpened"
          type="info"
          plain
          @click="loadHistory(1, false)"
        >
          查看历史
        </el-button>
        <el-button
          v-else
          type="info"
          plain
          :loading="historyPhase === 'loading'"
          @click="loadHistory(1, false)"
        >
          刷新历史
        </el-button>
      </div>
    </header>

    <div class="ticket-panel__body ticket-ai__body">
      <p
        v-if="generationError"
        class="ticket-action-error"
        role="alert"
      >
        {{ generationError }}
      </p>

      <p
        v-if="!historyOpened && !selected"
        class="ticket-ai__hint"
      >
        已保存的建议只对当前负责人可见；工单结束后由最后负责人查看。
      </p>

      <el-skeleton
        v-if="historyPhase === 'loading'"
        animated
        :rows="3"
        aria-busy="true"
        aria-label="正在加载 AI 建议历史"
      />
      <p
        v-if="historyError"
        class="ticket-action-error"
        role="alert"
      >
        {{ historyError }}
      </p>

      <EmptyState
        v-if="historyPhase === 'ready' && historyItems.length === 0 && !selected"
        title="还没有 AI 建议"
        description="生成并保存后，可以在这里查看历史。"
      />

      <div
        v-if="historyItems.length > 0"
        class="ticket-ai__history"
      >
        <p class="ticket-ai__hint">
          已保存 {{ historyTotal }} 条建议，按时间倒序
        </p>
        <div class="ticket-ai__history-list">
          <el-button
            v-for="item in historyItems"
            :key="item.id"
            type="info"
            plain
            :class="{ 'ticket-ai__history-item--selected': selected?.id === item.id }"
            @click="selected = item"
          >
            {{ formatDateTime(item.createdAt) }} · {{ item.summary.slice(0, 80) }}{{ item.summary.length > 80 ? '…' : '' }}
          </el-button>
        </div>
        <el-button
          v-if="hasMoreHistory"
          type="info"
          plain
          :loading="loadingMore"
          @click="loadHistory(historyPage + 1, true)"
        >
          加载更多建议
        </el-button>
      </div>

      <article
        v-if="selected"
        class="ticket-ai__result"
        aria-label="已保存的 AI 建议"
      >
        <div class="ticket-ai__result-head">
          <span>保存于 {{ formatDateTime(selected.createdAt) }}</span>
          <span>模型：{{ selected.model === 'deepseek-flash' ? 'DeepSeek-V4.1 Flash' : selected.model }}</span>
        </div>
        <h3>工单摘要</h3>
        <p class="ticket-ai__text">
          {{ selected.summary }}
        </p>
        <h3>待核实问题</h3>
        <ol>
          <li
            v-for="(item, index) in selected.questions"
            :key="index"
          >
            {{ item }}
          </li>
        </ol>
        <h3>建议处理步骤</h3>
        <ol>
          <li
            v-for="(item, index) in selected.suggestedSteps"
            :key="index"
          >
            {{ item }}
          </li>
        </ol>
        <p class="ticket-ai__hint">
          这些是建议，不能视为已经完成的处理事实。
        </p>
        <el-button
          v-if="canFillRecord"
          class="ticket-ai__fill"
          type="success"
          @click="fillProcessingRecord(selected)"
        >
          填入处理记录草稿
        </el-button>
        <details class="ticket-ai__snapshot">
          <summary>查看本次发送的内容</summary>
          <dl class="ticket-facts">
            <dt>标题</dt><dd>{{ selected.input.title }}</dd>
            <dt>描述</dt><dd class="ticket-ai__text">
              {{ selected.input.description }}
            </dd>
            <dt>分类</dt><dd>{{ selected.input.category }}</dd>
            <dt>优先级</dt><dd>{{ ticketPriorityLabel(selected.input.priority) }}</dd>
            <dt>状态</dt><dd>{{ ticketStatusLabel(selected.input.status) }}</dd>
          </dl>
          <div
            v-if="selected.sources?.length"
            class="ticket-ai__sources"
          >
            <h3>本次参考来源</h3>
            <div
              v-for="source in selected.sources"
              :key="source.label"
              class="ticket-ai__source"
            >
              <strong>{{ source.label }} {{ source.ticketNo }} · {{ source.title }}</strong>
              <p class="ticket-ai__text">
                {{ source.processExcerpt }}
              </p>
            </div>
          </div>
        </details>
      </article>
    </div>

    <el-dialog
      v-model="previewOpen"
      title="确认发送给 AI 的工单内容"
      class="admin-dialog ticket-ai__dialog"
      :close-on-click-modal="false"
      :close-on-press-escape="!generating"
      :show-close="!generating"
      :width="640"
    >
      <p class="ticket-ai__hint">
        请核对下面五项工单内容及参考来源。请先删改标题、描述中的敏感信息；附件不会发送。
      </p>
      <div class="ticket-ai__preview">
        <label for="ticket-ai-title">标题 <span aria-hidden="true">*</span></label>
        <el-input
          id="ticket-ai-title"
          v-model="confirmedTitle"
          maxlength="200"
          show-word-limit
          :disabled="generating"
          placeholder="请输入发送给 AI 的标题"
          @blur="validateTitle"
        />
        <p
          v-if="titleError"
          class="ticket-action-error"
          role="alert"
        >
          {{ titleError }}
        </p>
        <label for="ticket-ai-description">描述 <span aria-hidden="true">*</span></label>
        <el-input
          id="ticket-ai-description"
          v-model="confirmedDescription"
          type="textarea"
          :rows="7"
          maxlength="10000"
          show-word-limit
          :disabled="generating"
          placeholder="请输入发送给 AI 的问题描述"
          @blur="validateDescription"
        />
        <p
          v-if="descriptionError"
          class="ticket-action-error"
          role="alert"
        >
          {{ descriptionError }}
        </p>
        <dl class="ticket-facts">
          <dt>分类</dt><dd>{{ ticket.category.name }}</dd>
          <dt>优先级</dt><dd>{{ ticketPriorityLabel(ticket.priority) }}</dd>
          <dt>状态</dt><dd>{{ ticketStatusLabel(ticket.status) }}</dd>
        </dl>
        <div
          class="ticket-ai__sources"
          aria-label="将发送给 AI 的参考来源"
        >
          <h3>参考来源</h3>
          <el-skeleton
            v-if="previewPhase === 'loading'"
            animated
            :rows="2"
            aria-busy="true"
            aria-label="正在加载参考来源"
          />
          <p
            v-else-if="previewPhase === 'ready' && sourcesPreview?.sources.length === 0"
            class="ticket-ai__hint"
          >
            本次没有参考来源，仅发送上面五项工单内容。
          </p>
          <template v-else-if="previewPhase === 'ready'">
            <p class="ticket-ai__hint">
              以下摘录将一并发送，请逐条核对。
            </p>
            <div
              v-for="source in sourcesPreview?.sources"
              :key="source.label"
              class="ticket-ai__source"
            >
              <strong>{{ source.label }} {{ source.title }}</strong>
              <p class="ticket-ai__text">
                {{ source.processExcerpt }}
              </p>
            </div>
          </template>
          <p
            v-if="previewError"
            class="ticket-action-error"
            role="alert"
          >
            {{ previewError }}
          </p>
          <el-button
            v-if="previewPhase === 'error'"
            type="info"
            plain
            @click="loadSourcesPreview"
          >
            重试加载来源
          </el-button>
        </div>
      </div>
      <p
        v-if="generationError"
        class="ticket-action-error"
        role="alert"
      >
        {{ generationError }}
      </p>
      <template #footer>
        <el-button
          :disabled="generating"
          @click="previewOpen = false"
        >
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="generating"
          :disabled="previewPhase !== 'ready' || !sourcesPreview"
          @click="generate"
        >
          确认并生成
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
