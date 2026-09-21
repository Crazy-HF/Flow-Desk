<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import AppBreadcrumb from '@/components/AppBreadcrumb.vue'
import AppHeader from '@/components/AppHeader.vue'
import AppSidebar from '@/components/AppSidebar.vue'

/**
 * 登录后的应用壳，四层职责分离：
 * 整幅顶栏（品牌位 + 账户区）、权限侧栏、面包屑条、主体内容。
 *
 * <p>壳只负责"把四层摆在正确的位置"，不读业务数据：导航条目来自
 * `constants/authorization.ts`，主体内容由 `RouterView` 决定。</p>
 */
const route = useRoute()
const navigationDrawerOpen = ref(false)

/** 窄屏抽屉是导航的临时展开，跳转后必须收起，否则新页面会被抽屉盖住。 */
watch(
  () => route.fullPath,
  () => {
    navigationDrawerOpen.value = false
  },
)
</script>

<template>
  <div class="app-shell">
    <a
      class="app-shell__skip-link"
      href="#main-content"
    >跳到主体内容</a>

    <header class="app-shell__header">
      <div class="app-shell__brand">
        <RouterLink
          class="app-shell__brand-link"
          to="/"
        >
          FlowDesk
        </RouterLink>
      </div>

      <AppHeader @toggle-navigation="navigationDrawerOpen = true" />
    </header>

    <div class="app-shell__body">
      <AppSidebar class="app-shell__sidebar" />
      <section class="app-shell__workspace">
        <AppBreadcrumb />
        <main
          id="main-content"
          class="app-shell__main"
        >
          <RouterView />
        </main>
      </section>
    </div>

    <el-drawer
      v-model="navigationDrawerOpen"
      direction="ltr"
      size="var(--fd-sidebar-drawer-width)"
      title="主导航"
    >
      <AppSidebar @navigate="navigationDrawerOpen = false" />
    </el-drawer>
  </div>
</template>
