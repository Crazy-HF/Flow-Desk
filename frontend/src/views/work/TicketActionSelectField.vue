<script setup lang="ts">
/**
 * 动作确认框里的"先选一个目标"输入（调整分类 / 调整优先级 / 转交对象）。
 *
 * <p>写成 `.vue` 单文件组件而不是在 `TicketDetailView` 里用渲染函数或运行时模板字符串，理由与
 * `TicketActionContentField.vue` 相同：整个应用按**运行时版 Vue** 打包，`template` 选项在生产构建里
 * 不会被编译——渲染出来只有一个占位注释，用户看到的是一个没有控件的标签。而在渲染函数里直接拼
 * `el-select`，`modelValue` / `onUpdate:modelValue` 只是普通 prop，选中值是否回流要依赖 Element Plus
 * 的内部实现（那会变成"选了分类却提交空值"）。单文件组件由构建期编译，`v-model` 就是组件契约本身，
 * 两个问题都不存在。</p>
 *
 * <p>固定的 `id` 同理：`ElMessageBox` 的 `message` 内容渲染在 `body` 下，`<label for>` 与控件必须
 * 各自认得出对方，不能靠就近嵌套。</p>
 *
 * <p>"一个可选项都没有"不另造空态组件：转交时列不出人，说的是"这件事现在做不了"这一句话，
 * 用与页面其它地方同一句式的次要文字说清就够，不需要一块虚线空状态占住弹窗。</p>
 */
defineProps<{
  /** 字段名：调整分类写"调整后的分类"，转交写"接手人"。 */
  label: string
  placeholder: string
  /** 可选目标：分类与接手人是 id，优先级是编码，所以值的类型是 `string | number`。 */
  options: readonly { value: string | number; label: string }[]
  /** 选项还在取（分类选项、转交候选人）。 */
  loading?: boolean
  /** 一个可选项都没有时的说明：转交时就是"没有人可以接手"。 */
  emptyText: string
  /** 调用方判定这次选择不可用（例如选项没取成功）：不可用时连下拉都不该点得开。 */
  disabled?: boolean
}>()

/**
 * 选中值可以为空：弹窗刚打开时用户本来就还没选，`undefined` 就是这一种状态。
 *
 * <p>**不用空字符串代替"没选"**：Element Plus 的 select 把 `''` 当作没有选中，会退回显示占位符，
 * 于是"选了一个空值"和"还没选"在界面上再也分不开。</p>
 */
const model = defineModel<string | number | undefined>({ required: true })

/** 见组件注释：`<label for>` 要认得到控件，不能依赖嵌套，所以 id 必须是固定的。 */
const fieldId = 'ticket-action-select'
</script>

<template>
  <label
    class="ticket-action-dialog__field"
    :for="fieldId"
  >
    <span class="ticket-action-dialog__label">{{ label }}</span>
    <el-select
      :id="fieldId"
      v-model="model"
      :disabled="disabled || loading || options.length === 0"
      :loading="loading"
      :placeholder="placeholder"
    >
      <el-option
        v-for="option in options"
        :key="option.value"
        :label="option.label"
        :value="option.value"
      />
    </el-select>
    <!-- 不打开下拉也要能看出"还在取"和"确实没人可接"是两件事，所以说明文字放在字段下面。
         用 span 而不是 p：label 的内容模型只允许短语内容，块级元素放进去是无效结构 -->
    <span
      v-if="loading"
      class="ticket-action-dialog__hint"
    >
      正在加载可选项…
    </span>
    <span
      v-else-if="options.length === 0 && emptyText !== ''"
      class="ticket-action-dialog__hint"
    >
      {{ emptyText }}
    </span>
  </label>
</template>
