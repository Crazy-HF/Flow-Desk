<script setup lang="ts">
import {
  Check,
  CircleClose,
  Delete,
  Edit,
  Plus,
  Refresh,
  Search,
  Setting,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, onMounted, reactive, ref, shallowRef } from 'vue'
import { useRouter } from 'vue-router'

import { describeError } from '@/api/errorMessages'
import { createRole, deleteRole, listPermissions, listRoles, updateRole } from '@/api/rbac'
import type { PermissionDetail, RoleDetail } from '@/api/rbac'
import AdminListPanel from '@/components/AdminListPanel.vue'
import AppPage from '@/components/AppPage.vue'
import ProtectedMark from '@/components/ProtectedMark.vue'
import { isProtectedRole, PROTECTED_ROLE_CODE } from '@/constants/authorization'
import { useAdminList } from './useAdminList'
import { useCompactPagination } from './useCompactPagination'

/** 角色管理（`docs/api-design.md` 8.2.1，`TASK-060`）。 */
const router = useRouter()
const keyword = ref('')
const compactPagination = useCompactPagination()

/**
 * 列宽：`el-table` 用 `parseInt` 解析 `width` / `min-width`，`7rem` 会变成 7px，
 * 因此写无单位像素数；标识锚点列容纳保护标记取 224，操作列放 3 个图标按钮取 144。
 */
const COLUMN = {
  select: 44,
  ident: 224,
  name: 128,
  description: 176,
  count: 112,
  actions: 144,
} as const

const { items, total, pageNo, pageSize, phase, errorMessage, retrying, search, changePage, changePageSize } =
  useAdminList<RoleDetail>((page) =>
    listRoles({
      ...page,
      ...(keyword.value.trim() ? { keyword: keyword.value.trim() } : {}),
    }),
  )

const emptyTitle = computed(() => (keyword.value.trim() ? '没有匹配的角色' : '还没有自定义角色'))
const emptyDescription = computed(() =>
  keyword.value.trim()
    ? '换一个角色编码或名称再查一次。'
    : '三种内置角色随系统预置；新建的角色会出现在这里。',
)

/** 角色权限下拉的数据源支持 `keyword` 同时匹配编码与名称，因此按远程检索实现。 */
const permissionOptions = shallowRef<PermissionDetail[]>([])
const permissionLoading = ref(false)

async function searchPermissions(query: string): Promise<void> {
  permissionLoading.value = true
  try {
    const result = await listPermissions({ pageNo: 1, pageSize: 50, ...(query.trim() ? { keyword: query.trim() } : {}) })
    permissionOptions.value = result.items
  } catch {
    permissionOptions.value = []
  } finally {
    permissionLoading.value = false
  }
}

// ---------- 创建 ----------

interface CreateForm {
  code: string
  name: string
  description: string
  permissionIds: number[]
}

const createVisible = ref(false)
const creating = ref(false)
const createError = ref('')
const createFormRef = ref<FormInstance>()
const createForm = reactive<CreateForm>({ code: '', name: '', description: '', permissionIds: [] })
const createRules: FormRules<CreateForm> = {
  code: [
    { required: true, message: '请输入角色编码', trigger: 'blur' },
    {
      pattern: /^[A-Z][A-Z0-9_]*$/,
      message: '编码用大写字母、数字与下划线，且以字母开头',
      trigger: 'blur',
    },
    { max: 50, message: '角色编码最大长度为 50', trigger: 'blur' },
  ],
  name: [
    { required: true, message: '请输入角色名称', trigger: 'blur' },
    { max: 50, message: '角色名称最大长度为 50', trigger: 'blur' },
  ],
  description: [{ max: 255, message: '角色描述最大长度为 255', trigger: 'blur' }],
}

async function openCreate(): Promise<void> {
  createError.value = ''
  createForm.code = ''
  createForm.name = ''
  createForm.description = ''
  createForm.permissionIds = []
  createVisible.value = true
  await searchPermissions('')
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
    await createRole({
      code: createForm.code.trim(),
      name: createForm.name.trim(),
      ...(createForm.description.trim() ? { description: createForm.description.trim() } : {}),
      permissionIds: [...createForm.permissionIds],
    })
    createVisible.value = false
    ElMessage.success('角色已创建')
    await search()
  } catch (error) {
    createError.value = describeError(error, '创建失败，请稍后重试')
  } finally {
    creating.value = false
  }
}

// ---------- 修改 ----------

interface EditForm {
  name: string
  description: string
}

const editing = ref<RoleDetail | null>(null)
const saving = ref(false)
const editError = ref('')
const editFormRef = ref<FormInstance>()
const editForm = reactive<EditForm>({ name: '', description: '' })
const editRules: FormRules<EditForm> = {
  name: [
    { required: true, message: '请输入角色名称', trigger: 'blur' },
    { max: 50, message: '角色名称最大长度为 50', trigger: 'blur' },
  ],
  description: [{ max: 255, message: '角色描述最大长度为 255', trigger: 'blur' }],
}

function openEdit(row: RoleDetail): void {
  editError.value = ''
  editForm.name = row.name
  editForm.description = row.description ?? ''
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
    await updateRole(target.id, {
      name: editForm.name.trim(),
      description: editForm.description.trim(),
    })
    editing.value = null
    ElMessage.success('角色已更新')
    await search()
  } catch (error) {
    editError.value = describeError(error, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

// ---------- 删除 ----------

async function removeRole(row: RoleDetail): Promise<void> {
  try {
    await deleteRole(row.id)
    ElMessage.success('角色已删除')
    await search()
  } catch (error) {
    ElMessage.error(describeError(error, '删除失败，请刷新后重试'))
  }
}

function openPermissions(row: RoleDetail): void {
  void router.push({ name: 'admin-role-permissions', query: { roleId: String(row.id) } })
}

function resetFilters(): void {
  keyword.value = ''
  void search()
}

// ---------- 工具栏选择 ----------

/** 工具栏的"修改 / 删除"作用于恰好选中一行时的那一行；受保护角色即使选中也不放行。 */
const selectedRows = ref<RoleDetail[]>([])
const selectedOne = computed(() => (selectedRows.value.length === 1 ? selectedRows.value[0] : null))
const selectedProtected = computed(() =>
  Boolean(selectedOne.value && isProtectedRole(selectedOne.value.code)),
)

function onSelectionChange(rows: RoleDetail[]): void {
  selectedRows.value = rows
}

async function removeSelected(): Promise<void> {
  if (selectedOne.value && !selectedProtected.value) {
    await removeRole(selectedOne.value)
  }
}

onMounted(() => {
  void search()
})
</script>

<template>
  <AppPage
    class="admin-page"
    description="角色是权限的容器；系统管理员角色受保护，编码创建后不可修改。"
    layout="list"
    title="角色管理"
  >
    <section
      class="admin-filter-card"
      aria-label="角色查询条件"
    >
      <div class="admin-filter-fields">
        <span class="admin-filter-field">
          角色名称
          <el-input
            id="role-keyword"
            v-model="keyword"
            aria-label="角色名称"
            class="admin-filter-input admin-filter-input--wide"
            clearable
            placeholder="请输入角色编码或名称"
            @keyup.enter="search"
          />
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
      class="admin-data"
      aria-label="角色数据"
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
          修改
        </el-button>
        <el-popconfirm
          :disabled="!selectedOne || selectedProtected"
          title="角色存在用户或权限授权关系时删除会被拒绝。确定删除？"
          cancel-button-text="取消"
          confirm-button-text="确定"
          width="17rem"
          @confirm="removeSelected"
        >
          <template #reference>
            <el-button
              :disabled="!selectedOne || selectedProtected"
              :icon="Delete"
              type="danger"
              plain
            >
              删除
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

      <AdminListPanel
        :empty-description="emptyDescription"
        :empty-title="emptyTitle"
        :error-message="errorMessage"
        :is-empty="items.length === 0"
        label="角色列表"
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
            label="角色编码"
            :width="COLUMN.ident"
          >
            <template #default="{ row }">
              <span class="admin-ident">{{ row.code }}</span>
              <ProtectedMark
                v-if="isProtectedRole(row.code)"
                label="内置"
                :reason="`${PROTECTED_ROLE_CODE} 是系统内置的管理员角色：不能删除，也不能取消它的角色管理授权`"
              />
            </template>
          </el-table-column>
          <el-table-column
            label="角色名称"
            :min-width="COLUMN.name"
            prop="name"
            show-overflow-tooltip
          />
          <el-table-column
            label="描述"
            :min-width="COLUMN.description"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span :class="{ 'admin-muted': !row.description }">
                {{ row.description || '未填写' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            label="已授权限"
            :width="COLUMN.count"
          >
            <template #default="{ row }">
              <span class="admin-count">{{ row.permissionIds.length }}</span>
              <span class="admin-muted"> 项</span>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="操作"
            :width="COLUMN.actions"
          >
            <template #default="{ row }">
              <div class="admin-row-actions">
                <el-tooltip
                  content="修改角色"
                  placement="top"
                >
                  <el-button
                    :icon="Edit"
                    aria-label="修改角色"
                    type="success"
                    plain
                    circle
                    @click="openEdit(row)"
                  />
                </el-tooltip>
                <el-tooltip
                  content="维护角色权限"
                  placement="top"
                >
                  <el-button
                    :icon="Setting"
                    aria-label="维护角色权限"
                    type="primary"
                    plain
                    circle
                    @click="openPermissions(row)"
                  />
                </el-tooltip>
                <el-popconfirm
                  title="角色存在用户或权限授权关系时删除会被拒绝。确定删除？"
                  cancel-button-text="取消"
                  confirm-button-text="确定"
                  :disabled="isProtectedRole(row.code)"
                  width="17rem"
                  @confirm="removeRole(row)"
                >
                  <template #reference>
                    <el-button
                      :disabled="isProtectedRole(row.code)"
                      :icon="Delete"
                      aria-label="删除角色"
                      type="danger"
                      plain
                      circle
                    />
                  </template>
                </el-popconfirm>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </AdminListPanel>

      <div
        v-if="phase === 'ready' && items.length > 0"
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

    <el-dialog
      v-model="createVisible"
      class="admin-dialog"
      title="新建角色"
      width="32rem"
      :close-on-click-modal="false"
    >
      <el-form
        ref="createFormRef"
        label-position="top"
        :model="createForm"
        :rules="createRules"
      >
        <el-form-item
          label="角色编码"
          prop="code"
        >
          <el-input
            v-model="createForm.code"
            maxlength="50"
            placeholder="请输入角色编码"
          />
        </el-form-item>
        <el-form-item
          label="角色名称"
          prop="name"
        >
          <el-input
            v-model="createForm.name"
            maxlength="50"
            placeholder="请输入角色名称"
          />
        </el-form-item>
        <el-form-item
          label="描述"
          prop="description"
        >
          <el-input
            v-model="createForm.description"
            maxlength="255"
            placeholder="请输入角色描述"
            type="textarea"
          />
        </el-form-item>
        <el-form-item label="权限">
          <el-select
            v-model="createForm.permissionIds"
            class="admin-form-control"
            filterable
            multiple
            :loading="permissionLoading"
            placeholder="请选择权限"
            remote
            reserve-keyword
            :remote-method="searchPermissions"
          >
            <el-option
              v-for="permission in permissionOptions"
              :key="permission.id"
              :label="`${permission.name}（${permission.code}）`"
              :value="permission.id"
            />
          </el-select>
          <p class="admin-hint">
            可以先不授权限：角色创建后在「角色权限授权」中继续维护。
          </p>
        </el-form-item>

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
      title="修改角色"
      width="28rem"
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
          label="角色名称"
          prop="name"
        >
          <el-input
            v-model="editForm.name"
            maxlength="50"
            placeholder="请输入角色名称"
          />
        </el-form-item>
        <el-form-item
          label="描述"
          prop="description"
        >
          <el-input
            v-model="editForm.description"
            maxlength="255"
            placeholder="请输入角色描述"
            type="textarea"
          />
        </el-form-item>
        <p class="admin-hint">
          编码创建后不可修改：授权关系与操作记录都按编码引用角色。
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
          type="success"
          @click="submitEdit"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </AppPage>
</template>
