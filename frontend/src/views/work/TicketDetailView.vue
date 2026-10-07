<script setup lang="ts">
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, h, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { describeError } from '@/api/errorMessages'
import { errorCode } from '@/api/http'
import {
  addProcessingRecord,
  claimTicket,
  confirmResolution,
  getTicket,
  listTicketRecords,
  reportUnresolved,
  requestSupplementTicket,
  submitResolution,
  supplementTicket,
  withdrawSupplementRequest,
} from '@/api/tickets'
import type { TicketActionResult, TicketDetail, TicketRecord } from '@/api/tickets'
import AppPage from '@/components/AppPage.vue'
import {
  describeRecordContext,
  permittedActions,
  ticketCloseMethodLabel,
  ticketCloseReasonLabel,
  ticketCompletionMethodLabel,
  TICKET_ACTIONS,
  ticketPriorityLabel,
  ticketPriorityTone,
  ticketRecordTypeLabel,
  ticketStatusLabel,
  ticketStatusTone,
} from '@/constants/tickets'
import type { TicketActionMeta, TicketActionName } from '@/constants/tickets'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import TicketActionContentField from './TicketActionContentField.vue'

/**
 * 工单详情与处理时间线（`docs/api-design.md` 5.4 / 5.5），以及这张工单上可执行的六个动作
 * （6.3：`claim` / `add-processing-record` / `submit-resolution` / `request-supplement` /
 * `withdraw-supplement-request`；6.4：`confirm-resolution` / `report-unresolved` / `supplement`）。
 *
 * <p>三处容易读错的地方，这里显式处理：</p>
 * <p>1. **无权与不存在是同一种结果**。后端对"没有查看权限"和"编号不存在"统一返回
 * `404/TICKET_NOT_FOUND`，刻意不向调用方确认工单是否存在。界面因此也只给一种解释，
 * 不去猜"大概是没有权限"。</p>
 * <p>2. **按钮来自 `allowedActions`，不是前端推导**。后端按当前用户、角色、工单关系与状态算好
 * 可用动作；界面再用 `permittedActions` 叠一层本账号权限判断（应对"取详情之后被撤权"的窗口）。
 * 完整状态机里剩下的动作（调整分类/优先级、转交、关闭、取消）尚未在服务端放行，
 * 所以它们只在服务端开始返回时才会出现——这不是漏做，是不摆按不动的按钮。</p>
 * <p>3. **动作结果里的 `version` 必须回写到详情**。每个动作都带乐观锁：不刷新就拿旧版本
 * 再发一次，会得到 `409/TICKET_CONFLICT`。所以每次动作成功后重新取详情与时间线，
 * 冲突（409）时也主动重新取一次，把版本对齐到服务端的当前值。</p>
 *
 * <p>时间线按 `sequenceNo` 正序分页，所以这里用"加载更多"向后追加，而不是页码跳转：
 * 记录是业务事实的顺序，追加既不会重排，也不会在翻页时把已经读过的上下文顶掉。</p>
 */
const route = useRoute()
const auth = useAuthStore()

const ticketNo = computed(() => String(route.params.ticketNo ?? ''))

const detail = ref<TicketDetail | null>(null)
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
/** 编号不存在或当前账号看不到它：两者在后端是同一种结果，界面也当作同一种。 */
const absent = ref(false)

const pageTitle = computed(() => detail.value?.title ?? '工单详情')

/**
 * 重新取详情。
 *
 * <p>`silent` 用于动作成功后的回写：这一趟是刷新数据，不是重新打开页面，不能把已经渲染好的
 * 详情退回骨架屏——用户刚点完按钮，页面闪一下会比按钮没有反馈更像出错。</p>
 */
async function loadDetail(silent = false): Promise<void> {
  if (!silent) {
    phase.value = 'loading'
  }
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

async function loadRecords(page: number, append: boolean, silent = false): Promise<void> {
  if (append) {
    loadingMore.value = true
    moreError.value = ''
  } else if (!silent) {
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
    } else if (!silent) {
      records.value = []
      timelineError.value = message
      timelinePhase.value = 'error'
    }
    // silent 且失败时保持现有时间线：动作已经成功，把记录清空只会看起来像动作失败了
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
  actionError.value = ''
  void loadAll()
})

// ---------- 这张工单上的动作 ----------

/** 可渲染的动作：`allowedActions` 与当前账号权限的交集，理由见页面注释第 2 点。 */
const availableActions = computed(() =>
  detail.value ? permittedActions(detail.value.allowedActions, (code) => auth.hasPermission(code)) : [],
)

/** 正在执行的动作名；同一时刻只放行一个，按钮据此整体禁用。 */
const runningAction = ref<TicketActionName | null>(null)

/**
 * 期限这一栏怎么读。
 *
 * <p>`actionDeadlineAt` 是同一个字段，对两种人却是两件事：对**要动手的那个人**说的是
 * "我要在什么时候之前做完"，对**等结果的那个人**说的是"等到什么时候为止"。所以标签按
 * **当前工单状态**取（等待态各有各的期限名），提示再按**这件事归谁做**分岔——两个等待态
 * 都不再有一个对谁都能读、但谁都不准的"当前期限"。</p>
 *
 * <p>两种等待态都要写清"到期不会自动处理"：本版本没有超时自动任务
 * （`docs/implementation-plan.md` 9.3 第 4 条的用户裁决），期限到期不会自动改变状态。
 * **提示按状态与角色给，不按可做动作的多寡给**——负责人只有「撤回补充请求」这一件事可做，
 * 若用"有没有动作"判断，负责人在待补充时反而看不到这句提示，而界面恰恰就在暗示
 * "到点系统会处理"。</p>
 */
const deadlineFact = computed<{ label: string; hint?: string } | null>(() => {
  if (!detail.value?.actionDeadlineAt) {
    return null
  }

  if (detail.value.status === 'WAITING_FOR_CONFIRMATION') {
    // 同一句提示不能同时说给两个人：提交人要做的是"去确认"，负责人要做的是"别催、等提交人"
    return availableActions.value.includes('confirm-resolution')
      ? {
          label: '确认期限',
          hint: '到期不会自动处理，仍需要你手动确认。',
        }
      : {
          label: '确认期限',
          hint: '到期不会自动处理，需要提交人手动确认，工单会一直停在待员工确认。',
        }
  }

  if (detail.value.status === 'WAITING_FOR_REQUESTER') {
    // 提交人看到的是"我要在什么时候之前补充"，负责人看到的是"等员工回到什么时候"
    return availableActions.value.includes('supplement')
      ? {
          label: '补充截止时间',
          hint: '到期不会自动关闭工单，仍需提交人手动补充。',
        }
      : {
          label: '补充期限',
          hint: '到期不会自动处理，工单会一直停在待员工补充。',
        }
  }

  /**
   * 只有两个等待态会有期限（`ck_ticket_status_deadline`），走到这里说明状态与字段对不上。
   * 不去猜一个好看的标签：把日期原样显示出来，标签直说是"期限"。
   */
  return { label: '期限' }
})

/** 动作失败的原因：显示在动作区里，而不是只在右上角闪一下。 */
const actionError = ref('')

/**
 * 正文输入。
 *
 * <p>放在组件里而不是 `ElMessageBox.prompt`：`prompt` 只给单行 `input`，而处理过程与解决结果
 * 都是要写几段话的正文，且契约上限是 10000 字符。自定义插槽里用一个受控 `ref`，
 * 确认时校验、失败时保留用户已经写好的内容。</p>
 */
const actionContent = ref('')

/**
 * 动作名 → 请求。
 *
 * <p>刻意写成 `Record<TicketActionName, ...>` 而不是 if/else 链：登记表里新增一个动作却忘了
 * 接上请求时，类型检查会直接报错；if/else 的兜底 `else` 则会把请求悄悄发成**另一个动作**。
 * 2026-10-06 的片 A E2E 实测到的正是这一种：两个新动作落进了 `else`，被发成 `submit-resolution`
 * ——界面上按钮点了像没反应，服务端收到的却是错误动作（若状态刚好允许，还会真的改错东西）。</p>
 */
const actionRequests: Record<
  TicketActionName,
  (
    ticketNo: string,
    payload: { version: number },
    content: string,
  ) => Promise<TicketActionResult>
> = {
  claim: (ticketNo, payload) => claimTicket(ticketNo, payload),
  'confirm-resolution': (ticketNo, payload) => confirmResolution(ticketNo, payload),
  'add-processing-record': (ticketNo, payload, content) =>
    addProcessingRecord(ticketNo, { ...payload, content }),
  'submit-resolution': (ticketNo, payload, content) =>
    submitResolution(ticketNo, { ...payload, content }),
  'withdraw-supplement-request': (ticketNo, payload, content) =>
    withdrawSupplementRequest(ticketNo, { ...payload, reason: content }),
  'report-unresolved': (ticketNo, payload, content) =>
    reportUnresolved(ticketNo, { ...payload, reason: content }),
  'request-supplement': (ticketNo, payload, content) =>
    requestSupplementTicket(ticketNo, { ...payload, content }),
  supplement: (ticketNo, payload, content) => supplementTicket(ticketNo, { ...payload, content }),
}

async function performAction(name: TicketActionName, content: string): Promise<void> {
  const current = detail.value
  if (!current || runningAction.value) {
    return
  }

  runningAction.value = name
  actionError.value = ''
  try {
    await actionRequests[name](current.ticketNo, { version: current.version }, content)

    // 动作结果里的 status/version 必须回写：下一动作要靠新版本做乐观锁
    await loadDetail(true)
    await loadRecords(1, false, true)
    ElMessage.success(`${TICKET_ACTIONS[name].label}成功`)
  } catch (error) {
    actionError.value = describeError(error, `${TICKET_ACTIONS[name].label}没有成功，请稍后重试`)
    /**
     * `409/TICKET_CONFLICT` 是版本过期：别人已经改过这张工单。重新取一次详情把版本对齐，
     * 同时让按钮按服务端最新状态重算——否则页面会停在一个"看起来还能点"的旧快照上，
     * 用户再点一次还是同一个 409。
     */
    if (errorCode(error) === 'TICKET_CONFLICT') {
      await loadDetail(true)
      await loadRecords(1, false, true)
    }
  } finally {
    runningAction.value = null
  }
}

/**
 * 确认框的内容：说明 + 需要写正文时的输入框。
 *
 * <p>输入框是一个独立的单文件组件（`TicketActionContentField.vue`），不是在这里用渲染函数
 * 拼出来的：应用按运行时版 Vue 打包，`template` 选项不会被编译，而 `ElInput` 的
 * `modelValue` / `update:modelValue` 又是 props 而不是事件，渲染函数里写
 * `onUpdate:modelValue` 只会得到一个普通 prop，输入不会回流——那会变成"填了内容却提交空正文"。
 * 单文件组件里的 `v-model` 由构建期编译，两个问题都不存在。</p>
 */
function buildActionDialog(meta: TicketActionMeta) {
  return h('div', { class: 'ticket-action-dialog__body' }, [
    h('p', { class: 'ticket-action-dialog__note' }, meta.description),
    meta.content
      ? h(TicketActionContentField, {
          label: meta.content.label,
          placeholder: meta.content.placeholder,
          maxLength: meta.content.maxLength,
          modelValue: actionContent.value,
          'onUpdate:modelValue': (value: string) => {
            actionContent.value = value
          },
        })
      : null,
  ])
}

/**
 * 打开确认框并执行动作。
 *
 * <p>确认框用 `ElMessageBox.confirm` + `message` 插槽，而不是 `prompt`：`prompt` 只给单行输入，
 * 而处理过程与解决结果都是要写几段话的正文（契约上限 10000 字符）。</p>
 *
 * <p>校验走 `beforeClose`，而不是"先关弹窗再检查"：`handleAction` 在 `beforeClose` 里
 * 只有调用 `done()` 才真正关闭（见 Element Plus `message-box/src/index.vue`）。
 * 所以正文为空时提示一句、**不调用 `done()`**，用户写好的其它内容与弹窗都还在原处；
 * 若先关掉再提示，用户得重新点一次按钮、重写一遍。</p>
 */
async function runAction(name: TicketActionName): Promise<void> {
  const meta = TICKET_ACTIONS[name]
  actionError.value = ''
  actionContent.value = ''

  await new Promise<void>((resolve) => {
    void ElMessageBox.confirm(meta.description, meta.label, {
      confirmButtonText: meta.label,
      cancelButtonText: '取消',
      /** 影响终态的动作不许点遮罩关掉：一次误点就把工单推进终态，代价比多按一次取消大。 */
      closeOnClickModal: false,
      customClass: 'admin-dialog ticket-action-dialog',
      message: () => buildActionDialog(meta),
      beforeClose: (action, _instance, done) => {
        if (action !== 'confirm') {
          done()
          resolve()
          return
        }

        const content = actionContent.value.trim()
        if (meta.content && content === '') {
          ElMessage.warning(`请先填写${meta.content.label}`)
          return
        }

        done()
        // 请求本身不阻塞弹窗关闭：动作结果由页面上的动作区与提示反馈
        void performAction(name, content).finally(resolve)
      },
    }).catch(() => {
      // 取消或关闭：用户自己放弃，不做动作，也不报错
      resolve()
    })
  })
}
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
            v-if="availableActions.length > 0"
            class="ticket-panel ticket-action-panel"
            aria-labelledby="ticket-actions-heading"
          >
            <header class="ticket-panel__header">
              <h2 id="ticket-actions-heading">
                现在可以做的操作
              </h2>
              <p class="ticket-panel__count">
                可操作项由服务端按你的权限、与这张工单的关系和当前状态判定
              </p>
            </header>
            <div class="ticket-panel__body">
              <!-- 按钮固定在左，说明文字吃掉剩余宽度：主操作的位置不随说明长短左右移动 -->
              <div
                v-for="name in availableActions"
                :key="name"
                class="ticket-action"
              >
                <el-button
                  :loading="runningAction === name"
                  :disabled="runningAction !== null && runningAction !== name"
                  :type="TICKET_ACTIONS[name].destructive ? 'warning' : 'primary'"
                  @click="runAction(name)"
                >
                  {{ TICKET_ACTIONS[name].label }}
                </el-button>
                <p class="ticket-action__note">
                  {{ TICKET_ACTIONS[name].description }}
                </p>
              </div>
            </div>
          </section>

          <p
            v-if="actionError"
            class="ticket-action-error"
            role="alert"
          >
            {{ actionError }}
          </p>

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

                <template v-if="deadlineFact">
                  <!-- 同一个字段对三种人是三件事：负责人看的是"等员工到什么时候"，
                       提交人在待补充时看的是"我要在什么时候之前补充"，在待确认时看的是"什么时候之前确认"。
                       标签与提示都由 deadlineFact 按当前视角给出。 -->
                  <dt>{{ deadlineFact.label }}</dt>
                  <dd>
                    {{ formatDateTime(detail.actionDeadlineAt) }}
                    <span
                      v-if="deadlineFact.hint"
                      class="ticket-facts__hint"
                    >{{ deadlineFact.hint }}</span>
                  </dd>
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
