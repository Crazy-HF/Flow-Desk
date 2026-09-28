<script setup lang="ts">
import { Lock } from '@element-plus/icons-vue'

/**
 * 受保护对象标记。
 *
 * <p>FlowDesk 有三条真实存在的保护不变量：`SYSTEM_ADMIN` 角色不可删除、`RBAC_MANAGE`
 * 权限不可删除、以及最后一个启用管理员不可被停用或移除管理员角色。这三条在三个页面上
 * 用同一枚标记表达——它就是本组管理页的 signature：同类后台的默认产物是"所有行平等，
 * 为什么删不掉只能从灰掉的按钮去猜"。</p>
 *
 * <p>标记本身不承担授权判断：后端始终会独立拒绝越权或破坏不变量的请求。</p>
 */
defineProps<{
  /** 保护原因，作为悬停说明与读屏文本；文本必须写清"为什么"。 */
  reason: string
  /** 行内短标签；默认「受保护」。 */
  label?: string
}>()
</script>

<template>
  <span
    class="protected-mark"
    :title="reason"
  >
    <el-icon
      class="protected-mark__icon"
      aria-hidden="true"
    >
      <Lock />
    </el-icon>
    <span aria-hidden="true">{{ label ?? '受保护' }}</span>
    <span class="sr-only">（{{ reason }}）</span>
  </span>
</template>
