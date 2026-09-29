import { http } from './http'
import type { ApiEnvelope } from './http'
import { pageQueryParams } from './pagination'
import type { PageQueryParams, PageResult } from './pagination'

/**
 * 分类管理接口（`docs/api-design.md` 8.4，前缀 `/fd/v1/admin/categories`）。
 *
 * <p>第三个领域模块，与 `rbac.ts` / `users.ts` 平级：`api/` 保持扁平，基础设施与领域文件靠命名区分
 * （2026-09-22 裁定，见 `docs/modules/rbac.md` 12.3）。六个端点全部要求 `CATEGORY_MANAGE`。</p>
 *
 * <p>与其它管理资源不同的一点：分类同时是**业务配置**，它的启用状态与展示顺序直接决定员工在
 * 「新建工单」里能选到什么、按什么顺序选。因此这里的写操作都是业务动作，而不是资料维护：
 * 停用会让分类立刻从员工端消失，但历史工单继续显示它。</p>
 */

/** `ticket_category.status` 的两个取值，与 `ck_ticket_category_status` 一致。 */
export type CategoryStatus = 'ENABLED' | 'DISABLED'

/** 分类详情。
 *
 * <p>`version` 是所有写操作的乐观锁依据：修改、启停、删除都必须带回来。
 * `updatedAt` 只用于界面展示，不参与冲突判断。</p>
 */
export interface CategoryDetail {
  id: number
  name: string
  status: CategoryStatus
  /** 展示顺序，非负整数；员工端下拉按 `sortOrder` 再按 `id` 升序。 */
  sortOrder: number
  createdAt: string | null
  updatedAt: string | null
  version: number
}

/** 员工端可见的分类选项（`docs/api-design.md` 7.3）：只有 `id` 与 `name`。 */
export interface CategoryOption {
  id: number
  name: string
}

/**
 * 查询启用中的分类选项，供「新建工单」使用。
 *
 * <p>与 {@link listCategories} 是两个不同用途的端点：这里是**员工端选项**，只返回启用分类，
 * 顺序就是员工看到的下拉顺序（`sortOrder` 再 `id`）；管理页那张列表要看得到停用分类与状态，
 * 因此走 `/admin/categories`。停用分类不出现在这里，但历史工单继续显示它的名称——
 * 详情与列表里的分类名来自工单自己的快照，不依赖这个接口。</p>
 */
export async function listCategoryOptions(): Promise<CategoryOption[]> {
  const response = await http.get<ApiEnvelope<CategoryOption[]>>('/categories/options')
  return response.data.data
}

export interface CategoryListParams extends PageQueryParams {
  /** 按名称做字面子串搜索。 */
  keyword?: string
  /** 只看某个状态的分类；不传则启用与停用一起返回。 */
  status?: CategoryStatus
}

export interface CreateCategoryPayload {
  name: string
  sortOrder: number
}

export interface UpdateCategoryPayload {
  name: string
  sortOrder: number
  version: number
}

/**
 * 分页查询分类，启用与停用一起返回。
 *
 * <p>后端默认按 `sortOrder, id` 升序——与员工端下拉看到的一致，管理页因此不需要另设默认排序。</p>
 */
export async function listCategories(
  params: CategoryListParams = {},
): Promise<PageResult<CategoryDetail>> {
  const { keyword, status, ...page } = params
  const response = await http.get<ApiEnvelope<PageResult<CategoryDetail>>>('/admin/categories', {
    params: {
      ...pageQueryParams(page),
      ...(keyword ? { keyword } : {}),
      ...(status ? { status } : {}),
    },
  })
  return response.data.data
}

/** 新建分类。名称去首尾空白后唯一；同名并发创建由后端唯一索引兜底为 `409`。 */
export async function createCategory(payload: CreateCategoryPayload): Promise<CategoryDetail> {
  const response = await http.post<ApiEnvelope<CategoryDetail>>('/admin/categories', payload)
  return response.data.data
}

/** 修改名称或排序值；`version` 与行数据不一致时后端返回 `409/CATEGORY_CONFLICT`，不静默覆盖。 */
export async function updateCategory(
  categoryId: number,
  payload: UpdateCategoryPayload,
): Promise<CategoryDetail> {
  const response = await http.put<ApiEnvelope<CategoryDetail>>(
    `/admin/categories/${categoryId}`,
    payload,
  )
  return response.data.data
}

/** 启用分类：启用后员工在「新建工单」里才能再次选到它。 */
export async function enableCategory(
  categoryId: number,
  version: number,
): Promise<CategoryDetail> {
  const response = await http.post<ApiEnvelope<CategoryDetail>>(
    `/admin/categories/${categoryId}/actions/enable`,
    { version },
  )
  return response.data.data
}

/** 停用分类：不影响历史工单，但新工单与分类调整都不能再选它。 */
export async function disableCategory(
  categoryId: number,
  version: number,
): Promise<CategoryDetail> {
  const response = await http.post<ApiEnvelope<CategoryDetail>>(
    `/admin/categories/${categoryId}/actions/disable`,
    { version },
  )
  return response.data.data
}

/**
 * 删除分类。
 *
 * <p>删除接口按 8.4 契约通过**必填查询参数** `version` 传递期望版本，请求体为空。
 * 已被工单引用的分类由数据库外键拒绝，后端转成 `409/CATEGORY_IN_USE`；物理删除，不做软删。</p>
 */
export async function deleteCategory(categoryId: number, version: number): Promise<void> {
  await http.delete<ApiEnvelope<null>>(`/admin/categories/${categoryId}`, {
    params: { version },
  })
}
