<script setup lang="ts">
/**
 * 动作确认框里"条件下出现的第二个输入"（片 D 的重复工单编号）。
 *
 * <p>写成 `.vue` 单文件组件的理由与 `TicketActionContentField.vue` 相同：整个应用按**运行时版 Vue**
 * 打包，`template` 选项在生产构建里不会被编译——渲染函数里拼 `el-input` 只会剩下一行标签，
 * 输入框整个不存在；而在渲染函数里直接传 `modelValue` / `onUpdate:modelValue`，两者都只是普通 prop，
 * 输入不会回流，表现是"填了单号却提交空值"。单文件组件由构建期编译，两个问题都不存在。</p>
 *
 * <p>**单行而不是多行**：这里要填的是一张工单的编号（≤32 字符的标识），不是一段说明。
 * 用一个五行高的文本域会让用户以为可以写理由，而服务端只拿这个字段去解析"哪张工单"，
 * 写进去的任何解释都只会让解析失败（`400`）。</p>
 *
 * <p>固定 `id`：`ElMessageBox` 的内容渲染在 `body` 下，`<label for>` 与输入框必须各自认得出对方，
 * 不能靠就近嵌套。</p>
 */
defineProps<{
  /** 字段名：关闭工单写"重复的工单编号"。 */
  label: string
  placeholder: string
  /** 与后端 `@Size(max = 32)` 对齐的上限。 */
  maxLength: number
}>()

const model = defineModel<string>({ required: true })

const fieldId = 'ticket-action-conditional-text'
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
      :maxlength="maxLength"
      :placeholder="placeholder"
    />
  </label>
</template>
