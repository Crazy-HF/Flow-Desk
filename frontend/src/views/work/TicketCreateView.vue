<script setup lang="ts">
import { CircleCheck, Promotion, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { listCategoryOptions } from '@/api/categories'
import type { CategoryOption } from '@/api/categories'
import { describeError } from '@/api/errorMessages'
import { createSubmissionKey, createTicket, listTickets } from '@/api/tickets'
import type { CreateTicketPayload, TicketListItem, TicketPriority } from '@/api/tickets'
import AppPage from '@/components/AppPage.vue'
import AttachmentUpload from '@/components/AttachmentUpload.vue'
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
  /** 空串表示"没有关联原工单"：提交时不带这个键（`CreateTicketPayload.sourceTicketNo`）。 */
  sourceTicketNo: string
}

const form = reactive<TicketForm>({
  title: '',
  description: '',
  categoryId: null,
  priority: 'MEDIUM',
  sourceTicketNo: '',
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

// ---------- 关联原工单（可选） ----------

/**
 * 原工单搜索（`docs/kickoff.md` 4.10、`docs/api-design.md` 7.5）。
 *
 * <p>候选来自「我提交的」这一个范围——员工只能关联自己有权查看的原工单，服务端还会按详情
 * 同一条可见性规则复核一次。用户输入关键词后才发请求；选中值就是工单编号。</p>
 *
 * <p>与动作弹窗的选项同一套防串写做法：每次搜索持一个递增令牌，只认最后一次的响应——
 * 否则先发的慢响应会覆盖后发的结果，用户看到的是上一个关键词的工单列表。</p>
 */
const sourceOptions = ref<TicketListItem[]>([])
const sourcePhase = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const sourceError = ref('')
let sourceRequest = 0

async function searchSourceTickets(keyword: string): Promise<void> {
  const text = keyword.trim()
  const request = (sourceRequest += 1)

  if (text === '') {
    sourceOptions.value = []
    sourceError.value = ''
    sourcePhase.value = 'idle'
    return
  }

  sourcePhase.value = 'loading'
  sourceError.value = ''
  try {
    const result = await listTickets({ scope: 'REQUESTED_BY_ME', keyword: text, pageSize: 10 })
    if (request !== sourceRequest) {
      return
    }
    sourceOptions.value = result.items
    sourcePhase.value = 'ready'
  } catch (error) {
    if (request !== sourceRequest) {
      return
    }
    sourceOptions.value = []
    sourceError.value = describeError(error, '原工单没有搜索成功，请稍后重试')
    sourcePhase.value = 'error'
  }
}

/** 下拉里没有选项时说什么：三种"空"不是一回事，不能统一写成"暂无数据"。 */
const sourceEmptyText = computed(() => {
  if (sourceError.value !== '') {
    return sourceError.value
  }
  if (sourcePhase.value === 'loading') {
    return '正在搜索…'
  }
  if (sourcePhase.value === 'ready') {
    return '没有找到匹配的工单，换个关键词试试。'
  }
  return '输入编号或标题，搜索你提交过的工单。'
})

// ---------- 附件 ----------

/**
 * 已经上传成功、等待随本次创建绑定的附件标识（`docs/api-design.md` 7.1）。
 *
 * <p>附件是**先上传、后绑定**两段式：上传接口先落库并返回 id，创建工单时再把 id 列表
 * 放进 `attachmentIds` 绑定。所以这里存的不是文件，而是"已经存在于服务端、还没有归属"的标识。
 * 用户移除一个附件时不需要（也没有）删除接口——未绑定的附件由对账任务按保留期清理。</p>
 */
const attachmentIds = ref<number[]>([])

/** 还有文件在上传中：此时不允许提交。 */
const attachmentsUploading = ref(false)

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
    // 没选原工单时**不带这个键**：与关闭弹窗的条件输入同一口径，不是给服务端送一个空串
    ...(form.sourceTicketNo === '' ? {} : { sourceTicketNo: form.sourceTicketNo }),
    // 一个附件都没有时不带这个键：空数组与"没有附件"在契约里应当是同一件事
    ...(attachmentIds.value.length === 0 ? {} : { attachmentIds: attachmentIds.value }),
  }
}

/**
 * 内容变了就不再是"同一次提交"。
 *
 * <p>继续用旧键的话，后端会按旧内容的键命中已经建好的那一张工单并把它返回给前端，
 * 用户刚改的内容被静默丢弃。清掉键，下次提交才会真正带上新内容。</p>
 *
 * <p>附件也算内容：换掉一个附件之后仍然复用旧键，绑定的会是上一次的那一批 id。</p>
 */
watch(
  () => [form.title, form.description, form.categoryId, form.priority, form.sourceTicketNo],
  () => clearSubmission(),
)

// 附件单独一个 watch：`attachmentIds` 是数组，放进上面那个数组里会被判成每次都变
watch(attachmentIds, () => clearSubmission(), { deep: true })

/** 只用于界面提示：让用户看到"这一次提交"是哪一个，重试时它不会变。 */
const shortKey = computed(() => submissionKey.value?.slice(0, 8) ?? '')

async function submit(): Promise<void> {
  // 请求期间重复点击不再发第二次：本次提交已经冻结，第二次点击不会带来任何新信息
  if (submitting.value) {
    return
  }
  /**
   * 附件还在传就不提交。
   *
   * <p>附件没传完就建单，用户以为带上了、实际没带上，而后端只会安静地返回一张没有附件的
   * 工单——这种"看起来成功"的丢失比一次明确的报错更糟。所以这里把它挡在提交之前，
   * 提交按钮同时处于禁用态。</p>
   */
  if (attachmentsUploading.value) {
    ElMessage.warning('还有附件正在上传，等它传完再提交')
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

      <el-form-item
        label="关联原工单"
        prop="sourceTicketNo"
      >
        <el-select
          id="ticket-source"
          v-model="form.sourceTicketNo"
          aria-label="关联原工单"
          class="ticket-form__source"
          clearable
          filterable
          :loading="sourcePhase === 'loading'"
          placeholder="请输入编号或标题，搜索你提交过的工单（可选）"
          remote
          :remote-method="searchSourceTickets"
        >
          <el-option
            v-for="option in sourceOptions"
            :key="option.ticketNo"
            :label="`${option.ticketNo} ${option.title}`"
            :value="option.ticketNo"
          />
          <template #empty>
            <p class="ticket-form__hint">
              {{ sourceEmptyText }}
            </p>
          </template>
        </el-select>
        <p class="ticket-form__hint">
          如果这个问题之前提交过，可以关联原来那张工单；原工单保持不变，两张单会互相显示关联关系。
        </p>
      </el-form-item>

      <el-form-item label="附件">
        <!--
          附件不是必填，也不进 el-form 的校验：它在"上传"这一步就已经被服务端校验过一次
          （类型、大小、数量），这里再拦一遍只会得到两份可能不一致的规则。
        -->
        <AttachmentUpload
          v-model="attachmentIds"
          v-model:uploading="attachmentsUploading"
          :disabled="submitting"
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

        <p
          v-if="attachmentsUploading"
          class="ticket-commit__pending"
        >
          附件正在上传，传完之后才能提交。
        </p>

        <p class="ticket-form__note">
          <el-icon><CircleCheck /></el-icon>
          提交后工单进入「待受理」，由 IT 支持人员领取；处理过程中你可以随时补充信息。
        </p>

        <el-button
          :icon="Promotion"
          :loading="submitting"
          :disabled="attachmentsUploading"
          type="primary"
          @click="submit"
        >
          提交工单
        </el-button>
      </div>
    </el-form>
  </AppPage>
</template>
