<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import { useAuthStore } from '@/stores/auth'

defineEmits<{
  toggleNavigation: []
}>()

const auth = useAuthStore()
const { user } = storeToRefs(auth)
const router = useRouter()

const passwordDialogVisible = ref(false)

async function handleSignOut(): Promise<void> {
  await auth.signOut()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <header class="app-header">
    <el-button
      class="app-header__menu-button"
      text
      aria-label="打开主导航"
      @click="$emit('toggleNavigation')"
    >
      菜单
    </el-button>

    <RouterLink
      class="app-header__brand"
      to="/"
    >
      FlowDesk
    </RouterLink>

    <div class="app-header__account">
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
  </header>
</template>
