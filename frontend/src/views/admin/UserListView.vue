<script setup lang="ts">
import { Check, CircleClose, Edit, Key, Plus, Refresh, Search, Share } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, onMounted, reactive, ref, shallowRef } from 'vue'
import { useRouter } from 'vue-router'

import { describeError } from '@/api/errorMessages'
import { listRoles } from '@/api/rbac'
import type { RoleDetail } from '@/api/rbac'
import {
  createUser,
  disableUser,
  enableUser,
  listUsers,
  resetUserPassword,
  updateUser,
} from '@/api/users'
import type { UserDetail, UserStatus } from '@/api/users'
import AdminListPanel from '@/components/AdminListPanel.vue'
import AppPagination from '@/components/AppPagination.vue'
import AppPage from '@/components/AppPage.vue'
import ProtectedMark from '@/components/ProtectedMark.vue'
import { isProtectedRole } from '@/constants/authorization'
import { useAuthStore } from '@/stores/auth'
import { useAdminList } from '@/composables/useAdminList'

/** 用户管理（`docs/api-design.md` 8.2，`TASK-062`）。 */
const auth = useAuthStore()
const router = useRouter()

/**
 * 列宽。
 *
 * <p>`el-table` 用 `parseInt` 解析 `width` / `min-width`，写成 `7rem` 只会得到 7px，
 * 列会被压扁成一条、行高被逐字换行撑到数百像素（本页首版即如此，已实测修正）。
 * 所以这里给无单位像素数：勾选列 44、标识锚点 176，操作列容纳 3 个图标按钮取 144。</p>
 */
const COLUMN = {
  select: 44,
  ident: 176,
  name: 144,
  roles: 280,
  status: 96,
  actions: 144,
} as const

const keyword = ref('')
const statusFilter = ref<UserStatus | ''>('')
const roleFilter = ref<number | ''>('')

const { items, total, pageNo, pageSize, phase, errorMessage, retrying, search, load } =
  useAdminList<UserDetail>((page) =>
    listUsers({
      ...page,
      ...(keyword.value.trim() ? { keyword: keyword.value.trim() } : {}),
      ...(statusFilter.value ? { status: statusFilter.value } : {}),
      ...(roleFilter.value ? { roleId: roleFilter.value } : {}),
    }),
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

const hasFilters = computed(() =>
  Boolean(keyword.value.trim() || statusFilter.value || roleFilter.value),
)
const emptyTitle = computed(() => (hasFilters.value ? '没有符合条件的用户' : '还没有账号'))
const emptyDescription = computed(() =>
  hasFilters.value
    ? '换一个登录名、账号状态或角色再查一次。'
    : '创建第一个账号后，这里会显示它的状态与角色。',
)

/** 角色下拉的数据源是 `/fd/v1/admin/roles`，它要求 `RBAC_MANAGE`；只有 `USER_MANAGE` 的账号不提问它。 */
const canPickRole = computed(() => auth.hasPermission('RBAC_MANAGE'))
const roleOptions = shallowRef<RoleDetail[]>([])

async function loadRoleOptions(): Promise<void> {
  if (!canPickRole.value) {
    return
  }
  try {
    const result = await listRoles({ pageNo: 1, pageSize: 100 })
    roleOptions.value = result.items
  } catch {
    // 角色下拉是筛选便利，不是数据源：取不到时保持空列表，列表本身仍可用
    roleOptions.value = []
  }
}

function resetFilters(): void {
  keyword.value = ''
  statusFilter.value = ''
  roleFilter.value = ''
  void search()
}

// ---------- 创建 ----------

interface CreateForm {
  username: string
  displayName: string
  initialPassword: string
  roleIds: number[]
}

const createVisible = ref(false)
const creating = ref(false)
const createError = ref('')
const createFormRef = ref<FormInstance>()
const createForm = reactive<CreateForm>({
  username: '',
  displayName: '',
  initialPassword: '',
  roleIds: [],
})
const createRules: FormRules<CreateForm> = {
  username: [
    { required: true, message: '请输入登录名', trigger: 'blur' },
    { max: 64, message: '登录名最大长度为 64', trigger: 'blur' },
  ],
  displayName: [
    { required: true, message: '请输入显示名称', trigger: 'blur' },
    { max: 100, message: '显示名称最大长度为 100', trigger: 'blur' },
  ],
  initialPassword: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 8, max: 64, message: '初始密码长度为 8 到 64 个字符', trigger: 'blur' },
  ],
}

function openCreate(): void {
  createError.value = ''
  createForm.username = ''
  createForm.displayName = ''
  createForm.initialPassword = ''
  createForm.roleIds = []
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  const form = createFormRef.value
  if (!form) {
    return
  }
  const valid = await form.validate().catch(() => false)
  if (!valid) {
    return
  }

  creating.value = true
  createError.value = ''
  try {
    await createUser({
      username: createForm.username.trim(),
      displayName: createForm.displayName.trim(),
      initialPassword: createForm.initialPassword,
      roleIds: [...createForm.roleIds],
    })
    createVisible.value = false
    ElMessage.success('账号已创建，请通过安全渠道把初始密码告知本人')
    await search()
  } catch (error) {
    createError.value = describeError(error, '创建失败，请稍后重试')
  } finally {
    creating.value = false
  }
}

// ---------- 修改资料 ----------

interface EditForm {
  displayName: string
}

const editing = ref<UserDetail | null>(null)
const saving = ref(false)
const editError = ref('')
const editFormRef = ref<FormInstance>()
const editForm = reactive<EditForm>({ displayName: '' })
const editRules: FormRules<EditForm> = {
  displayName: [
    { required: true, message: '请输入显示名称', trigger: 'blur' },
    { max: 100, message: '显示名称最大长度为 100', trigger: 'blur' },
  ],
}

function openEdit(row: UserDetail): void {
  editError.value = ''
  editForm.displayName = row.displayName
  editing.value = row
}

async function submitEdit(): Promise<void> {
  const form = editFormRef.value
  const target = editing.value
  if (!form || !target) {
    return
  }
  const valid = await form.validate().catch(() => false)
  if (!valid) {
    return
  }

  saving.value = true
  editError.value = ''
  try {
    await updateUser(target.id, {
      displayName: editForm.displayName.trim(),
      // 版本随行数据一起提交：被别人改过时后端返回 USER_CONFLICT 而不是静默覆盖
      version: target.version,
    })
    editing.value = null
    ElMessage.success('资料已更新')
    await search()
  } catch (error) {
    editError.value = describeError(error, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

// ---------- 启用 / 停用 ----------

async function toggleStatus(row: UserDetail): Promise<void> {
  statusPendingId.value = row.id
  try {
    if (row.status === 'ENABLED') {
      await disableUser(row.id, row.version)
      ElMessage.success('账号已停用，该用户现有登录会话已失效')
    } else {
      await enableUser(row.id, row.version)
      ElMessage.success('账号已启用')
    }
    await search()
  } catch (error) {
    ElMessage.error(describeError(error, '状态没有改变，请刷新后重试'))
  } finally {
    statusPendingId.value = null
  }
}

// ---------- 重置密码 ----------

interface PasswordForm {
  newPassword: string
  confirmPassword: string
}

const resetting = ref<UserDetail | null>(null)
const resettingPassword = ref(false)
const resetError = ref('')
const passwordFormRef = ref<FormInstance>()
const passwordForm = reactive<PasswordForm>({ newPassword: '', confirmPassword: '' })

const passwordRules: FormRules<PasswordForm> = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '新密码长度为 8 到 64 个字符', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

function openReset(row: UserDetail): void {
  resetError.value = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  resetting.value = row
}

async function submitReset(): Promise<void> {
  const form = passwordFormRef.value
  const target = resetting.value
  if (!form || !target) {
    return
  }
  const valid = await form.validate().catch(() => false)
  if (!valid) {
    return
  }

  resettingPassword.value = true
  resetError.value = ''
  try {
    await resetUserPassword(target.id, {
      newPassword: passwordForm.newPassword,
      version: target.version,
    })
    resetting.value = null
    ElMessage.success('密码已重置，该用户全部登录会话已失效')
    await search()
  } catch (error) {
    resetError.value = describeError(error, '重置失败，请稍后重试')
  } finally {
    resettingPassword.value = false
  }
}

// ---------- 行内动作与工具栏选择 ----------

/** 表格选中行：工具栏的编辑 / 停用 / 重置密码只作用于"恰好选中一行"时的那一行。 */
const selectedRows = ref<UserDetail[]>([])
const selectedOne = computed(() => (selectedRows.value.length === 1 ? selectedRows.value[0] : null))

function onSelectionChange(rows: UserDetail[]): void {
  selectedRows.value = rows
}

/** 状态开关的进行中标记：只让被点的那一行转圈，避免整表 loading。 */
const statusPendingId = ref<number | null>(null)

const statusActionLabel = computed(() => {
  if (!selectedOne.value) {
    return '停用/启用'
  }
  return selectedOne.value.status === 'ENABLED' ? '停用' : '启用'
})

function openGrants(row: UserDetail): void {
  void router.push({ name: 'admin-user-roles', query: { userId: String(row.id) } })
}

async function toggleSelectedStatus(): Promise<void> {
  if (selectedOne.value) {
    await toggleStatus(selectedOne.value)
  }
}

/** 启用的管理员行：停用或移除管理员角色会撞上"至少保留一个启用管理员"的保护规则。 */
function holdsProtectedRole(row: UserDetail): boolean {
  return row.status === 'ENABLED' && row.roles.some((role) => isProtectedRole(role.code))
}

onMounted(() => {
  void loadRoleOptions()
  void search()
})
</script>

<template>
  <AppPage
    class="admin-page user-list-page"
    layout="list"
    title="用户管理"
  >
    <section
      class="admin-filter-card"
      aria-label="用户查询条件"
    >
      <div class="admin-filter-fields">
        <span class="admin-filter-field">
          登录名
          <el-input
            id="user-keyword"
            v-model="keyword"
            aria-label="登录名"
            class="admin-filter-input admin-filter-input--wide"
            clearable
            placeholder="请输入登录名或显示名"
            @keyup.enter="search"
          />
        </span>

        <span class="admin-filter-field">
          账号状态
          <el-select
            id="user-status"
            v-model="statusFilter"
            aria-label="账号状态"
            class="admin-filter-input admin-filter-input--narrow"
            clearable
            placeholder="请选择账号状态"
          >
            <el-option
              label="启用"
              value="ENABLED"
            />
            <el-option
              label="停用"
              value="DISABLED"
            />
          </el-select>
        </span>

        <span
          v-if="canPickRole"
          class="admin-filter-field"
        >
          角色
          <el-select
            id="user-role"
            v-model="roleFilter"
            aria-label="角色"
            class="admin-filter-input admin-filter-input--narrow"
            clearable
            filterable
            placeholder="请选择角色"
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
            :icon="Search"
            type="primary"
            @click="search"
          >
            查询
          </el-button>
          <el-button
            :icon="Refresh"
            type="info"
            plain
            @click="resetFilters"
          >
            重置
          </el-button>
        </div>
      </div>
    </section>

    <section
      class="user-list-data"
      aria-label="用户数据"
    >
      <div class="admin-action-bar">
        <el-button
          :icon="Plus"
          type="primary"
          @click="openCreate"
        >
          新增
        </el-button>
        <el-button
          :disabled="!selectedOne"
          :icon="Edit"
          type="success"
          plain
          @click="selectedOne && openEdit(selectedOne)"
        >
          编辑
        </el-button>
        <el-popconfirm
          :disabled="!selectedOne"
          :title="
            selectedOne?.status === 'ENABLED'
              ? '停用后该用户全部登录会话立即失效。确定停用？'
              : '确定启用该账号？'
          "
          cancel-button-text="取消"
          confirm-button-text="确定"
          width="17rem"
          @confirm="toggleSelectedStatus"
        >
          <template #reference>
            <el-button
              :disabled="!selectedOne"
              :icon="selectedOne?.status === 'ENABLED' ? CircleClose : Check"
              :type="selectedOne?.status === 'DISABLED' ? 'success' : 'danger'"
              plain
            >
              {{ statusActionLabel }}
            </el-button>
          </template>
        </el-popconfirm>
        <el-button
          :disabled="!selectedOne"
          :icon="Key"
          type="warning"
          plain
          @click="selectedOne && openReset(selectedOne)"
        >
          重置密码
        </el-button>

        <div class="admin-action-bar__end">
          <el-tooltip
            content="刷新列表"
            placement="top"
          >
            <el-button
              :icon="Refresh"
              :loading="retrying"
              type="info"
              plain
              aria-label="刷新列表"
              @click="search"
            />
          </el-tooltip>
        </div>
      </div>

      <AdminListPanel
        :empty-description="emptyDescription"
        :empty-title="emptyTitle"
        :error-message="errorMessage"
        :is-empty="items.length === 0"
        label="用户列表"
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
        <el-table
          :data="items"
          row-key="id"
          :row-style="{ height: 'var(--fd-admin-row-height)' }"
          border
          class="admin-table"
          @selection-change="onSelectionChange"
        >
          <el-table-column
            :width="COLUMN.select"
            type="selection"
          />
          <el-table-column
            :fixed="false"
            label="登录名"
            :min-width="COLUMN.ident"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span class="admin-ident">{{ row.username }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="显示名称"
            :min-width="COLUMN.name"
            prop="displayName"
            show-overflow-tooltip
          />
          <el-table-column
            label="角色"
            :min-width="COLUMN.roles"
          >
            <template #default="{ row }">
              <span
                v-if="row.roles.length === 0"
                class="admin-muted"
              >无业务权限</span>
              <span
                v-else
                class="admin-role-list"
              >
                <span
                  v-for="role in row.roles"
                  :key="role.id"
                  class="admin-role"
                >{{ role.name }}</span>
              </span>
              <ProtectedMark
                v-if="holdsProtectedRole(row)"
                class="admin-role-list__mark"
                label="管理员保护"
                reason="停用该账号或移除其管理员角色时，系统要求至少保留一个启用管理员，否则会被拒绝"
              />
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="状态"
            :width="COLUMN.status"
          >
            <template #default="{ row }">
              <el-popconfirm
                :title="
                  row.status === 'ENABLED'
                    ? '停用后该用户全部登录会话立即失效。确定停用？'
                    : '确定启用该账号？'
                "
                cancel-button-text="取消"
                confirm-button-text="确定"
                width="17rem"
                @confirm="toggleStatus(row)"
              >
                <template #reference>
                  <el-switch
                    :aria-label="`${row.displayName}的账号状态，当前${row.status === 'ENABLED' ? '启用' : '停用'}`"
                    :loading="statusPendingId === row.id"
                    :model-value="row.status === 'ENABLED'"
                  />
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            :fixed="false"
            label="操作"
            :width="COLUMN.actions"
          >
            <template #default="{ row }">
              <div class="admin-row-actions">
                <el-tooltip
                  content="编辑资料"
                  placement="top"
                >
                  <el-button
                    :icon="Edit"
                    aria-label="编辑资料"
                    size="small"
                    type="success"
                    plain
                    circle
                    @click="openEdit(row)"
                  />
                </el-tooltip>
                <el-tooltip
                  content="重置密码"
                  placement="top"
                >
                  <el-button
                    :icon="Key"
                    aria-label="重置密码"
                    size="small"
                    type="warning"
                    plain
                    circle
                    @click="openReset(row)"
                  />
                </el-tooltip>
                <el-tooltip
                  content="角色授权"
                  placement="top"
                >
                  <el-button
                    v-if="canPickRole"
                    :icon="Share"
                    aria-label="角色授权"
                    size="small"
                    type="primary"
                    plain
                    circle
                    @click="openGrants(row)"
                  />
                </el-tooltip>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </AdminListPanel>

      <AppPagination
        :has-subject="true"
        :page="pageNo"
        :page-size="pageSize"
        :phase="phase"
        :total-elements="total"
        @change="onPaginationChange"
      />
    </section>

    <el-dialog
      v-model="createVisible"
      class="admin-dialog"
      title="新建用户"
      width="30rem"
      :close-on-click-modal="false"
    >
      <el-form
        ref="createFormRef"
        label-position="top"
        :model="createForm"
        :rules="createRules"
      >
        <el-form-item
          label="登录名"
          prop="username"
        >
          <el-input
            v-model="createForm.username"
            maxlength="64"
            placeholder="请输入登录名"
          />
        </el-form-item>
        <el-form-item
          label="显示名称"
          prop="displayName"
          show-overflow-tooltip
        >
          <el-input
            v-model="createForm.displayName"
            maxlength="100"
            placeholder="请输入显示名称"
          />
        </el-form-item>
        <el-form-item
          label="初始密码"
          prop="initialPassword"
        >
          <el-input
            v-model="createForm.initialPassword"
            autocomplete="new-password"
            placeholder="请输入初始密码"
            show-password
            type="password"
          />
        </el-form-item>
        <el-form-item
          v-if="canPickRole"
          label="角色"
        >
          <el-select
            v-model="createForm.roleIds"
            class="admin-form-control"
            filterable
            multiple
            placeholder="请选择角色"
          >
            <el-option
              v-for="role in roleOptions"
              :key="role.id"
              :label="`${role.name}（${role.code}）`"
              :value="role.id"
            />
          </el-select>
          <p class="admin-hint">
            可以留空：零角色账号仍能登录，但没有任何业务权限。
          </p>
        </el-form-item>
        <p
          v-else
          class="admin-hint"
        >
          角色需要由有授权权限的管理员分配。你可以先创建账号。
        </p>

        <p
          v-if="createError"
          class="admin-error"
          role="alert"
        >
          {{ createError }}
        </p>
      </el-form>

      <template #footer>
        <el-button
          :icon="CircleClose"
          type="info"
          plain
          @click="createVisible = false"
        >
          取消
        </el-button>
        <el-button
          :loading="creating"
          :icon="Plus"
          type="primary"
          @click="submitCreate"
        >
          创建
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      class="admin-dialog"
      :model-value="editing !== null"
      title="修改资料"
      width="26rem"
      :close-on-click-modal="false"
      @update:model-value="editing = null"
    >
      <el-form
        ref="editFormRef"
        label-position="top"
        :model="editForm"
        :rules="editRules"
      >
        <el-form-item
          label="显示名称"
          prop="displayName"
          show-overflow-tooltip
        >
          <el-input
            v-model="editForm.displayName"
            maxlength="100"
            placeholder="请输入显示名称"
          />
        </el-form-item>
        <p class="admin-hint">
          登录名创建后不可修改。
        </p>
        <p
          v-if="editError"
          class="admin-error"
          role="alert"
        >
          {{ editError }}
        </p>
      </el-form>

      <template #footer>
        <el-button
          :icon="CircleClose"
          type="info"
          plain
          @click="editing = null"
        >
          取消
        </el-button>
        <el-button
          :loading="saving"
          :icon="Check"
          type="primary"
          @click="submitEdit"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      class="admin-dialog"
      :model-value="resetting !== null"
      title="重置密码"
      width="26rem"
      :close-on-click-modal="false"
      @update:model-value="resetting = null"
    >
      <el-form
        ref="passwordFormRef"
        label-position="top"
        :model="passwordForm"
        :rules="passwordRules"
      >
        <el-form-item
          label="新密码"
          prop="newPassword"
        >
          <el-input
            v-model="passwordForm.newPassword"
            autocomplete="new-password"
            placeholder="请输入新密码"
            show-password
            type="password"
          />
        </el-form-item>
        <el-form-item
          label="确认新密码"
          prop="confirmPassword"
        >
          <el-input
            v-model="passwordForm.confirmPassword"
            autocomplete="new-password"
            placeholder="请再次输入新密码"
            show-password
            type="password"
          />
        </el-form-item>
        <p class="admin-hint">
          重置成功后该用户全部登录会话立即失效，需要重新登录。
        </p>
        <p
          v-if="resetError"
          class="admin-error"
          role="alert"
        >
          {{ resetError }}
        </p>
      </el-form>

      <template #footer>
        <el-button
          :icon="CircleClose"
          type="info"
          plain
          @click="resetting = null"
        >
          取消
        </el-button>
        <el-button
          :loading="resettingPassword"
          :icon="Key"
          type="warning"
          @click="submitReset"
        >
          重置
        </el-button>
      </template>
    </el-dialog>
  </AppPage>
</template>
