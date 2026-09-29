import { http } from './http'
import type { ApiEnvelope } from './http'
import { DEFAULT_PAGE_SIZE } from './pagination'
import type { PageResult } from './pagination'

/**
 * 工单接口（`docs/api-design.md` 第 5、6 节，前缀 `/fd/v1/tickets`）。
 *
 * <p>第四个领域模块，与 `rbac.ts` / `users.ts` / `categories.ts` 平级：`api/` 保持扁平，
 * 基础设施与领域文件靠命名区分（2026-09-22 裁定，见 `docs/modules/rbac.md` 12.3）。</p>
 *
 * <p>这一组接口有两处和前面的管理接口不同的地方，都在这里收口，页面不再各自处理：</p>
 * <p>1. **列表用必填的 `scope` 表达工作范围**，而不是让后端按角色暗中切换含义；多角色用户
 * 可以显式选择当前工作场景。范围与权限的对应关系见 `TICKET_SCOPES`。</p>
 * <p>2. **创建用 `submissionKey` 防重**：创建前不存在可校验的版本，所以幂等键是唯一的保护。
 * 键与请求内容的冻结规则见 `useSubmissionGuard`，本文件只负责按契约发送。</p>
 */

/** 工作范围编码，与后端 `TicketScope` 一致。 */
export type TicketScope = 'REQUESTED_BY_ME' | 'PENDING_QUEUE' | 'ASSIGNED_TO_ME' | 'PARTICIPATED_BY_ME'

/** 工单状态编码，与后端 `TicketStatus` 一致；中文含义见 `docs/database-design.md` 20.1。 */
export type TicketStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'WAITING_FOR_REQUESTER'
  | 'WAITING_FOR_CONFIRMATION'
  | 'COMPLETED'
  | 'CANCELED'
  | 'CLOSED'

/** 优先级编码，与后端 `TicketPriority` 一致。 */
export type TicketPriority = 'LOW' | 'MEDIUM' | 'HIGH'

/**
 * 排序编码，与后端 `TicketListSort` 一致。
 *
 * <p>契约只接受这四个固定编码，不接受任意字段名；不传时由后端按 `scope` 选默认值
 * （队列按优先级，其他范围按更新时间），前端不复制这份默认规则。</p>
 */
export type TicketListSort = 'UPDATED_DESC' | 'CREATED_DESC' | 'CREATED_ASC' | 'PRIORITY_DESC_CREATED_ASC'

/** 记录执行者类型：系统动作没有操作人。 */
export type TicketActorType = 'USER' | 'SYSTEM'

export interface TicketUserSummary {
  id: number
  displayName: string
}

export interface TicketCategorySummary {
  id: number
  name: string
}

/**
 * 列表项：只有识别与筛选所需字段，不含问题正文。
 *
 * <p>可空字段沿用既有约定写成 `| null`（与 `users.ts` / `rbac.ts` / `categories.ts` 一致）。
 * 但要记住一个事实：后端配了 `spring.jackson.default-property-inclusion=non_null`
 * （`src/main/resources/application.yml`），值为 null 的字段**不会出现在 JSON 里**，
 * 前端实际拿到的是 `undefined`。所以判空一律用真值判断或 `?.`，
 * **不要用 `'assignee' in row` 这类"键是否存在"的写法**，也先别单独把这里改成可选类型——
 * 那会让四个 `api/` 模块对同一件事有两种写法。</p>
 */
export interface TicketListItem {
  ticketNo: string
  title: string
  category: TicketCategorySummary
  priority: TicketPriority
  status: TicketStatus
  requester: TicketUserSummary
  /** 待受理工单没有负责人（该键在响应里不出现）。 */
  assignee: TicketUserSummary | null
  /** 待补充或待确认时的当前有效截止时间，其他状态下不出现。 */
  actionDeadlineAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

/** 详情：列表项字段加原始问题描述与终态信息。 */
export interface TicketDetail extends TicketListItem {
  description: string
  /** 仅已完成时有值，其他状态下不出现。 */
  completionMethod: string | null
  /** 仅已关闭时有值，其他状态下不出现。 */
  closeMethod: string | null
  closeReason: string | null
  /** 终态结束时间，非终态下不出现。 */
  endedAt: string | null
  /**
   * 后端按当前用户、角色、工单关系与状态计算的可用动作。
   *
   * <p>阶段 2 没有已实现的工单动作，后端固定返回空数组——界面因此不渲染任何动作按钮，
   * 也不自行推导"应该有"的按钮。这个字段不能替代动作接口再次鉴权。</p>
   */
  allowedActions: string[]
}

/**
 * 时间线记录。
 *
 * <p>`context` 由后端按记录类型挑选公开字段，不返回记录与工单内部主键；界面按类型渲染，
 * 见 `constants/tickets.ts` 的 `describeRecordContext`。</p>
 */
export interface TicketRecord {
  sequenceNo: number
  recordType: string
  actorType: TicketActorType
  /** 系统动作没有操作人（该键在响应里不出现，见上面的 `non_null` 说明）。 */
  actor: TicketUserSummary | null
  createdAt: string
  context: Record<string, unknown>
}

export interface TicketListParams {
  scope: TicketScope
  /**
   * 契约字段名是 `page` / `size`（`docs/api-design.md` 5.3），这里沿用页面侧已经统一的
   * `pageNo` / `pageSize` 词汇，在请求出口一次性翻译，避免两套页码命名同时存在于组件里。
   */
  pageNo?: number
  pageSize?: number
  status?: TicketStatus[]
  priority?: TicketPriority[]
  categoryId?: number
  keyword?: string
  /** ISO 8601 带时区偏移的创建时间范围，含起止端点。 */
  createdFrom?: string
  createdTo?: string
  /** 缺省时不发送：让后端按 `scope` 决定默认排序。 */
  sort?: TicketListSort
}

export interface TicketRecordParams {
  pageNo?: number
  pageSize?: number
}

export interface CreateTicketPayload {
  /** 客户端为本次提交生成的 UUID，由 `createSubmissionKey()` 产生。 */
  submissionKey: string
  title: string
  description: string
  categoryId: number
  priority: TicketPriority
}

export interface CreatedTicket {
  ticketNo: string
  status: TicketStatus
  version: number
  createdAt: string
}

/**
 * 集合参数写成逗号分隔的单个字符串。
 *
 * <p>不能在 params 里直接传数组：axios 默认的 `paramsSerializer` 以 `indexes: false` 调
 * `toFormData`，数组会被序列化成 `status[]=PENDING`（见 `node_modules/axios/lib/helpers/
 * toFormData.js`），而 Spring 绑定的参数名是 `status`，`status[]` 绑不上——筛选条件会被
 * 静默忽略，接口照常返回 200。逗号分隔是契约本身的形式：Spring 的 `StringToCollectionConverter`
 * 按逗号拆分后再逐个转成枚举。</p>
 */
function csv(values: readonly string[] | undefined): string | undefined {
  if (!values || values.length === 0) {
    return undefined
  }
  return values.join(',')
}

/**
 * 分页查询工单。
 *
 * <p>`scope` 必填且每个范围分别校验权限：`REQUESTED_BY_ME` 要 `TICKET_VIEW_OWN`，
 * `PENDING_QUEUE` 要 `TICKET_VIEW_QUEUE`，另外两个要 `TICKET_VIEW_PARTICIPATED`。
 * 前端通过 `TICKET_SCOPES` 决定展示哪些范围，后端仍是最终授权边界。</p>
 */
export async function listTickets(
  params: TicketListParams,
): Promise<PageResult<TicketListItem>> {
  const keyword = params.keyword?.trim()
  const status = csv(params.status)
  const priority = csv(params.priority)

  const response = await http.get<ApiEnvelope<PageResult<TicketListItem>>>('/tickets', {
    params: {
      scope: params.scope,
      page: params.pageNo ?? 1,
      size: params.pageSize ?? DEFAULT_PAGE_SIZE,
      ...(status ? { status } : {}),
      ...(priority ? { priority } : {}),
      ...(params.categoryId ? { categoryId: params.categoryId } : {}),
      ...(keyword ? { keyword } : {}),
      ...(params.createdFrom ? { createdFrom: params.createdFrom } : {}),
      ...(params.createdTo ? { createdTo: params.createdTo } : {}),
      ...(params.sort ? { sort: params.sort } : {}),
    },
  })
  return response.data.data
}

/**
 * 查询工单详情。
 *
 * <p>无权查看与确实不存在**统一返回** `404/TICKET_NOT_FOUND`，后端刻意不向调用方确认该编号
 * 是否存在；界面因此也只给一种解释，不猜"是不是没权限"。</p>
 */
export async function getTicket(ticketNo: string): Promise<TicketDetail> {
  const response = await http.get<ApiEnvelope<TicketDetail>>(
    `/tickets/${encodeURIComponent(ticketNo)}`,
  )
  return response.data.data
}

/**
 * 分页查询工单时间线，顺序固定为 `sequenceNo` 升序。
 *
 * <p>不发送任何排序参数：`TicketRecordQuery` 只接受"没有 orderBy、方向为 asc"这一种形式，
 * 传排序参数会得到 `400/VALIDATION_FAILED`。</p>
 */
export async function listTicketRecords(
  ticketNo: string,
  params: TicketRecordParams = {},
): Promise<PageResult<TicketRecord>> {
  const response = await http.get<ApiEnvelope<PageResult<TicketRecord>>>(
    `/tickets/${encodeURIComponent(ticketNo)}/records`,
    {
      params: {
        page: params.pageNo ?? 1,
        size: params.pageSize ?? DEFAULT_PAGE_SIZE,
      },
    },
  )
  return response.data.data
}

/**
 * 创建工单（`multipart/form-data`，只发送 `ticket` JSON 部分）。
 *
 * <p>阶段 2 不上传附件，也不传 `sourceTicketNo`。multipart 是契约本身的形式（后续附件直接
 * 复用同一个请求），所以这里不改成 JSON body。</p>
 *
 * <p>`ticket` 部件必须自带 `application/json` 类型：后端用 `@RequestPart` 接收，
 * 它按**部件自己的 Content-Type** 选消息转换器，只声明 `application/json` 的部件才会被
 * 反序列化成 `CreateTicketCommand`；用普通字符串 append 会得到 `415`。</p>
 */
export async function createTicket(payload: CreateTicketPayload): Promise<CreatedTicket> {
  const form = new FormData()
  form.append('ticket', new Blob([JSON.stringify(payload)], { type: 'application/json' }))

  const response = await http.post<ApiEnvelope<CreatedTicket>>('/tickets', form)
  return response.data.data
}

/**
 * 生成一次提交的幂等键。
 *
 * <p>后端按正则校验 UUID 形式，键非法会直接 `400`，所以这里不做"够用就行"的短标识。
 * 优先用 `crypto.randomUUID()`；它只在安全上下文可用，因此退回同一个随机源拼 v4 UUID
 * （时间戳或 `Math.random()` 都不能用来做幂等键：碰撞会让两张不同的工单被判成同一次提交）。</p>
 */
export function createSubmissionKey(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }

  const bytes = crypto.getRandomValues(new Uint8Array(16))
  // 版本位与变体位按 RFC 4122 v4 写死，其余 122 位保持随机
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')

  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join('-')
}
