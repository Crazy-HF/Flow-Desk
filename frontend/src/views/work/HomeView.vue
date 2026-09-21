<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { describeError } from '@/api/errorMessages'
import AppPage from '@/components/AppPage.vue'
import EmptyState from '@/components/EmptyState.vue'
import { labelForRole, navigationEntries } from '@/constants/authorization'
import { useAuthStore } from '@/stores/auth'

type HomeLoadState = 'loading' | 'ready' | 'error'

const entryDescriptions: Record<string, string> = {
  tickets: '查看与你有关的工单、处理进度与后续动作。',
  'ticket-new': '提交新的问题或服务请求，并补充处理所需信息。',
  dashboard: '查看工单协作过程中的关键趋势与概览。',
  'admin-users': '维护账号状态、角色与可用权限。',
  'admin-categories': '维护受理分类与工单归属规则。',
}

const auth = useAuthStore()
const loadState = ref<HomeLoadState>(auth.user ? 'ready' : 'loading')
const loadError = ref('')
const retrying = ref(false)

const pageDescription = computed(() => {
  if (loadState.value === 'loading') {
    return '正在恢复你的身份与工作入口。'
  }
  if (loadState.value === 'error') {
    return '身份信息暂时没有同步完成。'
  }
  const roles = roleNames.value.length > 0 ? roleNames.value.join('、') : '未分配角色'
  return `${auth.user?.displayName} · ${roles}`
})

/** 入口与顶部导航使用同一份权限声明，避免首页展示越权或失效链接。 */
const visibleEntries = computed(() =>
  navigationEntries
    .filter((entry) =>
      auth.hasAnyPermission(Array.isArray(entry.permission) ? entry.permission : [entry.permission]),
    )
    .map((entry) => ({ ...entry, description: entryDescriptions[entry.name] })),
)
const entryGroups = computed(() =>
  [
    {
      key: 'work',
      label: '工作入口',
      entries: visibleEntries.value.filter((entry) => entry.group === 'work'),
    },
    {
      key: 'admin',
      label: '管理入口',
      entries: visibleEntries.value.filter((entry) => entry.group === 'admin'),
    },
  ].filter((group) => group.entries.length > 0),
)
const roleNames = computed(() => auth.roles.map((code) => labelForRole(code)))

async function loadHome(isRetry = false): Promise<void> {
  if (isRetry) {
    retrying.value = true
  } else {
    loadState.value = 'loading'
  }
  loadError.value = ''

  try {
    await auth.loadCurrentUser()
    loadState.value = 'ready'
  } catch (error) {
    loadError.value = describeError(error, '身份信息加载失败，请检查网络后重试')
    loadState.value = 'error'
  } finally {
    retrying.value = false
  }
}

function retryHome(): void {
  void loadHome(true)
}

onMounted(() => {
  if (!auth.user) {
    void loadHome()
  }
})
</script>

<template>
  <AppPage
    class="home-page"
    :description="pageDescription"
    title="欢迎回来"
  >
    <el-skeleton
      v-if="loadState === 'loading'"
      animated
      aria-label="正在加载首页"
      aria-busy="true"
    >
      <template #template>
        <section class="home-workspace home-skeleton">
          <div class="home-workflow home-skeleton__rail">
            <el-skeleton-item
              class="home-skeleton__label"
              variant="text"
            />
            <el-skeleton-item
              class="home-skeleton__heading"
              variant="h3"
            />
            <el-skeleton-item
              v-for="index in 3"
              :key="index"
              class="home-skeleton__step"
              :class="{ 'home-skeleton__step--support': index === 2 }"
              variant="rect"
            />
          </div>

          <div class="home-entry-region home-skeleton__entries">
            <el-skeleton-item
              class="home-skeleton__heading"
              variant="h3"
            />
            <el-skeleton-item
              class="home-skeleton__line"
              variant="text"
            />
            <el-skeleton-item
              v-for="index in 2"
              :key="index"
              class="home-skeleton__row"
              variant="rect"
            />
          </div>
        </section>
      </template>
    </el-skeleton>

    <section
      v-else-if="loadState === 'error'"
      class="home-status home-status--error"
      role="alert"
    >
      <p class="home-status__label">
        身份信息未同步
      </p>
      <h2>暂时无法打开工作台</h2>
      <p>{{ loadError }}</p>
      <el-button
        type="primary"
        :disabled="retrying"
        :loading="retrying"
        @click="retryHome"
      >
        重新加载
      </el-button>
    </section>

    <section
      v-else
      class="home-workspace"
      aria-label="首页工作台"
    >
      <aside
        class="home-workflow"
        aria-labelledby="home-workflow-title"
      >
        <p class="home-workflow__label">
          协作路径
        </p>
        <h2 id="home-workflow-title">
          问题从提交走向确认
        </h2>

        <ol class="home-workflow__steps">
          <li class="home-workflow__step home-workflow__step--requester">
            <span class="home-workflow__number">01</span>
            <span class="home-workflow__step-copy">
              <small>员工</small>
              <strong>提交问题</strong>
              <span>说明现象与期望。</span>
            </span>
          </li>
          <li class="home-workflow__step home-workflow__step--support">
            <span class="home-workflow__number">02</span>
            <span class="home-workflow__step-copy">
              <small>IT 支持</small>
              <strong>接手处理</strong>
              <span>记录进展与结果。</span>
            </span>
          </li>
          <li class="home-workflow__step home-workflow__step--requester">
            <span class="home-workflow__number">03</span>
            <span class="home-workflow__step-copy">
              <small>员工</small>
              <strong>确认结果</strong>
              <span>完成验收或继续协作。</span>
            </span>
          </li>
        </ol>

        <p class="home-workflow__note">
          交接不中断处理脉络。
        </p>
      </aside>

      <div class="home-entry-region">
        <header class="home-section-header">
          <h2>你的工作入口</h2>
          <p>根据当前身份与权限显示。</p>
        </header>

        <nav
          v-if="visibleEntries.length > 0"
          class="home-entry-groups"
          aria-label="可用工作入口"
        >
          <section
            v-for="group in entryGroups"
            :key="group.key"
            class="home-entry-group"
            :aria-labelledby="`home-entry-group-${group.key}`"
          >
            <h3 :id="`home-entry-group-${group.key}`">
              {{ group.label }}
            </h3>
            <div class="home-entry-list">
              <RouterLink
                v-for="entry in group.entries"
                :key="entry.name"
                class="home-entry"
                :to="{ name: entry.name }"
              >
                <span class="home-entry__content">
                  <strong>{{ entry.label }}</strong>
                  <small>{{ entry.description }}</small>
                </span>
                <span
                  class="home-entry__action"
                  aria-hidden="true"
                >进入</span>
              </RouterLink>
            </div>
          </section>
        </nav>

        <EmptyState
          v-else
          title="暂无可用入口"
          description="当前账号还没有可展示的业务权限，请联系管理员分配角色。"
        />
      </div>
    </section>
  </AppPage>
</template>
