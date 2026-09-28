<script setup lang="ts">
defineProps<{
  title: string
  description?: string
  layout?: 'card' | 'list'
}>()
</script>

<template>
  <main
    id="main-content"
    class="page"
    :class="{ 'page--list': layout === 'list' }"
    tabindex="-1"
  >
    <section :class="layout === 'list' ? 'page__workspace' : 'page__card'">
      <header class="page__header">
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
  </main>
</template>
