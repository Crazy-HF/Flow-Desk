<script setup lang="ts">
import { Delete, Plus, Refresh, Setting } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { describeError } from '@/api/errorMessages'
import { clearUserRoles, grantUserRoles, listRoles, listUserRoleGrants, revokeUserRole } from '@/api/rbac'
import type { RoleDetail, UserRoleGrant } from '@/api/rbac'
import { getUser, listUsers } from '@/api/users'
import type { UserDetail } from '@/api/users'
import AdminListPanel from '@/components/AdminListPanel.vue'
import AppPagination from '@/components/AppPagination.vue'
import AppPage from '@/components/AppPage.vue'
import EmptyState from '@/components/EmptyState.vue'
import { formatDateTime } from '@/utils/format'
import { useAdminList } from '@/composables/useAdminList'

/**
 * 用户角色授权（`docs/api-design.md` 8.2.1，`TASK-060`）。
 *
 * <p>列表用"关系行"而不是普通数据表：一条授权就是一条责任记录——谁 → 什么 → 何时由谁授予，
 * 与工单时间线同源。授予走批量增量端点（一个用户 × 多个角色），界面不循环单条 `POST`。</p>
 */
const route = useRoute()
const router = useRouter()

const subject = ref<UserDetail | null>(null)
const subjectOptions = shallowRef<UserDetail[]>([])
const subjectLoading = ref(false)

const roleOptions = shallowRef<RoleDetail[]>([])
const selectedRoleIds = ref<number[]>([])
const granting = ref(false)
const grantError = ref('')

const subjectId = computed(() => subject.value?.id ?? 0)

const { items, total, pageNo, pageSize, phase, errorMessage, retrying, search, load } =
  useAdminList<UserRoleGrant>((page) =>
    listUserRoleGrants({ ...page, userId: subjectId.value }),
  )

/**
 * 分页变化：AppPagination 只报告"用户想要哪一页、每页多少条"，取数仍由 useAdminList.load 负责。
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

/** 远程用户检索：数据源是 `GET /fd/v1/users`（`USER_MANAGE`）。 */
async function searchSubjects(query: string): Promise<void> {
  subjectLoading.value = true
  try {
    const result = await listUsers({
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

async function loadRoleOptions(): Promise<void> {
  try {
    const result = await listRoles({ pageNo: 1, pageSize: 100 })
    roleOptions.value = result.items
  } catch {
    roleOptions.value = []
  }
}

async function selectSubject(userId: number): Promise<void> {
  try {
    const user = await getUser(userId)
    subject.value = user
    subjectOptions.value = [user]
    selectedRoleIds.value = []
    grantError.value = ''
    await search()
  } catch (error) {
    ElMessage.error(describeError(error, '用户信息没有取到，请重新检索'))
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
  if (!subject.value || selectedRoleIds.value.length === 0) {
    grantError.value = '请先选择用户与至少一个角色'
    return
  }

  granting.value = true
  grantError.value = ''
  try {
    const granted = await grantUserRoles(subject.value.id, [...selectedRoleIds.value])
    ElMessage.success(`已提交 ${selectedRoleIds.value.length} 个角色，实际新增 ${granted.length} 条授权`)
    selectedRoleIds.value = []
    await search()
  } catch (error) {
    grantError.value = describeError(error, '授权失败，整批未生效')
  } finally {
    granting.value = false
  }
}

async function revoke(grant: UserRoleGrant): Promise<void> {
  try {
    await revokeUserRole(grant.userId, grant.roleId)
    ElMessage.success(`已撤销「${grant.roleName}」`)
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
    await clearUserRoles(subject.value.id)
    ElMessage.success('已清空该用户的全部角色，账号仍可登录但没有业务权限')
    await search()
  } catch (error) {
    ElMessage.error(describeError(error, '清空失败，请刷新后重试'))
  }
}

function openPermissions(): void {
  void router.push({ name: 'admin-role-permissions' })
}

onMounted(async () => {
  await loadRoleOptions()
  const preset = Number(route.query.userId ?? '')
  if (Number.isInteger(preset) && preset > 0) {
    await selectSubject(preset)
  }
})
</script>

<template>
  <AppPage
    class="admin-page"
    description="一个用户可以同时持有多个角色：已存在的关系保留原授权时间，任一角色不存在则整批不生效。"
    layout="list"
    title="用户角色授权"
  >
    <section
      class="admin-filter-card grant-form"
      aria-label="授予用户角色"
    >
      <div class="admin-filter-fields">
        <span class="admin-filter-field">
          授权对象
          <el-select
            id="grant-subject"
            aria-label="授权对象"
            class="admin-filter-input admin-filter-input--wide"
            clearable
            filterable
            :loading="subjectLoading"
            :model-value="subject?.id ?? null"
            placeholder="请输入登录名或显示名检索"
            remote
            reserve-keyword
            :remote-method="searchSubjects"
            size="small"
            @change="onSubjectChange"
          >
            <el-option
              v-for="user in subjectOptions"
              :key="user.id"
              :label="`${user.displayName}（${user.username}）`"
              :value="user.id"
            />
          </el-select>
        </span>

        <span class="admin-filter-field">
          要授予的角色
          <el-select
            id="grant-roles"
            v-model="selectedRoleIds"
            aria-label="要授予的角色"
            class="admin-filter-input"
            collapse-tags
            filterable
            multiple
            placeholder="请选择角色"
            size="small"
          >
            <el-option
              v-for="role in roleOptions"
              :key="role.id"
              :label="`${role.name}（${role.code}）`"
              :value="role.id"
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
      aria-label="用户角色授权数据"
    >
      <div
        v-if="subject"
        class="admin-action-bar"
      >
        <el-popconfirm
          title="清空后该用户不再有任何角色，账号仍可登录但没有业务权限。确定清空？"
          cancel-button-text="取消"
          confirm-button-text="确定"
          width="19rem"
          @confirm="clearAll"
        >
          <template #reference>
            <el-button
              :icon="Delete"
              type="danger"
              plain
            >
              清空全部角色
            </el-button>
          </template>
        </el-popconfirm>
        <el-button
          :icon="Setting"
          type="info"
          plain
          @click="openPermissions"
        >
          去维护角色权限
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

      <EmptyState
        v-if="!subject"
        description="选定用户后，这里会显示他当前持有的角色与每条授权的来源。"
        title="先选择要授权的用户"
      />

      <AdminListPanel
        v-else
        empty-description="零角色是合法状态：账号仍可登录，但没有任何业务权限。"
        empty-title="该用户当前没有角色"
        :error-message="errorMessage"
        :is-empty="items.length === 0"
        label="用户角色授权列表"
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
            <span>用户</span>
            <span aria-hidden="true" />
            <span>角色</span>
            <span>授权记录</span>
            <span>操作</span>
          </div>
          <ul class="grant-list-body">
            <li
              v-for="grant in items"
              :key="`${grant.userId}-${grant.roleId}`"
              class="grant-row"
            >
              <span class="grant-row__subject">
                <span class="admin-ident">{{ grant.username }}</span>
                <span class="grant-row__meta">用户 #{{ grant.userId }}</span>
              </span>
              <span
                class="grant-row__axis"
                aria-hidden="true"
              />
              <span class="grant-row__object">
                <span class="grant-row__name">{{ grant.roleName }}</span>
                <span class="admin-ident grant-row__code">{{ grant.roleCode }}</span>
              </span>
              <span class="grant-row__audit">
                <span class="admin-time">{{ formatDateTime(grant.grantedAt) }}</span>
                <span class="grant-row__meta">
                  {{ grant.grantedBy ? `授权人 #${grant.grantedBy}` : '迁移预置' }}
                </span>
              </span>
              <span class="grant-row__action">
                <el-popconfirm
                  :title="`撤销「${grant.roleName}」会立即失效该用户的登录会话。确定撤销？`"
                  cancel-button-text="取消"
                  confirm-button-text="确定"
                  width="19rem"
                  @confirm="revoke(grant)"
                >
                  <template #reference>
                    <el-button
                      :icon="Delete"
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

      <AppPagination
        :has-subject="Boolean(subject)"
        :page="pageNo"
        :page-size="pageSize"
        :phase="phase"
        :total-elements="total"
        @change="onPaginationChange"
      />
    </section>

    <p
      v-if="subject"
      class="admin-hint"
    >
      撤销单条授权用行内入口；批量与清空一次提交整批，不会出现只生效一半的情况。
    </p>
  </AppPage>
</template>
