<script setup lang="ts">
import { Close, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'

import { describeError } from '@/api/errorMessages'
import {
  clearUserRoles,
  grantUserRoles,
  listRoles,
  listUserRoleGrants,
  revokeUserRole,
} from '@/api/rbac'
import type { RoleDetail, UserRoleGrant } from '@/api/rbac'
import { getUser } from '@/api/users'
import type { UserDetail } from '@/api/users'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'

/**
 * 用户角色分配抽屉（`docs/api-design.md` 8.2.1）。
 *
 * <p>语义与原「用户角色授权」页完全一致：已有授权逐条可撤销，新角色按批授予，
 * 不是"整体替换"。这样抽屉只是同一组接口的另一种入口，不会绕开任何保护规则：
 * 移除 IT 支持角色时若名下有在办工单，后端仍以 `409` 拒绝，抽屉只负责把原因说清楚。</p>
 */
const props = defineProps<{
  modelValue: boolean
  /** 从行上带进来的用户；打开期间以接口返回的最新详情为准。 */
  user: UserDetail | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 授权关系发生变化，列表需要刷新（保持原筛选与页码）。 */
  saved: []
}>()

const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission('RBAC_MANAGE'))

const subject = ref<UserDetail | null>(null)
const roleOptions = ref<RoleDetail[]>([])
const grants = ref<UserRoleGrant[]>([])
const selectedRoleIds = ref<number[]>([])
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const saveError = ref('')
const saving = ref(false)
const revokingId = ref<number | null>(null)

const grantedIds = computed(() => new Set(grants.value.map((grant) => grant.roleId)))
const availableRoles = computed(() => roleOptions.value.filter((role) => !grantedIds.value.has(role.id)))
const hasItSupport = computed(
  () => subject.value?.roles.some((role) => role.code === 'IT_SUPPORT') ?? false,
)

const title = '分配角色'

/** 角色下拉的数据源是分页接口：角色数量少，一次取全，避免选择时还要远程检索。 */
async function listAllRoles(): Promise<RoleDetail[]> {
  const roles: RoleDetail[] = []
  let pageNo = 1
  while (true) {
    const result = await listRoles({ pageNo, pageSize: 100 })
    roles.push(...result.items)
    if (roles.length >= result.totalElements || result.items.length === 0) {
      return roles
    }
    pageNo += 1
  }
}

async function loadGrants(): Promise<void> {
  const userId = props.user?.id
  if (!userId) {
    return
  }
  const page = await listUserRoleGrants({ userId, pageNo: 1, pageSize: 100 })
  grants.value = page.items
}

async function load(): Promise<void> {
  const userId = props.user?.id
  if (!userId) {
    return
  }
  phase.value = 'loading'
  errorMessage.value = ''
  saveError.value = ''
  selectedRoleIds.value = []
  try {
    const [detail, roles, page] = await Promise.all([
      getUser(userId),
      listAllRoles(),
      listUserRoleGrants({ userId, pageNo: 1, pageSize: 100 }),
    ])
    subject.value = detail
    roleOptions.value = roles
    grants.value = page.items
    phase.value = 'ready'
  } catch (error) {
    errorMessage.value = describeError(error, '授权信息没有加载成功，请稍后重试')
    phase.value = 'error'
  }
}

function close(): void {
  emit('update:modelValue', false)
}

async function save(): Promise<void> {
  const target = subject.value
  if (!target || selectedRoleIds.value.length === 0) {
    return
  }
  saving.value = true
  saveError.value = ''
  try {
    const granted = await grantUserRoles(target.id, [...selectedRoleIds.value])
    ElMessage.success(
      `已提交 ${selectedRoleIds.value.length} 个角色，实际新增 ${granted.length} 条授权`,
    )
    selectedRoleIds.value = []
    await loadGrants()
    emit('saved')
  } catch (error) {
    saveError.value = describeError(error, '授权失败，整批未生效')
  } finally {
    saving.value = false
  }
}

async function revoke(grant: UserRoleGrant): Promise<void> {
  revokingId.value = grant.roleId
  saveError.value = ''
  try {
    await revokeUserRole(grant.userId, grant.roleId)
    ElMessage.success(`已撤销「${grant.roleName}」`)
    await loadGrants()
    emit('saved')
  } catch (error) {
    // 409 的典型来源是"该用户仍有在办工单"，文案由错误码映射给出，页面不重写业务原因。
    saveError.value = describeError(error, '撤销失败，请刷新后重试')
  } finally {
    revokingId.value = null
  }
}

async function clearAll(): Promise<void> {
  const target = subject.value
  if (!target) {
    return
  }
  saving.value = true
  saveError.value = ''
  try {
    await clearUserRoles(target.id)
    ElMessage.success('已清空该用户的全部角色，账号仍可登录但没有业务权限')
    await loadGrants()
    emit('saved')
  } catch (error) {
    saveError.value = describeError(error, '清空失败，请刷新后重试')
  } finally {
    saving.value = false
  }
}

/**
 * 打开时重新取数：行上的 `roles` 可能已经过期，授权关系也必须以服务端为准。
 * 关闭不重置 `subject`，避免关闭动画期间标题闪成占位文案。
 */
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      subject.value = props.user
      void load()
    }
  },
)
</script>

<template>
  <el-drawer
    class="grant-drawer"
    :model-value="modelValue"
    :with-header="false"
    :close-on-click-modal="false"
    :size="'34rem'"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="grant-drawer__header">
      <div class="grant-drawer__heading">
        <h2>{{ title }}</h2>
        <p v-if="subject">
          {{ subject.displayName }} · {{ subject.username }} · 账号{{ subject.status === 'ENABLED' ? '启用中' : '已停用' }}
        </p>
        <RouterLink
          v-if="subject"
          class="grant-drawer__full-link"
          :to="{ name: 'admin-user-roles', query: { userId: String(subject.id) } }"
          @click="close"
        >
          打开完整授权页
        </RouterLink>
      </div>
      <button
        class="grant-drawer__close"
        type="button"
        aria-label="关闭"
        title="关闭"
        @click="close"
      >
        <el-icon aria-hidden="true">
          <Close />
        </el-icon>
      </button>
    </div>

    <div class="grant-drawer__body">
      <p
        v-if="!canManage"
        class="grant-drawer__state"
        role="status"
      >
        当前账号没有角色授权权限，只能查看，不能修改。
      </p>

      <el-skeleton
        v-if="phase === 'loading'"
        animated
        :rows="6"
        aria-label="正在加载授权信息"
        aria-busy="true"
      />

      <section
        v-else-if="phase === 'error'"
        class="grant-drawer__state grant-drawer__state--error"
        role="alert"
      >
        <h3>授权信息没有加载成功</h3>
        <p>{{ errorMessage }}</p>
        <el-button
          :icon="Refresh"
          type="primary"
          @click="load"
        >
          重新加载
        </el-button>
      </section>

      <template v-else>
        <section
          class="grant-drawer__section"
          aria-labelledby="user-role-current"
        >
          <header class="grant-drawer__section-head">
            <h3 id="user-role-current">
              当前角色
            </h3>
            <span>{{ grants.length }} 项</span>
          </header>

          <p
            v-if="grants.length === 0"
            class="grant-drawer__empty"
          >
            该用户当前没有角色。零角色是合法状态：账号仍可登录，但没有任何业务权限。
          </p>
          <ul
            v-else
            class="grant-drawer__list"
          >
            <li
              v-for="grant in grants"
              :key="`${grant.userId}-${grant.roleId}`"
              class="grant-drawer__row"
            >
              <span class="grant-drawer__row-main">
                <strong>{{ grant.roleName }}</strong>
                <span class="grant-drawer__code">{{ grant.roleCode }}</span>
              </span>
              <span class="grant-drawer__row-meta">
                {{ formatDateTime(grant.grantedAt) }}
                <template v-if="grant.grantedBy">
                  · 授权人 #{{ grant.grantedBy }}
                </template>
                <template v-else>
                  · 迁移预置
                </template>
              </span>
              <el-popconfirm
                :title="`撤销「${grant.roleName}」会立即失效该用户的登录会话。确定撤销？`"
                cancel-button-text="取消"
                confirm-button-text="确定"
                width="19rem"
                @confirm="revoke(grant)"
              >
                <template #reference>
                  <el-button
                    :disabled="!canManage"
                    :loading="revokingId === grant.roleId"
                    type="danger"
                    plain
                    size="small"
                  >
                    撤销
                  </el-button>
                </template>
              </el-popconfirm>
            </li>
          </ul>
        </section>

        <section
          class="grant-drawer__section"
          aria-labelledby="user-role-available"
        >
          <header class="grant-drawer__section-head">
            <h3 id="user-role-available">
              添加角色
            </h3>
            <span>可多选</span>
          </header>

          <p
            v-if="availableRoles.length === 0"
            class="grant-drawer__empty"
          >
            所有角色都已授予，没有可添加的项。
          </p>
          <el-checkbox-group
            v-else
            v-model="selectedRoleIds"
            class="grant-choice-list"
            aria-label="可添加的角色"
          >
            <el-checkbox
              v-for="role in availableRoles"
              :key="role.id"
              class="grant-choice"
              :label="`${role.name}（${role.code}）`"
              :value="role.id"
              :disabled="!canManage || saving"
            />
          </el-checkbox-group>
        </section>

        <div
          v-if="hasItSupport"
          class="grant-drawer__notice"
        >
          <strong>该用户仍持有 IT 支持角色。</strong>
          若其名下有在办工单，撤销该角色会被拒绝；请先在用户管理里完成工单交接。
        </div>

        <p
          v-if="saveError"
          class="grant-drawer__error"
          role="alert"
        >
          {{ saveError }}
        </p>
      </template>
    </div>

    <div class="grant-drawer__footer">
      <el-popconfirm
        v-if="phase === 'ready' && grants.length > 0"
        title="清空后该用户不再有任何角色，账号仍可登录但没有业务权限。确定清空？"
        cancel-button-text="取消"
        confirm-button-text="确定"
        width="19rem"
        @confirm="clearAll"
      >
        <template #reference>
          <el-button
            :disabled="!canManage || saving"
            type="danger"
            plain
          >
            清空全部
          </el-button>
        </template>
      </el-popconfirm>
      <span class="grant-drawer__footer-spacer" />
      <el-button
        type="info"
        plain
        @click="close"
      >
        取消
      </el-button>
      <el-button
        :disabled="!canManage || selectedRoleIds.length === 0"
        :loading="saving"
        type="primary"
        @click="save"
      >
        保存
      </el-button>
    </div>
  </el-drawer>
</template>
