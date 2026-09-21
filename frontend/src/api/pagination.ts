/**
 * 通用分页契约，与后端 `common/web/PageQuery`、`PageResult` 一一对应。
 *
 * <p>页码从 1 开始；排序字段由后端按白名单校验，未列入白名单的字段会被拒绝。</p>
 */
export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface PageQueryParams {
  pageNo?: number
  pageSize?: number
  /** 排序字段，逗号分隔；必须落在后端为该接口声明的白名单内。 */
  orderBy?: string
  orderDirection?: 'asc' | 'desc'
}

export const DEFAULT_PAGE_SIZE = 20

export function pageQueryParams(params: PageQueryParams = {}): PageQueryParams {
  return {
    pageNo: params.pageNo ?? 1,
    pageSize: params.pageSize ?? DEFAULT_PAGE_SIZE,
    ...(params.orderBy ? { orderBy: params.orderBy, orderDirection: params.orderDirection ?? 'asc' } : {}),
  }
}
