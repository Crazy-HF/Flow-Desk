<script setup lang="ts">
/**
 * 动作确认框里的正文输入。
 *
 * <p>写成 `.vue` 单文件组件而不是在 `TicketDetailView` 里用运行时模板字符串：整个应用按
 * **运行时版 Vue** 打包，`template` 选项在生产构建里不会被编译——渲染出来只有一个占位注释，
 * 用户在确认框里看到的是一行孤零零的标签，<strong>输入框整个不存在</strong>。
 * 单文件组件由构建期编译，不依赖运行时编译器，也与仓库里其它组件的写法一致。</p>
 */
defineProps<{
  /** 字段名：处理记录写"处理内容"，提交解决结果写"解决结果"。 */
  label: string
  placeholder: string
  /** 与后端 `@Size(max = 10000)` 对齐的上限。 */
  maxLength: number
}>()

const model = defineModel<string>({ required: true })

/**
 * 固定的 `id`：`ElMessageBox` 的 `message` 插槽渲染在 `body` 下，
 * `<label for>` 与输入框必须各自认得出对方，不能靠就近嵌套。
 */
const fieldId = 'ticket-action-content'
</script>

<template>
  <label
    class="ticket-action-dialog__field"
    :for="fieldId"
  >
    <span class="ticket-action-dialog__label">{{ label }}</span>
    <el-input
      :id="fieldId"
      v-model="model"
      type="textarea"
      :rows="5"
      :maxlength="maxLength"
      show-word-limit
      :placeholder="placeholder"
    />
  </label>
</template>
