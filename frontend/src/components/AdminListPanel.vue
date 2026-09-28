<script setup lang="ts">
import EmptyState from './EmptyState.vue'

/**
 * 管理端列表的四态容器：加载 / 错误 / 空 / 就绪。
 *
 * <p>管理端五个页面都是"查询 + 列表 + 分页"的同一种构图，抽出来的只有这四态——
 * 它们是硬规则（六态）里最容易被各页复制成五份的部分，也是失败与空结果必须显眼的地方。
 * 筛选控件、表格列与操作仍留在各页，因为它们才是每页真正不同的东西。</p>
 */
withDefaults(
  defineProps<{
    phase: 'loading' | 'ready' | 'error'
    /** 错误态文案，来自 `describeError` 的稳定错误码映射。 */
    errorMessage?: string
    isEmpty?: boolean
    emptyTitle?: string
    emptyDescription?: string
    /** 重试按钮的加载态。 */
    retrying?: boolean
    /** 读屏用的内容名，例如「用户列表」。 */
    label?: string
  }>(),
  {
    errorMessage: '',
    isEmpty: false,
    emptyTitle: '暂无数据',
    emptyDescription: '这里暂时没有可显示的内容。',
    retrying: false,
    label: '列表内容',
  },
)

const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="admin-panel">
    <el-skeleton
      v-if="phase === 'loading'"
      animated
      :aria-label="`正在加载${label}`"
      aria-busy="true"
    >
      <template #template>
        <div class="admin-panel__skeleton">
          <el-skeleton-item
            v-for="index in 5"
            :key="index"
            class="admin-panel__skeleton-row"
            variant="rect"
          />
        </div>
      </template>
    </el-skeleton>

    <section
      v-else-if="phase === 'error'"
      class="admin-panel__error"
      role="alert"
    >
      <h2>列表没有加载成功</h2>
      <p>{{ errorMessage }}</p>
      <el-button
        :loading="retrying"
        @click="emit('retry')"
      >
        重新加载
      </el-button>
    </section>

    <EmptyState
      v-else-if="isEmpty"
      :description="emptyDescription"
      :title="emptyTitle"
    />

    <slot v-else />
  </div>
</template>
