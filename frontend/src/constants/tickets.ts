import type {
  TicketCloseReason,
  TicketListSort,
  TicketPriority,
  TicketScope,
  TicketStatus,
} from '@/api/tickets'

/**
 * 工单领域的展示映射。
 *
 * <p>与 `authorization.ts` 同一层：编码到中文的对照只写一次，页面只负责排版。
 * 编码本身来自后端枚举（状态见 `docs/database-design.md` 20.1，记录类型见
 * `V1__create_schema.sql` 的 `ck_ticket_record_type`），这里不新增前端自造的取值。</p>
 */

/**
 * 工作范围：编码、权限、界面文案与空结果文案。
 *
 * <p>`permission` 决定这个范围在当前账号下是否出现。三个范围权限正好对应三种角色能力：
 * 普通员工看"我提交的"，IT 支持人员看"待受理"和自己的工单。**界面显隐不代替后端授权**——
 * 每个范围在后端是分别校验的。</p>
 */
export interface TicketScopeOption {
  value: TicketScope
  label: string
  permission: string
  /** 选中这个范围时页头写什么：这一屏是做什么用的、下一步该点哪里。 */
  pageDescription: string
  /** 这个范围下一张工单都没有时该说什么，而不是笼统地写"暂无数据"。 */
  emptyTitle: string
  emptyDescription: string
}

export const TICKET_SCOPES: readonly TicketScopeOption[] = [
  {
    value: 'REQUESTED_BY_ME',
    label: '我提交的',
    permission: 'TICKET_VIEW_OWN',
    pageDescription: '你提交过的工单，以及它们当前处理到哪一步。',
    emptyTitle: '你还没有提交过工单',
    emptyDescription: '遇到问题时从「新建工单」提交，之后在这里跟踪处理进度。',
  },
  {
    value: 'PENDING_QUEUE',
    label: '待受理',
    permission: 'TICKET_VIEW_QUEUE',
    pageDescription: '还没有人负责的工单，按优先级从高到低排列；打开一张即可领取。',
    emptyTitle: '待受理队列是空的',
    emptyDescription: '当前没有等待领取的工单，员工新提交的工单会出现在这里。',
  },
  {
    value: 'ASSIGNED_TO_ME',
    label: '我负责的',
    permission: 'TICKET_VIEW_PARTICIPATED',
    pageDescription: '当前由你负责的工单；打开一张可以记录处理过程或提交解决结果。',
    emptyTitle: '当前没有由你负责的工单',
    emptyDescription: '从「待受理」里领取工单后，它会出现在这里。',
  },
  {
    value: 'PARTICIPATED_BY_ME',
    label: '我参与的',
    permission: 'TICKET_VIEW_PARTICIPATED',
    pageDescription: '你领取或接手过的全部工单，包括已经转交出去的历史记录。',
    emptyTitle: '你还没有参与过工单',
    emptyDescription: '领取或接手过工单后，这里会保留完整的参与历史。',
  },
]

/** 状态中文名，与 `docs/database-design.md` 20.1 逐条对应。 */
export const ticketStatusLabels: Record<TicketStatus, string> = {
  PENDING: '待受理',
  PROCESSING: '处理中',
  WAITING_FOR_REQUESTER: '待员工补充',
  WAITING_FOR_CONFIRMATION: '待员工确认',
  COMPLETED: '已完成',
  CANCELED: '已取消',
  CLOSED: '已关闭',
}

/**
 * 状态标记的色调。只挑三个意思：还在等别人（waiting）、正在做（active）、已经结束（done/ended）。
 *
 * <p>不做成七种颜色的胶囊标签：颜色在这里是给"要不要我去处理"用的信号，
 * 具体是哪个状态由文字说清楚。</p>
 */
export type TicketTone = 'waiting' | 'active' | 'done' | 'ended'

const statusTones: Record<TicketStatus, TicketTone> = {
  PENDING: 'waiting',
  PROCESSING: 'active',
  WAITING_FOR_REQUESTER: 'waiting',
  WAITING_FOR_CONFIRMATION: 'waiting',
  COMPLETED: 'done',
  CANCELED: 'ended',
  CLOSED: 'ended',
}

export function ticketStatusTone(status: TicketStatus): TicketTone {
  return statusTones[status] ?? 'ended'
}

export function ticketStatusLabel(status: TicketStatus): string {
  return ticketStatusLabels[status] ?? status
}

/** 优先级中文名与终态无关：低、中、高三级，`docs/business-model.md` 已确认。 */
export const ticketPriorityLabels: Record<TicketPriority, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
}

export function ticketPriorityLabel(priority: TicketPriority): string {
  return ticketPriorityLabels[priority] ?? priority
}

/**
 * 优先级的色调。只有"高"占用语义色（危险），另外两级保持中性：
 * 一屏里如果三个优先级各有一种颜色，真正需要注意的那一张反而看不出来。
 */
export function ticketPriorityTone(priority: TicketPriority): 'high' | 'medium' | 'low' {
  if (priority === 'HIGH') {
    return 'high'
  }
  return priority === 'MEDIUM' ? 'medium' : 'low'
}

/** 列表状态筛选项；顺序按工单生命周期排，不按编码字母排。 */
export const TICKET_STATUS_OPTIONS: readonly { value: TicketStatus; label: string }[] = (
  [
    'PENDING',
    'PROCESSING',
    'WAITING_FOR_REQUESTER',
    'WAITING_FOR_CONFIRMATION',
    'COMPLETED',
    'CANCELED',
    'CLOSED',
  ] as const
).map((value) => ({ value, label: ticketStatusLabels[value] }))

/** 优先级筛选项，由高到低——用户找的是"最要紧的那几张"。 */
export const TICKET_PRIORITY_OPTIONS: readonly { value: TicketPriority; label: string }[] = (
  ['HIGH', 'MEDIUM', 'LOW'] as const
).map((value) => ({ value, label: ticketPriorityLabels[value] }))

/**
 * 排序选项。
 *
 * <p>`DEFAULT` 是界面上的哨兵值，表示"不发送 `sort`"：由后端按范围决定默认排序——
 * 队列先看优先级和创建时间，其他范围先看最近更新。前端不复制这份规则，否则两处默认值
 * 早晚会不一致。**不用空字符串做哨兵**：Element Plus 的 select 把 `''` 当作"没有选中"，
 * 会退回显示占位符。</p>
 */
export type TicketSortChoice = TicketListSort | 'DEFAULT'

export const TICKET_SORT_OPTIONS: readonly { value: TicketSortChoice; label: string }[] = [
  { value: 'DEFAULT', label: '默认排序' },
  { value: 'UPDATED_DESC', label: '最近更新' },
  { value: 'CREATED_DESC', label: '最新创建' },
  { value: 'CREATED_ASC', label: '最早创建' },
  { value: 'PRIORITY_DESC_CREATED_ASC', label: '优先级从高到低' },
]

/**
 * 记录类型中文名，与 `ck_ticket_record_type` 一一对应。
 *
 * <p>取值域跨两个迁移：`V1__create_schema.sql` 的 15 种，加 `V7__add_cancel_request.sql` 追加的
 * 4 种两阶段撤销记录。少一个键不会报错，只会让时间线里那一行显示成英文编码——
 * 所以这张表必须与迁移同步维护。</p>
 */
export const ticketRecordTypeLabels: Record<string, string> = {
  CREATE: '创建工单',
  CLAIM: '领取工单',
  PROCESS: '记录处理过程',
  CATEGORY_CHANGE: '调整分类',
  PRIORITY_CHANGE: '调整优先级',
  TRANSFER: '转交工单',
  ADMIN_HANDOFF: '管理性交接',
  SUPPLEMENT_REQUEST: '请求补充信息',
  REQUESTER_SUPPLEMENT: '提交补充信息',
  SUPPLEMENT_REQUEST_WITHDRAWN: '撤回补充请求',
  RESOLUTION: '提交解决结果',
  UNSATISFIED_FEEDBACK: '反馈问题仍未解决',
  COMPLETION: '完成工单',
  CANCELLATION: '撤销工单',
  CLOSURE: '关闭工单',
  // V7（2026-10-08 两阶段撤销）：发起、同意、驳回、撤回各是一种独立事实，
  // 与「待受理直接撤销」的 CANCELLATION 分开显示
  CANCELLATION_REQUEST: '申请撤销工单',
  CANCELLATION_APPROVED: '同意撤销申请',
  CANCELLATION_REJECTED: '驳回撤销申请',
  CANCELLATION_REQUEST_WITHDRAWN: '撤回撤销申请',
}

export function ticketRecordTypeLabel(recordType: string): string {
  return ticketRecordTypeLabels[recordType] ?? recordType
}

const completionMethodLabels: Record<string, string> = {
  REQUESTER_CONFIRMED: '员工确认完成',
  AUTO_CONFIRM_TIMEOUT: '超过确认期限自动完成',
}

const closeMethodLabels: Record<string, string> = {
  MANUAL: '人工关闭',
  AUTO_SUPPLEMENT_TIMEOUT: '超过补充期限自动关闭',
}

const closeReasonLabels: Record<string, string> = {
  DUPLICATE: '重复工单',
  OUT_OF_SCOPE: '超出支持范围',
  INVALID: '无效工单',
  REQUESTER_NO_RESPONSE: '提交人逾期未补充信息',
}

/**
 * 终态编码到中文的映射。
 *
 * <p>入参允许 `undefined`：后端 `default-property-inclusion=non_null` 会把 null 字段整个省掉，
 * 所以"没有完成方式"在响应里是**键不存在**，不是 `null`。这里统一把两者都当作"没有"。</p>
 */
export function ticketCompletionMethodLabel(method: string | null | undefined): string | null {
  return method ? (completionMethodLabels[method] ?? method) : null
}

export function ticketCloseMethodLabel(method: string | null | undefined): string | null {
  return method ? (closeMethodLabels[method] ?? method) : null
}

export function ticketCloseReasonLabel(reason: string | null | undefined): string | null {
  return reason ? (closeReasonLabels[reason] ?? reason) : null
}

/**
 * 关闭原因的可选项（片 D）：只列**人工关闭**允许的三种。
 *
 * <p>标签复用上面那份展示映射——下拉里选的原因与时间线上回看的原因是同一句话，
 * 不另写一份文案，两处也就不会漂移。第 4 个编码 `REQUESTER_NO_RESPONSE` 属于逾期未补充的
 * 自动关闭（`AUTO_SUPPLEMENT_TIMEOUT`），人工关闭传它会被 `ck_ticket_close_semantics` 拒绝，
 * 因此它只存在于展示映射里，不出现在选项里。</p>
 */
export const TICKET_CLOSE_REASON_OPTIONS: readonly { value: TicketCloseReason; label: string }[] = (
  ['DUPLICATE', 'OUT_OF_SCOPE', 'INVALID'] as const
).map((value) => ({ value, label: closeReasonLabels[value] ?? value }))

/**
 * 工单动作：界面能渲染的动作，取值与接口路径末段逐字一致。
 *
 * <p>**这是界面侧的登记表，不是可做动作的来源**——某个动作现在能不能做，只由详情响应里的
 * `allowedActions` 决定。这张表只回答"这个动作名对应什么按钮、什么权限、要不要先选目标值、
 * 要不要写一段说明"。</p>
 */
export type TicketActionName =
  | 'claim'
  | 'add-processing-record'
  | 'submit-resolution'
  | 'request-supplement'
  | 'confirm-resolution'
  | 'withdraw-supplement-request'
  | 'report-unresolved'
  | 'change-category'
  | 'change-priority'
  | 'transfer'
  | 'close'
  | 'supplement'
  | 'request-cancel'
  | 'withdraw-cancel-request'
  | 'approve-cancel'
  | 'reject-cancel'
  | 'cancel'

export interface TicketActionMeta {
  /** 按钮文字。 */
  label: string
  /** 提交前的说明：这个词在 IT 语境下有后果，不能只写"确定"。 */
  description: string
  /**
   * 这个动作应有的权限。只用于**前端二次收口**：后端已经按同一份权限算过 `allowedActions`，
   * 这里再查一次是为了应对"取详情后被撤权"的窗口，不是为了替代后端授权。
   *
   * <p>写法与 `authorization.ts` 的导航项一致（`string | readonly string[]`），但**语义相反**：
   * 导航项是"任一满足即可见"（`hasAnyPermission`），动作是"**全部满足**才渲染"——
   * 因为后端就是按合取算的：`close` 的 `canClose = canProcess && hasAuthority(TICKET_CLOSE)`，
   * 两条授权缺一不可。写成一个字符串等价于只要求一条。</p>
   */
  permission: string | readonly string[]
  /** 是否需要一段正文（处理记录、解决结果），以及正文在界面上的称呼。 */
  content?: { label: string; placeholder: string; maxLength: number }
  /**
   * 需要先选目标值的动作：分类 / 优先级 / 转交对象 / 关闭原因。
   *
   * <p>`source` 决定选项从哪来，而不是由页面按动作名分支：分类要问服务端「当前启用」的那一份，
   * 转交对象还必须由服务端判定谁有资格接手；**优先级与关闭原因是本地静态刻度**
   * （三级优先级、三种关闭原因），为它们发一次请求既多等一次往返，也多一个失败点。
   * 界面因此只多认一个本地取值，不新增第二套"选项从哪来"的机制。</p>
   */
  select?: {
    source: 'category' | 'priority' | 'assignee' | 'reasonCode'
    label: string
    placeholder: string
    /** 没有可选项时的提示（转交时没有人可选）。 */
    emptyText: string
  }
  /**
   * 条件下出现的第二个输入：已经选中的目标值等于 `whenValue` 时才出现（片 D 的「重复工单」）。
   *
   * <p>取值口径由 `whenValue` 显式给出，页面只按它渲染与提交：值不等于 `whenValue` 时，
   * 这个输入**既不出现在弹窗里，也不会出现在请求体里**。服务端对"非重复原因却带单号"返回
   * `400/VALIDATION_FAILED` 而不是忽略多传的字段，所以"隐藏"和"不发"必须是同一件事。</p>
   */
  conditionalText?: {
    /** 这个输入对应哪个契约字段（当前只有关闭工单的 `duplicateTicketNo`）。 */
    field: 'duplicateTicketNo'
    label: string
    placeholder: string
    maxLength: number
    /** 上面 `select` 槽里选中的值等于它时才出现；取值必须落在 `select` 的取值域内。 */
    whenValue: string
  }
  /** 原因类说明输入：与后端 `@Size(max=1000)` 对齐。 */
  reason?: { label: string; placeholder: string; maxLength: number }
  /** 执行前是否必须确认。影响工单终态的动作不能一点就走。 */
  destructive: boolean
}

/**
 * 正文长度上限与 `AddProcessingRecordCommand` / `SubmitResolutionCommand` 的
 * `@Size(max = 10000)` 对齐；前端先拦一次，避免用 400 告诉用户"写太长了"。
 */
const ACTION_CONTENT_MAX_LENGTH = 10000

/**
 * 原因类动作的长度上限，与 `WithdrawSupplementRequestCommand` / `ReportUnresolvedCommand` /
 * `ChangeCategoryCommand` / `ChangePriorityCommand` / `TransferCommand` 的 `@Size(max = 1000)`
 * 对齐（`docs/api-design.md` 6.3/6.4：转交、调整、撤回、未解决、取消与关闭说明最长 1000）。
 */
const ACTION_REASON_MAX_LENGTH = 1000

/**
 * 工单编号的长度上限，与 `CloseTicketCommand.duplicateTicketNo` 的 `@Size(max = 32)` 对齐。
 *
 * <p>编号本身是 `FD-YYYYMMDD-NNN` 这样的 16 位标识，32 是服务端允许的上限而不是期望长度；
 * 前端照抄上限，是为了"填不下"由界面先说，而不是让用户写完再吃一个 `400`。</p>
 */
const ACTION_TICKET_NO_MAX_LENGTH = 32

export const TICKET_ACTIONS: Record<TicketActionName, TicketActionMeta> = {
  claim: {
    label: '领取工单',
    description: '领取后这张工单由你负责，它会出现在「我负责的」范围里，其他同事不会再领到同一张。',
    permission: 'TICKET_CLAIM',
    destructive: false,
  },
  'add-processing-record': {
    label: '记录处理过程',
    description: '写下的内容会进入工单时间线，提交人和后续接手的人都能看到。',
    permission: 'TICKET_PROCESS',
    content: {
      label: '处理内容',
      placeholder: '请输入这次做了什么、查到什么、下一步打算怎么处理',
      maxLength: ACTION_CONTENT_MAX_LENGTH,
    },
    destructive: false,
  },
  'submit-resolution': {
    label: '提交解决结果',
    description:
      '提交后工单转为「待员工确认」，提交人需要在确认期限内确认；确认之前不能再追加处理记录。',
    permission: 'TICKET_PROCESS',
    content: {
      label: '解决结果',
      placeholder: '请输入问题的原因、采取的解决办法，以及提交人需要验证的内容',
      maxLength: ACTION_CONTENT_MAX_LENGTH,
    },
    destructive: true,
  },
  'request-supplement': {
    label: '请求补充信息',
    description:
      '提交后工单转为「待员工补充」，由提交人在补充期限内回复；在员工回复或你撤回之前不能提交解决结果。',
    permission: 'TICKET_PROCESS',
    content: {
      label: '需要补充的内容',
      placeholder: '请写清还需要员工提供什么，例如报错截图、设备编号、复现步骤',
      maxLength: ACTION_CONTENT_MAX_LENGTH,
    },
    destructive: false,
  },
  'confirm-resolution': {
    label: '确认已解决',
    description: '确认后工单进入终态「已完成」，不能再追加处理记录；如果问题仍然存在，请先不要确认。',
    permission: 'TICKET_REQUESTER_ACTION',
    destructive: true,
  },
  'withdraw-supplement-request': {
    label: '撤回补充请求',
    description: '撤回后工单回到「处理中」，原来的补充期限立即失效；需要员工补充时要重新发起请求。',
    permission: 'TICKET_PROCESS',
    content: {
      label: '撤回原因',
      placeholder: '请输入为什么不再需要这次补充',
      maxLength: ACTION_REASON_MAX_LENGTH,
    },
    destructive: false,
  },
  'report-unresolved': {
    label: '问题仍未解决',
    description:
      '填写后工单退回「处理中」，由原负责人继续处理；之前的解决结果会作为历史保留，不会被覆盖。',
    permission: 'TICKET_REQUESTER_ACTION',
    content: {
      label: '未解决原因',
      placeholder: '请输入还有哪些问题没解决，便于 IT 继续排查',
      maxLength: ACTION_REASON_MAX_LENGTH,
    },
    destructive: false,
  },
  /**
   * 完整状态机片 C：调整与转交。
   *
   * <p>三个动作的允许状态都是「处理中」与「待补充」，都要求当前负责人；转交单独要求
   * `TICKET_TRANSFER`（与处理权限分开授权），两个调整动作与「记录处理过程」共用 `TICKET_PROCESS`。
   * 按登记表顺序排在这里：主要流程（记录、解决、请求补充）在前，对工单属性的就地修正排在后面。</p>
   */
  'change-category': {
    label: '调整分类',
    description:
      '调整后这张工单立刻归到新分类，并在时间线上留下一条调整记录；状态、负责人和当前期限都不变。',
    permission: 'TICKET_PROCESS',
    select: {
      source: 'category',
      label: '调整后的分类',
      placeholder: '请选择调整后的分类',
      emptyText: '当前没有启用中的分类，暂时无法调整。',
    },
    reason: {
      label: '调整原因',
      placeholder: '请输入调整原因，例如问题实际属于另一类设备',
      maxLength: ACTION_REASON_MAX_LENGTH,
    },
    destructive: false,
  },
  'change-priority': {
    label: '调整优先级',
    description:
      '调整后优先级立刻生效，并在时间线上留下一条调整记录；状态、负责人和当前期限都不变。',
    permission: 'TICKET_PROCESS',
    select: {
      source: 'priority',
      label: '调整后的优先级',
      placeholder: '请选择调整后的优先级',
      emptyText: '优先级选项不可用，请关闭这个窗口后重新打开。',
    },
    reason: {
      label: '调整原因',
      placeholder: '请输入调整原因，例如影响范围扩大，需要提前处理',
      maxLength: ACTION_REASON_MAX_LENGTH,
    },
    destructive: false,
  },
  transfer: {
    label: '转交工单',
    description:
      '转交后由新负责人立即接手，不需要对方确认；工单状态不变，「待员工补充」期间转交，补充期限也不会重新计算。',
    permission: 'TICKET_TRANSFER',
    select: {
      source: 'assignee',
      label: '接手人',
      placeholder: '请选择接手这张工单的同事',
      emptyText: '当前没有可以接手的同事，这张工单暂时转不出去。',
    },
    reason: {
      label: '转交原因',
      placeholder: '请输入转交原因，例如这块设备由这位同事更熟悉',
      maxLength: ACTION_REASON_MAX_LENGTH,
    },
    destructive: true,
  },
  /**
   * 完整状态机片 D：结束路径的第一半——IT 异常关闭。
   *
   * <p>排在 `transfer` 之后：服务端的 `allowedActions` 也是"…转交、关闭"这个顺序，两个结束动作
   * 里它先出现，是因为它要求当前负责人身份（人已经在工单上）；`cancel` 在整张表最后。</p>
   *
   * <p>`destructive = true` 的理由：一次点击就把工单推进**终态**，而且 v1 明确不提供申诉或恢复
   * （`docs/kickoff.md` 4.8），所以按钮用警示色、确认框里必须把后果写清楚。</p>
   *
   * <p>**两条授权同时成立**（2026-10-08 用户裁决）：关闭结束整张工单，必须建立在处理权限之上，
   * 后端 `canClose` 就是 `canProcess && TICKET_CLOSE`。早先这里只写了 `TICKET_CLOSE`，
   * 差别只在"取详情后被撤掉 `TICKET_PROCESS`"的窗口里——按钮还摆着，点下去得到 403；
   * 与片 C 的 `transfer` 只要求 `TICKET_TRANSFER` 是两条不同的口径，不要互相看齐。</p>
   */
  close: {
    label: '关闭工单',
    description:
      '关闭后工单进入终态「已关闭」，不能再继续处理、转交或提交解决结果。这条路径用于重复、超出 IT 支持范围或无效的工单；「暂时解决不了」不在此列。',
    permission: ['TICKET_PROCESS', 'TICKET_CLOSE'],
    select: {
      source: 'reasonCode',
      label: '关闭原因',
      placeholder: '请选择关闭原因',
      emptyText: '关闭原因选项不可用，请关闭这个窗口后重新打开。',
    },
    conditionalText: {
      field: 'duplicateTicketNo',
      label: '重复的工单编号',
      placeholder: '请输入另一张有效工单的编号，例如 FD-20261008-001',
      maxLength: ACTION_TICKET_NO_MAX_LENGTH,
      whenValue: 'DUPLICATE',
    },
    reason: {
      label: '关闭说明',
      placeholder: '请输入具体说明，例如与哪张工单重复、为什么不属于 IT 支持范围',
      maxLength: ACTION_REASON_MAX_LENGTH,
    },
    destructive: true,
  },
  /**
   * 提交人侧的补充动作：位置在 `close` 之后、`cancel` 之前。
   *
   * <p>它与 `cancel` 会在「待补充」上同时出现，所以顺序按用户处境定：先看到"把事情做完"
   * （补充信息），再看到"终止这件事"（撤销）。其余提交人动作（确认、反馈未解决）都靠一句话
   * 说清，不需要额外输入。</p>
   */
  supplement: {
    label: '提交补充信息',
    description:
      '提交后工单回到「处理中」，原负责人继续处理；原来的补充期限立即失效，不需要再等它到期。',
    permission: 'TICKET_REQUESTER_ACTION',
    content: {
      label: '补充内容',
      placeholder: '请按 IT 的请求补充信息，例如完整的报错文字、设备编号或复现步骤',
      maxLength: ACTION_CONTENT_MAX_LENGTH,
    },
    destructive: false,
  },
  /**
   * 两阶段撤销（2026-10-08 规则变更，`docs/kickoff.md` 4.7）：提交人**申请**撤销。
   *
   * <p>只有「处理中 / 待补充 / 待确认」会出现这一格——这三个状态已经有人负责，单方面终止
   * 需要当前负责人同意；「待受理」没有负责人，仍由下面的 `cancel` 直接撤销。</p>
   *
   * <p>它不是终态动作：申请期间工单状态与期限都不变，提交人随时可以撤回（`withdraw-cancel-request`），
   * 负责人也可以驳回（工单继续处理）。`destructive = false` 就是这层意思——一次点击不结束任何东西。
   * 位置在 `supplement` 之后：两者会在「待补充」上同时出现，仍按"先把事情做完，再谈终止"排。</p>
   */
  'request-cancel': {
    label: '申请撤销工单',
    description:
      '提交后不会立刻取消：工单继续按当前状态流转，由当前负责人决定是否同意。在对方处理之前，你随时可以撤回这次申请。',
    permission: 'TICKET_REQUESTER_ACTION',
    reason: {
      label: '申请撤销的原因',
      placeholder: '请输入为什么要终止这张工单，例如问题已自行解决、或这是一次重复提交',
      maxLength: ACTION_REASON_MAX_LENGTH,
    },
    destructive: false,
  },
  /**
   * 提交人撤回自己刚发起的撤销申请：工单回到"没有被申请撤销"，可以再次发起。
   *
   * <p>没有 `reason` 槽——撤回不需要理由，而且申请里原本那段说明仍然留在时间线上，
   * 再写一遍只会多一条重复信息。</p>
   */
  'withdraw-cancel-request': {
    label: '撤回撤销申请',
    description:
      '撤回后工单恢复为「没有被申请撤销」，你可以稍后重新发起；申请里写过的说明会保留在时间线上。',
    permission: 'TICKET_REQUESTER_ACTION',
    destructive: false,
  },
  /**
   * 当前负责人同意撤销申请：一次点击把工单推进终态「已取消」，因此 `destructive = true`。
   *
   * <p>权限只要 `TICKET_PROCESS`，**不要求 `TICKET_CLOSE`**：同意撤销让工单进入"已取消"而不是
   * "已关闭"，与 `close` 的双权限口径是两条不同的线，不要互相看齐。</p>
   */
  'approve-cancel': {
    label: '同意撤销',
    description:
      '同意后工单进入终态「已取消」，不会再有人处理它；负责人与此前的处理记录都会保留。本版本不支持恢复。',
    permission: 'TICKET_PROCESS',
    destructive: true,
  },
  /**
   * 当前负责人驳回撤销申请：工单留在原状态继续处理，这次申请失效。
   *
   * <p>`destructive = false`：它**不结束**工单，只是不同意这一次申请（提交人还可以再发起），
   * 因此按钮不该用警示色。但驳回理由必填——没有理由，提交人只看到"被驳回"而无从调整。</p>
   */
  'reject-cancel': {
    label: '驳回撤销申请',
    description:
      '驳回后工单保持当前状态继续处理，这次申请失效；提交人能看到你写的理由，也可以稍后再次发起申请。',
    permission: 'TICKET_PROCESS',
    reason: {
      label: '驳回理由',
      placeholder: '请输入为什么还需要继续处理，例如问题尚未定位、正在等待供应商回复',
      maxLength: ACTION_REASON_MAX_LENGTH,
    },
    destructive: false,
  },
  /**
   * 完整状态机片 D：结束路径的第二半——提交人撤销。
   *
   * <p>**2026-10-08 规则变更后只剩「待受理」**：那个状态没有负责人，不需要谁批准。
   * 处理中 / 待补充 / 待确认改走上面的 `request-cancel` → `approve-cancel`，所以它与
   * `request-cancel` 在同一张单上不会同时出现。`destructive = true` 的理由不变：一次点击结束
   * 这张工单，v1 不支持恢复（`docs/kickoff.md` 4.7），确认框必须写明"处理记录与负责人会保留、
   * 但工单不会再被处理"。</p>
   */
  cancel: {
    label: '撤销工单',
    description:
      '撤销后工单进入终态「已取消」，不会再有人处理它；已经产生的处理记录与负责人都会保留。本版本不支持恢复，如果问题依然存在，需要重新提交一张工单。',
    permission: 'TICKET_REQUESTER_ACTION',
    reason: {
      label: '撤销原因',
      placeholder: '请输入撤销原因，例如问题已自行解决，或这是一次重复提交',
      maxLength: ACTION_REASON_MAX_LENGTH,
    },
    destructive: true,
  },
}

/**
 * 把详情的 `allowedActions` 收敛成界面真正会渲染的动作。
 *
 * <p>两件事必须同时成立才渲染按钮：动作名在登记表里（后端将来新增的动作名在界面实现之前
 * 不会变成一个按不动的按钮），并且当前账号仍持有该动作声明的**全部**权限。顺序按登记表声明顺序，
 * 与后端返回顺序无关——同一张工单的按钮不该因为服务端拼接顺序变化而换位置。</p>
 */
export function permittedActions(
  allowedActions: readonly string[],
  hasPermission: (code: string) => boolean,
): TicketActionName[] {
  const allowed = new Set(allowedActions)
  return (Object.keys(TICKET_ACTIONS) as TicketActionName[]).filter(
    (name) => allowed.has(name) && holdsActionPermission(TICKET_ACTIONS[name].permission, hasPermission),
  )
}

/**
 * 动作的权限二次收口：登记表里写了几条，就必须**全部**持有。
 *
 * <p>与导航项的 `hasAnyPermission` 相反是有意的——导航是"有其中一个入口权限就显示菜单"，
 * 动作是"后端按合取算出来的能力"，任缺一条后端都会给 403（如 `close` 同时要
 * `TICKET_PROCESS` 与 `TICKET_CLOSE`）。</p>
 */
function holdsActionPermission(
  permission: string | readonly string[],
  hasPermission: (code: string) => boolean,
): boolean {
  return typeof permission === 'string'
    ? hasPermission(permission)
    : permission.every((code) => hasPermission(code))
}

/** 记录上下文里一条可直接渲染的"标签 + 文本"。 */
export interface TicketRecordFact {
  label: string
  value: string
}

function text(value: unknown): string | null {
  if (typeof value !== 'string' || value.trim() === '') {
    return null
  }
  return value
}

/**
 * 把一条记录的 `context` 翻成给人看的事实列表。
 *
 * <p>三点约定：</p>
 * <p>1. **不渲染内部主键**。上下文里的 `categoryId` / `toCategoryId` / `assigneeId` /
 * `fromAssigneeId` 等是数据库 id，界面上出现"分类 3、负责人 7"没有意义；当前分类与负责人
 * 由详情快照给出，已经显示在属性栏。它们留在上下文里是给后续动作接口复用的。</p>
 * <p>2. **不认识的值不猜**。解码表没收录的完成方式、关闭原因按原样显示，而不是丢掉——
 * 丢掉会让一条记录看起来"什么也没发生"。</p>
 * <p>3. **状态与优先级变化放在最后**：先读发生了什么，再读它把工单推到了哪一步。</p>
 */
export function describeRecordContext(
  context: Record<string, unknown>,
  formatDateTime: (value: string) => string,
): TicketRecordFact[] {
  const facts: TicketRecordFact[] = []

  const content = text(context.content)
  if (content) {
    facts.push({ label: '内容', value: content })
  }

  const reason = text(context.reason)
  if (reason) {
    facts.push({ label: '原因', value: reason })
  }

  const deadlineAt = text(context.deadlineAt)
  if (deadlineAt) {
    facts.push({ label: '期限', value: formatDateTime(deadlineAt) })
  }

  const completionMethod = text(context.completionMethod)
  if (completionMethod) {
    facts.push({
      label: '完成方式',
      value: completionMethodLabels[completionMethod] ?? completionMethod,
    })
  }

  const closeMethod = text(context.closeMethod)
  if (closeMethod) {
    facts.push({ label: '关闭方式', value: closeMethodLabels[closeMethod] ?? closeMethod })
  }

  const closeReason = text(context.closeReason)
  if (closeReason) {
    facts.push({ label: '关闭原因', value: closeReasonLabels[closeReason] ?? closeReason })
  }

  const fromStatus = text(context.fromStatus) as TicketStatus | null
  const toStatus = text(context.toStatus) as TicketStatus | null
  if (fromStatus && toStatus) {
    facts.push({
      label: '状态',
      value: `${ticketStatusLabel(fromStatus)} → ${ticketStatusLabel(toStatus)}`,
    })
  } else if (toStatus) {
    facts.push({ label: '状态', value: ticketStatusLabel(toStatus) })
  }

  const fromPriority = text(context.fromPriority) as TicketPriority | null
  const toPriority = text(context.toPriority) as TicketPriority | null
  if (fromPriority && toPriority) {
    facts.push({
      label: '优先级',
      value: `${ticketPriorityLabel(fromPriority)} → ${ticketPriorityLabel(toPriority)}`,
    })
  } else if (toPriority) {
    facts.push({ label: '优先级', value: ticketPriorityLabel(toPriority) })
  }

  return facts
}
