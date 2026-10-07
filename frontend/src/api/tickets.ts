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
 * 转交候选人选项（`docs/api-design.md` 7.4，`GET /tickets/{ticketNo}/transfer-candidates`）。
 *
 * <p>只有标识与显示名：专用最小字段接口，不替代通用的用户搜索，也不返回用户名或角色。
 * 两个字段在服务端都由行数据直接给出、不会为 null，所以这里不写成 `field?: T`
 * （`non_null` 约定只影响可能为 null 的字段，写法与 `CategoryOption` 一致）。</p>
 */
export interface TicketAssigneeOption {
  id: number
  displayName: string
}

/**
 * 列表项：只有识别与筛选所需字段，不含问题正文。
 *
 * <p>可选字段写成 `field?: T`（`users.ts` / `rbac.ts` / `categories.ts` 同一写法）。依据是一个
 * 事实：后端配了 `spring.jackson.default-property-inclusion=non_null`
 * （`src/main/resources/application.yml`），值为 null 的字段**不会出现在 JSON 里**，
 * 前端实际拿到的是 `undefined`。此前四个模块都写成 `| null`，类型与运行时不符（2026-10-06 统一）。
 * 判空一律用真值判断或 `?.`，**不要用 `'assignee' in row` 这类"键是否存在"的写法**。</p>
 */
export interface TicketListItem {
  ticketNo: string
  title: string
  category: TicketCategorySummary
  priority: TicketPriority
  status: TicketStatus
  requester: TicketUserSummary
  /** 待受理工单没有负责人（该键在响应里不出现）。 */
  assignee?: TicketUserSummary
  /** 待补充或待确认时的当前有效截止时间，其他状态下不出现。 */
  actionDeadlineAt?: string
  createdAt: string
  updatedAt: string
  version: number
}

/** 详情：列表项字段加原始问题描述与终态信息。 */
export interface TicketDetail extends TicketListItem {
  description: string
  /** 仅已完成时有值，其他状态下不出现。 */
  completionMethod?: string
  /** 仅已关闭时有值，其他状态下不出现。 */
  closeMethod?: string
  closeReason?: string
  /** 终态结束时间，非终态下不出现。 */
  endedAt?: string
  /**
   * 后端按当前用户、角色、工单关系与状态计算的可用动作。
   *
   * <p>动作名与接口路径末段**逐字一致**（`TicketQueryServiceImpl.toDetail` 的构造注释），
   * 目前会出现的取值是 `claim`、`add-processing-record`、`confirm-resolution`
   * （`docs/api-design.md` 6.3）；界面只按这个数组渲染按钮，
   * **不自行推导"应该有"的动作**——比如「提交解决结果」在服务端放行该动作前不会出现。</p>
   *
   * <p>这个字段不能替代动作接口再次鉴权：它只说明"后端认为现在可以做"，不是许可凭证。
   * 动作仍可能因为并发（`409/TICKET_CONFLICT`）或资格变化而失败。</p>
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
  actor?: TicketUserSummary
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

/** 领取：只需要客户端最后读到的版本，服务端用它做乐观锁与并发资格判断。 */
export interface ClaimTicketPayload {
  version: number
}

/** 追加处理记录：正文最长 10000 字符，服务端会在校验前去掉首尾空白。 */
export interface AddProcessingRecordPayload {
  version: number
  content: string
}

/** 提交解决结果：与处理记录同样的正文约束，成功后进入待确认并生成确认期限。 */
export interface SubmitResolutionPayload {
  version: number
  content: string
}

/** 确认问题已解决：不需要正文，完成方式由服务端固定为"员工确认完成"。 */
export interface ConfirmResolutionPayload {
  version: number
}

/** 撤回补充请求：原因必填，strip 后 1～1000 字符（`docs/api-design.md` 6.3）。 */
export interface WithdrawSupplementRequestPayload {
  version: number
  reason: string
}

/** 反馈问题未解决：原因必填，长度约束与撤回一致。 */
export interface ReportUnresolvedPayload {
  version: number
  reason: string
}

/** 请求员工补充信息：正文最长 10000 字符，补充期限由服务端按配置计算。 */
export interface RequestSupplementPayload {
  version: number
  content: string
}

/**
 * 提交补充信息：正文约束与处理记录一致。
 *
 * <p>接口是 `multipart/form-data`（契约本身的形式，附件落地后复用同一个请求），所以这里描述的
 * 是 `ticket` 部件里的 JSON 内容，不是请求体的全部。</p>
 */
export interface SupplementPayload {
  version: number
  content: string
}

/** 调整分类（`docs/api-design.md` 6.3）：目标分类必须存在且启用，原因 strip 后 1～1000 字符。 */
export interface ChangeCategoryPayload {
  version: number
  categoryId: number
  reason: string
}

/** 调整优先级：允许状态与分类调整相同，原因约束一致。 */
export interface ChangePriorityPayload {
  version: number
  priority: TicketPriority
  reason: string
}

/** 直接转交：新负责人必须仍是候选人接口列出的启用 IT 用户，原因约束一致。 */
export interface TransferPayload {
  version: number
  newAssigneeId: number
  reason: string
}

export interface CreatedTicket {
  ticketNo: string
  status: TicketStatus
  version: number
  createdAt: string
}

/**
 * 工单动作的统一成功结果（`docs/api-design.md` 6.2）。
 *
 * <p>四个动作返回同一份"最新快照摘要"，所以界面只有一种处理方式：用返回值刷新当前状态与版本，
 * 不去猜每个动作各自改了什么。`version` 尤其重要——它是下一次动作的乐观锁前置条件，
 * 不刷新就会拿旧版本再发一次，得到 `409/TICKET_CONFLICT`。</p>
 */
export interface TicketActionResult {
  ticketNo: string
  status: TicketStatus
  /** 当前或最后负责人；终止状态下保留，没有负责人时不出现（`non_null` 约定）。 */
  assignee?: TicketUserSummary
  /** 只有待补充与待确认有值，其他状态下不出现。 */
  actionDeadlineAt?: string
  version: number
  /** 动作发生时间，由服务端给出，不用客户端时钟。 */
  actionTime: string
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
 * 领取工单（`docs/api-design.md` 6.3）。
 *
 * <p>资格由服务端判定（队列可见、未被领取、不是自己提交的、具备领取资格的角色行），
 * 前端只用 `allowedActions` 决定是否摆出按钮。并发的两次领取只有一次会成功，
 * 另一次得到 `409/TICKET_CONFLICT`。</p>
 */
export async function claimTicket(
  ticketNo: string,
  payload: ClaimTicketPayload,
): Promise<TicketActionResult> {
  const response = await http.post<ApiEnvelope<TicketActionResult>>(
    `/tickets/${encodeURIComponent(ticketNo)}/actions/claim`,
    payload,
  )
  return response.data.data
}

/**
 * 当前负责人追加处理记录。
 *
 * <p>成功时状态与负责人都不变，只有 `version` 与记录序号递增——它是"我把进展写下来"，
 * 不是状态迁移。待确认状态下服务端会拒绝（`409/TICKET_ACTION_FORBIDDEN`）：
 * 已经交出解决结果之后不能再改处理记录。</p>
 */
export async function addProcessingRecord(
  ticketNo: string,
  payload: AddProcessingRecordPayload,
): Promise<TicketActionResult> {
  const response = await http.post<ApiEnvelope<TicketActionResult>>(
    `/tickets/${encodeURIComponent(ticketNo)}/actions/add-processing-record`,
    payload,
  )
  return response.data.data
}

/**
 * 当前负责人提交解决结果。
 *
 * <p>成功后进入 `WAITING_FOR_CONFIRMATION`，确认期限由服务端按
 * `flowdesk.ticket.confirmation-window`（默认 7 天）计算，前端不参与推算。</p>
 */
export async function submitResolution(
  ticketNo: string,
  payload: SubmitResolutionPayload,
): Promise<TicketActionResult> {
  const response = await http.post<ApiEnvelope<TicketActionResult>>(
    `/tickets/${encodeURIComponent(ticketNo)}/actions/submit-resolution`,
    payload,
  )
  return response.data.data
}

/**
 * 提交人确认问题已解决。
 *
 * <p>这是提交人的终态动作，不需要正文：完成方式固定为"员工确认完成"。
 * 权限闸门先于身份闸门——没有 `TICKET_REQUESTER_ACTION` 的账号得到 `403`，
 * 有权限但不是提交人的账号才得到 `404`（不可见）。</p>
 */
export async function confirmResolution(
  ticketNo: string,
  payload: ConfirmResolutionPayload,
): Promise<TicketActionResult> {
  const response = await http.post<ApiEnvelope<TicketActionResult>>(
    `/tickets/${encodeURIComponent(ticketNo)}/actions/confirm-resolution`,
    payload,
  )
  return response.data.data
}

/**
 * 当前负责人撤回补充请求（`docs/api-design.md` 6.3，完整状态机片 A）。
 *
 * <p>成功后工单从「待员工补充」回到「处理中」，原补充期限由服务端清空；撤回原因必填。
 * 期限与状态都由服务端判定，前端只负责展示并重新取详情。</p>
 */
export async function withdrawSupplementRequest(
  ticketNo: string,
  payload: WithdrawSupplementRequestPayload,
): Promise<TicketActionResult> {
  const response = await http.post<ApiEnvelope<TicketActionResult>>(
    `/tickets/${encodeURIComponent(ticketNo)}/actions/withdraw-supplement-request`,
    payload,
  )
  return response.data.data
}

/**
 * 提交人反馈问题未解决（`docs/api-design.md` 6.4，完整状态机片 A）。
 *
 * <p>工单从「待员工确认」退回「处理中」，原确认期限失效、负责人保留，之前的解决结果
 * 作为历史留在时间线上——退回不是"驳回"，也不换负责人。</p>
 */
export async function reportUnresolved(
  ticketNo: string,
  payload: ReportUnresolvedPayload,
): Promise<TicketActionResult> {
  const response = await http.post<ApiEnvelope<TicketActionResult>>(
    `/tickets/${encodeURIComponent(ticketNo)}/actions/report-unresolved`,
    payload,
  )
  return response.data.data
}

/**
 * 当前负责人请求员工补充信息（`docs/api-design.md` 6.3，完整状态机片 B）。
 *
 * <p>工单从「处理中」进入「待员工补充」，负责人保持不变；补充期限由服务端按
 * `flowdesk.ticket.supplement-window`（默认 7 天）计算，前端不参与推算，也不判定期限。
 * 「待补充」期间要再次请求必须先撤回——服务端只允许从「处理中」发起。</p>
 */
export async function requestSupplementTicket(
  ticketNo: string,
  payload: RequestSupplementPayload,
): Promise<TicketActionResult> {
  const response = await http.post<ApiEnvelope<TicketActionResult>>(
    `/tickets/${encodeURIComponent(ticketNo)}/actions/request-supplement`,
    payload,
  )
  return response.data.data
}

/**
 * 提交人补充信息（`docs/api-design.md` 6.4，完整状态机片 B）。
 *
 * <p>工单从「待员工补充」回到「处理中」，原补充期限由服务端清空，原负责人继续处理。
 * 请求是 `multipart/form-data`，写法与 {@link createTicket} 完全一致：只发送 `ticket` 部件，
 * **该部件必须自带 `application/json` 类型**，否则 `@RequestPart` 会按部件自己的 Content-Type
 * 选转换器并给出 `415`。附件（`files` 部件）属完整版 backlog 第 2 项，本版本传文件 part 会得到
 * `400/VALIDATION_FAILED`，因此这里不拼任何文件字段。</p>
 */
export async function supplementTicket(
  ticketNo: string,
  payload: SupplementPayload,
): Promise<TicketActionResult> {
  const form = new FormData()
  form.append('ticket', new Blob([JSON.stringify(payload)], { type: 'application/json' }))

  const response = await http.post<ApiEnvelope<TicketActionResult>>(
    `/tickets/${encodeURIComponent(ticketNo)}/actions/supplement`,
    form,
  )
  return response.data.data
}

/**
 * 当前负责人调整工单分类（`docs/api-design.md` 6.3，完整状态机片 C）。
 *
 * <p>「处理中」与「待补充」两个状态都允许调整：状态、负责人与当前期限都不变，只替换分类并追加一条
 * `CATEGORY_CHANGE` 记录。目标分类必须存在且处于启用状态，停用分类由服务端拒绝（`400`）。</p>
 */
export async function changeTicketCategory(
  ticketNo: string,
  payload: ChangeCategoryPayload,
): Promise<TicketActionResult> {
  const response = await http.post<ApiEnvelope<TicketActionResult>>(
    `/tickets/${encodeURIComponent(ticketNo)}/actions/change-category`,
    payload,
  )
  return response.data.data
}

/**
 * 当前负责人调整工单优先级。
 *
 * <p>允许状态、身份与分类调整完全相同，服务端因此复用同一条判定；前端不复制"什么状态能调整"，
 * 只按 `allowedActions` 摆按钮。同值调整服务端会放行，界面上由弹窗校验挡掉——见 `TicketDetailView`。</p>
 */
export async function changeTicketPriority(
  ticketNo: string,
  payload: ChangePriorityPayload,
): Promise<TicketActionResult> {
  const response = await http.post<ApiEnvelope<TicketActionResult>>(
    `/tickets/${encodeURIComponent(ticketNo)}/actions/change-priority`,
    payload,
  )
  return response.data.data
}

/**
 * 当前负责人把工单直接转交给另一名 IT 支持人员。
 *
 * <p>采用直接转交：接收方不需要确认，成功后新负责人立即承担处理责任；状态与当前期限不变，
 * 「待员工补充」期间转交也不会重新计算补充期限。候选人由服务端按"启用、仍是 IT 处理人、
 * 排除提交人与当前负责人"筛出，前端不自行过滤。</p>
 */
export async function transferTicket(
  ticketNo: string,
  payload: TransferPayload,
): Promise<TicketActionResult> {
  const response = await http.post<ApiEnvelope<TicketActionResult>>(
    `/tickets/${encodeURIComponent(ticketNo)}/actions/transfer`,
    payload,
  )
  return response.data.data
}

/**
 * 查询可以接手这张工单的同事（`docs/api-design.md` 7.4）。
 *
 * <p>只有「处理中」「待补充」的当前负责人且具备 `TICKET_TRANSFER` 才可调用：状态或负责人变化得到
 * `409/TICKET_CONFLICT`，工单不可见得到 `404/TICKET_NOT_FOUND`。**一个候选人都没有时返回空数组**，
 * 不是错误——界面据此说明"暂时转不出去"，而不是摆一个空的必填下拉。</p>
 */
export async function listTransferCandidates(ticketNo: string): Promise<TicketAssigneeOption[]> {
  const response = await http.get<ApiEnvelope<TicketAssigneeOption[]>>(
    `/tickets/${encodeURIComponent(ticketNo)}/transfer-candidates`,
  )
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
