<script setup lang="ts">
withDefaults(defineProps<{ title?: string; description?: string }>(), {
  title: '暂无数据',
  description: '这里暂时没有可显示的内容。',
})
</script>

<template>
  <div class="empty-state">
    <p class="empty-state__title">
      {{ title }}
    </p>
    <p class="empty-state__description">
      {{ description }}
    </p>
  </div>
</template>
