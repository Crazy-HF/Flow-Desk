import { http } from './http'
import type { ApiEnvelope } from './http'
import type {
  TicketCategorySummary,
  TicketPriority,
  TicketStatus,
  TicketUserSummary,
} from './tickets'

/**
 * 数据概览接口（`docs/api-design.md` 8.5，`GET /fd/v1/dashboard/tickets`）。
 *
 * <p>第五个领域模块，与 `tickets.ts` 平级：`api/` 保持扁平（2026-09-22 裁定）。</p>
 *
 * <p>这一组接口与工单列表有三处刻意的差别，都收口在这里：</p>
 * <p>1. **单次只选一个 IT 范围**：不接受 `REQUESTED_BY_ME`，也不把三个范围合并成"全部可见"。
 * 并集需要另一套去重与授权规则，且无法与列表逐项对账（设计文档的方案取舍）。</p>
 * <p>2. **只有三个筛选参数**：`createdFrom` / `createdTo` / `categoryId`。状态、优先级、负责人、
 * 分页、排序都不接受——服务端按不支持参数返回 `400`，不是静默忽略。</p>
 * <p>3. **四组与总数同源**：每组求和都等于 `total`，因此界面不做二次求和，也不补零值行。</p>
 */

/** 概览允许的范围，与后端 `TicketDashboardQuery` 的校验一致。 */
export type DashboardScope = 'PENDING_QUEUE' | 'ASSIGNED_TO_ME' | 'PARTICIPATED_BY_ME'

export interface DashboardStatusCount {
  status: TicketStatus
  count: number
}

export interface DashboardPriorityCount {
  priority: TicketPriority
  count: number
}

export interface DashboardCategoryCount {
  category: TicketCategorySummary
  count: number
}

/**
 * 负责人分组。
 *
 * <p>未分配工单的 `assignee` **不出现**在响应里（后端 `non_null` 序列化约定），
 * 因此这里写成可选字段，判空用真值判断而不是 `'assignee' in row`。</p>
 */
export interface DashboardAssigneeCount {
  assignee?: TicketUserSummary
  count: number
}

/**
 * 概览结果。
 *
 * <p>只返回命中的分组，不补零值行；没有匹配工单时 `total` 为 `0` 且四个数组都是空数组
 * （是空数组，不是缺键）。状态按状态机顺序、优先级按高→中→低、分类与负责人按数量降序
 * 再按 ID 升序——顺序由服务端固定，界面不重排。</p>
 */
export interface TicketDashboard {
  scope: DashboardScope
  total: number
  byStatus: DashboardStatusCount[]
  byPriority: DashboardPriorityCount[]
  byCategory: DashboardCategoryCount[]
  byAssignee: DashboardAssigneeCount[]
}

export interface DashboardParams {
  scope: DashboardScope
  /** ISO 8601 带时区偏移的创建时间范围，双端含。 */
  createdFrom?: string
  createdTo?: string
  categoryId?: number
}

/**
 * 查询当前范围与筛选下的工单统计。
 *
 * <p>权限是三层叠加：`DASHBOARD_VIEW` + 当前持有 `IT_SUPPORT` + 选中范围的查看权限。
 * 界面按权限隐藏入口与范围，后端仍是最终授权边界：任一层不满足返回 `403/ACCESS_DENIED`，
 * 而不是返回空统计。</p>
 */
export async function fetchTicketDashboard(params: DashboardParams): Promise<TicketDashboard> {
  const response = await http.get<ApiEnvelope<TicketDashboard>>('/dashboard/tickets', {
    params: {
      scope: params.scope,
      ...(params.createdFrom ? { createdFrom: params.createdFrom } : {}),
      ...(params.createdTo ? { createdTo: params.createdTo } : {}),
      ...(params.categoryId ? { categoryId: params.categoryId } : {}),
    },
  })
  return response.data.data
}
