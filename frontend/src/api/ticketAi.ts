import { http } from './http'
import type { ApiEnvelope } from './http'
import { DEFAULT_PAGE_SIZE } from './pagination'
import type { PageResult } from './pagination'
import type { TicketPriority, TicketStatus } from './tickets'

/** 负责人确认后实际发送给模型的五项工单内容。 */
export interface TicketAiInputSnapshot {
  title: string
  description: string
  category: string
  priority: TicketPriority
  status: TicketStatus
}

/** 与后端 GenerateTicketAiSuggestionCommand 对应。 */
export interface GenerateTicketAiSuggestionPayload {
  requestId: string
  version: number
  title: string
  description: string
  sourceDigest: string
}

/** 预览只暴露实际外发的摘录，不暴露来源工单编号。 */
export interface TicketAiSourcePreview {
  label: string
  title: string
  processExcerpt: string
}

export interface TicketAiSourcesPreview {
  sources: TicketAiSourcePreview[]
  sourceDigest: string
}

/** 历史中的编号来自服务端重新授权后的来源快照。 */
export interface TicketAiSuggestionSource extends TicketAiSourcePreview {
  ticketNo: string
}

/** 生成响应与历史记录共用的结果。 */
export interface TicketAiSuggestion {
  id: number
  ticketNo: string
  requestId: string
  input: TicketAiInputSnapshot
  summary: string
  questions: string[]
  suggestedSteps: string[]
  model: string
  promptVersion: string
  sources: TicketAiSuggestionSource[]
  /** 后端在供应商未返回用量时省略这些字段。 */
  inputTokens?: number
  outputTokens?: number
  createdAt: string
}

export interface TicketAiHistoryParams {
  pageNo?: number
  pageSize?: number
}

/** 预览不占额；服务端用版本检查本次来源集合。 */
export async function previewTicketAiSources(
  ticketNo: string,
  version: number,
): Promise<TicketAiSourcesPreview> {
  const response = await http.get<ApiEnvelope<TicketAiSourcesPreview>>(
    `/tickets/${encodeURIComponent(ticketNo)}/ai-suggestions/sources-preview`,
    { params: { version } },
  )
  return response.data.data
}

/** 生成并保存建议；模型调用可等待约 30 秒，不能沿用公共客户端的 10 秒超时。 */
export async function generateTicketAiSuggestion(
  ticketNo: string,
  payload: GenerateTicketAiSuggestionPayload,
): Promise<TicketAiSuggestion> {
  const response = await http.post<ApiEnvelope<TicketAiSuggestion>>(
    `/tickets/${encodeURIComponent(ticketNo)}/ai-suggestions`,
    payload,
    { timeout: 60_000 },
  )
  return response.data.data
}

/** 历史顺序由后端固定，只发送分页参数。 */
export async function listTicketAiSuggestions(
  ticketNo: string,
  params: TicketAiHistoryParams = {},
): Promise<PageResult<TicketAiSuggestion>> {
  const response = await http.get<ApiEnvelope<PageResult<TicketAiSuggestion>>>(
    `/tickets/${encodeURIComponent(ticketNo)}/ai-suggestions`,
    {
      params: {
        pageNo: params.pageNo ?? 1,
        pageSize: params.pageSize ?? DEFAULT_PAGE_SIZE,
      },
    },
  )
  return response.data.data
}
