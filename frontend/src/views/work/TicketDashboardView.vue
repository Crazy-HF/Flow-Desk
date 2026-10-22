<script setup lang="ts">
import { Refresh, Search } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'

import { fetchTicketDashboard } from '@/api/dashboard'
import type { DashboardScope, TicketDashboard } from '@/api/dashboard'
import { describeError } from '@/api/errorMessages'
import AppPage from '@/components/AppPage.vue'
import EmptyState from '@/components/EmptyState.vue'
import { DASHBOARD_SCOPES } from '@/constants/dashboard'
import {
  ticketPriorityLabel,
  ticketPriorityTone,
  ticketStatusLabel,
  ticketStatusTone,
} from '@/constants/tickets'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'

/**
 * 数据概览（`docs/api-design.md` 8.5，`GET /fd/v1/dashboard/tickets`）。
 *
 * <p>这一页回答的是"我能看的工单现在有多少、分布在哪"，不是趋势也不是绩效：</p>
 * <p>1. **范围是必填的工作视角**，不是筛选条件。三个 IT 范围各自对应一种工作场景，权限也分别校验
 * （队列要 `TICKET_VIEW_QUEUE`，另外两个要 `TICKET_VIEW_PARTICIPATED`）。界面只列出当前账号确实
 * 具备的范围；**显隐不代替后端授权**。</p>
 * <p>2. **总数与四组同源**：服务端在同一个一致性快照里算好，界面不二次求和，也不补零值行。</p>
 * <p>3. **分类选项来自当前范围与时间的 `byCategory`**：`/categories/options` 只返回启用分类，
 * 用它会让"停用但仍挂着历史工单的分类"筛不出来。选择分类后保留这份选项、只刷新统计。</p>
 *
 * <p>首版不做分组点击钻取：工单列表路由只从地址读取 `scope`，还带不上时间与分类条件，
 * 点进去会看到与这里不一致的集合——那比不给入口更糟。</p>
 */
const auth = useAuthStore()

/**
 * 概览要求 `DASHBOARD_VIEW` **与** IT 身份同时成立。
 *
 * <p>路由守卫只校验权限码；IT 身份在这里判断，用于"不发请求、给出解释"，
 * 服务端仍会独立核验（多角色管理员即便有权限码也会被 `403`）。</p>
 */
const canViewDashboard = computed(
  () => auth.hasPermission('DASHBOARD_VIEW') && auth.roles.includes('IT_SUPPORT'),
)

/** 只保留当前账号具备查看权限的范围；没有可用范围时不发请求，也不渲染统计区。 */
const permittedScopes = computed(() =>
  DASHBOARD_SCOPES.filter((option) => auth.hasPermission(option.permission)),
)

/** 默认停在待受理（IT 的第一件事）；不可用时退到第一个可用范围。 */
function initialScope(): DashboardScope {
  const preferred = permittedScopes.value.find((option) => option.value === 'PENDING_QUEUE')
  return preferred?.value ?? permittedScopes.value[0]?.value ?? 'PENDING_QUEUE'
}

const scope = ref<DashboardScope>(initialScope())
const currentScope = computed(
  () => DASHBOARD_SCOPES.find((option) => option.value === scope.value) ?? DASHBOARD_SCOPES[0]!,
)

const createdRange = ref<string[] | null>(null)
const categoryFilter = ref<number | null>(null)

/**
 * 分类选项：由"未按分类过滤"的响应给出。
 *
 * <p>选择分类后**不覆盖**这份选项——否则筛完只剩一个选项，用户没法再切到别的分类。
 * 切换范围或时间会清掉分类筛选，下一次响应又是全量的，选项随之刷新。</p>
 */
const categoryOptions = ref<{ id: number; name: string }[]>([])

const dashboard = ref<TicketDashboard | null>(null)
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')

const total = computed(() => dashboard.value?.total ?? 0)

const hasFilters = computed(
  () => categoryFilter.value !== null || (createdRange.value?.length ?? 0) > 0,
)

/** 页头说明这一步在看什么，而不是写一句对所有范围都成立的套话。 */
const pageDescription = computed(() => currentScope.value.description)

/** 有筛选时把条件写出来：数字变小或归零时，用户需要一眼看到"是哪个条件在起作用"。 */
const filterSummary = computed(() => {
  const parts: string[] = []
  if (createdRange.value?.[0] && createdRange.value[1]) {
    parts.push(
      `创建时间 ${formatDateTime(createdRange.value[0])} 至 ${formatDateTime(createdRange.value[1])}`,
    )
  }
  if (categoryFilter.value !== null) {
    const matched = categoryOptions.value.find((option) => option.id === categoryFilter.value)
    parts.push(`分类 ${matched?.name ?? categoryFilter.value}`)
  }
  return parts.join(' · ')
})

interface GroupRow {
  key: string
  label: string
  count: number
  /** 状态与优先级沿用列表页的小圆点，两页对同一编码的读法保持一致。 */
  tone?: string
}

interface DashboardGroup {
  key: string
  title: string
  rows: GroupRow[]
}

/**
 * 四组按服务端给定的顺序渲染。
 *
 * <p>不在这里重排、也不补零：服务端已按"状态机顺序 / 高→中→低 / 数量降序、ID 升序、
 * 未分配最后"固定次序，界面再排一次只会与后端分叉。</p>
 */
const groups = computed<DashboardGroup[]>(() => {
  const current = dashboard.value
  if (!current) {
    return []
  }

  return [
    {
      key: 'status',
      title: '状态',
      rows: current.byStatus.map((row) => ({
        key: row.status,
        label: ticketStatusLabel(row.status),
        count: row.count,
        tone: ticketStatusTone(row.status),
      })),
    },
    {
      key: 'priority',
      title: '优先级',
      rows: current.byPriority.map((row) => ({
        key: row.priority,
        label: ticketPriorityLabel(row.priority),
        count: row.count,
        tone: ticketPriorityTone(row.priority),
      })),
    },
    {
      key: 'category',
      title: '分类',
      rows: current.byCategory.map((row) => ({
        key: `category-${row.category.id}`,
        label: row.category.name,
        count: row.count,
      })),
    },
    {
      key: 'assignee',
      title: '负责人',
      rows: current.byAssignee.map((row) => ({
        key: row.assignee ? `assignee-${row.assignee.id}` : 'assignee-unassigned',
        label: row.assignee?.displayName ?? '未分配',
        count: row.count,
      })),
    },
  ]
})

const emptyTitle = computed(() =>
  hasFilters.value ? '没有符合条件的工单' : currentScope.value.emptyTitle,
)

const emptyDescription = computed(() =>
  hasFilters.value
    ? '换一个创建时间或分类再查一次，也可以重置筛选条件。'
    : currentScope.value.emptyDescription,
)

/**
 * 取数。
 *
 * <p>只拉当前筛选的数据，不轮询：概览是"现在有多少"的问答，不是实时看板。
 * 失败时保留筛选条件与页面结构，只把统计区换成可重试的错误态。</p>
 */
async function load(): Promise<void> {
  if (!canViewDashboard.value || permittedScopes.value.length === 0) {
    return
  }

  phase.value = 'loading'
  try {
    const result = await fetchTicketDashboard({
      scope: scope.value,
      ...(createdRange.value?.[0] ? { createdFrom: createdRange.value[0] } : {}),
      ...(createdRange.value?.[1] ? { createdTo: createdRange.value[1] } : {}),
      ...(categoryFilter.value !== null ? { categoryId: categoryFilter.value } : {}),
    })
    dashboard.value = result
    // 未按分类过滤时刷新选项；已过滤时保留，避免筛完只剩一个选项
    if (categoryFilter.value === null) {
      categoryOptions.value = result.byCategory.map((row) => ({
        id: row.category.id,
        name: row.category.name,
      }))
    }
    phase.value = 'ready'
  } catch (error) {
    errorMessage.value = describeError(error, '数据概览没有加载成功，请稍后重试')
    phase.value = 'error'
  }
}

/**
 * 切换范围：清掉分类筛选再取数。
 *
 * <p>不清会带着一个在新范围里可能毫无命中的分类条件，让新范围看起来"没有数据"。
 * 时间条件保留——它是用户主动设定的观察窗口，与范围无关。</p>
 */
function changeScope(value: unknown): void {
  const next = DASHBOARD_SCOPES.find((option) => option.value === value)?.value
  if (!next || next === scope.value) {
    return
  }
  scope.value = next
  categoryFilter.value = null
  void load()
}

function onScopeSelected(value: unknown): void {
  changeScope(value)
}

/** 时间条件变化同样清掉分类：旧分类可能落在新窗口之外。 */
function onDateChanged(): void {
  categoryFilter.value = null
}

function resetFilters(): void {
  createdRange.value = null
  categoryFilter.value = null
  void load()
}

onMounted(() => {
  if (canViewDashboard.value && permittedScopes.value.length > 0) {
    void load()
  }
})
</script>

<template>
  <AppPage
    class="admin-page ticket-dashboard-page"
    :description="pageDescription"
    layout="list"
    title="数据概览"
  >
    <section
      v-if="!canViewDashboard"
      class="ticket-state"
      role="status"
    >
      <h2>数据概览只对 IT 支持人员开放</h2>
      <p>
        这一页统计的是 IT 工单的可见范围，因此需要「数据概览」权限与 IT 支持身份同时成立。
        如果你的工作确实需要它，请联系管理员。
      </p>
    </section>

    <section
      v-else-if="permittedScopes.length === 0"
      class="ticket-state"
      role="status"
    >
      <h2>当前账号没有可统计的工单范围</h2>
      <p>
        待受理、我负责的、我参与的三种范围分别授权，你的账号暂时不在任何一种范围内。
        请联系管理员分配对应角色。
      </p>
    </section>

    <template v-else>
      <section
        class="admin-filter-card"
        aria-label="数据概览查询条件"
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
              @change="onDateChanged"
            />
          </span>

          <span
            v-if="categoryOptions.length > 0 || categoryFilter !== null"
            class="admin-filter-field"
          >
            分类
            <el-select
              id="dashboard-category"
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

          <div class="admin-filter-actions">
            <el-button
              :icon="Search"
              type="primary"
              @click="load"
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
        class="admin-data dashboard-data"
        aria-label="数据概览结果"
      >
        <el-skeleton
          v-if="phase === 'loading'"
          animated
          aria-busy="true"
          aria-label="正在加载数据概览"
        >
          <template #template>
            <div class="dashboard-skeleton__body">
              <el-skeleton-item
                class="dashboard-skeleton__total"
                variant="rect"
              />
              <div class="dashboard-skeleton__grid">
                <el-skeleton-item
                  v-for="index in 4"
                  :key="index"
                  class="dashboard-skeleton__group"
                  variant="rect"
                />
              </div>
            </div>
          </template>
        </el-skeleton>

        <section
          v-else-if="phase === 'error'"
          class="admin-panel__error"
          role="alert"
        >
          <h2>数据概览没有加载成功</h2>
          <p>{{ errorMessage }}</p>
          <el-button
            :icon="Refresh"
            type="primary"
            @click="load"
          >
            重新加载
          </el-button>
        </section>

        <div
          v-else
          class="dashboard-summary"
        >
          <div class="dashboard-total">
            <p class="dashboard-total__scope">
              {{ currentScope.label }}
            </p>
            <p class="dashboard-total__value">
              {{ total }}
            </p>
            <p class="dashboard-total__caption">
              张工单符合当前条件
            </p>
            <p
              v-if="filterSummary"
              class="dashboard-total__filter"
            >
              {{ filterSummary }}
            </p>
          </div>

          <EmptyState
            v-if="total === 0"
            class="dashboard-empty"
            :description="emptyDescription"
            :title="emptyTitle"
          />

          <div
            v-else
            class="dashboard-groups"
          >
            <section
              v-for="group in groups"
              :key="group.key"
              class="dashboard-group"
            >
              <h2 class="dashboard-group__title">
                {{ group.title }}
              </h2>
              <ul class="dashboard-group__list">
                <li
                  v-for="row in group.rows"
                  :key="row.key"
                  class="dashboard-group__row"
                >
                  <span
                    class="dashboard-group__label"
                    :title="row.label"
                  >
                    <span
                      v-if="row.tone"
                      class="ticket-mark__dot"
                      :class="`ticket-mark__dot--${row.tone}`"
                    />
                    {{ row.label }}
                  </span>
                  <span class="dashboard-group__count">{{ row.count }}</span>
                </li>
              </ul>
            </section>
          </div>
        </div>
      </section>
    </template>
  </AppPage>
</template>
