<script setup lang="ts">
import { ArrowDown, Close } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'

import { usePageTabs } from '@/composables/usePageTabs'
import type { PageTab } from '@/composables/usePageTabs'

/**
 * 页面标签带：只呈现实际打开过的路由，可切换、可关闭。
 *
 * <p>它不承担导航职责——侧栏回答"还能去哪"，标签带回答"我刚去过哪"。
 * 因此这里不做菜单、不做缓存，也不预置任何没访问过的页面。</p>
 */
const route = useRoute()
const router = useRouter()
const { tabs, close, closeOthers, closeAll } = usePageTabs()

function activate(tab: PageTab): void {
  if (tab.path !== route.path) {
    void router.push(tab.path)
  }
}

/** 只有关闭当前标签才需要跳转；关掉别的标签时视图不该动。 */
function onClose(tab: PageTab): void {
  const fallback = close(tab.path, route.path)
  if (fallback !== null) {
    void router.push(fallback)
  }
}

function onCommand(command: string | number | object): void {
  if (command === 'others') {
    closeOthers(route.path)
    return
  }
  if (command === 'all') {
    void router.push(closeAll())
  }
}
</script>

<template>
  <div class="app-tabs">
    <ul
      class="app-tabs__list"
      aria-label="已打开的页面"
    >
      <li
        v-for="tab in tabs"
        :key="tab.path"
        class="app-tabs__item"
        :class="{ 'app-tabs__item--active': tab.path === route.path }"
      >
        <button
          class="app-tabs__activate"
          type="button"
          :aria-current="tab.path === route.path ? 'page' : undefined"
          @click="activate(tab)"
        >
          {{ tab.title }}
        </button>
        <button
          v-if="!tab.affix"
          class="app-tabs__close"
          type="button"
          :aria-label="`关闭${tab.title}`"
          :title="`关闭${tab.title}`"
          @click="onClose(tab)"
        >
          <el-icon aria-hidden="true">
            <Close />
          </el-icon>
        </button>
      </li>
    </ul>

    <el-dropdown
      class="app-tabs__tools"
      trigger="click"
      @command="onCommand"
    >
      <button
        class="app-tabs__tool-button"
        type="button"
        aria-label="标签操作"
        title="标签操作"
      >
        <el-icon aria-hidden="true">
          <ArrowDown />
        </el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="others">
            关闭其他标签
          </el-dropdown-item>
          <el-dropdown-item command="all">
            关闭全部标签
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>
