<script setup lang="ts">
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, h, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { describeError } from '@/api/errorMessages'
import { errorCode } from '@/api/http'
import { listCategoryOptions } from '@/api/categories'
import {
  addProcessingRecord,
  approveCancel,
  cancelTicket,
  changeTicketCategory,
  changeTicketPriority,
  claimTicket,
  closeTicket,
  confirmResolution,
  getTicket,
  listTicketRecords,
  listTransferCandidates,
  rejectCancel,
  reportUnresolved,
  requestCancelTicket,
  requestSupplementTicket,
  submitResolution,
  supplementTicket,
  transferTicket,
  withdrawCancelRequest,
  withdrawSupplementRequest,
} from '@/api/tickets'
import type {
  TicketActionResult,
  TicketCloseReason,
  TicketDetail,
  TicketPriority,
  TicketRecord,
} from '@/api/tickets'
import AppPage from '@/components/AppPage.vue'
import {
  describeRecordContext,
  permittedActions,
  ticketCloseMethodLabel,
  ticketCloseReasonLabel,
  ticketCompletionMethodLabel,
  TICKET_ACTIONS,
  TICKET_CLOSE_REASON_OPTIONS,
  TICKET_PRIORITY_OPTIONS,
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
import TicketActionSelectField from './TicketActionSelectField.vue'
import TicketActionTextField from './TicketActionTextField.vue'

/**
 * 工单详情与处理时间线（`docs/api-design.md` 5.4 / 5.5），以及这张工单上可执行的动作
 * （6.3：`claim` / `add-processing-record` / `submit-resolution` / `request-supplement` /
 * `withdraw-supplement-request` / `change-category` / `change-priority` / `transfer` / `close` /
 * `approve-cancel` / `reject-cancel`；6.4：`confirm-resolution` / `report-unresolved` / `supplement` /
 * `cancel` / `request-cancel` / `withdraw-cancel-request`）。
 *
 * <p>三处容易读错的地方，这里显式处理：</p>
 * <p>1. **无权与不存在是同一种结果**。后端对"没有查看权限"和"编号不存在"统一返回
 * `404/TICKET_NOT_FOUND`，刻意不向调用方确认工单是否存在。界面因此也只给一种解释，
 * 不去猜"大概是没有权限"。</p>
 * <p>2. **按钮来自 `allowedActions`，不是前端推导**。后端按当前用户、角色、工单关系与状态算好
 * 可用动作；界面再用 `permittedActions` 叠一层本账号权限判断（应对"取详情之后被撤权"的窗口）。
 * 登记表之外的动作名（后端将来先放行的新动作）不会变成按钮——宁可不摆，也不摆一个按不动的。</p>
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

/**
 * 待批准的撤销申请怎么讲给当前这个人（2026-10-08 两阶段撤销）。
 *
 * <p>申请期间工单**状态不变**，所以"谁在等谁"推不出来，只能按"这一格动作归谁"分岔：
 * 有 `approve-cancel` 的人是**做决定的那一方**（他需要先读到提交人写的理由），
 * 有 `withdraw-cancel-request` 的人是**发起的那一方**（他还能撤回），
 * 两者都没有的人只是旁观者（例如已经交接走的历史负责人、或刚好被撤掉处理权限的账号），
 * 这时只陈述事实，不指使他做任何事。</p>
 *
 * <p>与 `deadlineFact` 一样，提示按**角色**给而不是按"有没有按钮"给：负责人若刚好没有
 * `TICKET_PROCESS`，他也应该看到"有人在申请撤销"，而不是看到一块空的动作区。</p>
 */
const cancelRequestNotice = computed<{ lead: string; actionHint: string } | null>(() => {
  const current = detail.value
  if (!current?.cancelRequest) {
    return null
  }

  const requester = current.requester.displayName
  const assignee = current.assignee?.displayName

  if (availableActions.value.includes('approve-cancel')) {
    return {
      lead: `${requester}申请撤销这张工单，正在等你处理。`,
      actionHint: '在下面的操作里选择「同意撤销」（工单进入已取消）或「驳回撤销申请」（工单继续处理）。',
    }
  }

  if (availableActions.value.includes('withdraw-cancel-request')) {
    return {
      lead: '你已申请撤销这张工单，正在等待当前负责人处理。',
      // 人名直接贴着动词：中文里 name + 空格 + 动词会被读成一个停顿，而这里只是一个主语
      actionHint: assignee
        ? `在${assignee}处理之前，你可以在下面撤回这次申请。`
        : '在负责人处理之前，你可以在下面撤回这次申请。',
    }
  }

  return {
    lead: `${requester}已申请撤销这张工单，正在等待当前负责人处理。`,
    actionHint: '在负责人同意或驳回之前，工单仍按当前状态继续流转。',
  }
})

/** 动作失败的原因：显示在动作区里，而不是只在右上角闪一下。 */
const actionError = ref('')

/**
 * 动作要写的那段说明。
 *
 * <p>放在组件里而不是 `ElMessageBox.prompt`：`prompt` 只给单行 `input`，而处理过程与解决结果
 * 都是要写几段话的正文，且契约上限是 10000 字符。自定义插槽里用一个受控 `ref`，
 * 确认时校验、失败时保留用户已经写好的内容。</p>
 *
 * <p>登记表里它有两个字段名：正文类动作叫 `content`（处理记录、解决结果、补充内容），
 * 原因类动作叫 `reason`（撤回、未解决，以及片 C 的调整与转交）。**两者在界面上是同一件事**
 * ——"这个动作要写的那段说明"，形状也完全相同（标签、占位、长度上限），且没有任何一个动作会同时
 * 需要两段，所以共用这一个引用；调用处按动作自己的契约把值放进 `content` 或 `reason`。</p>
 */
const actionContent = ref('')

/**
 * 弹窗里先选的目标值：分类 id、优先级编码或接手人 id。
 *
 * <p>可空是它的正常状态：弹窗刚打开时用户还没选。值的类型由 `el-select` 统一给成
 * `string | number`，各动作在发请求前按自己的契约取用（`beforeClose` 已经拦掉"没选"）。</p>
 */
const actionTarget = ref<string | number | undefined>(undefined)

/**
 * 弹窗里**条件下出现**的那个输入：关闭工单选「重复工单」时要补的工单编号（片 D）。
 *
 * <p>与 `actionContent` 分开是因为它们的形状和归属都不同：正文/原因永远出现，是一段说明；
 * 这一个只在选中的原因等于登记表里的 `whenValue` 时才出现，填的是一张工单的编号。
 * **隐藏时它不参与提交**（空串表示"这次不带这个字段"），理由见 `activeConditionalField`。</p>
 */
const actionConditionalText = ref('')

/** 弹窗里的一个可选项。值可能是分类 / 用户 id 或优先级编码。 */
type ActionSelectChoice = { value: string | number; label: string }

/** 条件输入的登记信息（`TicketActionMeta.conditionalText` 声明了才有）。 */
type ConditionalTextField = NonNullable<TicketActionMeta['conditionalText']>

/**
 * 选项的取数状态。
 *
 * <p>选项是**打开弹窗之后**才取的（不能先取完再弹窗，否则用户先看到一段没有反馈的等待），
 * 所以三种状态都必须在弹窗里可见：取的过程中要有加载态，取失败要带着 `describeError` 的文案
 * 显示在弹窗里，而不是留下一个空下拉让用户对着它点确认。`idle` 只属于不需要选目标值的动作。</p>
 */
const actionSelectPhase = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const actionSelectOptions = ref<readonly ActionSelectChoice[]>([])
const actionSelectError = ref('')

/**
 * 最近一次打开弹窗所取的选项属于哪一趟。
 *
 * <p>选项是异步取的，而用户可以"打开 → 取消 → 立刻打开另一个动作"。没有这个令牌时，前一趟的响应
 * 会写进后一趟的弹窗——转交弹窗里列出分类就是现实后果，选中之后提交的是分类 id 当接手人 id。
 * 只认最后一次打开的那一趟。</p>
 */
let actionSelectRequest = 0

/**
 * 按动作的 `select.source` 取目标值选项。
 *
 * <p>两类来源，取法不同但出口是同一个：`priority` 与 `reasonCode` 直接用前端已有的静态刻度
 * （三级优先级、三种关闭原因），为它们各发一次请求既多等一次往返，也多一个失败点；
 * 分类与接手人必须问服务端——分类要的是"当前启用"的那一份，接手人还要服务端判定谁有资格接。</p>
 */
async function loadActionSelect(meta: TicketActionMeta): Promise<void> {
  const select = meta.select
  if (!select) {
    return
  }

  const request = (actionSelectRequest += 1)
  actionSelectPhase.value = 'loading'
  actionSelectError.value = ''
  actionSelectOptions.value = []

  try {
    let choices: readonly ActionSelectChoice[]
    if (select.source === 'priority') {
      choices = TICKET_PRIORITY_OPTIONS
    } else if (select.source === 'reasonCode') {
      choices = TICKET_CLOSE_REASON_OPTIONS
    } else if (select.source === 'category') {
      choices = (await listCategoryOptions()).map((option) => ({
        value: option.id,
        label: option.name,
      }))
    } else {
      choices = (await listTransferCandidates(ticketNo.value)).map((candidate) => ({
        value: candidate.id,
        label: candidate.displayName,
      }))
    }

    // 期间已经打开了另一个动作的弹窗：这一趟的结果不再写进去（见 actionSelectRequest）
    if (request !== actionSelectRequest) {
      return
    }
    actionSelectOptions.value = choices
    actionSelectPhase.value = 'ready'
  } catch (error) {
    if (request !== actionSelectRequest) {
      return
    }
    actionSelectError.value = describeError(error, `${select.label}选项没有加载成功，请稍后重试`)
    actionSelectPhase.value = 'error'
  }
}

/**
 * 选中的就是当前值。
 *
 * <p>服务端允许同值调整（它只看状态、身份与版本），但那样会在时间线上凭空多出一条
 * "分类从硬件改为硬件"。这类记录对后来读时间线的人只是噪音，所以界面在这里挡住。</p>
 *
 * <p>只有分类与优先级有"当前值"这回事：转交的候选人已经排除了提交人与当前负责人，
 * 关闭原因更不是工单上的一个既有字段。这两类一律返回 `false`，而不是落进某个兜底分支
 * 去和 `priority` 比——那种比较永远为假，看起来"没问题"，实际是把一个错误留在代码里。</p>
 */
function isUnchangedChoice(select: NonNullable<TicketActionMeta['select']>): boolean {
  const current = detail.value
  if (!current) {
    return false
  }
  if (select.source === 'category') {
    return actionTarget.value === current.category.id
  }
  if (select.source === 'priority') {
    return actionTarget.value === current.priority
  }
  return false
}

/**
 * 这次弹窗里条件输入是否出现；出现时返回它的登记信息。
 *
 * <p>**渲染、校验、提交三处共用这一条判定**：`whenValue` 只在这里比一次，任何一处单独改，
 * 都会出现"输入框没显示、请求里却带着它"这类只有服务端才发现的不一致——而服务端对多传的
 * 单号返回的是 `400`，用户看到的是一句与他刚做的事对不上的报错。</p>
 */
function activeConditionalField(meta: TicketActionMeta): ConditionalTextField | null {
  const field = meta.conditionalText
  if (!field) {
    return null
  }
  return String(actionTarget.value ?? '') === field.whenValue ? field : null
}

/**
 * 动作名 → 请求。
 *
 * <p>刻意写成 `Record<TicketActionName, ...>` 而不是 if/else 链：登记表里新增一个动作却忘了
 * 接上请求时，类型检查会直接报错；if/else 的兜底 `else` 则会把请求悄悄发成**另一个动作**。
 * 2026-10-06 的片 A E2E 实测到的正是这一种：两个新动作落进了 `else`，被发成 `submit-resolution`
 * ——界面上按钮点了像没反应，服务端收到的却是错误动作（若状态刚好允许，还会真的改错东西）。</p>
 *
 * <p>第三个参数是这个动作要写的那段说明，各动作按契约放进 `content` 或 `reason`；
 * 需要先选目标值的动作（片 C 的三个、片 D 的关闭）另外读 `actionTarget`，它的"没选"与"选了当前值"
 * 已在 `beforeClose` 里被拦下，所以这里不必再判空。第四个参数是条件输入的值，规则见它的注释。</p>
 */
const actionRequests: Record<
  TicketActionName,
  (
    ticketNo: string,
    payload: { version: number },
    content: string,
    /**
     * 条件输入的值（片 D 的「重复工单」）：没出现或留空时是空串，**空串表示"这次不带这个字段"**。
     * 调用处已按 `whenValue` 判过可见性，所以动作里不必再问一次"该不该发"。
     */
    conditionalText: string,
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
  'change-category': (ticketNo, payload, content) =>
    changeTicketCategory(ticketNo, {
      ...payload,
      categoryId: Number(actionTarget.value),
      reason: content,
    }),
  'change-priority': (ticketNo, payload, content) =>
    changeTicketPriority(ticketNo, {
      ...payload,
      // 下拉里的值就来自 TICKET_PRIORITY_OPTIONS 这三项，收窄不会接受契约之外的编码
      priority: String(actionTarget.value) as TicketPriority,
      reason: content,
    }),
  transfer: (ticketNo, payload, content) =>
    transferTicket(ticketNo, {
      ...payload,
      newAssigneeId: Number(actionTarget.value),
      reason: content,
    }),
  /**
   * 关闭工单：原因是静态三选一，说明走 `description`，被判定为重复时才多带一个工单编号。
   *
   * <p>非重复原因**不带** `duplicateTicketNo`：服务端对多传的单号返回 `400` 而不是忽略它，
   * 所以"输入框没出现"与"请求体里没有这个键"必须是同一件事（可见性在 `performAction` 里判定）。</p>
   */
  close: (ticketNo, payload, content, conditionalText) =>
    closeTicket(ticketNo, {
      ...payload,
      // 下拉里的值就来自 TICKET_CLOSE_REASON_OPTIONS 这三项，收窄不会接受契约之外的编码
      reasonCode: String(actionTarget.value) as TicketCloseReason,
      description: content,
      ...(conditionalText === '' ? {} : { duplicateTicketNo: conditionalText }),
    }),
  supplement: (ticketNo, payload, content) => supplementTicket(ticketNo, { ...payload, content }),
  cancel: (ticketNo, payload, content) => cancelTicket(ticketNo, { ...payload, reason: content }),
  /**
   * 两阶段撤销的四个动作（2026-10-08 规则变更）。
   *
   * <p>发起与驳回都要写一段说明（走 `reason`）；同意与撤回与 `claim` 一样只需要版本，
   * 登记表里没有 `reason` 槽，弹窗也就不会摆出一个没人看的输入框。</p>
   */
  'request-cancel': (ticketNo, payload, content) =>
    requestCancelTicket(ticketNo, { ...payload, reason: content }),
  'withdraw-cancel-request': (ticketNo, payload) => withdrawCancelRequest(ticketNo, payload),
  'approve-cancel': (ticketNo, payload) => approveCancel(ticketNo, payload),
  'reject-cancel': (ticketNo, payload, content) =>
    rejectCancel(ticketNo, { ...payload, reason: content }),
}

async function performAction(name: TicketActionName, content: string): Promise<void> {
  const current = detail.value
  if (!current || runningAction.value) {
    return
  }

  /**
   * 条件输入只在它该出现的时候取值：隐藏时给空串，动作据此决定"不带这个字段"。
   * 这条判定与 `beforeClose` 的校验共用 `activeConditionalField`，两处不会各判一次。
   */
  const conditionalText = activeConditionalField(TICKET_ACTIONS[name])
    ? actionConditionalText.value.trim()
    : ''

  runningAction.value = name
  actionError.value = ''
  try {
    await actionRequests[name](
      current.ticketNo,
      { version: current.version },
      content,
      conditionalText,
    )

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
 * 确认框的内容：说明 + 需要先选的目标值 + 条件下出现的第二个输入 + 要写的那段说明。
 *
 * <p>三个输入都是独立的单文件组件（`TicketActionSelectField.vue` /
 * `TicketActionTextField.vue` / `TicketActionContentField.vue`），不是在这里用渲染函数拼出来的：
 * 应用按运行时版 Vue 打包，`template` 选项不会被编译，而 `ElInput` / `ElSelect` 的
 * `modelValue` / `update:modelValue` 又是 props 而不是事件，渲染函数里写 `onUpdate:modelValue`
 * 只会得到一个普通 prop，输入不会回流——那会变成"填了原因却提交空正文"。单文件组件里的
 * `v-model` 由构建期编译，两个问题都不存在。</p>
 */
function buildActionDialog(meta: TicketActionMeta) {
  const select = meta.select
  /** 正文与原因在界面上是同一件事，只是契约字段名不同，理由见 `actionContent`。 */
  const textField = meta.content ?? meta.reason
  const conditionalField = activeConditionalField(meta)

  return h('div', { class: 'ticket-action-dialog__body' }, [
    h('p', { class: 'ticket-action-dialog__note' }, meta.description),
    select
      ? h(TicketActionSelectField, {
          label: select.label,
          placeholder: select.placeholder,
          options: actionSelectOptions.value,
          loading: actionSelectPhase.value === 'loading',
          disabled: actionSelectPhase.value !== 'ready',
          // 取失败时**不借"没有可选项"的说法**：那会让人以为服务端说没人可接手，
          // 真正的原因由下面那段说明单独给出
          emptyText: actionSelectError.value === '' ? select.emptyText : '',
          modelValue: actionTarget.value,
          'onUpdate:modelValue': (value: string | number | undefined) => {
            actionTarget.value = value
          },
        })
      : null,
    actionSelectError.value !== ''
      ? h('p', { class: 'ticket-action-dialog__error', role: 'alert' }, actionSelectError.value)
      : null,
    // 条件输入紧跟在触发它的那一项下面：刚选完「重复工单」，要补的编号就在同一只手上
    conditionalField
      ? h(TicketActionTextField, {
          label: conditionalField.label,
          placeholder: conditionalField.placeholder,
          maxLength: conditionalField.maxLength,
          modelValue: actionConditionalText.value,
          'onUpdate:modelValue': (value: string) => {
            actionConditionalText.value = value
          },
        })
      : null,
    textField
      ? h(TicketActionContentField, {
          label: textField.label,
          placeholder: textField.placeholder,
          maxLength: textField.maxLength,
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
 * 而处理过程与解决结果都是要写几段话的正文（契约上限 10000 字符），调整与转交还要先选目标值。</p>
 *
 * <p>校验走 `beforeClose`，而不是"先关弹窗再检查"：`handleAction` 在 `beforeClose` 里
 * 只有调用 `done()` 才真正关闭（见 Element Plus `message-box/src/index.vue`）。
 * 所以输入不完整时提示一句、**不调用 `done()`**，用户写好的其它内容与弹窗都还在原处；
 * 若先关掉再提示，用户得重新点一次按钮、重写一遍。</p>
 */
async function runAction(name: TicketActionName): Promise<void> {
  const meta = TICKET_ACTIONS[name]
  actionError.value = ''
  actionContent.value = ''
  actionTarget.value = undefined
  actionConditionalText.value = ''

  /**
   * 选项与弹窗同时开始：先取完再弹窗，用户会先看到一段没有任何反馈的等待；
   * 而 `loadActionSelect` 是同步进入加载态的，弹窗第一帧就能说明"正在取"。
   */
  void loadActionSelect(meta)

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

        const select = meta.select
        if (select) {
          if (actionSelectPhase.value === 'loading') {
            ElMessage.warning(`${select.label}还在加载，请稍候再提交`)
            return
          }
          if (actionSelectPhase.value === 'error') {
            ElMessage.warning(actionSelectError.value)
            return
          }
          if (actionSelectOptions.value.length === 0) {
            ElMessage.warning(select.emptyText)
            return
          }
          if (actionTarget.value === undefined) {
            ElMessage.warning(`请先选择${select.label}`)
            return
          }
          if (isUnchangedChoice(select)) {
            ElMessage.warning('选的是当前值，没有变化，不需要提交')
            return
          }
        }

        /**
         * 条件输入出现时就是必填：它对应的是服务端要解析的目标工单编号，缺了会被判
         * `400`；顺着上面的选择框往下先校验它，用户看到的第一句提示就是他刚做的那一步。
         */
        const conditionalField = activeConditionalField(meta)
        if (conditionalField && actionConditionalText.value.trim() === '') {
          ElMessage.warning(`请先填写${conditionalField.label}`)
          return
        }

        const text = actionContent.value.trim()
        const textField = meta.content ?? meta.reason
        if (textField && text === '') {
          ElMessage.warning(`请先填写${textField.label}`)
          return
        }

        done()
        // 请求本身不阻塞弹窗关闭：动作结果由页面上的动作区与提示反馈
        void performAction(name, text).finally(resolve)
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
          <!--
            待批准的撤销申请：申请期间工单状态不变，所以它推不出来，只能靠详情单独返回的
            cancelRequest。放在动作区**之前**而不是动作区里面：负责人要先读到"为什么有人要撤销"
            才谈得上同意或驳回，而这个块在没有决策权限的旁观者那里也必须看得见——那时动作区是空的。
          -->
          <section
            v-if="detail.cancelRequest"
            class="ticket-panel ticket-cancel-request"
            aria-labelledby="ticket-cancel-request-heading"
          >
            <header class="ticket-panel__header">
              <h2 id="ticket-cancel-request-heading">
                撤销申请待处理
              </h2>
              <p class="ticket-panel__count">
                申请期间工单的状态与期限都不变
              </p>
            </header>
            <div class="ticket-panel__body">
              <p class="ticket-cancel-request__lead">
                {{ cancelRequestNotice?.lead }}
              </p>
              <!-- 插值单独成行（模板规则要求）：Vue 的 condense 会把首尾空白去掉，pre-wrap 不会多出空行 -->
              <p class="ticket-cancel-request__reason">
                {{ detail.cancelRequest.reason }}
              </p>
              <dl class="ticket-facts ticket-cancel-request__facts">
                <dt>申请时间</dt>
                <dd>{{ formatDateTime(detail.cancelRequest.requestedAt) }}</dd>

                <dt>响应期限</dt>
                <dd>
                  {{ formatDateTime(detail.cancelRequest.deadlineAt) }}
                  <span class="ticket-facts__hint">
                    到期不会自动处理：工单不会因此自动取消，这次申请也不会自动失效。
                  </span>
                </dd>
              </dl>
              <p class="ticket-cancel-request__hint">
                {{ cancelRequestNotice?.actionHint }}
              </p>
            </div>
          </section>

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
