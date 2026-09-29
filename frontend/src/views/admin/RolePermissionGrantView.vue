<script setup lang="ts">
import { Delete, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute } from 'vue-router'

import { describeError } from '@/api/errorMessages'
import {
  clearRolePermissions,
  getRole,
  grantRolePermissions,
  listPermissions,
  listRolePermissionGrants,
  listRoles,
  revokeRolePermission,
} from '@/api/rbac'
import type { PermissionDetail, RoleDetail, RolePermissionGrant } from '@/api/rbac'
import AdminListPanel from '@/components/AdminListPanel.vue'
import AppPage from '@/components/AppPage.vue'
import EmptyState from '@/components/EmptyState.vue'
import {
  isProtectedPermission,
  isProtectedRole,
  PROTECTED_PERMISSION_CODE,
  PROTECTED_ROLE_CODE,
} from '@/constants/authorization'
import { formatDateTime } from '@/utils/format'
import { useAdminList } from './useAdminList'
import { useCompactPagination } from './useCompactPagination'

/**
 * 角色权限授权（`docs/api-design.md` 8.2.1，`TASK-060`）。
 *
 * <p>与用户角色授权同构：列表是"角色 → 权限"的关系行，审计列显示这条授权由谁在何时建立。
 * 权限选择器按 `keyword` 检索编码或名称，权限码搜索因此是远程检索而不是本地过滤。</p>
 */
const route = useRoute()
const compactPagination = useCompactPagination()

const subject = ref<RoleDetail | null>(null)
const subjectOptions = shallowRef<RoleDetail[]>([])
const subjectLoading = ref(false)

const permissionOptions = shallowRef<PermissionDetail[]>([])
const permissionLoading = ref(false)
const selectedPermissionIds = ref<number[]>([])
const granting = ref(false)
const grantError = ref('')

const subjectId = computed(() => subject.value?.id ?? 0)

const { items, total, pageNo, pageSize, phase, errorMessage, retrying, search, changePage, changePageSize } =
  useAdminList<RolePermissionGrant>((page) =>
    listRolePermissionGrants({ ...page, roleId: subjectId.value }),
  )

async function searchSubjects(query: string): Promise<void> {
  subjectLoading.value = true
  try {
    const result = await listRoles({
      pageNo: 1,
      pageSize: 20,
      ...(query.trim() ? { keyword: query.trim() } : {}),
    })
    subjectOptions.value = result.items
  } catch {
    subjectOptions.value = []
  } finally {
    subjectLoading.value = false
  }
}

async function searchPermissions(query: string): Promise<void> {
  permissionLoading.value = true
  try {
    const result = await listPermissions({
      pageNo: 1,
      pageSize: 50,
      ...(query.trim() ? { keyword: query.trim() } : {}),
    })
    permissionOptions.value = result.items
  } catch {
    permissionOptions.value = []
  } finally {
    permissionLoading.value = false
  }
}

async function selectSubject(roleId: number): Promise<void> {
  try {
    const role = await getRole(roleId)
    subject.value = role
    subjectOptions.value = [role]
    selectedPermissionIds.value = []
    grantError.value = ''
    await search()
  } catch (error) {
    ElMessage.error(describeError(error, '角色信息没有取到，请重新检索'))
  }
}

function onSubjectChange(value: number | null): void {
  if (value === null) {
    subject.value = null
    return
  }
  void selectSubject(value)
}

async function submitGrant(): Promise<void> {
  if (!subject.value || selectedPermissionIds.value.length === 0) {
    grantError.value = '请先选择角色与至少一个权限'
    return
  }

  granting.value = true
  grantError.value = ''
  try {
    const granted = await grantRolePermissions(subject.value.id, [...selectedPermissionIds.value])
    ElMessage.success(
      `已提交 ${selectedPermissionIds.value.length} 个权限，实际新增 ${granted.length} 条授权`,
    )
    selectedPermissionIds.value = []
    await search()
  } catch (error) {
    grantError.value = describeError(error, '授权失败，整批未生效')
  } finally {
    granting.value = false
  }
}

async function revoke(grant: RolePermissionGrant): Promise<void> {
  try {
    await revokeRolePermission(grant.roleId, grant.permissionId)
    ElMessage.success(`已撤销「${grant.permissionName}」`)
    await search()
  } catch (error) {
    ElMessage.error(describeError(error, '撤销失败，请刷新后重试'))
  }
}

async function clearAll(): Promise<void> {
  if (!subject.value) {
    return
  }
  try {
    await clearRolePermissions(subject.value.id)
    ElMessage.success('已清空该角色的全部权限')
    await search()
  } catch (error) {
    ElMessage.error(describeError(error, '清空失败，请刷新后重试'))
  }
}

/** `SYSTEM_ADMIN` 的 `RBAC_MANAGE` 授权不可撤销，行内禁用只是提示，后端仍会独立拒绝。 */
function clearsProtectedGrant(): boolean {
  return Boolean(subject.value && isProtectedRole(subject.value.code))
}

/** 单条关系是否可撤销：只有"SYSTEM_ADMIN × RBAC_MANAGE"这一格被保护规则挡住。 */
function canRevoke(grant: RolePermissionGrant): boolean {
  return !(isProtectedPermission(grant.permissionCode) && isProtectedRole(grant.roleCode))
}

onMounted(async () => {
  await searchPermissions('')
  const preset = Number(route.query.roleId ?? '')
  if (Number.isInteger(preset) && preset > 0) {
    await selectSubject(preset)
  }
})
</script>

<template>
  <AppPage
    class="admin-page"
    description="一个角色可以同时持有多个权限：权限变更后，持有该角色的全部用户需要重新登录。"
    layout="list"
    title="角色权限授权"
  >
    <section
      class="admin-filter-card grant-form"
      aria-label="授予角色权限"
    >
      <div class="admin-filter-fields">
        <span class="admin-filter-field">
          授权对象
          <el-select
            id="grant-role"
            aria-label="授权对象"
            class="admin-filter-input admin-filter-input--wide"
            clearable
            filterable
            :loading="subjectLoading"
            :model-value="subject?.id ?? null"
            placeholder="请输入角色编码或名称检索"
            remote
            reserve-keyword
            :remote-method="searchSubjects"
            size="small"
            @change="onSubjectChange"
          >
            <el-option
              v-for="role in subjectOptions"
              :key="role.id"
              :label="`${role.name}（${role.code}）`"
              :value="role.id"
            />
          </el-select>
        </span>

        <span class="admin-filter-field">
          要授予的权限
          <el-select
            id="grant-permissions"
            v-model="selectedPermissionIds"
            aria-label="要授予的权限"
            class="admin-filter-input"
            collapse-tags
            filterable
            multiple
            :loading="permissionLoading"
            placeholder="请输入权限编码或名称检索"
            remote
            reserve-keyword
            :remote-method="searchPermissions"
            size="small"
          >
            <el-option
              v-for="permission in permissionOptions"
              :key="permission.id"
              :label="`${permission.name}（${permission.code}）`"
              :value="permission.id"
            />
          </el-select>
        </span>

        <div class="admin-filter-actions">
          <el-button
            :icon="Plus"
            :loading="granting"
            size="small"
            type="primary"
            @click="submitGrant"
          >
            批量授予
          </el-button>
        </div>
      </div>

      <p
        v-if="grantError"
        class="admin-error"
        role="alert"
      >
        {{ grantError }}
      </p>
    </section>

    <section
      class="admin-data"
      aria-label="角色权限授权数据"
    >
      <div
        v-if="subject"
        class="admin-action-bar"
      >
        <el-popconfirm
          title="清空后该角色不再有任何权限，持有它的用户会话会失效。确定清空？"
          cancel-button-text="取消"
          confirm-button-text="确定"
          :disabled="clearsProtectedGrant()"
          width="19rem"
          @confirm="clearAll"
        >
          <template #reference>
            <el-button
              :disabled="clearsProtectedGrant()"
              :icon="Delete"
              :title="
                clearsProtectedGrant()
                  ? `${PROTECTED_ROLE_CODE} 仍持有 ${PROTECTED_PERMISSION_CODE} 时不能清空`
                  : undefined
              "
              type="danger"
              plain
            >
              清空全部权限
            </el-button>
          </template>
        </el-popconfirm>

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

      <EmptyState
        v-if="!subject"
        description="选定角色后，这里会显示它当前持有的权限与每条授权的来源。"
        title="先选择要授权的角色"
      />

      <AdminListPanel
        v-else
        empty-description="允许把角色清空到零权限；SYSTEM_ADMIN 的 RBAC_MANAGE 授权除外。"
        empty-title="该角色当前没有权限"
        :error-message="errorMessage"
        :is-empty="items.length === 0"
        label="角色权限授权列表"
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

        <div class="grant-list">
          <div class="grant-list-head">
            <span>角色</span>
            <span aria-hidden="true" />
            <span>权限</span>
            <span>授权记录</span>
            <span>操作</span>
          </div>
          <ul class="grant-list-body">
            <li
              v-for="grant in items"
              :key="`${grant.roleId}-${grant.permissionId}`"
              class="grant-row"
            >
              <span class="grant-row__subject">
                <span class="grant-row__name">{{ grant.roleCode }}</span>
                <span class="grant-row__meta">角色 #{{ grant.roleId }}</span>
              </span>
              <span
                class="grant-row__axis"
                aria-hidden="true"
              />
              <span class="grant-row__object">
                <span class="grant-row__name">{{ grant.permissionName }}</span>
                <span class="admin-ident grant-row__code">{{ grant.permissionCode }}</span>
              </span>
              <span class="grant-row__audit">
                <span class="admin-time">{{ formatDateTime(grant.grantedAt) }}</span>
                <span class="grant-row__meta">
                  {{ grant.grantedBy ? `授权人 #${grant.grantedBy}` : '迁移预置' }}
                </span>
              </span>
              <span class="grant-row__action">
                <el-popconfirm
                  title="撤销后持有该角色的用户会话会失效。确定撤销？"
                  cancel-button-text="取消"
                  confirm-button-text="确定"
                  :disabled="!canRevoke(grant)"
                  width="19rem"
                  @confirm="revoke(grant)"
                >
                  <template #reference>
                    <el-button
                      :disabled="!canRevoke(grant)"
                      :icon="Delete"
                      :title="
                        canRevoke(grant)
                          ? '撤销授权'
                          : `${PROTECTED_ROLE_CODE} 的 ${PROTECTED_PERMISSION_CODE} 授权不可撤销`
                      "
                      aria-label="撤销"
                      size="small"
                      type="danger"
                      plain
                      circle
                    />
                  </template>
                </el-popconfirm>
              </span>
            </li>
          </ul>
        </div>
      </AdminListPanel>

      <div
        v-if="subject && phase === 'ready' && items.length > 0"
        class="admin-pagination"
      >
        <el-pagination
          background
          :current-page="pageNo"
          :layout="compactPagination ? 'total, prev, pager, next' : 'total, sizes, prev, pager, next, jumper'"
          :pager-count="5"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          @current-change="changePage"
          @size-change="changePageSize"
        />
      </div>
    </section>
  </AppPage>
</template>
