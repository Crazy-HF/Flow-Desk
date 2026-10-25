<script setup lang="ts">
import { ArrowDown, Check, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { describeError } from '@/api/errorMessages'
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from '@/api/notifications'
import type { NotificationItem } from '@/api/notifications'
import AdminListPanel from '@/components/AdminListPanel.vue'
import AppPage from '@/components/AppPage.vue'
import AppPagination from '@/components/AppPagination.vue'
import { notificationCategoryLabel } from '@/constants/notifications'
import { useAdminList } from '@/composables/useAdminList'
import { formatDateTime } from '@/utils/format'

/**
 * 站内信收件箱（`NotificationController`，`GET /fd/v1/notifications` 等四个端点）。
 *
 * <p>这一页是**本人范围**：只要求认证、没有权限码，所以不做 `meta.permission` 收口，
 * 服务端按当前登录身份限定可见数据。四个端点里列表与「全部已读」用于本页，
 * 单条已读与顶部徽标同时被页面和顶栏铃铛使用。</p>
 *
 * <p>取数状态机与分页复用管理列表那两个共享件（`useAdminList` / `AppPagination`）：
 * 它们是"查询 + 列表 + 分页"的通用机制，与工单列表同一套。</p>
 */
const router = useRouter()

/**
 * 列宽：`el-table` 用 `parseInt` 解析 `width` / `min-width`，写 `7rem` 只会得到 7px，
 * 所以一律给无单位像素数。主列承载层级（标题在上、正文在下），用 `min-width` 吃掉富余宽度。
 */
const COLUMN = {
  state: 88,
  category: 176,
  title: 360,
  ticket: 176,
  createdAt: 168,
  actions: 96,
} as const

/** 只看未读（改了它要重新请求，所以属于筛选条件，放在筛选卡里）。 */
/** 筛选面板的展开状态：只有一个开关，收起后多留一行给列表。 */
const filtersOpen = ref(true)

const unreadOnly = ref(false)

const { items, total, pageNo, pageSize, phase, errorMessage, retrying, search, load } =
  useAdminList<NotificationItem>((page) =>
    listNotifications({
      ...page,
      ...(unreadOnly.value ? { unreadOnly: true } : {}),
    }),
  )

function onUnreadOnlyChange(): void {
  void search()
}

/**
 * 分页变化：`AppPagination` 只报告"用户想要哪一页、每页多少条"，取数仍由 `load` 负责。
 * 换每页条数必须回到第 1 页——否则会停在一个按新页大小算并不存在的页上。
 */
function onPaginationChange(next: { page: number; pageSize: number }): void {
  if (next.pageSize !== pageSize.value) {
    pageSize.value = next.pageSize
    void load({ page: 1 })
    return
  }
  void load({ page: next.page })
}

const emptyTitle = computed(() => (unreadOnly.value ? '没有未读通知' : '暂时没有通知'))

const emptyDescription = computed(() =>
  unreadOnly.value
    ? '关掉「只看未读」可以看到此前的全部通知。'
    : '当有工单需要你补充信息、确认解决结果或处理撤销请求时，这里会出现提醒。',
)

function isUnread(item: NotificationItem): boolean {
  return !item.readAt
}

/** 行样式：读过的条目退一档（主列标题转中性色，见 main.css）。 */
function rowClassName({ row }: { row: NotificationItem }): string {
  return isUnread(row) ? '' : 'notification-row--read'
}

/** 单条标记已读：成功后原位刷新当前页（`silent` 不把页面打回骨架）。 */
async function markOne(item: NotificationItem): Promise<void> {
  try {
    await markNotificationRead(item.id)
    await load({ silent: true })
  } catch (error) {
    ElMessage.error(describeError(error, '标记已读失败，请刷新后重试'))
  }
}

function onMarkReadClick(item: NotificationItem): void {
  void markOne(item)
}

/** 全部已读：把本人所有未读一次性标记；回到第 1 页并重取。 */
async function markAll(): Promise<void> {
  try {
    await markAllNotificationsRead()
    ElMessage.success('已把全部未读通知标记为已读')
    await search()
  } catch (error) {
    ElMessage.error(describeError(error, '操作失败，请刷新后重试'))
  }
}

function onMarkAllClick(): void {
  void markAll()
}

/**
 * 打开一条通知：先标记已读（失败不阻塞跳转），再按 `ticketNo` 深链到工单详情；
 * 没有关联工单时只标记已读并留在本页。
 *
 * <p>深链目标是工单详情自己的路由（`name: 'ticket-detail'`，参数 `ticketNo`）——
 * 那张工单是否可见由它的路由守卫与后端再校验一次，这里不做前置判断。</p>
 */
async function openNotification(item: NotificationItem): Promise<void> {
  if (isUnread(item)) {
    try {
      await markNotificationRead(item.id)
    } catch {
      // 标记已读失败不影响打开目标
    }
  }
  if (item.ticketNo) {
    await router.push({ name: 'ticket-detail', params: { ticketNo: item.ticketNo } })
    return
  }
  await load({ silent: true })
}

/** `el-table` 的 `row-click` 处理器：转交事件并返回 `void`。 */
function onRowClick(row: NotificationItem): void {
  void openNotification(row)
}

onMounted(() => {
  void search()
})
</script>

<template>
  <AppPage
    class="admin-page notification-page"
    description="别人对你在工单上做的操作会在这里提醒你；打开一条即可回到对应工单，没有关联工单的只标记已读。"
    layout="list"
    title="站内信"
  >
    <section
      class="admin-filter-card"
      :class="{ 'admin-filter-card--collapsed': !filtersOpen }"
      aria-label="站内信筛选"
    >
      <header class="admin-filter-card__head">
        <h2 class="admin-filter-card__title">
          筛选条件
        </h2>
        <button
          class="admin-filter-card__toggle"
          type="button"
          aria-controls="notification-filter-fields"
          :aria-expanded="filtersOpen"
          @click="filtersOpen = !filtersOpen"
        >
          {{ filtersOpen ? '收起' : '展开' }}
          <el-icon
            class="admin-filter-card__chevron"
            :class="{ 'admin-filter-card__chevron--collapsed': !filtersOpen }"
            aria-hidden="true"
          >
            <ArrowDown />
          </el-icon>
        </button>
      </header>

      <div
        v-show="filtersOpen"
        id="notification-filter-fields"
        class="admin-filter-card__body"
      >
        <div class="admin-filter-fields">
          <span class="admin-filter-field">
            状态
            <el-switch
              id="notification-unread-only"
              v-model="unreadOnly"
              active-text="只看未读"
              aria-label="只看未读通知"
              @change="onUnreadOnlyChange"
            />
          </span>
        </div>
      </div>
    </section>

    <section
      class="admin-data notification-data"
      aria-label="站内信数据"
    >
      <header class="admin-data__head">
        <div class="admin-data__heading">
          <h2 class="admin-data__title">
            站内信列表
          </h2>
          <p class="admin-data__summary">
            <template v-if="phase === 'ready'">
              共 {{ total }} 条通知，未读的会一直保留在这里。
            </template>
            <template v-else-if="phase === 'loading'">
              正在读取站内信…
            </template>
            <template v-else>
              记录数暂时无法读取。
            </template>
          </p>
        </div>

        <div class="admin-action-bar">
          <el-button
            :icon="Check"
            type="primary"
            @click="onMarkAllClick"
          >
            全部已读
          </el-button>

          <div class="admin-action-bar__end">
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
      </header>

      <AdminListPanel
        :empty-description="emptyDescription"
        :empty-title="emptyTitle"
        :error-message="errorMessage"
        :is-empty="items.length === 0"
        label="站内信列表"
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
          :row-class-name="rowClassName"
          row-key="id"
          :row-style="{ height: 'var(--fd-admin-row-height)' }"
          border
          class="admin-table"
          @row-click="onRowClick"
        >
          <el-table-column
            label="状态"
            :width="COLUMN.state"
          >
            <template #default="{ row }">
              <span class="notification-state">
                <span
                  class="notification-state__dot"
                  :class="{ 'notification-state__dot--unread': isUnread(row) }"
                />
                {{ isUnread(row) ? '未读' : '已读' }}
              </span>
            </template>
          </el-table-column>

          <el-table-column
            label="类型"
            :width="COLUMN.category"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ notificationCategoryLabel(row.category) }}
            </template>
          </el-table-column>

          <el-table-column
            label="内容"
            :min-width="COLUMN.title"
          >
            <template #default="{ row }">
              <div class="notification-cell">
                <span class="notification-cell__title">{{ row.title }}</span>
                <span
                  v-if="row.body"
                  class="notification-cell__body"
                >{{ row.body }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column
            label="关联工单"
            :width="COLUMN.ticket"
          >
            <template #default="{ row }">
              <RouterLink
                v-if="row.ticketNo"
                class="ticket-cell__no"
                :to="{ name: 'ticket-detail', params: { ticketNo: row.ticketNo } }"
                @click.stop
              >
                {{ row.ticketNo }}
              </RouterLink>
              <span
                v-else
                class="admin-muted"
              >
                无
              </span>
            </template>
          </el-table-column>

          <el-table-column
            align="right"
            label="时间"
            :width="COLUMN.createdAt"
          >
            <template #default="{ row }">
              <span class="admin-time">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>

          <el-table-column
            align="center"
            label="操作"
            :width="COLUMN.actions"
          >
            <template #default="{ row }">
              <div class="admin-row-actions">
                <el-tooltip
                  v-if="isUnread(row)"
                  content="标记已读"
                  placement="top"
                >
                  <el-button
                    :icon="Check"
                    aria-label="标记已读"
                    circle
                    plain
                    type="primary"
                    @click.stop="onMarkReadClick(row)"
                  />
                </el-tooltip>
                <span
                  v-else
                  class="admin-muted"
                >
                  已读
                </span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </AdminListPanel>

      <AppPagination
        :has-subject="true"
        :page="pageNo"
        :page-size="pageSize"
        :phase="phase"
        :total-elements="total"
        @change="onPaginationChange"
      />
    </section>
  </AppPage>
</template>
