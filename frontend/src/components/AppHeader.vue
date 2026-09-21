<script setup lang="ts">
import { FullScreen, Search } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 顶栏的账户区（顶栏右侧）。
 *
 * <p>品牌位在 `layout/index.vue` 里，与侧栏同宽；这里只管"当前身份能做什么"：
 * 搜索入口、全屏、当前身份与账号菜单。</p>
 */
defineEmits<{
  toggleNavigation: []
}>()

const auth = useAuthStore()
const { user } = storeToRefs(auth)
const router = useRouter()

const passwordDialogVisible = ref(false)
const isFullscreen = ref(false)

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
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', syncFullscreenState)
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
