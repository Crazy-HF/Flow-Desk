import type {
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
  /** 这个范围下一张工单都没有时该说什么，而不是笼统地写"暂无数据"。 */
  emptyTitle: string
  emptyDescription: string
}

export const TICKET_SCOPES: readonly TicketScopeOption[] = [
  {
    value: 'REQUESTED_BY_ME',
    label: '我提交的',
    permission: 'TICKET_VIEW_OWN',
    emptyTitle: '你还没有提交过工单',
    emptyDescription: '遇到问题时从「新建工单」提交，之后在这里跟踪处理进度。',
  },
  {
    value: 'PENDING_QUEUE',
    label: '待受理',
    permission: 'TICKET_VIEW_QUEUE',
    emptyTitle: '待受理队列是空的',
    emptyDescription: '当前没有等待领取的工单，员工新提交的工单会出现在这里。',
  },
  {
    value: 'ASSIGNED_TO_ME',
    label: '我负责的',
    permission: 'TICKET_VIEW_PARTICIPATED',
    emptyTitle: '当前没有由你负责的工单',
    emptyDescription: '从「待受理」里领取工单后，它会出现在这里。',
  },
  {
    value: 'PARTICIPATED_BY_ME',
    label: '我参与的',
    permission: 'TICKET_VIEW_PARTICIPATED',
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

/** 记录类型中文名，与 `V1__create_schema.sql` 的 `ck_ticket_record_type` 一一对应。 */
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
