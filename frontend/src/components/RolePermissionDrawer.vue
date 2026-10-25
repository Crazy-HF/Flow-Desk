<script setup lang="ts">
import { Close, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'

import { describeError } from '@/api/errorMessages'
import {
  clearRolePermissions,
  getRole,
  grantRolePermissions,
  listPermissions,
  listRolePermissionGrants,
  revokeRolePermission,
} from '@/api/rbac'
import type { PermissionDetail, RoleDetail, RolePermissionGrant } from '@/api/rbac'
import {
  isProtectedPermission,
  isProtectedRole,
  PROTECTED_PERMISSION_CODE,
  PROTECTED_ROLE_CODE,
} from '@/constants/authorization'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'

/**
 * 角色权限分配抽屉（`docs/api-design.md` 8.2.1）。
 *
 * <p>语义与原「角色权限授权」页一致：逐条撤销、按批授予。两条保护规则在抽屉里同样生效——
 * `SYSTEM_ADMIN` 不能清空，`SYSTEM_ADMIN × RBAC_MANAGE` 不能撤销；界面禁用只是提示，
 * 后端仍会独立拒绝。</p>
 */
const props = defineProps<{
  modelValue: boolean
  /** 从行上带进来的角色；打开期间以接口返回的最新详情为准。 */
  role: RoleDetail | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 授权关系发生变化，列表需要刷新（保持原筛选与页码）。 */
  saved: []
}>()

const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission('RBAC_MANAGE'))

const subject = ref<RoleDetail | null>(null)
const permissionOptions = ref<PermissionDetail[]>([])
const grants = ref<RolePermissionGrant[]>([])
const selectedPermissionIds = ref<number[]>([])
const keyword = ref('')
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const saveError = ref('')
const saving = ref(false)
const revokingId = ref<number | null>(null)

const grantedIds = computed(() => new Set(grants.value.map((grant) => grant.permissionId)))
const availablePermissions = computed(() => {
  const term = keyword.value.trim().toLowerCase()
  return permissionOptions.value
    .filter((permission) => !grantedIds.value.has(permission.id))
    .filter(
      (permission) =>
        term.length === 0 ||
        permission.name.toLowerCase().includes(term) ||
        permission.code.toLowerCase().includes(term),
    )
})

const title = '分配权限'
const clearsProtectedGrant = computed(() => Boolean(subject.value && isProtectedRole(subject.value.code)))

/** 只有"SYSTEM_ADMIN × RBAC_MANAGE"这一格被保护规则挡住，其余授权都可撤销。 */
function canRevoke(grant: RolePermissionGrant): boolean {
  return !(isProtectedPermission(grant.permissionCode) && isProtectedRole(grant.roleCode))
}

async function loadGrants(): Promise<void> {
  const roleId = props.role?.id
  if (!roleId) {
    return
  }
  const page = await listRolePermissionGrants({ roleId, pageNo: 1, pageSize: 100 })
  grants.value = page.items
}

async function load(): Promise<void> {
  const roleId = props.role?.id
  if (!roleId) {
    return
  }
  phase.value = 'loading'
  errorMessage.value = ''
  saveError.value = ''
  selectedPermissionIds.value = []
  keyword.value = ''
  try {
    const [detail, permissions, page] = await Promise.all([
      getRole(roleId),
      listPermissions({ pageNo: 1, pageSize: 100 }),
      listRolePermissionGrants({ roleId, pageNo: 1, pageSize: 100 }),
    ])
    subject.value = detail
    permissionOptions.value = permissions.items
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
  if (!target || selectedPermissionIds.value.length === 0) {
    return
  }
  saving.value = true
  saveError.value = ''
  try {
    const granted = await grantRolePermissions(target.id, [...selectedPermissionIds.value])
    ElMessage.success(
      `已提交 ${selectedPermissionIds.value.length} 个权限，实际新增 ${granted.length} 条授权`,
    )
    selectedPermissionIds.value = []
    await loadGrants()
    emit('saved')
  } catch (error) {
    saveError.value = describeError(error, '授权失败，整批未生效')
  } finally {
    saving.value = false
  }
}

async function revoke(grant: RolePermissionGrant): Promise<void> {
  revokingId.value = grant.permissionId
  saveError.value = ''
  try {
    await revokeRolePermission(grant.roleId, grant.permissionId)
    ElMessage.success(`已撤销「${grant.permissionName}」`)
    await loadGrants()
    emit('saved')
  } catch (error) {
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
    await clearRolePermissions(target.id)
    ElMessage.success('已清空该角色的全部权限')
    await loadGrants()
    emit('saved')
  } catch (error) {
    saveError.value = describeError(error, '清空失败，请刷新后重试')
  } finally {
    saving.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      subject.value = props.role
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
    :size="'36rem'"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="grant-drawer__header">
      <div class="grant-drawer__heading">
        <h2>{{ title }}</h2>
        <p v-if="subject">
          {{ subject.name }} · {{ subject.code }}
          <template v-if="isProtectedRole(subject.code)">
            · 系统内置角色，不可删除
          </template>
        </p>
        <RouterLink
          v-if="subject"
          class="grant-drawer__full-link"
          :to="{ name: 'admin-role-permissions', query: { roleId: String(subject.id) } }"
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
        当前账号没有角色权限授权权限，只能查看，不能修改。
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
          aria-labelledby="role-permission-current"
        >
          <header class="grant-drawer__section-head">
            <h3 id="role-permission-current">
              当前权限
            </h3>
            <span>{{ grants.length }} 项</span>
          </header>

          <p
            v-if="grants.length === 0"
            class="grant-drawer__empty"
          >
            该角色当前没有权限。允许把角色清空到零权限，持有它的用户仍可登录但没有业务能力。
          </p>
          <ul
            v-else
            class="grant-drawer__list"
          >
            <li
              v-for="grant in grants"
              :key="`${grant.roleId}-${grant.permissionId}`"
              class="grant-drawer__row"
            >
              <span class="grant-drawer__row-main">
                <strong>{{ grant.permissionName }}</strong>
                <span class="grant-drawer__code">{{ grant.permissionCode }}</span>
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
                :title="
                  canRevoke(grant)
                    ? '撤销后持有该角色的用户会话会失效。确定撤销？'
                    : `${PROTECTED_ROLE_CODE} 的 ${PROTECTED_PERMISSION_CODE} 授权不可撤销`
                "
                cancel-button-text="取消"
                confirm-button-text="确定"
                :disabled="!canManage || !canRevoke(grant)"
                width="19rem"
                @confirm="revoke(grant)"
              >
                <template #reference>
                  <el-button
                    :disabled="!canManage || !canRevoke(grant)"
                    :loading="revokingId === grant.permissionId"
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

          <p
            v-if="grants.some((grant) => !canRevoke(grant))"
            class="grant-drawer__notice"
          >
            <strong>{{ PROTECTED_ROLE_CODE }} 的 {{ PROTECTED_PERMISSION_CODE }} 授权受保护。</strong>
            撤销它会让系统失去唯一的角色管理入口，因此该条不提供撤销按钮。
          </p>
        </section>

        <section
          class="grant-drawer__section"
          aria-labelledby="role-permission-available"
        >
          <header class="grant-drawer__section-head">
            <h3 id="role-permission-available">
              添加权限
            </h3>
            <span>可多选</span>
          </header>

          <el-input
            v-model="keyword"
            :prefix-icon="Search"
            aria-label="搜索权限"
            class="grant-drawer__search"
            clearable
            placeholder="请输入权限编码或名称"
          />

          <p
            v-if="availablePermissions.length === 0"
            class="grant-drawer__empty"
          >
            {{ keyword.trim() ? '没有匹配的权限，换一个编码或名称再试。' : '所有权限都已授予，没有可添加的项。' }}
          </p>
          <el-checkbox-group
            v-else
            v-model="selectedPermissionIds"
            class="grant-choice-list"
            aria-label="可添加的权限"
          >
            <el-checkbox
              v-for="permission in availablePermissions"
              :key="permission.id"
              class="grant-choice"
              :label="`${permission.name}（${permission.code}）`"
              :value="permission.id"
              :disabled="!canManage || saving"
            />
          </el-checkbox-group>
        </section>

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
        :title="
          clearsProtectedGrant
            ? `${PROTECTED_ROLE_CODE} 仍持有 ${PROTECTED_PERMISSION_CODE} 时不能清空`
            : '清空后该角色不再有任何权限，持有它的用户会话会失效。确定清空？'
        "
        cancel-button-text="取消"
        confirm-button-text="确定"
        :disabled="!canManage || clearsProtectedGrant"
        width="19rem"
        @confirm="clearAll"
      >
        <template #reference>
          <el-button
            :disabled="!canManage || clearsProtectedGrant || saving"
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
        :disabled="!canManage || selectedPermissionIds.length === 0"
        :loading="saving"
        type="primary"
        @click="save"
      >
        保存
      </el-button>
    </div>
  </el-drawer>
</template>
