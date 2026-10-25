<script setup lang="ts">
import { ArrowDown, Close, HomeFilled, Tickets } from '@element-plus/icons-vue'
import type { Component } from 'vue'
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'

import { navigationEntries, navigationGroupLabels } from '@/constants/authorization'
import type { NavigationEntry, NavigationGroup } from '@/constants/authorization'
import { groupIcons, iconForRoute } from '@/constants/navigationIcons'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{
  /** 桌面端收起：只留图标列。窄屏抽屉始终以完整形态渲染。 */
  collapsed?: boolean
  /** 抽屉形态：多一个显式关闭按钮，不能只靠遮罩与 Esc。 */
  dismissible?: boolean
}>()

const emit = defineEmits<{
  navigate: []
}>()

const auth = useAuthStore()
const route = useRoute()

/**
 * 图标来自 `constants/navigationIcons.ts`：侧栏与首页入口共用同一份映射，
 * 同一个页面不会在两处得到两个图标。
 */

/**
 * 这里只控制界面入口；服务端仍会对每个请求重新鉴权。
 *
 * <p>声明了 `role` 的入口还要求持有该角色：数据概览（8.5）要求 `DASHBOARD_VIEW` 与
 * `IT_SUPPORT` 同时成立，只有权限码的账号点进去也只会得到 `403`。</p>
 */
const visibleEntries = computed(() =>
  navigationEntries.filter(
    (entry) =>
      auth.hasAnyPermission(Array.isArray(entry.permission) ? entry.permission : [entry.permission]) &&
      (entry.role === undefined || auth.roles.includes(entry.role)),
  ),
)

const groups = computed(() =>
  (['work', 'admin'] as NavigationGroup[])
    .map((key) => ({
      key,
      label: navigationGroupLabels[key],
      entries: visibleEntries.value.filter((entry) => entry.group === key),
    }))
    .filter((group) => group.entries.length > 0),
)

/** 默认全部展开：收起会藏起入口，不改变既有信息量；展开状态只在本次会话内保留。 */
const expanded = ref<Record<NavigationGroup, boolean>>({ work: true, admin: true })

function toggleGroup(key: NavigationGroup): void {
  expanded.value[key] = !expanded.value[key]
}

function isActive(name: string, activeRouteNames: string[] = []): boolean {
  return route.name === name || activeRouteNames.includes(String(route.name))
}

function iconFor(entry: NavigationEntry): Component {
  return iconForRoute(entry.name, entry.group)
}
</script>

<template>
  <aside class="app-sidebar">
    <RouterLink
      class="app-sidebar__brand"
      to="/"
      aria-label="FlowDesk 首页"
      @click="emit('navigate')"
    >
      <span
        class="app-sidebar__mark"
        aria-hidden="true"
      >
        <el-icon><Tickets /></el-icon>
      </span>
      <span class="app-sidebar__brand-name">FlowDesk</span>
    </RouterLink>

    <!-- 抽屉形态的显式关闭入口：放在品牌链接之外，避免按钮嵌在链接里（交互元素不能嵌套）。 -->
    <button
      v-if="props.dismissible"
      class="app-sidebar__dismiss"
      type="button"
      aria-label="关闭主导航"
      title="关闭主导航"
      @click="emit('navigate')"
    >
      <el-icon aria-hidden="true">
        <Close />
      </el-icon>
    </button>

    <nav
      class="app-sidebar__navigation"
      aria-label="主导航"
    >
      <RouterLink
        class="app-sidebar__link"
        :class="{ 'app-sidebar__link--active': route.name === 'home' }"
        :aria-current="route.name === 'home' ? 'page' : undefined"
        :aria-label="props.collapsed ? '首页' : undefined"
        :title="props.collapsed ? '首页' : undefined"
        :to="{ name: 'home' }"
        @click="emit('navigate')"
      >
        <el-icon
          class="app-sidebar__icon"
          aria-hidden="true"
        >
          <HomeFilled />
        </el-icon>
        <span class="app-sidebar__label">首页</span>
      </RouterLink>

      <section
        v-for="group in groups"
        :key="group.key"
        class="app-sidebar__group"
      >
        <button
          :id="`nav-group-${group.key}`"
          class="app-sidebar__group-label"
          type="button"
          :aria-controls="`nav-group-${group.key}-items`"
          :aria-expanded="expanded[group.key]"
          @click="toggleGroup(group.key)"
        >
          <el-icon
            class="app-sidebar__icon"
            aria-hidden="true"
          >
            <component :is="groupIcons[group.key]" />
          </el-icon>
          <span class="app-sidebar__label">{{ group.label }}</span>
          <el-icon
            class="app-sidebar__chevron"
            :class="{ 'app-sidebar__chevron--expanded': expanded[group.key] }"
            aria-hidden="true"
          >
            <ArrowDown />
          </el-icon>
        </button>

        <ul
          v-show="props.collapsed || expanded[group.key]"
          :id="`nav-group-${group.key}-items`"
          :aria-labelledby="`nav-group-${group.key}`"
        >
          <li
            v-for="entry in group.entries"
            :key="entry.name"
          >
            <RouterLink
              class="app-sidebar__link"
              :class="{
                'app-sidebar__link--active': isActive(entry.name, entry.activeRouteNames),
              }"
              :aria-current="isActive(entry.name, entry.activeRouteNames) ? 'page' : undefined"
              :aria-label="props.collapsed ? entry.label : undefined"
              :title="props.collapsed ? entry.label : undefined"
              :to="{ name: entry.name }"
              @click="emit('navigate')"
            >
              <el-icon
                class="app-sidebar__icon"
                aria-hidden="true"
              >
                <component :is="iconFor(entry)" />
              </el-icon>
              <span class="app-sidebar__label">{{ entry.label }}</span>
            </RouterLink>
          </li>
        </ul>
      </section>
    </nav>

    <p class="app-sidebar__notice">
      FlowDesk · 企业工单协作
    </p>
  </aside>
</template>
