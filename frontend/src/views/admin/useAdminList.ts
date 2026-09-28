import { ref, shallowRef } from 'vue'

import { describeError } from '@/api/errorMessages'
import { DEFAULT_PAGE_SIZE } from '@/api/pagination'
import type { PageResult } from '@/api/pagination'

/**
 * 管理端列表的取数状态机：loading / ready / error + 服务端分页。
 *
 * <p>放在 `views/admin/` 而不是新开顶层 `composables/`：它只服务这一层的五个页面，
 * `frontend/AGENTS.md` 要求"判断属于哪个现有分层，不要新开目录"。页面自己持有筛选条件，
 * 通过闭包把当前查询交给 `request`，因此这里不需要知道任何一页的查询字段。</p>
 */
export interface AdminListRequest {
  pageNo: number
  pageSize: number
}

export type AdminListPhase = 'loading' | 'ready' | 'error'

export function useAdminList<T>(request: (page: AdminListRequest) => Promise<PageResult<T>>) {
  const items = shallowRef<T[]>([])
  const total = ref(0)
  const pageNo = ref(1)
  const pageSize = ref(DEFAULT_PAGE_SIZE)
  const phase = ref<AdminListPhase>('loading')
  const errorMessage = ref('')
  /** 重试按钮与"刷新中"的加载态；与首屏骨架分开，避免刷新时整块闪回骨架。 */
  const retrying = ref(false)

  /**
   * 取一页数据。
   *
   * @param options.page 目标页码，缺省为当前页
   * @param options.silent 保留现有内容只做刷新（写操作成功后使用），不回到骨架态
   */
  async function load(options: { page?: number; silent?: boolean } = {}): Promise<void> {
    const page = options.page ?? pageNo.value
    retrying.value = true
    if (!options.silent) {
      phase.value = 'loading'
    }
    errorMessage.value = ''

    try {
      const result = await request({ pageNo: page, pageSize: pageSize.value })
      items.value = result.items
      total.value = result.totalElements
      // 以服务端返回的页码与页大小为准：删除末页最后一条后不会停在空页
      pageNo.value = result.page
      pageSize.value = result.size
      phase.value = 'ready'
    } catch (error) {
      errorMessage.value = describeError(error, '列表加载失败，请检查网络后重试')
      phase.value = 'error'
    } finally {
      retrying.value = false
    }
  }

  /** 首屏与"筛选条件变化"后回到第一页。 */
  async function search(): Promise<void> {
    await load({ page: 1 })
  }

  async function changePage(next: number): Promise<void> {
    await load({ page: next })
  }

  async function changePageSize(next: number): Promise<void> {
    pageSize.value = next
    await load({ page: 1 })
  }

  return {
    items,
    total,
    pageNo,
    pageSize,
    phase,
    errorMessage,
    retrying,
    load,
    search,
    changePage,
    changePageSize,
  }
}
