<script setup lang="ts">
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { describeError } from '@/api/errorMessages'
import { errorCode } from '@/api/http'
import { getUser } from '@/api/users'
import type { UserDetail } from '@/api/users'
import AppPage from '@/components/AppPage.vue'
import EmptyState from '@/components/EmptyState.vue'
import ProtectedMark from '@/components/ProtectedMark.vue'
import { isProtectedRole } from '@/constants/authorization'
import { formatDateTime } from '@/utils/format'

/** 用户管理的只读详情。写操作留在列表页，避免为同一版本编排两套入口。 */
const route = useRoute()
const userId = computed(() => String(route.params.userId ?? ''))
const user = ref<UserDetail | null>(null)
const phase = ref<'loading' | 'ready' | 'absent' | 'error'>('loading')
const errorMessage = ref('')
let requestSequence = 0

const protectedAdmin = computed(() =>
  user.value?.status === 'ENABLED'
    && user.value.roles.some((role) => isProtectedRole(role.code)),
)

async function loadUser(): Promise<void> {
  const sequence = ++requestSequence
  user.value = null
  errorMessage.value = ''
  const rawId = userId.value
  const id = Number(rawId)
  if (!/^[1-9]\d*$/.test(rawId) || !Number.isSafeInteger(id)) {
    phase.value = 'absent'
    return
  }

  phase.value = 'loading'
  try {
    const result = await getUser(id)
    if (sequence !== requestSequence) return
    user.value = result
    phase.value = 'ready'
  } catch (error) {
    if (sequence !== requestSequence) return
    const code = errorCode(error)
    phase.value = code === 'USER_NOT_FOUND' ? 'absent' : 'error'
    errorMessage.value = describeError(error, '用户信息没有加载成功，请稍后重试')
  }
}

watch(userId, () => void loadUser(), { immediate: true })
</script>

<template>
  <AppPage
    class="admin-page user-detail-page"
    hide-header
    layout="list"
    title="用户详情"
  >
    <div
      v-if="phase === 'loading'"
      class="ticket-panel"
      aria-busy="true"
      aria-label="正在加载用户详情"
    >
      <el-skeleton
        animated
        :rows="5"
      />
    </div>

    <section
      v-else-if="phase === 'absent'"
      class="ticket-state"
      role="status"
    >
      <h1>用户不存在</h1>
      <p>请核对地址中的用户编号，或从用户列表重新选择。</p>
      <RouterLink
        class="ticket-state__link"
        :to="{ name: 'admin-users' }"
      >
        <el-icon><ArrowLeft /></el-icon>
        返回用户列表
      </RouterLink>
    </section>

    <section
      v-else-if="phase === 'error'"
      class="ticket-state ticket-state--danger"
      role="alert"
    >
      <h1>用户信息没有加载成功</h1>
      <p>{{ errorMessage }}</p>
      <el-button
        :icon="Refresh"
        type="primary"
        @click="loadUser"
      >
        重新加载
      </el-button>
    </section>

    <template v-else-if="user">
      <section
        class="user-detail-hero"
        aria-labelledby="user-detail-title"
      >
        <div class="user-detail-hero__identity">
          <p class="user-detail-hero__username">
            {{ user.username }}
          </p>
          <h1 id="user-detail-title">
            {{ user.displayName }}
          </h1>
          <p class="user-detail-hero__status">
            <span
              class="user-detail-hero__status-dot"
              :class="{ 'user-detail-hero__status-dot--disabled': user.status === 'DISABLED' }"
              aria-hidden="true"
            />
            {{ user.status === 'ENABLED' ? '启用' : '停用' }}
          </p>
        </div>
        <div class="user-detail-hero__actions">
          <el-button
            :icon="Refresh"
            type="info"
            plain
            @click="loadUser"
          >
            刷新
          </el-button>
          <RouterLink
            class="user-detail-hero__back"
            :to="{ name: 'admin-users' }"
          >
            <el-icon><ArrowLeft /></el-icon>
            返回用户列表
          </RouterLink>
        </div>
      </section>

      <section
        class="user-detail-panel"
        aria-labelledby="user-detail-account-heading"
      >
        <h2 id="user-detail-account-heading">
          账号资料
        </h2>
        <dl class="user-detail-facts">
          <div><dt>登录名</dt><dd>{{ user.username }}</dd></div>
          <div><dt>显示名称</dt><dd>{{ user.displayName }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatDateTime(user.createdAt) }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ formatDateTime(user.updatedAt) }}</dd></div>
        </dl>
      </section>

      <section
        class="user-detail-panel"
        aria-labelledby="user-detail-roles-heading"
      >
        <h2 id="user-detail-roles-heading">
          当前角色
        </h2>
        <EmptyState
          v-if="user.roles.length === 0"
          title="无业务权限"
          description="当前账号尚未分配角色。"
        />
        <div
          v-else
          class="user-detail-roles"
        >
          <span
            v-for="role in user.roles"
            :key="role.id"
            class="admin-role"
          >{{ role.name }}</span>
          <ProtectedMark
            v-if="protectedAdmin"
            label="管理员保护"
            reason="停用该账号或移除其管理员角色时，系统要求至少保留一个启用管理员，否则会被拒绝"
          />
        </div>
      </section>
    </template>
  </AppPage>
</template>
