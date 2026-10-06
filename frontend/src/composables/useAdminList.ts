import { ref, shallowRef } from 'vue'

import { describeError } from '@/api/errorMessages'
import { DEFAULT_PAGE_SIZE } from '@/api/pagination'
import type { PageResult } from '@/api/pagination'

/**
 * 分页列表的取数状态机：loading / ready / error + 服务端分页。工单列表与管理端五页共用。
 *
 * <p>**位置**：`src/composables/`。它原先放在 `views/admin/`，理由是"只服务管理端五页"；
 * 2026-09-29 工单列表（`views/work/TicketListView.vue`）也开始复用后这条理由失效——
 * 两个不同领域互相 import 私有实现，改名或移动管理端目录都会连带弄坏工单页。
 * 2026-10-06 迁到共享层。名字里的 `Admin` 是历史遗留，保留是为了不动七个调用点与既有用例；
 * 它本身不含任何管理端业务：查询条件由页面持有并通过闭包交给 `request`。</p>
 *
 * <p>**并发**：页面在筛选或翻页时可能连续发起多次请求（例如快速改关键词再点查询），
 * 后发的请求不一定后到。这里用自增序号只接受"最新一次"的结果：过期响应既不覆盖列表，
 * 也不改 loading/error 状态——否则先到的旧数据会把新结果顶掉，或把已经就绪的页面打回错误态。</p>
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
  /** 已发起请求的序号；只有最新一次的结果会被采用。 */
  let latestRequest = 0

  /**
   * 取一页数据。
   *
   * @param options.page 目标页码，缺省为当前页
   * @param options.silent 保留现有内容只做刷新（写操作成功后使用），不回到骨架态
   */
  async function load(options: { page?: number; silent?: boolean } = {}): Promise<void> {
    const page = options.page ?? pageNo.value
    const requestId = ++latestRequest
    retrying.value = true
    if (!options.silent) {
      phase.value = 'loading'
    }
    errorMessage.value = ''

    try {
      const result = await request({ pageNo: page, pageSize: pageSize.value })
      if (requestId !== latestRequest) {
        return
      }
      items.value = result.items
      total.value = result.totalElements
      // 以服务端返回的页码与页大小为准：删除末页最后一条后不会停在空页
      pageNo.value = result.page
      pageSize.value = result.size
      phase.value = 'ready'
    } catch (error) {
      if (requestId !== latestRequest) {
        return
      }
      errorMessage.value = describeError(error, '列表加载失败，请检查网络后重试')
      phase.value = 'error'
    } finally {
      // 过期请求不得清掉"最新请求仍在进行中"的加载态
      if (requestId === latestRequest) {
        retrying.value = false
      }
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
