<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const currentTitle = computed(() => String(route.meta.title ?? '当前页面'))
</script>

<template>
  <nav
    class="app-breadcrumb"
    aria-label="面包屑"
  >
    <!-- 不用 / 分隔：每一级各自成块，层级靠底色与字重区分，间距由 CSS 的 gap 给 -->
    <el-breadcrumb separator="">
      <el-breadcrumb-item
        v-if="route.name !== 'home'"
        :to="{ name: 'home' }"
      >
        首页
      </el-breadcrumb-item>
      <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
    </el-breadcrumb>
  </nav>
</template>
