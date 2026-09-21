<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import AppBreadcrumb from '@/components/AppBreadcrumb.vue'
import AppHeader from '@/components/AppHeader.vue'
import AppSidebar from '@/components/AppSidebar.vue'
import { useAuthStore } from '@/stores/auth'

const { isAuthenticated } = storeToRefs(useAuthStore())
const route = useRoute()
const mobileNavigationOpen = ref(false)

watch(
  () => route.fullPath,
  () => {
    mobileNavigationOpen.value = false
  },
)
</script>

<template>
  <div
    v-if="isAuthenticated"
    class="app-shell"
  >
    <a
      class="app-shell__skip-link"
      href="#main-content"
    >跳到主体内容</a>
    <AppHeader @toggle-navigation="mobileNavigationOpen = true" />

    <div class="app-shell__body">
      <AppSidebar class="app-shell__sidebar" />
      <section class="app-shell__workspace">
        <AppBreadcrumb />
        <RouterView />
      </section>
    </div>

    <el-drawer
      v-model="mobileNavigationOpen"
      direction="ltr"
      size="var(--fd-sidebar-drawer-width)"
      title="主导航"
    >
      <AppSidebar @navigate="mobileNavigationOpen = false" />
    </el-drawer>
  </div>
  <RouterView v-else />
</template>
