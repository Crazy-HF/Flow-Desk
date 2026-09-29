<script setup lang="ts">
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { describeError } from '@/api/errorMessages'
import { errorCode } from '@/api/http'
import { getTicket, listTicketRecords } from '@/api/tickets'
import type { TicketDetail, TicketRecord } from '@/api/tickets'
import AppPage from '@/components/AppPage.vue'
import {
  describeRecordContext,
  ticketCloseMethodLabel,
  ticketCloseReasonLabel,
  ticketCompletionMethodLabel,
  ticketPriorityLabel,
  ticketPriorityTone,
  ticketRecordTypeLabel,
  ticketStatusLabel,
  ticketStatusTone,
} from '@/constants/tickets'
import { formatDateTime } from '@/utils/format'

/**
 * 工单详情与处理时间线（`docs/api-design.md` 5.4 / 5.5）。
 *
 * <p>两处容易读错的地方，这里显式处理：</p>
 * <p>1. **无权与不存在是同一种结果**。后端对"没有查看权限"和"编号不存在"统一返回
 * `404/TICKET_NOT_FOUND`，刻意不向调用方确认工单是否存在。界面因此也只给一种解释，
 * 不去猜"大概是没有权限"。</p>
 * <p>2. **不做动作区**。详情里的 `allowedActions` 在阶段 2 固定为空数组：没有已实现的
 * 工单动作，就不该摆出按不动的按钮。后续阶段实现动作后，按钮按这个字段渲染，
 * 而不是前端自行推导。</p>
 *
 * <p>时间线按 `sequenceNo` 正序分页，所以这里用"加载更多"向后追加，而不是页码跳转：
 * 记录是业务事实的顺序，追加既不会重排，也不会在翻页时把已经读过的上下文顶掉。</p>
 */
const route = useRoute()

const ticketNo = computed(() => String(route.params.ticketNo ?? ''))

const detail = ref<TicketDetail | null>(null)
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
/** 编号不存在或当前账号看不到它：两者在后端是同一种结果，界面也当作同一种。 */
const absent = ref(false)

const pageTitle = computed(() => detail.value?.title ?? '工单详情')

async function loadDetail(): Promise<void> {
  phase.value = 'loading'
  errorMessage.value = ''
  absent.value = false
  try {
    detail.value = await getTicket(ticketNo.value)
    phase.value = 'ready'
  } catch (error) {
    detail.value = null
    absent.value = errorCode(error) === 'TICKET_NOT_FOUND'
    errorMessage.value = describeError(error, '工单没有加载成功，请稍后重试')
    phase.value = 'error'
  }
}

// ---------- 时间线 ----------

const records = ref<TicketRecord[]>([])
const timelinePhase = ref<'loading' | 'ready' | 'error'>('loading')
const timelineError = ref('')
const recordsPage = ref(1)
const recordsTotal = ref(0)
const recordsTotalPages = ref(0)
const loadingMore = ref(false)
/** 追加下一页失败：只影响"更多"，已经读到的记录继续显示。 */
const moreError = ref('')

const hasMoreRecords = computed(() => recordsPage.value < recordsTotalPages.value)

const timeline = computed(() =>
  records.value.map((record) => ({
    ...record,
    facts: describeRecordContext(record.context, formatDateTime),
  })),
)

async function loadRecords(page: number, append: boolean): Promise<void> {
  if (append) {
    loadingMore.value = true
    moreError.value = ''
  } else {
    timelinePhase.value = 'loading'
    timelineError.value = ''
  }
  try {
    const result = await listTicketRecords(ticketNo.value, { pageNo: page })
    records.value = append ? [...records.value, ...result.items] : result.items
    recordsPage.value = result.page
    recordsTotal.value = result.totalElements
    recordsTotalPages.value = result.totalPages
    timelinePhase.value = 'ready'
  } catch (error) {
    const message = describeError(error, '处理记录没有加载成功，请稍后重试')
    if (append) {
      // 追加失败不清空已经读到的记录：它们仍然是有效的事实，不该被一次网络抖动擦掉
      moreError.value = message
    } else {
      records.value = []
      timelineError.value = message
      timelinePhase.value = 'error'
    }
  } finally {
    loadingMore.value = false
  }
}

async function loadAll(): Promise<void> {
  records.value = []
  recordsPage.value = 1
  recordsTotal.value = 0
  recordsTotalPages.value = 0
  await loadDetail()
  if (phase.value === 'ready') {
    await loadRecords(1, false)
  }
}

onMounted(() => {
  void loadAll()
})

// 详情页会在同一个组件实例里换编号（从列表再点一张工单），数据必须跟着重新取
watch(ticketNo, () => {
  void loadAll()
})
</script>

<template>
  <AppPage
    class="admin-page ticket-detail-page"
    layout="list"
    :title="pageTitle"
  >
    <div
      v-if="phase === 'loading'"
      class="ticket-panel"
      aria-busy="true"
      aria-label="正在加载工单详情"
    >
      <el-skeleton
        animated
        :rows="6"
      />
    </div>

    <section
      v-else-if="phase === 'error' && absent"
      class="ticket-state"
      role="status"
    >
      <h2>工单不存在或你没有查看权限</h2>
      <p>
        为避免泄露信息，系统对"编号不存在"和"没有权限查看"给出同一种结果，
        因此无法确认是哪种情况。请核对编号，或回到列表里选择你有权查看的工单。
      </p>
      <RouterLink
        class="ticket-state__link"
        :to="{ name: 'tickets' }"
      >
        <el-icon><ArrowLeft /></el-icon>
        返回工单列表
      </RouterLink>
    </section>

    <section
      v-else-if="phase === 'error'"
      class="ticket-state ticket-state--danger"
      role="alert"
    >
      <h2>工单没有加载成功</h2>
      <p>{{ errorMessage }}</p>
      <el-button
        :icon="Refresh"
        type="primary"
        @click="loadAll"
      >
        重新加载
      </el-button>
    </section>

    <template v-else-if="detail">
      <div class="ticket-meta">
        <span class="ticket-meta__no">{{ detail.ticketNo }}</span>
        <span class="ticket-mark">
          <span
            class="ticket-mark__dot"
            :class="`ticket-mark__dot--${ticketStatusTone(detail.status)}`"
          />
          {{ ticketStatusLabel(detail.status) }}
        </span>
        <span class="ticket-mark">
          <span
            class="ticket-mark__dot"
            :class="`ticket-mark__dot--${ticketPriorityTone(detail.priority)}`"
          />
          优先级 {{ ticketPriorityLabel(detail.priority) }}
        </span>
        <RouterLink
          class="ticket-meta__back"
          :to="{ name: 'tickets' }"
        >
          <el-icon><ArrowLeft /></el-icon>
          返回工单列表
        </RouterLink>
      </div>

      <div class="ticket-detail">
        <div class="ticket-detail__main">
          <section
            class="ticket-panel"
            aria-labelledby="ticket-description-heading"
          >
            <header class="ticket-panel__header">
              <h2 id="ticket-description-heading">
                问题描述
              </h2>
            </header>
            <div class="ticket-panel__body">
              <!-- 插值紧贴标签，不留模板缩进：这一块是 pre-wrap，多出来的空白会真的显示出来 -->
              <p class="ticket-description">
                {{ detail.description }}
              </p>
            </div>
          </section>

          <section
            class="ticket-panel"
            aria-labelledby="ticket-timeline-heading"
          >
            <header class="ticket-panel__header">
              <h2 id="ticket-timeline-heading">
                处理记录
              </h2>
              <p
                v-if="timelinePhase === 'ready' && recordsTotal > 0"
                class="ticket-panel__count"
              >
                共 {{ recordsTotal }} 条，按发生顺序排列
              </p>
            </header>
            <div class="ticket-panel__body">
              <el-skeleton
                v-if="timelinePhase === 'loading'"
                animated
                :rows="4"
                aria-busy="true"
                aria-label="正在加载处理记录"
              />

              <section
                v-else-if="timelinePhase === 'error'"
                class="ticket-state ticket-state--danger"
                role="alert"
              >
                <h3>处理记录没有加载成功</h3>
                <p>{{ timelineError }}</p>
                <el-button
                  :icon="Refresh"
                  type="primary"
                  @click="loadRecords(1, false)"
                >
                  重新加载
                </el-button>
              </section>

              <p
                v-else-if="timeline.length === 0"
                class="admin-muted"
              >
                这张工单还没有处理记录。
              </p>

              <ol
                v-else
                class="ticket-timeline"
              >
                <li
                  v-for="record in timeline"
                  :key="record.sequenceNo"
                  class="ticket-timeline__item"
                >
                  <span
                    class="ticket-timeline__marker"
                    aria-hidden="true"
                  />
                  <div class="ticket-timeline__body">
                    <div class="ticket-timeline__head">
                      <span class="ticket-timeline__type">{{ ticketRecordTypeLabel(record.recordType) }}</span>
                      <span class="ticket-timeline__meta">
                        {{ record.actor?.displayName ?? '系统' }} · {{ formatDateTime(record.createdAt) }}
                      </span>
                    </div>
                    <dl
                      v-if="record.facts.length > 0"
                      class="ticket-timeline__facts"
                    >
                      <template
                        v-for="fact in record.facts"
                        :key="fact.label"
                      >
                        <dt>{{ fact.label }}</dt>
                        <dd>{{ fact.value }}</dd>
                      </template>
                    </dl>
                  </div>
                </li>
              </ol>

              <div
                v-if="timelinePhase === 'ready' && (hasMoreRecords || moreError)"
                class="ticket-timeline__more"
              >
                <p
                  v-if="moreError"
                  class="ticket-commit__error"
                  role="alert"
                >
                  {{ moreError }}
                </p>
                <el-button
                  :loading="loadingMore"
                  :icon="Refresh"
                  type="info"
                  plain
                  @click="loadRecords(recordsPage + 1, true)"
                >
                  加载更多记录
                </el-button>
              </div>
            </div>
          </section>
        </div>

        <aside class="ticket-detail__side">
          <section
            class="ticket-panel"
            aria-labelledby="ticket-properties-heading"
          >
            <header class="ticket-panel__header">
              <h2 id="ticket-properties-heading">
                工单信息
              </h2>
            </header>
            <div class="ticket-panel__body">
              <dl class="ticket-facts">
                <dt>分类</dt>
                <dd>{{ detail.category.name }}</dd>

                <dt>状态</dt>
                <dd>{{ ticketStatusLabel(detail.status) }}</dd>

                <dt>优先级</dt>
                <dd>{{ ticketPriorityLabel(detail.priority) }}</dd>

                <dt>提交人</dt>
                <dd>{{ detail.requester.displayName }}</dd>

                <dt>负责人</dt>
                <dd>{{ detail.assignee?.displayName ?? '未分配' }}</dd>

                <template v-if="detail.actionDeadlineAt">
                  <dt>当前期限</dt>
                  <dd>{{ formatDateTime(detail.actionDeadlineAt) }}</dd>
                </template>

                <dt>创建时间</dt>
                <dd>{{ formatDateTime(detail.createdAt) }}</dd>

                <dt>最近更新</dt>
                <dd>{{ formatDateTime(detail.updatedAt) }}</dd>

                <template v-if="detail.endedAt">
                  <dt>结束时间</dt>
                  <dd>{{ formatDateTime(detail.endedAt) }}</dd>
                </template>

                <template v-if="ticketCompletionMethodLabel(detail.completionMethod)">
                  <dt>完成方式</dt>
                  <dd>{{ ticketCompletionMethodLabel(detail.completionMethod) }}</dd>
                </template>

                <template v-if="ticketCloseMethodLabel(detail.closeMethod)">
                  <dt>关闭方式</dt>
                  <dd>{{ ticketCloseMethodLabel(detail.closeMethod) }}</dd>
                </template>

                <template v-if="ticketCloseReasonLabel(detail.closeReason)">
                  <dt>关闭原因</dt>
                  <dd>{{ ticketCloseReasonLabel(detail.closeReason) }}</dd>
                </template>
              </dl>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </AppPage>
</template>
