<script setup lang="ts">
import { CircleCheck, Promotion, Refresh } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { listCategoryOptions } from '@/api/categories'
import type { CategoryOption } from '@/api/categories'
import { describeError } from '@/api/errorMessages'
import { createSubmissionKey, createTicket } from '@/api/tickets'
import type { CreateTicketPayload, TicketPriority } from '@/api/tickets'
import AppPage from '@/components/AppPage.vue'
import { useAuthStore } from '@/stores/auth'
import { useSubmissionGuard } from './useSubmissionGuard'

/**
 * 新建工单（`docs/api-design.md` 6.2，`POST /fd/v1/tickets`，要求 `TICKET_CREATE`）。
 *
 * <p>这一页与普通表单的差别只有一条，但它决定了整页的可靠性：**创建没有可校验的版本**，
 * 防重完全靠 `submissionKey`。所以"提交"不是一个瞬时动作，而是一个有身份的过程——
 * 请求失败、结果不确定、用户再点一次，都必须是同一次提交。规则集中在
 * `useSubmissionGuard`，这里只在两种情况下结束它：确定成功之后、用户改了内容之后。</p>
 *
 * <p>分类用启用中的分类选项接口，不是管理页那张列表：员工只能选启用分类，
 * 停用分类即使历史工单还在用，也不该出现在新工单里。</p>
 */
const router = useRouter()
const auth = useAuthStore()

/**
 * 界面上的"能不能建单"。
 *
 * <p>路由守卫已经按 `TICKET_CREATE` 拦过入口，这里不是重复鉴权，而是补一个守卫覆盖不到的场景：
 * **用户停在页面上时权限被撤**。那时表单还能填、能提交，提交后才拿到 403；与其让用户白填一遍，
 * 不如把页面直接换成说明。后端始终是最终授权边界。</p>
 */
const canCreate = computed(() => auth.hasPermission('TICKET_CREATE'))

interface TicketForm {
  title: string
  description: string
  categoryId: number | null
  priority: TicketPriority
}

const form = reactive<TicketForm>({
  title: '',
  description: '',
  categoryId: null,
  priority: 'MEDIUM',
})

const formRef = ref<FormInstance>()

const rules: FormRules<TicketForm> = {
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { max: 200, message: '标题最多 200 个字符', trigger: 'blur' },
  ],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  description: [
    { required: true, message: '请输入问题描述', trigger: 'blur' },
    { max: 10000, message: '问题描述最多 10000 个字符', trigger: 'blur' },
  ],
}

/**
 * 优先级不是单纯的排序值：它决定 IT 先看哪一张工单。所以每个选项都写明后果，
 * 而不是只给"低 / 中 / 高"三个字让用户猜。默认「中」，与 `docs/business-model.md` 一致。
 */
const PRIORITY_CHOICES: readonly { value: TicketPriority; label: string; hint: string }[] = [
  { value: 'HIGH', label: '高', hint: '无法继续工作，需要尽快处理' },
  { value: 'MEDIUM', label: '中', hint: '影响部分工作，可以暂时绕过' },
  { value: 'LOW', label: '低', hint: '不影响当前工作，方便时处理' },
]

// ---------- 分类选项 ----------

const categoryOptions = ref<CategoryOption[]>([])
const optionsPhase = ref<'loading' | 'ready' | 'error'>('loading')
const optionsError = ref('')

async function loadCategories(): Promise<void> {
  optionsPhase.value = 'loading'
  optionsError.value = ''
  try {
    categoryOptions.value = await listCategoryOptions()
    optionsPhase.value = 'ready'
  } catch (error) {
    optionsError.value = describeError(error, '分类没有加载成功，请检查网络后重试')
    optionsPhase.value = 'error'
  }
}

const noCategoryAvailable = computed(
  () => optionsPhase.value === 'ready' && categoryOptions.value.length === 0,
)

// ---------- 提交 ----------

const {
  key: submissionKey,
  isPending: submissionPending,
  claim: claimSubmission,
  clear: clearSubmission,
} = useSubmissionGuard<CreateTicketPayload>(createSubmissionKey)

const submitting = ref(false)
const failureMessage = ref('')

function buildPayload(submissionKeyValue: string): CreateTicketPayload {
  return {
    submissionKey: submissionKeyValue,
    title: form.title.trim(),
    description: form.description.trim(),
    // 调用前已经校验过分类必填，这里不可能为空
    categoryId: form.categoryId as number,
    priority: form.priority,
  }
}

/**
 * 内容变了就不再是"同一次提交"。
 *
 * <p>继续用旧键的话，后端会按旧内容的键命中已经建好的那一张工单并把它返回给前端，
 * 用户刚改的内容被静默丢弃。清掉键，下次提交才会真正带上新内容。</p>
 */
watch(
  () => [form.title, form.description, form.categoryId, form.priority],
  () => clearSubmission(),
)

/** 只用于界面提示：让用户看到"这一次提交"是哪一个，重试时它不会变。 */
const shortKey = computed(() => submissionKey.value?.slice(0, 8) ?? '')

async function submit(): Promise<void> {
  // 请求期间重复点击不再发第二次：本次提交已经冻结，第二次点击不会带来任何新信息
  if (submitting.value) {
    return
  }
  const instance = formRef.value
  if (!instance) {
    return
  }
  const valid = await instance.validate().catch(() => false)
  if (!valid || form.categoryId === null) {
    return
  }

  // claim 只在第一次生成键并冻结内容；重试拿到的是同一个键和同一份内容
  const payload = claimSubmission(buildPayload)

  submitting.value = true
  failureMessage.value = ''
  try {
    const created = await createTicket(payload)
    // 只有确定了成功才结束这次提交身份
    clearSubmission()
    await router.push({ name: 'ticket-detail', params: { ticketNo: created.ticketNo } })
  } catch (error) {
    // 失败与结果不确定都保留原请求：重试会复用同一个键，不会重复建单
    failureMessage.value = describeError(error, '提交没有完成，请重试；重试不会重复创建工单')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  // 没有创建能力时不发这个请求：它只会拿到 403，还会在控制台留下一条误导人的失败
  if (canCreate.value) {
    void loadCategories()
  }
})
</script>

<template>
  <AppPage
    class="ticket-create-page"
    description="说清遇到的问题。提交后可以在「我提交的」里跟踪进度，处理过程中还能补充信息。"
    title="新建工单"
  >
    <section
      v-if="!canCreate"
      class="ticket-state"
      role="status"
    >
      <h2>当前账号不能创建工单</h2>
      <p>
        创建工单需要「创建工单」能力。你的权限可能在打开这个页面之后发生了变化，
        请联系管理员确认角色；如果只是想跟进已有的问题，可以回到工单列表查看。
      </p>
    </section>

    <section
      v-else-if="optionsPhase === 'error'"
      class="ticket-state ticket-state--danger"
      role="alert"
    >
      <h2>分类没有加载成功</h2>
      <p>{{ optionsError }}</p>
      <el-button
        :icon="Refresh"
        type="primary"
        @click="loadCategories"
      >
        重新加载
      </el-button>
    </section>

    <section
      v-else-if="noCategoryAvailable"
      class="ticket-state"
    >
      <h2>还没有可用的分类</h2>
      <p>
        分类由管理员在「分类管理」里维护，至少要有一个启用中的分类才能提交工单。
        请联系管理员开通后再来提交。
      </p>
      <el-button
        :icon="Refresh"
        type="info"
        plain
        @click="loadCategories"
      >
        重新加载
      </el-button>
    </section>

    <el-form
      v-else
      ref="formRef"
      class="ticket-form"
      :disabled="submitting"
      label-position="top"
      :model="form"
      :rules="rules"
    >
      <el-form-item
        label="标题"
        prop="title"
      >
        <el-input
          id="ticket-title"
          v-model="form.title"
          aria-label="标题"
          maxlength="200"
          placeholder="请输入一句话概括的问题，例如「办公区打印机无法连接」"
        />
      </el-form-item>

      <el-form-item
        label="分类"
        prop="categoryId"
      >
        <el-select
          id="ticket-category"
          v-model="form.categoryId"
          aria-label="分类"
          class="ticket-form__control"
          :loading="optionsPhase === 'loading'"
          placeholder="请选择分类"
        >
          <el-option
            v-for="option in categoryOptions"
            :key="option.id"
            :label="option.name"
            :value="option.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item
        label="优先级"
        prop="priority"
      >
        <el-radio-group
          v-model="form.priority"
          class="ticket-priority"
        >
          <el-radio
            v-for="choice in PRIORITY_CHOICES"
            :key="choice.value"
            class="ticket-priority__option"
            :value="choice.value"
          >
            <span class="ticket-priority__label">{{ choice.label }}</span>
            <span class="ticket-priority__hint">{{ choice.hint }}</span>
          </el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item
        label="问题描述"
        prop="description"
      >
        <el-input
          id="ticket-description"
          v-model="form.description"
          aria-label="问题描述"
          :autosize="{ minRows: 8, maxRows: 20 }"
          maxlength="10000"
          placeholder="请描述现象、出现时间、影响范围和已经尝试过的处理方式；信息越具体，处理越快"
          show-word-limit
          type="textarea"
        />
      </el-form-item>

      <div class="ticket-commit">
        <p
          v-if="submissionPending"
          class="ticket-commit__pending"
        >
          <span class="ticket-commit__key">提交编号 {{ shortKey }}</span>
          上一次提交还没有确认结果。再次提交会沿用同一个提交编号和同一份内容，不会重复建单；
          也可以先去
          <RouterLink
            class="ticket-commit__link"
            :to="{ name: 'tickets', query: { scope: 'REQUESTED_BY_ME' } }"
          >
            「我提交的」
          </RouterLink>
          确认是否已经建单。
        </p>

        <p
          v-if="failureMessage"
          class="ticket-commit__error"
          role="alert"
        >
          {{ failureMessage }}
        </p>

        <p class="ticket-form__note">
          <el-icon><CircleCheck /></el-icon>
          提交后工单进入「待受理」，由 IT 支持人员领取；处理过程中你可以随时补充信息。
        </p>

        <el-button
          :icon="Promotion"
          :loading="submitting"
          type="primary"
          @click="submit"
        >
          提交工单
        </el-button>
      </div>
    </el-form>
  </AppPage>
</template>
