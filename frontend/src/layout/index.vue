<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import AppBreadcrumb from '@/components/AppBreadcrumb.vue'
import AppHeader from '@/components/AppHeader.vue'
import AppPageTabs from '@/components/AppPageTabs.vue'
import AppSidebar from '@/components/AppSidebar.vue'
import { usePageTabs } from '@/composables/usePageTabs'

/**
 * 登录后的应用壳，三层职责分离：
 * 深蓝侧栏回答"还能去哪"，顶栏回答"我在哪 / 我是谁"，页面标签带回答"我刚去过哪"。
 *
 * <p>壳只负责把这三层摆在正确的位置，不读业务数据：导航条目来自
 * `constants/authorization.ts`，主体内容由 `RouterView` 决定。</p>
 */
const route = useRoute()
const navigationDrawerOpen = ref(false)
/** 桌面端侧栏收起状态：只在本次会话内保留，不做持久化（换设备不该继承折叠偏好）。 */
const collapsed = ref(false)
const { sync, reset } = usePageTabs()

/**
 * 每次进入新路由都登记标签；窄屏抽屉随之收起，否则新页面会被抽屉盖住。
 * `immediate` 让直接打开深链时也能得到「首页 + 当前页」两个标签。
 */
watch(
  () => route.fullPath,
  () => {
    sync(route)
    navigationDrawerOpen.value = false
  },
  { immediate: true },
)

/** 应用壳只在退出登录时卸载；此时清空标签，下一个人不会看到上一个人的浏览痕迹。 */
onBeforeUnmount(reset)
</script>

<template>
  <div
    class="app-shell"
    :class="{ 'app-shell--collapsed': collapsed }"
  >
    <a
      class="app-shell__skip-link"
      href="#main-content"
    >跳到主体内容</a>

    <AppSidebar
      class="app-shell__sidebar"
      :collapsed="collapsed"
    />

    <div class="app-shell__main">
      <header class="app-shell__header">
        <AppHeader
          :collapsed="collapsed"
          @toggle-collapse="collapsed = !collapsed"
          @toggle-navigation="navigationDrawerOpen = true"
        >
          <AppBreadcrumb />
        </AppHeader>
      </header>

      <AppPageTabs class="app-shell__tabs" />

      <main
        id="main-content"
        class="app-shell__workspace"
        tabindex="-1"
      >
        <RouterView />
      </main>
    </div>

    <el-drawer
      v-model="navigationDrawerOpen"
      class="navigation-drawer"
      direction="ltr"
      size="var(--fd-sidebar-drawer-width)"
      :with-header="false"
    >
      <AppSidebar
        dismissible
        @navigate="navigationDrawerOpen = false"
      />
    </el-drawer>
  </div>
</template>
