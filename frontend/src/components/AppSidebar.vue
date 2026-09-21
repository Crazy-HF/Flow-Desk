<script setup lang="ts">
import { ArrowDown } from '@element-plus/icons-vue'
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'

import { navigationEntries } from '@/constants/authorization'
import { useAuthStore } from '@/stores/auth'

const emit = defineEmits<{
  navigate: []
}>()

type GroupKey = 'work' | 'admin'

const auth = useAuthStore()
const route = useRoute()

/** 这里只控制界面入口；服务端仍会对每个请求重新鉴权。 */
const visibleEntries = computed(() =>
  navigationEntries.filter((entry) =>
    auth.hasAnyPermission(Array.isArray(entry.permission) ? entry.permission : [entry.permission]),
  ),
)

const groups = computed(() =>
  [
    { key: 'work' as GroupKey, label: '业务工作', entries: visibleEntries.value.filter((entry) => entry.group === 'work') },
    { key: 'admin' as GroupKey, label: '系统管理', entries: visibleEntries.value.filter((entry) => entry.group === 'admin') },
  ].filter((group) => group.entries.length > 0),
)

/** 默认全部展开：收起会藏起入口，不改变既有信息量；展开状态只在本次会话内保留。 */
const expanded = ref<Record<GroupKey, boolean>>({ work: true, admin: true })

function toggleGroup(key: GroupKey): void {
  expanded.value[key] = !expanded.value[key]
}

function isActive(name: string, activeRouteNames: string[] = []): boolean {
  return route.name === name || activeRouteNames.includes(String(route.name))
}

/** 一级项与首页同规格；只有"有二级菜单的栏目"才是可折叠的。 */
function groupHasActiveEntry(entries: { name: string; activeRouteNames?: string[] }[]): boolean {
  return entries.some((entry) => isActive(entry.name, entry.activeRouteNames))
}
</script>

<template>
  <aside class="app-sidebar">
    <nav
      class="app-sidebar__navigation"
      aria-label="主导航"
    >
      <RouterLink
        class="app-sidebar__link"
        :class="{ 'app-sidebar__link--active': route.name === 'home' }"
        :aria-current="route.name === 'home' ? 'page' : undefined"
        :to="{ name: 'home' }"
        @click="emit('navigate')"
      >
        首页
      </RouterLink>

      <section
        v-for="group in groups"
        :key="group.key"
        class="app-sidebar__group"
      >
        <button
          :id="`nav-group-${group.key}`"
          class="app-sidebar__link app-sidebar__group-toggle"
          :class="{ 'app-sidebar__link--active': groupHasActiveEntry(group.entries) }"
          type="button"
          :aria-controls="`nav-group-${group.key}-items`"
          :aria-expanded="expanded[group.key]"
          @click="toggleGroup(group.key)"
        >
          <span>{{ group.label }}</span>
          <el-icon
            class="app-sidebar__chevron"
            :class="{ 'app-sidebar__chevron--expanded': expanded[group.key] }"
            aria-hidden="true"
          >
            <ArrowDown />
          </el-icon>
        </button>

        <ul
          v-show="expanded[group.key]"
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
              :to="{ name: entry.name }"
              @click="emit('navigate')"
            >
              {{ entry.label }}
            </RouterLink>
          </li>
        </ul>
      </section>
    </nav>

    <p class="app-sidebar__notice">
      菜单按当前权限显示，接口授权以后端校验为准。
    </p>
  </aside>
</template>
