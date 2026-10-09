<script setup lang="ts">
import { computed } from 'vue'

import { useCompactPagination } from '@/composables/useCompactPagination'

/**
 * 列表页分页条。七个列表页原本各自写了一份完全相同的 `el-pagination`，
 * 这里把「什么时候显示」「宽窄屏两套 layout」「有哪些页大小」收敛成一处：
 * 任何一页要改分页语言，只改本组件。
 *
 * <p>**显示条件写在组件内部**，页面不需要再套 `v-if`。默认条件是
 * 「数据已就绪 且 至少一条」；授权页在未选主体时连列表都不该显示，
 * 用 `hasSubject` 追加那一条。</p>
 *
 * <p>**窄屏阈值不在这里**：`useCompactPagination()` 与 `main.css` 的媒体查询
 * 是同一个 52rem，两处必须同步改（见 `frontend/PAGE-TEMPLATE.md` §4.3）。</p>
 */
const props = withDefaults(
  defineProps<{
    /** 当前页码，从 1 开始。 */
    page: number
    /** 每页条数。 */
    pageSize: number
    /** 记录总数。 */
    totalElements: number
    /** 取数状态；只有 `ready` 才显示分页。 */
    phase: 'loading' | 'ready' | 'error'
    /**
     * 列表是否已有主体（授权页：选中用户或角色之后才有关系列表）。
     * 缺省为 `true`，普通列表页不用传。
     */
    hasSubject?: boolean
    /** 可选的每页条数，缺省 `[10, 20, 50]`。 */
    pageSizes?: readonly number[]
  }>(),
  {
    hasSubject: true,
    pageSizes: () => [10, 20, 50],
  },
)

const emit = defineEmits<{
  /** 页码或每页条数变化；每页条数变化时同时带上新的页码（后端按第 1 页返回）。 */
  change: [payload: { page: number; pageSize: number }]
}>()

const compact = useCompactPagination()

const layout = computed(() =>
  compact.value ? 'total, prev, pager, next' : 'total, sizes, prev, pager, next, jumper',
)

const visible = computed(
  () => props.hasSubject && props.phase === 'ready' && props.totalElements > 0,
)

function onCurrentChange(next: number): void {
  if (next !== props.page) {
    emit('change', { page: next, pageSize: props.pageSize })
  }
}

function onSizeChange(next: number): void {
  if (next !== props.pageSize) {
    emit('change', { page: 1, pageSize: next })
  }
}
</script>

<template>
  <div
    v-if="visible"
    class="admin-pagination"
  >
    <el-pagination
      background
      :current-page="page"
      :layout="layout"
      :pager-count="5"
      :page-size="pageSize"
      :page-sizes="[...pageSizes]"
      :total="totalElements"
      @current-change="onCurrentChange"
      @size-change="onSizeChange"
    />
  </div>
</template>
