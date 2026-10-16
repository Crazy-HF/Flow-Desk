<script setup lang="ts">
import { Bell, FullScreen, Search } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  getUnreadNotificationCount,
  listNotifications,
  markNotificationRead,
} from '@/api/notifications'
import type { NotificationItem } from '@/api/notifications'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import { notificationCategoryLabel } from '@/constants/notifications'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'

/**
 * 顶栏的账户区（顶栏右侧）。
 *
 * <p>品牌位在 `layout/index.vue` 里，与侧栏同宽；这里只管"当前身份能做什么"：
 * 搜索入口、站内信铃铛、全屏、当前身份与账号菜单。</p>
 *
 * <p>站内信端点只要求认证、没有权限码，所以这里不做权限收口，只按当前身份轮询未读数。
 * 轮询失败一律静默：通知接口失败不该弹全局错误，也不该打断顶栏其它功能。</p>
 */
defineEmits<{
  toggleNavigation: []
}>()

const auth = useAuthStore()
const { user } = storeToRefs(auth)
const router = useRouter()

const passwordDialogVisible = ref(false)
const isFullscreen = ref(false)

// ---------- 站内信 ----------

/** 未读徽标轮询间隔；与后端 `NotificationCleanupTask` 无关，只是顶栏的刷新节奏。 */
const POLL_INTERVAL_MS = 60_000
/** 气泡里最多展示几条最近通知。 */
const RECENT_LIMIT = 5

const unreadCount = ref(0)
const bellOpen = ref(false)
const recentItems = ref<NotificationItem[]>([])
const recentLoading = ref(false)
let pollTimer: number | undefined

async function refreshUnreadCount(): Promise<void> {
  try {
    unreadCount.value = await getUnreadNotificationCount()
  } catch {
    // 轮询失败静默：保留上一次的值，不弹错误、不清零
  }
}

async function loadRecent(): Promise<void> {
  recentLoading.value = true
  try {
    const page = await listNotifications({ pageNo: 1, pageSize: RECENT_LIMIT })
    recentItems.value = page.items
  } catch {
    recentItems.value = []
  } finally {
    recentLoading.value = false
  }
}

/** 气泡展开时拉一次最近通知：不在收起时预取，避免无谓请求。 */
function onBellShow(): void {
  void loadRecent()
}

/**
 * 打开一条通知：先标记已读（失败不阻塞跳转），再按 `ticketNo` 深链到工单详情；
 * 没有关联工单时落到收件箱。
 */
async function openNotification(item: NotificationItem): Promise<void> {
  bellOpen.value = false
  if (!item.readAt) {
    try {
      await markNotificationRead(item.id)
      await refreshUnreadCount()
    } catch {
      // 标记已读失败不影响打开目标
    }
  }
  if (item.ticketNo) {
    await router.push({ name: 'ticket-detail', params: { ticketNo: item.ticketNo } })
    return
  }
  await router.push({ name: 'notifications' })
}

/** 事件处理器只做转交并返回 `void`，避免依赖组件库对 emit 返回类型的放宽。 */
function onOpenNotification(item: NotificationItem): void {
  void openNotification(item)
}

function viewAllNotifications(): void {
  bellOpen.value = false
  void router.push({ name: 'notifications' })
}

// ---------- 全屏与账号 ----------

function syncFullscreenState(): void {
  isFullscreen.value = document.fullscreenElement !== null
}

/** 进入 / 退出全屏。浏览器可能因权限策略拒绝，拒绝时保持原状，不改按钮文案。 */
async function toggleFullscreen(): Promise<void> {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else {
      await document.documentElement.requestFullscreen()
    }
  } catch {
    syncFullscreenState()
  }
}

async function handleSignOut(): Promise<void> {
  await auth.signOut()
  await router.replace({ name: 'login' })
}

onMounted(() => {
  document.addEventListener('fullscreenchange', syncFullscreenState)
  void refreshUnreadCount()
  pollTimer = window.setInterval(() => {
    void refreshUnreadCount()
  }, POLL_INTERVAL_MS)
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', syncFullscreenState)
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
})
</script>

<template>
  <div class="app-header">
    <el-button
      class="app-header__menu-button"
      text
      aria-label="打开主导航"
      @click="$emit('toggleNavigation')"
    >
      菜单
    </el-button>

    <div class="app-header__actions">
      <!-- 后端还没有检索接口：保留搜索位并明确禁用，不用可点击的假输入框冒充功能。 -->
      <button
        class="app-header__search"
        type="button"
        disabled
        title="搜索功能尚未接入"
      >
        <el-icon aria-hidden="true">
          <Search />
        </el-icon>
        <span>搜索</span>
      </button>

      <el-popover
        v-model:visible="bellOpen"
        placement="bottom-end"
        popper-class="notification-popover"
        trigger="click"
        @show="onBellShow"
      >
        <template #reference>
          <button
            class="app-header__icon-button"
            type="button"
            :aria-label="unreadCount > 0 ? `站内信，${unreadCount} 条未读` : '站内信'"
            title="站内信"
          >
            <el-badge
              class="app-header__bell-badge"
              :hidden="unreadCount === 0"
              :max="99"
              :value="unreadCount"
            >
              <el-icon aria-hidden="true">
                <Bell />
              </el-icon>
            </el-badge>
          </button>
        </template>

        <div class="notification-popover__body">
          <p class="notification-popover__title">
            站内信
          </p>

          <p
            v-if="recentLoading"
            class="notification-popover__hint"
          >
            正在加载…
          </p>
          <p
            v-else-if="recentItems.length === 0"
            class="notification-popover__hint"
          >
            暂无通知
          </p>
          <ul
            v-else
            class="notification-popover__list"
          >
            <li
              v-for="item in recentItems"
              :key="item.id"
            >
              <button
                class="notification-popover__item"
                type="button"
                @click="onOpenNotification(item)"
              >
                <span
                  class="notification-popover__dot"
                  :class="{ 'notification-popover__dot--read': item.readAt }"
                />
                <span class="notification-popover__text">
                  <span class="notification-popover__item-title">{{ item.title }}</span>
                  <span class="notification-popover__meta">
                    {{ notificationCategoryLabel(item.category) }} · {{ formatDateTime(item.createdAt) }}
                  </span>
                </span>
              </button>
            </li>
          </ul>

          <button
            class="notification-popover__more"
            type="button"
            @click="viewAllNotifications"
          >
            查看全部
          </button>
        </div>
      </el-popover>

      <button
        class="app-header__icon-button"
        type="button"
        :aria-label="isFullscreen ? '退出全屏' : '进入全屏'"
        :title="isFullscreen ? '退出全屏' : '进入全屏'"
        @click="toggleFullscreen"
      >
        <el-icon aria-hidden="true">
          <FullScreen />
        </el-icon>
      </button>

      <span class="app-header__identity">{{ user?.displayName }}</span>

      <el-dropdown trigger="click">
        <el-button text>
          账号
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="passwordDialogVisible = true">
              修改密码
            </el-dropdown-item>
            <el-dropdown-item
              divided
              @click="handleSignOut"
            >
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <ChangePasswordDialog v-model="passwordDialogVisible" />
  </div>
</template>
