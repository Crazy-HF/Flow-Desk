<script setup lang="ts">
/**
 * 页面骨架。
 *
 * <p>`card` 是阅读型页面（居中、限宽）；`list` 是列表页（跟随工作区宽度，标题只留给读屏，
 * 视觉上的标题由面包屑、页面标签带和面板标题承担）；`hideHeader` 用于自带主视觉的页面
 * （首页 hero、工单详情头部），此时页面自己提供唯一的 h1。</p>
 */
defineProps<{
  title: string
  description?: string
  layout?: 'card' | 'list'
  hideHeader?: boolean
}>()
</script>

<template>
  <div
    class="page"
    :class="{ 'page--list': layout === 'list' }"
  >
    <section :class="layout === 'list' ? 'page__workspace' : 'page__card'">
      <header
        v-if="!hideHeader"
        class="page__header"
      >
        <h1>{{ title }}</h1>
        <p
          v-if="description"
          class="page__description"
        >
          {{ description }}
        </p>
      </header>
      <slot />
    </section>
  </div>
</template>
