import { ref } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

/**
 * 页面标签带的状态。
 *
 * <p>标签只来自**实际访问过的路由**：没有动态菜单、没有预置清单，因此不会出现
 * "看得见却打不开"的假标签。刷新页面或直接打开深链时，标签带重置为「首页 + 当前页」；
 * 退出登录时由应用壳调用 `reset()`，下一个人不会看到上一个人的浏览痕迹。</p>
 *
 * <p>键用 `route.path` 而不是 `route.name`：工单详情、用户详情这类同路由不同参数的页面
 * 各自是一个标签（`/tickets/FD-...-001` 与 `002` 是两件工作）；查询串不参与，
 * 同一个列表页带不同筛选条件仍是同一个标签。</p>
 */
export interface PageTab {
  path: string
  title: string
  /** 固定标签（首页）不可关闭，也不参与"关闭其他"的淘汰。 */
  affix: boolean
}

/** 错误页与登录页不进入标签带：它们不是"打开过的页面"，关掉也无处可回。 */
const EXCLUDED_ROUTE_NAMES = new Set(['login', 'forbidden', 'not-found'])

/**
 * 标签数量上限。超过后从最早的非固定标签开始淘汰——只淘汰一个，
 * 让"打开第 13 个"的代价可预期，而不是一次清掉一半。
 */
const MAX_TABS = 12

const HOME_TAB: PageTab = { path: '/', title: '首页', affix: true }

const tabs = ref<PageTab[]>([{ ...HOME_TAB }])

function currentRoutePath(route: RouteLocationNormalizedLoaded): string {
  return route.path
}

export function usePageTabs() {
  /** 把当前路由登记为标签；重复访问只更新标题，不重复插入。 */
  function sync(route: RouteLocationNormalizedLoaded): void {
    if (EXCLUDED_ROUTE_NAMES.has(String(route.name))) {
      return
    }
    const title = route.meta.title
    if (typeof title !== 'string' || title.length === 0) {
      return
    }

    const path = currentRoutePath(route)
    const existing = tabs.value.find((tab) => tab.path === path)
    if (existing) {
      existing.title = title
      return
    }

    tabs.value.push({ path, title, affix: false })
    if (tabs.value.length > MAX_TABS) {
      const oldest = tabs.value.findIndex((tab) => !tab.affix)
      if (oldest >= 0) {
        tabs.value.splice(oldest, 1)
      }
    }
  }

  /**
   * 关闭一个标签。
   *
   * @param path 要关闭的标签路径。
   * @param currentPath 当前所在路径；关闭的不是当前标签时不需要跳转。
   * @returns 关闭的是当前标签时，返回应当跳转的目标；否则返回 `null`。
   *          当前标签关闭后落到左邻，没有左邻就落到首页——与浏览器标签的直觉一致。
   */
  function close(path: string, currentPath: string): string | null {
    const index = tabs.value.findIndex((tab) => tab.path === path)
    if (index < 0 || tabs.value[index]?.affix === true) {
      return null
    }

    tabs.value.splice(index, 1)
    const remaining = tabs.value
    if (remaining.length === 0) {
      tabs.value.push({ ...HOME_TAB })
      return HOME_TAB.path
    }
    if (path !== currentPath) {
      return null
    }
    return remaining[Math.max(0, index - 1)]?.path ?? HOME_TAB.path
  }

  /** 只保留固定标签与指定标签；返回指定标签的路径，便于调用方决定是否跳转。 */
  function closeOthers(path: string): string {
    tabs.value = tabs.value.filter((tab) => tab.affix || tab.path === path)
    return path
  }

  /** 关闭全部可关闭标签并回到首页。 */
  function closeAll(): string {
    tabs.value = tabs.value.filter((tab) => tab.affix)
    if (tabs.value.length === 0) {
      tabs.value.push({ ...HOME_TAB })
    }
    return HOME_TAB.path
  }

  /** 退出登录时清空，避免下一个会话继承上一个人的标签。 */
  function reset(): void {
    tabs.value = [{ ...HOME_TAB }]
  }

  return { tabs, sync, close, closeOthers, closeAll, reset }
}
