<script setup lang="ts">
import { ArrowDown, Check, CircleClose, Edit, Key, Plus, Refresh, Search, Share } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { describeError } from '@/api/errorMessages'
import { listRoles } from '@/api/rbac'
import type { RoleDetail } from '@/api/rbac'
import {
  createUser,
  disableUser,
  enableUser,
  getActiveTicketAssignments,
  getHandoffCandidates,
  getUser,
  listUsers,
  replaceUserRoles,
  resetUserPassword,
  updateUser,
} from '@/api/users'
import type { ActiveTicketAssignment, HandoffCandidate, UserDetail, UserStatus } from '@/api/users'
import AdminListPanel from '@/components/AdminListPanel.vue'
import AppPagination from '@/components/AppPagination.vue'
import AppPage from '@/components/AppPage.vue'
import ProtectedMark from '@/components/ProtectedMark.vue'
import UserRoleDrawer from '@/components/UserRoleDrawer.vue'
import { isProtectedRole } from '@/constants/authorization'
import { ticketStatusLabel } from '@/constants/tickets'
import { useAuthStore } from '@/stores/auth'
import { useAdminList } from '@/composables/useAdminList'

/** 用户管理（`docs/api-design.md` 8.2，`TASK-062`）。 */
const auth = useAuthStore()
const route = useRoute()

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
/** 筛选面板展开状态：默认展开，收起只影响视觉，不影响已生效的条件。 */
const filtersOpen = ref(true)
/** 角色分配抽屉：从行入口打开，保存后刷新列表但保留筛选与页码。 */
const drawerOpen = ref(false)
const drawerUser = ref<UserDetail | null>(null)
const roleTrigger = ref<HTMLElement | null>(null)

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
const canHandoff = computed(() => auth.hasPermission('TICKET_ADMIN_HANDOFF'))
const roleOptions = shallowRef<RoleDetail[]>([])
const roleOptionsLoaded = ref(false)

async function loadRoleOptions(): Promise<void> {
  if (!canPickRole.value) {
    return
  }
  try {
    const roles: RoleDetail[] = []
    let pageNo = 1
    while (true) {
      const result = await listRoles({ pageNo, pageSize: 100 })
      roles.push(...result.items)
      if (roles.length >= result.totalElements || result.items.length === 0) break
      pageNo += 1
    }
    roleOptions.value = roles
    roleOptionsLoaded.value = true
  } catch {
    // 角色下拉是筛选便利，不是数据源：取不到时保持空列表，列表本身仍可用
    roleOptions.value = []
    roleOptionsLoaded.value = false
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

interface HandoffRow {
  ticketNo: string
  ticketVersion: number
  newAssigneeId: number | null
}

const roleEditing = ref<UserDetail | null>(null)
const roleSelection = ref<number[]>([])
const rolePending = ref(false)
const roleError = ref('')
const handoffTarget = ref<UserDetail | null>(null)
const handoffAction = ref<'disable' | 'roles'>('disable')
const handoffAssignments = shallowRef<ActiveTicketAssignment[]>([])
const handoffOptions = shallowRef<Record<string, HandoffCandidate[]>>({})
const handoffFormRef = ref<FormInstance>()
const handoffForm = reactive<{ reason: string; rows: HandoffRow[] }>({ reason: '', rows: [] })
const handoffPending = ref(false)
const handoffLoading = ref(false)
const handoffError = ref('')
const handoffRules: FormRules = {
  reason: [
    { required: true, whitespace: true, message: '请输入交接原因', trigger: 'blur' },
    { max: 1000, message: '交接原因最多 1000 个字符', trigger: 'blur' },
  ],
}

function openRoleEdit(row: UserDetail): void {
  if (!roleOptionsLoaded.value) {
    ElMessage.error('角色列表未能加载，请刷新页面后重试')
    return
  }
  roleSelection.value = row.roles.map((role) => role.id)
  roleError.value = ''
  roleEditing.value = row
}

async function prepareHandoff(row: UserDetail, action: 'disable' | 'roles'): Promise<boolean> {
  if (!canHandoff.value) return false
  handoffLoading.value = true
  try {
    const assignments = await getActiveTicketAssignments(row.id)
    if (assignments.length === 0) return false
    const options = await Promise.all(assignments.map((item) => getHandoffCandidates(item.ticketNo)))
    handoffAssignments.value = assignments
    handoffOptions.value = Object.fromEntries(assignments.map((item, index) => [item.ticketNo, options[index] ?? []]))
    handoffForm.reason = ''
    handoffForm.rows = assignments.map((item) => ({
      ticketNo: item.ticketNo,
      ticketVersion: item.version,
      newAssigneeId: null,
    }))
    handoffError.value = ''
    handoffAction.value = action
    handoffTarget.value = row
    return true
  } catch (error) {
    ElMessage.error(describeError(error, '交接信息未能加载，请刷新后重试'))
    return true
  } finally {
    handoffLoading.value = false
  }
}

async function submitHandoff(): Promise<void> {
  const target = handoffTarget.value
  if (!target || !await handoffFormRef.value?.validate().catch(() => false)) return
  handoffPending.value = true
  handoffError.value = ''
  try {
    const handoffs = handoffForm.rows.map((row) => ({
      ticketNo: row.ticketNo,
      ticketVersion: row.ticketVersion,
      newAssigneeId: row.newAssigneeId!,
    }))
    const plan = { version: target.version, handoffReason: handoffForm.reason.trim(), handoffs }
    if (handoffAction.value === 'disable') {
      await disableUser(target.id, plan)
      ElMessage.success('工单已交接，账号已停用且现有会话已失效')
    } else {
      await replaceUserRoles(target.id, { ...plan, roleIds: [...roleSelection.value] })
      roleEditing.value = null
      ElMessage.success('工单已交接，角色已更新')
    }
    handoffTarget.value = null
    await search()
  } catch (error) {
    handoffError.value = describeError(error, '交接未生效，请刷新列表后重试')
  } finally {
    handoffPending.value = false
  }
}

async function submitRoles(): Promise<void> {
  const target = roleEditing.value
  if (!target) return
  const itRole = target.roles.find((role) => role.code === 'IT_SUPPORT')
  const removesIt = itRole !== undefined && !roleSelection.value.includes(itRole.id)
  if (removesIt && await prepareHandoff(target, 'roles')) return
  rolePending.value = true
  roleError.value = ''
  try {
    await replaceUserRoles(target.id, { roleIds: [...roleSelection.value], version: target.version })
    roleEditing.value = null
    ElMessage.success('角色已更新')
    await search()
  } catch (error) {
    roleError.value = describeError(error, '角色没有更新，请刷新后重试')
  } finally {
    rolePending.value = false
  }
}

async function toggleStatus(row: UserDetail): Promise<void> {
  statusPendingId.value = row.id
  try {
    if (row.status === 'ENABLED') {
      if (await prepareHandoff(row, 'disable')) return
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

function openGrants(row: UserDetail, event?: MouseEvent): void {
  if (!roleOptionsLoaded.value) {
    ElMessage.error('角色列表未能加载，请刷新页面后重试')
    return
  }
  roleTrigger.value = (event?.currentTarget as HTMLElement | null) ?? null
  drawerUser.value = row
  drawerOpen.value = true
}

/** 抽屉关闭后把焦点还给触发行：键盘用户不应该被丢回页面顶部。 */
watch(drawerOpen, (open) => {
  if (!open) {
    void nextTick(() => roleTrigger.value?.focus())
  }
})

async function toggleSelectedStatus(): Promise<void> {
  if (selectedOne.value) {
    await toggleStatus(selectedOne.value)
  }
}

/** 启用的管理员行：停用或移除管理员角色会撞上"至少保留一个启用管理员"的保护规则。 */
function holdsProtectedRole(row: UserDetail): boolean {
  return row.status === 'ENABLED' && row.roles.some((role) => isProtectedRole(role.code))
}

onMounted(async () => {
  await Promise.all([loadRoleOptions(), search()])
  const userId = Number(route.query.userId ?? '')
  if (!canPickRole.value || !roleOptionsLoaded.value
      || !Number.isInteger(userId) || userId <= 0) return
  try {
    openRoleEdit(await getUser(userId))
  } catch (error) {
    ElMessage.error(describeError(error, '用户信息没有取到，请重新检索'))
  }
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
      :class="{ 'admin-filter-card--collapsed': !filtersOpen }"
      aria-label="用户查询条件"
    >
      <header class="admin-filter-card__head">
        <h2 class="admin-filter-card__title">
          筛选条件
        </h2>
        <button
          class="admin-filter-card__toggle"
          type="button"
          aria-controls="user-filter-fields"
          :aria-expanded="filtersOpen"
          @click="filtersOpen = !filtersOpen"
        >
          {{ filtersOpen ? '收起' : '展开' }}
          <el-icon
            class="admin-filter-card__chevron"
            :class="{ 'admin-filter-card__chevron--collapsed': !filtersOpen }"
            aria-hidden="true"
          >
            <ArrowDown />
          </el-icon>
        </button>
      </header>

      <div
        v-show="filtersOpen"
        id="user-filter-fields"
        class="admin-filter-card__body"
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
              搜索
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
      </div>
    </section>

    <section
      class="admin-data"
      aria-label="用户数据"
    >
      <header class="admin-data__head">
        <div class="admin-data__heading">
          <h2 class="admin-data__title">
            用户列表
          </h2>
          <p class="admin-data__summary">
            <template v-if="phase === 'ready'">
              共 {{ total }} 条记录，支持按登录名、账号状态与角色筛选。
            </template>
            <template v-else-if="phase === 'loading'">
              正在读取账号列表…
            </template>
            <template v-else>
              记录数暂时无法读取。
            </template>
          </p>
        </div>

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
          <el-button
            v-if="canPickRole"
            :disabled="!selectedOne || !roleOptionsLoaded"
            :icon="Share"
            type="primary"
            plain
            @click="selectedOne && openRoleEdit(selectedOne)"
          >
            调整角色
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
      </header>

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
              <RouterLink
                class="admin-ident admin-ident--link"
                :to="{ name: 'admin-user-detail', params: { userId: row.id } }"
              >
                {{ row.username }}
              </RouterLink>
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
                    type="warning"
                    plain
                    circle
                    @click="openReset(row)"
                  />
                </el-tooltip>
                <el-tooltip
                  content="分配角色"
                  placement="top"
                >
                  <el-button
                    v-if="canPickRole"
                    :icon="Share"
                    aria-label="分配角色"
                    data-test="assign-roles"
                    type="primary"
                    plain
                    circle
                    @click="openGrants(row, $event)"
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

    <UserRoleDrawer
      v-model="drawerOpen"
      :user="drawerUser"
      @saved="search"
    />

    <el-dialog
      class="admin-dialog"
      :model-value="roleEditing !== null"
      title="调整用户角色"
      width="30rem"
      :close-on-click-modal="false"
      @update:model-value="roleEditing = null"
    >
      <p
        v-if="roleEditing"
        class="admin-hint"
      >
        {{ roleEditing.displayName }}（{{ roleEditing.username }}）的角色将替换为以下选择。
      </p>
      <el-select
        v-model="roleSelection"
        aria-label="调整后的角色"
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
      <p
        v-if="roleError"
        class="admin-error"
        role="alert"
      >
        {{ roleError }}
      </p>
      <template #footer>
        <el-button
          type="info"
          plain
          @click="roleEditing = null"
        >
          取消
        </el-button>
        <el-button
          :loading="rolePending || handoffLoading"
          type="primary"
          @click="submitRoles"
        >
          保存角色
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      class="admin-dialog"
      :model-value="handoffTarget !== null"
      title="交接在办工单"
      width="46rem"
      :close-on-click-modal="false"
      @update:model-value="handoffTarget = null"
    >
      <p class="admin-hint">
        先为每张在办工单指定新负责人。提交后将同时完成工单交接与账号变更。
      </p>
      <el-form
        ref="handoffFormRef"
        label-position="top"
        :model="handoffForm"
        :rules="handoffRules"
      >
        <div class="handoff-list">
          <div
            v-for="(ticket, index) in handoffAssignments"
            :key="ticket.ticketNo"
            class="handoff-list__row"
          >
            <div class="handoff-list__meta">
              <strong>{{ ticket.ticketNo }}</strong>
              <span>{{ ticketStatusLabel(ticket.status) }} · 当前负责人 {{ ticket.assigneeDisplayName }}</span>
            </div>
            <el-form-item
              label="新负责人"
              :prop="`rows.${index}.newAssigneeId`"
              :rules="[{ required: true, message: '请选择新负责人', trigger: 'change' }]"
            >
              <el-select
                v-model="handoffForm.rows[index]!.newAssigneeId"
                :aria-label="`${ticket.ticketNo}的新负责人`"
                class="admin-form-control"
                filterable
                placeholder="请选择新负责人"
              >
                <el-option
                  v-for="candidate in handoffOptions[ticket.ticketNo] ?? []"
                  :key="candidate.id"
                  :label="candidate.displayName"
                  :value="candidate.id"
                />
              </el-select>
              <p
                v-if="handoffOptions[ticket.ticketNo]?.length === 0"
                class="admin-hint"
              >
                暂无可接替的 IT 人员，请先安排可用人员。
              </p>
            </el-form-item>
          </div>
        </div>
        <el-form-item
          label="交接原因"
          prop="reason"
          required
        >
          <el-input
            v-model="handoffForm.reason"
            aria-label="交接原因"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            placeholder="请输入交接原因"
          />
        </el-form-item>
      </el-form>
      <p
        v-if="handoffError"
        class="admin-error"
        role="alert"
      >
        {{ handoffError }}
      </p>
      <template #footer>
        <el-button
          type="info"
          plain
          @click="handoffTarget = null"
        >
          取消
        </el-button>
        <el-button
          :loading="handoffPending"
          type="primary"
          @click="submitHandoff"
        >
          确认交接
        </el-button>
      </template>
    </el-dialog>

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
