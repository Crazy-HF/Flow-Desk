import { http } from './http'
import type { ApiEnvelope } from './http'
import { pageQueryParams } from './pagination'
import type { PageQueryParams, PageResult } from './pagination'

/**
 * {{TITLE}}接口（`docs/api-design.md` {{API_SECTION}}，前缀 `{{API_PREFIX}}`）。
 *
 * <p>骨架由 `frontend/scripts/scaffold.mjs` 生成，与 `categories.ts` / `rbac.ts` 同构：
 * `api/` 保持扁平，基础设施与领域文件靠命名区分。</p>
 *
 * <p>**生成后必做**：把下面的类型与函数按真实契约改完，并确认
 * `docs/api-design.md` 里确实有这一组端点——没有契约就不要先写页面。</p>
 */

/** {{TITLE}}详情。写操作若带乐观锁，把 `version` 一并放进来。 */
export interface {{TYPE_DETAIL}} {
  id: number
  name: string
}

export interface {{TYPE_LIST_PARAMS}} extends PageQueryParams {
  /** 按名称做字面子串搜索。 */
  keyword?: string
}

export interface Create{{CLASS}}Payload {
  name: string
}

/**
 * 分页查询{{TITLE}}。
 *
 * <p>`pageQueryParams` 负责 `pageNo` / `pageSize` 的缺省值与排序参数拼装，
 * 页面只负责把筛选条件透传进来。</p>
 */
export async function {{API_LIST}}(
  params: {{TYPE_LIST_PARAMS}} = {},
): Promise<PageResult<{{TYPE_DETAIL}}>> {
  const { keyword, ...page } = params
  const response = await http.get<ApiEnvelope<PageResult<{{TYPE_DETAIL}}>>>(
    '{{API_PREFIX}}',
    {
      params: {
        ...pageQueryParams(page),
        ...(keyword ? { keyword } : {}),
      },
    },
  )
  return response.data.data
}

/** 新建{{TITLE}}。 */
export async function {{API_CREATE}}(
  payload: Create{{CLASS}}Payload,
): Promise<{{TYPE_DETAIL}}> {
  const response = await http.post<ApiEnvelope<{{TYPE_DETAIL}}>>('{{API_PREFIX}}', payload)
  return response.data.data
}

/** 删除{{TITLE}}。物理删除；被引用时由后端转成 `409`。 */
export async function {{API_DELETE}}(id: number): Promise<void> {
  await http.delete<ApiEnvelope<null>>(`{{API_PREFIX}}/${id}`)
}
