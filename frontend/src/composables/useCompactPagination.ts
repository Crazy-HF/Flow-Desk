import { onMounted, onUnmounted, ref } from 'vue'
import type { Ref } from 'vue'

/**
 * 管理列表分页的窄屏收敛。
 *
 * <p>宽屏用完整分页（共 N 条 / 条页选择 / 页码 / 前后页 / 前往 N 页）；窗口收窄到
 * 「条页」与「前往」会被挤到下一行、把分页条撑成两行时，降为「共 N 条 / 页码 / 前后页」。
 * 分页仍是同一个组件与同一套事件，只换 `layout`，所以翻页行为与页码状态不受影响。</p>
 *
 * <p>阈值必须与 `main.css` 里管理端的窄屏媒体查询保持一致——CSS 无法读 JS 常量，
 * 因此这一条写在两处注释里，改动时两处一起改。</p>
 */
export const COMPACT_PAGINATION_QUERY = '(max-width: 52rem)'

/** 只订阅变化，不关心当前值：当前值统一从 `matches` 读。 */
interface MediaQueryListener {
  matches: boolean
  addEventListener: (type: 'change', listener: () => void) => void
  removeEventListener: (type: 'change', listener: () => void) => void
}

/**
 * 取媒体查询对象。
 *
 * <p>不依赖 `window.matchMedia` 一定存在：jsdom 没有实现它，而页面在没有断点能力的
 * 环境里应当退回宽屏的完整分页，而不是让整个组件在 setup 阶段抛错。
 * 这也是唯一需要包一层的原因——不使用 `addListener` 这类已废弃 API。</p>
 */
function mediaQuery(): MediaQueryListener | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return null
  }
  return window.matchMedia(COMPACT_PAGINATION_QUERY)
}

/**
 * @returns 是否应当使用收敛版分页；窗口尺寸变化时自动跟随。
 */
export function useCompactPagination(): Ref<boolean> {
  const compact = ref(false)
  const query = mediaQuery()

  function sync(): void {
    compact.value = query?.matches ?? false
  }

  onMounted(() => {
    sync()
    query?.addEventListener('change', sync)
  })
  onUnmounted(() => query?.removeEventListener('change', sync))

  return compact
}
