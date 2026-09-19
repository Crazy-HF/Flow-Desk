<script setup lang="ts">
import { computed } from 'vue'

import { useAuthStore } from '@/stores/auth'

/** 演示权限到能力的映射；没有权限的能力不会出现在页面上（后端仍是最终授权边界）。 */
const capabilityLabels: { code: string; label: string }[] = [
  { code: 'TICKET_CREATE', label: '创建工单' },
  { code: 'TICKET_VIEW_OWN', label: '查看我提交的工单' },
  { code: 'TICKET_VIEW_QUEUE', label: '查看待受理队列' },
  { code: 'TICKET_PROCESS', label: '处理工单' },
  { code: 'DASHBOARD_VIEW', label: '数据概览' },
  { code: 'USER_MANAGE', label: '用户管理' },
  { code: 'CATEGORY_MANAGE', label: '分类管理' },
]

const auth = useAuthStore()
const visibleCapabilities = computed(() =>
  capabilityLabels.filter((capability) => auth.hasPermission(capability.code)),
)
</script>

<template>
  <main class="home">
    <section class="home__card">
      <p class="home__eyebrow">
        欢迎回来
      </p>
      <h1>{{ auth.user?.displayName }}</h1>
      <p class="home__meta">
        登录名 {{ auth.user?.username }} · 角色
        <span
          v-for="role in auth.roles"
          :key="role"
          class="home__tag"
        >{{ role }}</span>
      </p>

      <h2 class="home__section">
        当前账号具备的能力
      </h2>
      <ul
        v-if="visibleCapabilities.length > 0"
        class="home__capabilities"
      >
        <li
          v-for="capability in visibleCapabilities"
          :key="capability.code"
        >
          {{ capability.label }}
        </li>
      </ul>
      <p
        v-else
        class="home__hint"
      >
        该账号没有任何业务能力，请联系管理员分配角色。
      </p>

      <p class="home__hint">
        工单、队列与数据概览页面将在后续阶段实现，当前已完成登录、会话恢复与本人改密。
      </p>
    </section>
  </main>
</template>
