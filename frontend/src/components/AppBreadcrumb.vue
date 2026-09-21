<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import { navigationEntries, navigationGroupLabels } from '@/constants/authorization'

interface BreadcrumbCrumb {
  label: string
  /** 可跳转时给 `to`；末级与"栏目"没有目标，就是纯文字。 */
  to?: { name: string }
  /** 一级栏目是容器而不是页面，单独标出来，样式上不做成"块"。 */
  container?: boolean
}

const route = useRoute()

const currentTitle = computed(() => String(route.meta.title ?? '当前页面'))

/**
 * 面包屑与侧栏共用同一份导航声明（`constants/authorization.ts`），层级固定为
 * 「首页 / 一级栏目 / 页面」：
 *
 * <ul>
 *   <li>首页：只有一级；</li>
 *   <li>栏目下的页面：首页 / 栏目 / 页面；</li>
 *   <li>页面下的子路由（如工单详情）：首页 / 栏目 / 页面 / 子页面，页面那一级可点回列表；</li>
 *   <li>不在导航声明里的路由（403、404）：首页 / 当前页标题。</li>
 * </ul>
 *
 * <p>同级之间切换（用户管理 → 分类管理）只换末级，"栏目"那一级不动——这是面包屑表达
 * 层级的方式；访问历史不在这里累加。</p>
 */
const crumbs = computed<BreadcrumbCrumb[]>(() => {
  const routeName = String(route.name ?? '')

  if (routeName === 'home') {
    return [{ label: currentTitle.value }]
  }

  const home: BreadcrumbCrumb = { label: '首页', to: { name: 'home' } }
  const entry = navigationEntries.find(
    (item) => item.name === routeName || item.activeRouteNames?.includes(routeName),
  )

  if (!entry) {
    return [home, { label: currentTitle.value }]
  }

  const group: BreadcrumbCrumb = { label: navigationGroupLabels[entry.group], container: true }
  const entryCrumb: BreadcrumbCrumb = { label: entry.label }

  if (entry.name === routeName) {
    return [home, group, entryCrumb]
  }

  // 当前路由是某个入口的子路由：入口那一级补回可点击的列表页
  return [home, group, { ...entryCrumb, to: { name: entry.name } }, { label: currentTitle.value }]
})
</script>

<template>
  <nav
    class="app-breadcrumb"
    aria-label="面包屑"
  >
    <!-- 不用 / 分隔：每一级各自成块，层级靠底色与字重区分，间距由 CSS 的 gap 给 -->
    <el-breadcrumb separator="">
      <el-breadcrumb-item
        v-for="(crumb, index) in crumbs"
        :key="`${crumb.label}-${index}`"
        :class="{ 'app-breadcrumb__group': crumb.container }"
        :to="crumb.to"
      >
        {{ crumb.label }}
      </el-breadcrumb-item>
    </el-breadcrumb>
  </nav>
</template>
