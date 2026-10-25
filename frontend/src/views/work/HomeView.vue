<script setup lang="ts">
import { ArrowRight } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'

import { describeError } from '@/api/errorMessages'
import AppPage from '@/components/AppPage.vue'
import EmptyState from '@/components/EmptyState.vue'
import { labelForRole, navigationEntries } from '@/constants/authorization'
import { iconForRoute } from '@/constants/navigationIcons'
import { useAuthStore } from '@/stores/auth'

type HomeLoadState = 'loading' | 'ready' | 'error'

const entryDescriptions: Record<string, string> = {
  tickets: '查看与你有关的工单、处理进度与后续动作。',
  'ticket-new': '提交新的问题或服务请求，并补充处理所需信息。',
  dashboard: '查看工单协作过程中的关键趋势与概览。',
  'admin-users': '维护账号状态、角色与可用权限。',
  'admin-roles': '维护角色信息，以及每个角色持有的权限。',
  'admin-permissions': '维护权限编码与说明，供角色授权引用。',
  'admin-user-roles': '给用户批量授予或撤销角色，并查看授权来源。',
  'admin-role-permissions': '给角色批量授予或撤销权限，并查看授权来源。',
  'admin-categories': '维护受理分类与工单归属规则。',
}

const auth = useAuthStore()
const loadState = ref<HomeLoadState>(auth.user ? 'ready' : 'loading')
const loadError = ref('')
const retrying = ref(false)

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
      key: 'work' as const,
      label: '业务工作',
      entries: visibleEntries.value.filter((entry) => entry.group === 'work'),
    },
    {
      key: 'admin' as const,
      label: '系统管理',
      entries: visibleEntries.value.filter((entry) => entry.group === 'admin'),
    },
  ].filter((group) => group.entries.length > 0),
)
const roleNames = computed(() => auth.roles.map((code) => labelForRole(code)))

const canCreateTicket = computed(() => auth.hasPermission('TICKET_CREATE'))
const canViewTickets = computed(() =>
  auth.hasAnyPermission(['TICKET_VIEW_OWN', 'TICKET_VIEW_QUEUE', 'TICKET_VIEW_PARTICIPATED']),
)

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
    hide-header
    layout="list"
    title="首页"
  >
    <el-skeleton
      v-if="loadState === 'loading'"
      animated
      aria-label="正在加载首页"
      aria-busy="true"
    >
      <template #template>
        <section class="home-skeleton">
          <el-skeleton-item
            class="home-skeleton__hero"
            variant="rect"
          />
          <div class="home-skeleton__grid">
            <el-skeleton-item
              class="home-skeleton__panel"
              variant="rect"
            />
            <el-skeleton-item
              class="home-skeleton__panel"
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
      <h1>暂时无法打开工作台</h1>
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

    <template v-else>
      <section class="home-hero">
        <p class="home-hero__eyebrow">
          FlowDesk 工作台
        </p>
        <h1>欢迎回来</h1>
        <p class="home-hero__lead">
          从提交、受理到确认完成，每张工单的状态、负责人和下一步动作都在同一处。
        </p>
        <p class="home-hero__meta">
          {{ auth.user?.displayName }} · {{ roleNames.length > 0 ? roleNames.join('、') : '未分配角色' }}
        </p>
        <div class="home-hero__actions">
          <RouterLink
            v-if="canCreateTicket"
            class="home-hero__button home-hero__button--primary"
            :to="{ name: 'ticket-new' }"
          >
            新建工单
          </RouterLink>
          <RouterLink
            v-if="canViewTickets"
            class="home-hero__button"
            :to="{ name: 'tickets' }"
          >
            查看工单
          </RouterLink>
        </div>
      </section>

      <div class="home-grid">
        <section
          class="home-panel home-panel--entries"
          aria-labelledby="home-entries-title"
        >
          <header class="home-panel__head">
            <div class="home-panel__heading">
              <h2 id="home-entries-title">
                工作入口
              </h2>
              <p>按当前身份与权限显示，没有权限的入口不会出现。</p>
            </div>
          </header>

          <div class="home-panel__body">
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
                    <span
                      class="home-entry__icon"
                      aria-hidden="true"
                    >
                      <el-icon>
                        <component :is="iconForRoute(entry.name, entry.group)" />
                      </el-icon>
                    </span>
                    <span class="home-entry__content">
                      <strong>{{ entry.label }}</strong>
                      <small>{{ entry.description }}</small>
                    </span>
                    <el-icon
                      class="home-entry__arrow"
                      aria-hidden="true"
                    >
                      <ArrowRight />
                    </el-icon>
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

        <section
          class="home-panel home-panel--flow"
          aria-labelledby="home-flow-title"
        >
          <header class="home-panel__head">
            <div class="home-panel__heading">
              <h2 id="home-flow-title">
                协作路径
              </h2>
              <p>一张工单从提出到关闭，只经过这三个角色。</p>
            </div>
          </header>

          <div class="home-panel__body">
            <ol class="home-flow">
              <li class="home-flow__step">
                <span class="home-flow__number">01</span>
                <span class="home-flow__copy">
                  <small>员工</small>
                  <strong>提交问题</strong>
                  <span>说明现象、期望与影响范围，附上截图或日志。</span>
                </span>
              </li>
              <li class="home-flow__step">
                <span class="home-flow__number">02</span>
                <span class="home-flow__copy">
                  <small>IT 支持</small>
                  <strong>接手处理</strong>
                  <span>领取工单、记录进展，必要时转交给更合适的人。</span>
                </span>
              </li>
              <li class="home-flow__step">
                <span class="home-flow__number">03</span>
                <span class="home-flow__copy">
                  <small>员工</small>
                  <strong>确认结果</strong>
                  <span>验收通过即完成，未解决则回到处理中继续协作。</span>
                </span>
              </li>
            </ol>

            <p class="home-flow__note">
              交接不中断处理脉络：换人时工单编号、时间线与附件都留在原处。
            </p>
          </div>
        </section>
      </div>
    </template>
  </AppPage>
</template>
