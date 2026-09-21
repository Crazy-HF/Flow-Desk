<script setup lang="ts">
import { storeToRefs } from 'pinia'

import AppLayout from '@/layout/index.vue'
import { useAuthStore } from '@/stores/auth'

/** 入口只做一次分流：已登录进应用壳，未登录直接交给路由（登录页 / 403 / 404）。 */
const { isAuthenticated } = storeToRefs(useAuthStore())
</script>

<template>
  <AppLayout v-if="isAuthenticated" />
  <RouterView v-else />
</template>
