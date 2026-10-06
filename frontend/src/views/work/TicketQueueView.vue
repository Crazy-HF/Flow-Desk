<script setup lang="ts">
import TicketListView from './TicketListView.vue'

/**
 * IT 工作台（`docs/api-design.md` 5.3，`GET /fd/v1/tickets?scope=PENDING_QUEUE`）。
 *
 * <p>IT 人员的日常工作只有两屏：先看「待受理」里有什么、领一张，再回到「我负责的」继续处理。
 * 所以这一页不是另写一张表，而是把工单列表**默认停在待受理**（`defaultScope`），
 * 把"我提交的"这类员工视角从默认位置让开——IT 人员一天里几乎不需要看自己提交的工单，
 * 但"我负责的"必须一跳到。</p>
 *
 * <p>范围仍由 `TICKET_SCOPES` 按权限渲染：只有 `TICKET_VIEW_QUEUE` 的账号看不到「我提交的」，
 * 只有 `TICKET_VIEW_OWN` 的账号看不到这一页的入口（侧栏按 `TICKET_VIEW_QUEUE` 显隐）。
 * 界面显隐不代替后端授权，后端对每个范围分别鉴权。</p>
 */
</script>

<template>
  <TicketListView
    default-scope="PENDING_QUEUE"
    title="IT 工作台"
  />
</template>
