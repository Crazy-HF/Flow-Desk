<script setup lang="ts">
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { listCategoryOptions } from '@/api/categories'
import type { CategoryOption } from '@/api/categories'
import { listTickets } from '@/api/tickets'
import type { TicketListItem, TicketPriority, TicketScope, TicketStatus } from '@/api/tickets'
import AdminListPanel from '@/components/AdminListPanel.vue'
import AppPage from '@/components/AppPage.vue'
import {
  TICKET_PRIORITY_OPTIONS,
  TICKET_SCOPES,
  TICKET_SORT_OPTIONS,
  TICKET_STATUS_OPTIONS,
  ticketPriorityLabel,
  ticketPriorityTone,
  ticketStatusLabel,
  ticketStatusTone,
} from '@/constants/tickets'
import type { TicketSortChoice } from '@/constants/tickets'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import { useAdminList } from '@/composables/useAdminList'
import { useCompactPagination } from '@/composables/useCompactPagination'

/**
 * 工单列表（`docs/api-design.md` 5.3，`GET /fd/v1/tickets`）。
 *
 * <p>这一页的第一层不是筛选条件，而是**工作范围**：`scope` 是必填参数，四种取值对应三种
 * 角色的工作场景，权限也是按范围分别校验的（`REQUESTED_BY_ME` 要 `TICKET_VIEW_OWN`，
 * 队列要 `TICKET_VIEW_QUEUE`，另外两个要 `TICKET_VIEW_PARTICIPATED`）。所以范围单独占一行，
 * 界面只列出当前账号确实具备的范围。**这只是入口显隐，后端仍是最终授权边界。**</p>
 *
 * <p>取数状态机与窄屏分页复用管理端那两个共享件：它们是"查询 + 列表 + 分页"的通用机制，
 * 不含任何管理业务。它们目前位于 `views/admin/`，随本轮首次被业务页复用，
 * 位置整理（迁到共享层）已登记在 `PROJECT_STATUS.md`。</p>
 *
 * <p>同一份实现被两条路由复用：`/tickets`（员工自己的工单，默认「我提交的」）与
 * `/tickets/queue`（IT 工作台，默认「待受理」）。差别只有默认范围与页头文案，所以走 props
 * 而不是再写一份列表——两张表一旦各写一遍，筛选、分页、空态就会慢慢长歪。</p>
 */
const props = withDefaults(
  defineProps<{
    /** 没有可用的地址参数时选中的范围；传入值必须在该账号有权限的范围里，否则回退到第一个。 */
    defaultScope?: TicketScope
    title?: string
  }>(),
  {
    defaultScope: 'REQUESTED_BY_ME',
    title: '工单',
  },
)

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const compactPagination = useCompactPagination()

/**
 * 列宽：`el-table` 用 `parseInt` 解析 `width` / `min-width`，写 `7rem` 只会得到 7px
 * （2026-09-24 实测），所以这里一律给无单位像素数。
 *
 * <p>主列承载层级（标题在上、编号在下），用 `min-width` 让剩余宽度都给它：工单标题长短差别
 * 很大，把富余宽度给最需要读的那一列，比平均分给每一列更耐看。其余列固定宽度，
 * 合计约 1096px，1440 宽的工作区放得下，窄屏由表格自己横向滚动。</p>
 */
const COLUMN = {
  ticket: 320,
  category: 132,
  priority: 88,
  status: 132,
  requester: 128,
  assignee: 128,
  updatedAt: 168,
} as const

// ---------- 范围 ----------

/** 只保留当前账号具备权限的范围；没有范围权限时不发请求，也不渲染那张表。 */
const permittedScopes = computed(() =>
  TICKET_SCOPES.filter((option) => auth.hasPermission(option.permission)),
)

/**
 * 初始范围优先取地址里的 `scope`（「新建工单」提交失败时就是靠它把用户带回来确认的），
 * 其次取 `defaultScope`（必须自己也有权限），最后才退回第一个可用范围。
 *
 * <p>地址里的范围没有权限时静默忽略，不报错——它不是用户手动触发的操作。
 * 页头文案也按当前范围走：留在「我提交的」上却写着"待受理队列"会直接误导。</p>
 */
function initialScope(): TicketScope {
  const requested = route.query.scope
  if (typeof requested === 'string') {
    const matched = TICKET_SCOPES.find(
      (option) => option.value === requested && auth.hasPermission(option.permission),
    )
    if (matched) {
      return matched.value
    }
  }
  const fallback = TICKET_SCOPES.find(
    (option) => option.value === props.defaultScope && auth.hasPermission(option.permission),
  )
  return fallback?.value ?? permittedScopes.value[0]?.value ?? 'REQUESTED_BY_ME'
}

const scope = ref<TicketScope>(initialScope())
const currentScope = computed(
  () => TICKET_SCOPES.find((option) => option.value === scope.value) ?? TICKET_SCOPES[0],
)

/** 页头按当前范围说明这一步该做什么，而不是写一句对所有范围都成立的套话。 */
const pageDescription = computed(() => currentScope.value?.pageDescription ?? '')

/**
 * 队列范围在后端固定为 `PENDING`（`TicketMapper` 里 `WHERE t.status = 'PENDING'`），
 * 此时状态筛选会被拼成"待受理且状态属于 X"，只会得到空列表。所以在队列范围下不提供状态筛选，
 * 并且在切过去时清掉已选值。
 */
const statusFilterAvailable = computed(() => scope.value !== 'PENDING_QUEUE')

// ---------- 筛选条件 ----------

const keyword = ref('')
const statusFilter = ref<TicketStatus[]>([])
const priorityFilter = ref<TicketPriority[]>([])
const categoryFilter = ref<number | null>(null)
const createdRange = ref<string[] | null>(null)
const sort = ref<TicketSortChoice>('DEFAULT')

/**
 * 分类筛选用启用中的分类选项（这是员工能拿到的唯一分类来源）。停用分类不出现在这里，
 * 因此筛不到"已停用分类的历史工单"——这是当前契约的限制，不是界面取舍，不额外兜底。
 * 选项加载失败时只隐藏这一项，不影响工单列表本身。
 */
const categoryOptions = ref<CategoryOption[]>([])
const categoryFilterAvailable = ref(false)

async function loadCategoryOptions(): Promise<void> {
  try {
    categoryOptions.value = await listCategoryOptions()
    categoryFilterAvailable.value = categoryOptions.value.length > 0
  } catch {
    categoryFilterAvailable.value = false
  }
}

const { items, total, pageNo, pageSize, phase, errorMessage, retrying, search, changePage, changePageSize } =
  useAdminList<TicketListItem>((page) =>
    listTickets({
      scope: scope.value,
      ...page,
      ...(statusFilterAvailable.value && statusFilter.value.length > 0
        ? { status: statusFilter.value }
        : {}),
      ...(priorityFilter.value.length > 0 ? { priority: priorityFilter.value } : {}),
      ...(categoryFilter.value ? { categoryId: categoryFilter.value } : {}),
      ...(keyword.value.trim() ? { keyword: keyword.value.trim() } : {}),
      ...(createdRange.value?.[0] ? { createdFrom: createdRange.value[0] } : {}),
      ...(createdRange.value?.[1] ? { createdTo: createdRange.value[1] } : {}),
      // DEFAULT 是"默认排序"：不发送 sort，由后端按范围决定，前端不复制这份规则
      ...(sort.value === 'DEFAULT' ? {} : { sort: sort.value }),
    }),
  )

const hasFilters = computed(
  () =>
    keyword.value.trim() !== '' ||
    statusFilter.value.length > 0 ||
    priorityFilter.value.length > 0 ||
    categoryFilter.value !== null ||
    (createdRange.value?.length ?? 0) > 0,
)

const emptyTitle = computed(() =>
  hasFilters.value ? '没有符合条件的工单' : (currentScope.value?.emptyTitle ?? '暂无工单'),
)

const emptyDescription = computed(() =>
  hasFilters.value
    ? '换一个关键词、状态或时间范围再查一次，也可以重置筛选条件。'
    : (currentScope.value?.emptyDescription ?? '这里暂时没有可显示的工单。'),
)

function resetFilters(): void {
  keyword.value = ''
  statusFilter.value = []
  priorityFilter.value = []
  categoryFilter.value = null
  createdRange.value = null
  void search()
}

/**
 * 切换范围：回到第 1 页并同步地址。
 *
 * <p>用 `replace` 而不是 `push`：范围切换属于同一页面的视图状态，不该在浏览器历史里
 * 堆成一串"后退一次换一个范围"。</p>
 *
 * <p>地址里不写默认范围，而是**把 `scope` 去掉**。否则 `/tickets/queue` 上看过一次
 * 「我提交的」之后，地址会永久停在 `?scope=REQUESTED_BY_ME`，页头文案与默认范围再也回不去。</p>
 */
async function changeScope(value: unknown): Promise<void> {
  const next = TICKET_SCOPES.find((option) => option.value === value)?.value
  if (!next || next === scope.value) {
    return
  }
  // 先改本地再改地址：route.query 的 watch 会看到"地址值与当前范围一致"而直接返回，
  // 否则一次点击会发出两次列表请求
  scope.value = next
  if (next === 'PENDING_QUEUE') {
    statusFilter.value = []
  }
  const query = { ...route.query }
  if (next === props.defaultScope) {
    delete query.scope
  } else {
    query.scope = next
  }
  await router.replace({ query })
  await search()
}

/**
 * 事件处理器只做转交并返回 `void`。
 *
 * <p>把异步函数直接挂在组件事件上会依赖"Vue 把 emit 处理器的返回类型放宽成 any 或 void"这条
 * 内部约定；这里显式转成 `void`，参数也收成 `unknown`，本页就不会因为 Element Plus 改了
 * emit 的类型签名而编译失败。</p>
 */
function onScopeSelected(value: unknown): void {
  void changeScope(value)
}

// 地址里的范围是权威值：用户直接改地址、或从「新建工单」失败提示跳进来时，界面跟着走
watch(
  () => route.query.scope,
  (value) => {
    if (typeof value !== 'string' || value === scope.value) {
      return
    }
    const matched = TICKET_SCOPES.find((option) => option.value === value)
    if (matched && auth.hasPermission(matched.permission)) {
      scope.value = matched.value
      void search()
    }
  },
)

/**
 * 两条列表路由（`/tickets` 与 `/tickets/queue`）共用同一个组件，vue-router 在它们之间跳转时
 * **复用组件实例**，`onMounted` 不会再跑。只换默认范围不重新取数，用户会看到旧范围的列表
 * 配新页头——所以这里显式回到该路由的默认范围并重取。
 */
watch(
  () => props.defaultScope,
  (value) => {
    const matched = TICKET_SCOPES.find(
      (option) => option.value === value && auth.hasPermission(option.permission),
    )
    if (matched && matched.value !== scope.value) {
      scope.value = matched.value
      void search()
    }
  },
)

const canCreate = computed(() => auth.hasPermission('TICKET_CREATE'))

onMounted(() => {
  void loadCategoryOptions()
  if (permittedScopes.value.length > 0) {
    void search()
  }
})
</script>

<template>
  <AppPage
    class="admin-page ticket-list-page"
    :description="pageDescription"
    layout="list"
    :title="props.title"
  >
    <section
      v-if="permittedScopes.length === 0"
      class="ticket-state"
      role="status"
    >
      <h2>当前账号没有可查看的工单范围</h2>
      <p>
        工单列表按"我提交的 / 待受理 / 我负责的 / 我参与的"四种范围分别授权，
        你的账号暂时不在任何一种范围内。请联系管理员分配对应角色。
      </p>
    </section>

    <template v-else>
      <section
        class="admin-filter-card"
        aria-label="工单查询条件"
      >
        <div class="ticket-scope-bar">
          <span class="ticket-scope-bar__label">范围</span>
          <el-radio-group
            :model-value="scope"
            @update:model-value="onScopeSelected"
          >
            <el-radio-button
              v-for="option in permittedScopes"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
        </div>

        <div class="admin-filter-fields">
          <span class="admin-filter-field">
            关键词
            <el-input
              id="ticket-keyword"
              v-model="keyword"
              aria-label="工单关键词"
              class="admin-filter-input admin-filter-input--wide"
              clearable
              placeholder="请输入工单编号或标题"
              @keyup.enter="search"
            />
          </span>

          <span
            v-if="statusFilterAvailable"
            class="admin-filter-field"
          >
            状态
            <el-select
              id="ticket-status"
              v-model="statusFilter"
              aria-label="工单状态"
              class="admin-filter-input"
              clearable
              collapse-tags
              multiple
              placeholder="请选择状态"
            >
              <el-option
                v-for="option in TICKET_STATUS_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </span>

          <span class="admin-filter-field">
            优先级
            <el-select
              id="ticket-priority"
              v-model="priorityFilter"
              aria-label="工单优先级"
              class="admin-filter-input admin-filter-input--narrow"
              clearable
              collapse-tags
              multiple
              placeholder="请选择优先级"
            >
              <el-option
                v-for="option in TICKET_PRIORITY_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </span>

          <span
            v-if="categoryFilterAvailable"
            class="admin-filter-field"
          >
            分类
            <el-select
              id="ticket-category"
              v-model="categoryFilter"
              aria-label="工单分类"
              class="admin-filter-input"
              clearable
              placeholder="请选择分类"
            >
              <el-option
                v-for="option in categoryOptions"
                :key="option.id"
                :label="option.name"
                :value="option.id"
              />
            </el-select>
          </span>

          <span class="admin-filter-field">
            创建时间
            <el-date-picker
              v-model="createdRange"
              aria-label="创建时间范围"
              class="ticket-filter-range"
              end-placeholder="结束时间"
              range-separator="到"
              start-placeholder="开始时间"
              type="datetimerange"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
            />
          </span>

          <div class="admin-filter-actions">
            <el-button
              :icon="Search"
              type="primary"
              @click="search"
            >
              查询
            </el-button>
            <el-button
              :icon="Refresh"
              type="info"
              plain
              @click="resetFilters"
            >
              重置
            </el-button>
          </div>
        </div>
      </section>

      <section
        class="admin-data ticket-list-data"
        aria-label="工单数据"
      >
        <div class="admin-action-bar">
          <el-button
            v-if="canCreate"
            :icon="Plus"
            type="primary"
            @click="router.push({ name: 'ticket-new' })"
          >
            新建工单
          </el-button>

          <div class="admin-action-bar__end">
            <el-select
              id="ticket-sort"
              v-model="sort"
              aria-label="工单排序"
              class="ticket-sort"
              @change="search"
            >
              <el-option
                v-for="option in TICKET_SORT_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-tooltip
              content="刷新列表"
              placement="top"
            >
              <el-button
                :icon="Refresh"
                :loading="retrying"
                aria-label="刷新列表"
                type="info"
                plain
                @click="search"
              />
            </el-tooltip>
          </div>
        </div>

        <AdminListPanel
          :empty-description="emptyDescription"
          :empty-title="emptyTitle"
          :error-message="errorMessage"
          :is-empty="items.length === 0"
          label="工单列表"
          :phase="phase"
          :retrying="retrying"
          @retry="search"
        >
          <template #retry-action>
            <el-button
              :icon="Refresh"
              :loading="retrying"
              type="primary"
              @click="search"
            >
              重新加载
            </el-button>
          </template>

          <el-table
            :data="items"
            row-key="ticketNo"
            :row-style="{ height: 'var(--fd-admin-row-height)' }"
            border
            class="admin-table"
          >
            <el-table-column
              label="工单"
              :min-width="COLUMN.ticket"
            >
              <template #default="{ row }">
                <div class="ticket-cell">
                  <RouterLink
                    class="ticket-cell__title"
                    :to="{ name: 'ticket-detail', params: { ticketNo: row.ticketNo } }"
                  >
                    {{ row.title }}
                  </RouterLink>
                  <span class="ticket-cell__no">{{ row.ticketNo }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column
              label="分类"
              :width="COLUMN.category"
              prop="category.name"
              show-overflow-tooltip
            />

            <el-table-column
              align="center"
              label="优先级"
              :width="COLUMN.priority"
            >
              <template #default="{ row }">
                <span class="ticket-mark">
                  <span
                    class="ticket-mark__dot"
                    :class="`ticket-mark__dot--${ticketPriorityTone(row.priority)}`"
                  />
                  {{ ticketPriorityLabel(row.priority) }}
                </span>
              </template>
            </el-table-column>

            <el-table-column
              label="状态"
              :width="COLUMN.status"
            >
              <template #default="{ row }">
                <span class="ticket-mark">
                  <span
                    class="ticket-mark__dot"
                    :class="`ticket-mark__dot--${ticketStatusTone(row.status)}`"
                  />
                  {{ ticketStatusLabel(row.status) }}
                </span>
              </template>
            </el-table-column>

            <el-table-column
              label="提交人"
              :width="COLUMN.requester"
              prop="requester.displayName"
              show-overflow-tooltip
            />

            <el-table-column
              label="负责人"
              :width="COLUMN.assignee"
            >
              <template #default="{ row }">
                <span v-if="row.assignee">{{ row.assignee.displayName }}</span>
                <span
                  v-else
                  class="admin-muted"
                >
                  未分配
                </span>
              </template>
            </el-table-column>

            <el-table-column
              align="right"
              label="最近更新"
              :width="COLUMN.updatedAt"
            >
              <template #default="{ row }">
                <span class="admin-time">{{ formatDateTime(row.updatedAt) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </AdminListPanel>

        <div
          v-if="phase === 'ready' && items.length > 0"
          class="admin-pagination"
        >
          <el-pagination
            background
            :current-page="pageNo"
            :layout="compactPagination ? 'total, prev, pager, next' : 'total, sizes, prev, pager, next, jumper'"
            :pager-count="5"
            :page-size="pageSize"
            :page-sizes="[10, 20, 50]"
            :total="total"
            @current-change="changePage"
            @size-change="changePageSize"
          />
        </div>
      </section>
    </template>
  </AppPage>
</template>
