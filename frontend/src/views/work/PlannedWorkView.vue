<script setup lang="ts">
import { useRoute } from 'vue-router'

import AppPage from '@/components/AppPage.vue'
import EmptyState from '@/components/EmptyState.vue'

/** 已规划但尚未实现的页面：先占位，保证导航、权限判断与路由守卫现在就能被验证。 */
const route = useRoute()
</script>

<template>
  <AppPage
    :description="`该页面由 ${route.meta.plannedTask ?? '后续任务'} 实现。`"
    :title="route.meta.title ?? '待实现页面'"
  >
    <EmptyState description="接口与页面会在对应任务中一起交付；当前仅占位，用于验证导航与权限判断。" />
  </AppPage>
</template>
