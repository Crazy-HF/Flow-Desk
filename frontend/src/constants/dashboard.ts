import type { DashboardScope } from '@/api/dashboard'
import { TICKET_SCOPES } from './tickets'

/**
 * 数据概览的展示映射（`docs/api-design.md` 8.5）。
 *
 * <p>范围编码、中文名与权限码**不复制**：它们直接取自 `TICKET_SCOPES`，与工单列表共用一份，
 * 避免同一份"待受理/我负责的/我参与的"出现两套写法。这里只补概览自己的页面文案——
 * 列表问"这一屏有哪些工单"，概览问"这一屏的工单怎么分布"，两者不是同一句话。</p>
 */

/** 概览只接受三个 IT 范围；`REQUESTED_BY_ME` 是员工视角，没有概览语义。 */
export const DASHBOARD_SCOPE_VALUES = [
  'PENDING_QUEUE',
  'ASSIGNED_TO_ME',
  'PARTICIPATED_BY_ME',
] as const satisfies readonly DashboardScope[]

export interface DashboardScopeOption {
  value: DashboardScope
  label: string
  /** 该范围要求的工单查看权限，决定这个范围在当前账号下是否出现。 */
  permission: string
  /** 选中这个范围时页头写什么。 */
  description: string
  /** 该范围一张工单都没有时说什么，而不是笼统地写"暂无数据"。 */
  emptyTitle: string
  emptyDescription: string
}

const overviewCopy: Record<DashboardScope, Omit<DashboardScopeOption, 'value' | 'label' | 'permission'>> = {
  PENDING_QUEUE: {
    description: '还没有人负责的工单有多少、压在哪些优先级和分类上。',
    emptyTitle: '待受理队列是空的',
    emptyDescription: '当前没有等待领取的工单，员工新提交的工单会出现在这里。',
  },
  ASSIGNED_TO_ME: {
    description: '当前由你负责的工单分布，包括已经完成或关闭、仍保留你为最后负责人的那些。',
    emptyTitle: '当前没有由你负责的工单',
    emptyDescription: '从「待受理」里领取工单后，它的分布会出现在这里。',
  },
  PARTICIPATED_BY_ME: {
    description: '你领取或接手过的全部工单分布，包括已经转交出去的历史记录。',
    emptyTitle: '你还没有参与过工单',
    emptyDescription: '领取或接手过工单后，这里会保留完整的参与历史。',
  },
}

export const DASHBOARD_SCOPES: readonly DashboardScopeOption[] = DASHBOARD_SCOPE_VALUES.map(
  (value) => {
    const base = TICKET_SCOPES.find((option) => option.value === value)
    if (!base) {
      throw new Error(`TICKET_SCOPES 缺少概览范围 ${value}`)
    }
    return {
      value,
      label: base.label,
      permission: base.permission,
      ...overviewCopy[value],
    }
  },
)
