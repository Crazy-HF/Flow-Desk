<script setup lang="ts">
import { Check, CircleClose, Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { describeError } from '@/api/errorMessages'
import { createPermission, deletePermission, listPermissions, updatePermission } from '@/api/rbac'
import type { PermissionDetail } from '@/api/rbac'
import AdminListPanel from '@/components/AdminListPanel.vue'
import AppPagination from '@/components/AppPagination.vue'
import AppPage from '@/components/AppPage.vue'
import ProtectedMark from '@/components/ProtectedMark.vue'
import { isProtectedPermission, PROTECTED_PERMISSION_CODE } from '@/constants/authorization'
import { useAdminList } from '@/composables/useAdminList'

/** 权限管理（`docs/api-design.md` 8.2.1，`TASK-060`）。 */
const keyword = ref('')

/**
 * 列宽：`el-table` 用 `parseInt` 解析 `width` / `min-width`，`7rem` 会变成 7px，
 * 因此写无单位像素数；标识锚点列容纳保护标记取 240，操作列放 2 个图标按钮取 120。
 */
const COLUMN = {
  select: 44,
  ident: 240,
  name: 144,
  description: 192,
  count: 112,
  actions: 120,
} as const

const { items, total, pageNo, pageSize, phase, errorMessage, retrying, search, load } =
  useAdminList<PermissionDetail>((page) =>
    listPermissions({
      ...page,
      ...(keyword.value.trim() ? { keyword: keyword.value.trim() } : {}),
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

const emptyTitle = computed(() =>
  keyword.value.trim() ? '没有匹配的权限' : '还没有权限',
)
const emptyDescription = computed(() =>
  keyword.value.trim()
    ? '换一个权限编码或名称再查一次。'
    : '权限随系统预置；新建的权限要先授予角色，持有该角色的账号才会获得对应能力。',
)

// ---------- 创建 ----------

interface CreateForm {
  code: string
  name: string
  description: string
}

const createVisible = ref(false)
const creating = ref(false)
const createError = ref('')
const createFormRef = ref<FormInstance>()
const createForm = reactive<CreateForm>({ code: '', name: '', description: '' })
const createRules: FormRules<CreateForm> = {
  code: [
    { required: true, message: '请输入权限编码', trigger: 'blur' },
    {
      pattern: /^[A-Z][A-Z0-9_]*$/,
      message: '编码用大写字母、数字与下划线，且以字母开头',
      trigger: 'blur',
    },
    { max: 100, message: '权限编码最大长度为 100', trigger: 'blur' },
  ],
  name: [
    { required: true, message: '请输入权限名称', trigger: 'blur' },
    { max: 100, message: '权限名称最大长度为 100', trigger: 'blur' },
  ],
  description: [{ max: 255, message: '权限描述最大长度为 255', trigger: 'blur' }],
}

function openCreate(): void {
  createError.value = ''
  createForm.code = ''
  createForm.name = ''
  createForm.description = ''
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
    await createPermission({
      code: createForm.code.trim(),
      name: createForm.name.trim(),
      ...(createForm.description.trim() ? { description: createForm.description.trim() } : {}),
    })
    createVisible.value = false
    ElMessage.success('权限已创建')
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

const editing = ref<PermissionDetail | null>(null)
const saving = ref(false)
const editError = ref('')
const editFormRef = ref<FormInstance>()
const editForm = reactive<EditForm>({ name: '', description: '' })
const editRules: FormRules<EditForm> = {
  name: [
    { required: true, message: '请输入权限名称', trigger: 'blur' },
    { max: 100, message: '权限名称最大长度为 100', trigger: 'blur' },
  ],
  description: [{ max: 255, message: '权限描述最大长度为 255', trigger: 'blur' }],
}

function openEdit(row: PermissionDetail): void {
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
    await updatePermission(target.id, {
      name: editForm.name.trim(),
      description: editForm.description.trim(),
    })
    editing.value = null
    ElMessage.success('权限已更新')
    await search()
  } catch (error) {
    editError.value = describeError(error, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

// ---------- 删除 ----------

async function removePermission(row: PermissionDetail): Promise<void> {
  try {
    await deletePermission(row.id)
    ElMessage.success('权限已删除')
    await search()
  } catch (error) {
    ElMessage.error(describeError(error, '删除失败，请刷新后重试'))
  }
}

function resetFilters(): void {
  keyword.value = ''
  void search()
}

// ---------- 工具栏选择 ----------

/** 工具栏的"修改 / 删除"作用于恰好选中一行时的那一行；受保护权限即使选中也不放行。 */
const selectedRows = ref<PermissionDetail[]>([])
const selectedOne = computed(() =>
  selectedRows.value.length === 1 ? selectedRows.value[0] : null,
)
const selectedProtected = computed(() =>
  Boolean(selectedOne.value && isProtectedPermission(selectedOne.value.code)),
)

function onSelectionChange(rows: PermissionDetail[]): void {
  selectedRows.value = rows
}

async function removeSelected(): Promise<void> {
  if (selectedOne.value && !selectedProtected.value) {
    await removePermission(selectedOne.value)
  }
}

onMounted(() => {
  void search()
})
</script>

<template>
  <AppPage
    class="admin-page"
    description="权限是访问能力的开关；编码创建后不可修改，被角色引用时不可删除。"
    layout="list"
    title="权限管理"
  >
    <section
      class="admin-filter-card"
      aria-label="权限查询条件"
    >
      <div class="admin-filter-fields">
        <span class="admin-filter-field">
          权限名称
          <el-input
            id="permission-keyword"
            v-model="keyword"
            aria-label="权限名称"
            class="admin-filter-input admin-filter-input--wide"
            clearable
            placeholder="请输入权限编码或名称"
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
      aria-label="权限数据"
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
          title="权限仍被角色引用时删除会被拒绝。确定删除？"
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
        label="权限列表"
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
            label="权限编码"
            :width="COLUMN.ident"
          >
            <template #default="{ row }">
              <span class="admin-ident">{{ row.code }}</span>
              <ProtectedMark
                v-if="isProtectedPermission(row.code)"
                label="内置"
                :reason="`${PROTECTED_PERMISSION_CODE} 是系统内置的管理权限：不能删除，也不能从系统管理员角色撤销`"
              />
            </template>
          </el-table-column>
          <el-table-column
            label="名称"
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
            label="引用角色"
            :width="COLUMN.count"
          >
            <template #default="{ row }">
              <span class="admin-count">{{ row.roleIds.length }}</span>
              <span class="admin-muted"> 个</span>
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
                  content="修改权限"
                  placement="top"
                >
                  <el-button
                    :icon="Edit"
                    aria-label="修改权限"
                    type="success"
                    plain
                    circle
                    @click="openEdit(row)"
                  />
                </el-tooltip>
                <el-popconfirm
                  title="权限仍被角色引用时删除会被拒绝。确定删除？"
                  cancel-button-text="取消"
                  confirm-button-text="确定"
                  :disabled="isProtectedPermission(row.code)"
                  width="17rem"
                  @confirm="removePermission(row)"
                >
                  <template #reference>
                    <el-button
                      :disabled="isProtectedPermission(row.code)"
                      :icon="Delete"
                      aria-label="删除权限"
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
      title="新建权限"
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
          label="权限编码"
          prop="code"
        >
          <el-input
            v-model="createForm.code"
            maxlength="100"
            placeholder="请输入权限编码"
          />
        </el-form-item>
        <el-form-item
          label="权限名称"
          prop="name"
        >
          <el-input
            v-model="createForm.name"
            maxlength="100"
            placeholder="请输入权限名称"
          />
        </el-form-item>
        <el-form-item
          label="描述"
          prop="description"
        >
          <el-input
            v-model="createForm.description"
            maxlength="255"
            placeholder="请输入权限描述"
            type="textarea"
          />
        </el-form-item>
        <p class="admin-hint">
          新建权限只是登记一项能力：要真正生效，还需要把它授予角色，并在使用它的地方接入检查。
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
      title="修改权限"
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
          label="权限名称"
          prop="name"
        >
          <el-input
            v-model="editForm.name"
            maxlength="100"
            placeholder="请输入权限名称"
          />
        </el-form-item>
        <el-form-item
          label="描述"
          prop="description"
        >
          <el-input
            v-model="editForm.description"
            maxlength="255"
            placeholder="请输入权限描述"
            type="textarea"
          />
        </el-form-item>
        <p class="admin-hint">
          编码创建后不可修改：授权关系与操作记录都按编码引用权限。
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
