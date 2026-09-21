<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <main
    id="main-content"
    class="page"
    tabindex="-1"
  >
    <section class="page__card">
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
