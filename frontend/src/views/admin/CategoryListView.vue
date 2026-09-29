<script setup lang="ts">
import { Check, CircleClose, Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import {
  createCategory,
  deleteCategory,
  disableCategory,
  enableCategory,
  listCategories,
  updateCategory,
} from '@/api/categories'
import type { CategoryDetail, CategoryStatus } from '@/api/categories'
import { describeError } from '@/api/errorMessages'
import AdminListPanel from '@/components/AdminListPanel.vue'
import AppPage from '@/components/AppPage.vue'
import { useAdminList } from './useAdminList'
import { useCompactPagination } from './useCompactPagination'

/**
 * 分类管理（`docs/api-design.md` 8.4，`CATEGORY_MANAGE`）。
 *
 * <p>这一页和另外四个管理页有一处本质差别：分类不是账号或授权关系，而是**业务配置**——
 * 启用状态与排序值直接决定员工提交工单时能选到什么、按什么顺序选。所以这里的每个写操作
 * 都要在界面上说清它对员工端的影响，而不是只改一行数据。</p>
 *
 * <p>分类没有受保护对象，也没有编码：名称就是业务标识，因此本页不出现保护标记与编码列。</p>
 */
const compactPagination = useCompactPagination()

/**
 * 列宽：`el-table` 用 `parseInt` 解析 `width` / `min-width`，写 `7rem` 只会得到 7px，
 * 列被压成一条并把行高撑到数百像素（2026-09-24 实测）。所以这里给无单位像素数：
 * 勾选列 44、排序值 96、状态开关 96、操作列放 2 个图标按钮取 120。
 *
 * <p>不设"创建时间 / 更新时间"列：时间是审计信息，`main.css` 明确它不进管理列表列，
 * 多一列就把表格推出视口，可扫读性反而下降。</p>
 */
const COLUMN = {
  select: 44,
  name: 240,
  sortOrder: 96,
  status: 96,
  actions: 120,
} as const

const keyword = ref('')
const statusFilter = ref<CategoryStatus | ''>('')

const { items, total, pageNo, pageSize, phase, errorMessage, retrying, search, changePage, changePageSize } =
  useAdminList<CategoryDetail>((page) =>
    listCategories({
      ...page,
      ...(keyword.value.trim() ? { keyword: keyword.value.trim() } : {}),
      ...(statusFilter.value ? { status: statusFilter.value } : {}),
    }),
  )

const hasFilters = computed(() => Boolean(keyword.value.trim() || statusFilter.value))
const emptyTitle = computed(() => (hasFilters.value ? '没有符合条件的分类' : '还没有分类'))
const emptyDescription = computed(() =>
  hasFilters.value
    ? '换一个分类名称或状态再查一次。'
    : '新建第一个分类后，员工才能在「新建工单」里选择它。',
)

function resetFilters(): void {
  keyword.value = ''
  statusFilter.value = ''
  void search()
}

// ---------- 新建 ----------

interface CategoryForm {
  name: string
  /**
   * `el-input-number` 在内容被清空时会给出 `undefined`，所以字段本身允许为空，
   * 由表单校验负责拦住它；提交前再收窄一次，不把 `undefined` 发给后端。
   */
  sortOrder: number | undefined
}

const createVisible = ref(false)
const creating = ref(false)
const createError = ref('')
const createFormRef = ref<FormInstance>()
const createForm = reactive<CategoryForm>({ name: '', sortOrder: 0 })
const createRules: FormRules<CategoryForm> = {
  name: [
    { required: true, message: '请输入分类名称', trigger: 'blur' },
    { max: 100, message: '分类名称最大长度为 100', trigger: 'blur' },
  ],
  sortOrder: [{ required: true, message: '请输入排序值', trigger: 'blur' }],
}

function openCreate(): void {
  createError.value = ''
  createForm.name = ''
  // 0 最小：新建的分类默认排在员工端下拉最前面，要放末尾就填一个更大的值
  createForm.sortOrder = 0
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
  const sortOrder = createForm.sortOrder
  if (sortOrder === undefined) {
    return
  }

  creating.value = true
  createError.value = ''
  try {
    await createCategory({
      name: createForm.name.trim(),
      sortOrder,
    })
    createVisible.value = false
    ElMessage.success('分类已创建，员工现在可以在「新建工单」里选择它')
    await search()
  } catch (error) {
    createError.value = describeError(error, '创建失败，请稍后重试')
  } finally {
    creating.value = false
  }
}

// ---------- 修改 ----------

const editing = ref<CategoryDetail | null>(null)
const saving = ref(false)
const editError = ref('')
const editFormRef = ref<FormInstance>()
const editForm = reactive<CategoryForm>({ name: '', sortOrder: 0 })
const editRules: FormRules<CategoryForm> = {
  name: [
    { required: true, message: '请输入分类名称', trigger: 'blur' },
    { max: 100, message: '分类名称最大长度为 100', trigger: 'blur' },
  ],
  sortOrder: [{ required: true, message: '请输入排序值', trigger: 'blur' }],
}

function openEdit(row: CategoryDetail): void {
  editError.value = ''
  editForm.name = row.name
  editForm.sortOrder = row.sortOrder
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
  const sortOrder = editForm.sortOrder
  if (sortOrder === undefined) {
    return
  }

  saving.value = true
  editError.value = ''
  try {
    await updateCategory(target.id, {
      name: editForm.name.trim(),
      sortOrder,
      // 版本随行数据一起提交：被别人改过时后端返回 CATEGORY_CONFLICT 而不是静默覆盖
      version: target.version,
    })
    editing.value = null
    ElMessage.success('分类已更新')
    await search()
  } catch (error) {
    editError.value = describeError(error, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

// ---------- 启用 / 停用 ----------

/** 只让被点的那一行转圈，避免整表 loading。 */
const statusPendingId = ref<number | null>(null)

async function toggleStatus(row: CategoryDetail): Promise<void> {
  statusPendingId.value = row.id
  try {
    if (row.status === 'ENABLED') {
      await disableCategory(row.id, row.version)
      ElMessage.success('分类已停用：新工单不能再选它，历史工单仍显示原分类')
    } else {
      await enableCategory(row.id, row.version)
      ElMessage.success('分类已启用，员工可以重新选到它')
    }
    await search()
  } catch (error) {
    ElMessage.error(describeError(error, '状态没有改变，请刷新后重试'))
  } finally {
    statusPendingId.value = null
  }
}

// ---------- 删除 ----------

async function removeCategory(row: CategoryDetail): Promise<void> {
  try {
    await deleteCategory(row.id, row.version)
    ElMessage.success('分类已删除')
    await search()
  } catch (error) {
    ElMessage.error(describeError(error, '删除失败，请刷新后重试'))
  }
}

// ---------- 工具栏选择 ----------

/** 工具栏的"编辑 / 停用启用 / 删除"作用于恰好选中一行时的那一行。 */
const selectedRows = ref<CategoryDetail[]>([])
const selectedOne = computed(() => (selectedRows.value.length === 1 ? selectedRows.value[0] : null))

function onSelectionChange(rows: CategoryDetail[]): void {
  selectedRows.value = rows
}

const statusActionLabel = computed(() => {
  if (!selectedOne.value) {
    return '停用/启用'
  }
  return selectedOne.value.status === 'ENABLED' ? '停用' : '启用'
})

async function toggleSelectedStatus(): Promise<void> {
  if (selectedOne.value) {
    await toggleStatus(selectedOne.value)
  }
}

async function removeSelected(): Promise<void> {
  if (selectedOne.value) {
    await removeCategory(selectedOne.value)
  }
}

onMounted(() => {
  void search()
})
</script>

<template>
  <AppPage
    class="admin-page"
    description="分类决定员工提交工单时能选到什么；停用只影响新工单，历史工单继续显示原分类。"
    layout="list"
    title="分类管理"
  >
    <section
      class="admin-filter-card"
      aria-label="分类查询条件"
    >
      <div class="admin-filter-fields">
        <span class="admin-filter-field">
          分类名称
          <el-input
            id="category-keyword"
            v-model="keyword"
            aria-label="分类名称"
            class="admin-filter-input admin-filter-input--wide"
            clearable
            placeholder="请输入分类名称"
            @keyup.enter="search"
          />
        </span>

        <span class="admin-filter-field">
          分类状态
          <el-select
            id="category-status"
            v-model="statusFilter"
            aria-label="分类状态"
            class="admin-filter-input admin-filter-input--narrow"
            clearable
            placeholder="请选择分类状态"
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
      aria-label="分类数据"
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
              ? '停用后员工在「新建工单」里选不到它，历史工单不受影响。确定停用？'
              : '确定启用该分类？'
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
        <el-popconfirm
          :disabled="!selectedOne"
          title="只有从未被工单使用的分类才能删除；已被使用的分类会被拒绝，只能停用。确定删除？"
          cancel-button-text="取消"
          confirm-button-text="确定"
          width="19rem"
          @confirm="removeSelected"
        >
          <template #reference>
            <el-button
              :disabled="!selectedOne"
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
        label="分类列表"
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
            label="分类名称"
            :min-width="COLUMN.name"
            prop="name"
            show-overflow-tooltip
          />
          <el-table-column
            label="排序值"
            :width="COLUMN.sortOrder"
          >
            <template #default="{ row }">
              <span class="admin-count">{{ row.sortOrder }}</span>
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
                    ? '停用后员工在「新建工单」里选不到它，历史工单不受影响。确定停用？'
                    : '确定启用该分类？'
                "
                cancel-button-text="取消"
                confirm-button-text="确定"
                width="17rem"
                @confirm="toggleStatus(row)"
              >
                <template #reference>
                  <el-switch
                    :aria-label="`${row.name}的分类状态，当前${row.status === 'ENABLED' ? '启用' : '停用'}`"
                    :loading="statusPendingId === row.id"
                    :model-value="row.status === 'ENABLED'"
                  />
                </template>
              </el-popconfirm>
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
                  content="修改分类"
                  placement="top"
                >
                  <el-button
                    :icon="Edit"
                    aria-label="修改分类"
                    type="success"
                    plain
                    circle
                    @click="openEdit(row)"
                  />
                </el-tooltip>
                <el-popconfirm
                  title="只有从未被工单使用的分类才能删除；已被使用的分类会被拒绝，只能停用。确定删除？"
                  cancel-button-text="取消"
                  confirm-button-text="确定"
                  width="19rem"
                  @confirm="removeCategory(row)"
                >
                  <template #reference>
                    <el-button
                      :icon="Delete"
                      aria-label="删除分类"
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
      title="新建分类"
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
          label="分类名称"
          prop="name"
        >
          <el-input
            v-model="createForm.name"
            maxlength="100"
            placeholder="请输入分类名称"
          />
        </el-form-item>
        <el-form-item
          label="排序值"
          prop="sortOrder"
        >
          <el-input-number
            v-model="createForm.sortOrder"
            class="admin-form-control"
            :min="0"
            :precision="0"
            :step="10"
          />
        </el-form-item>
        <p class="admin-hint">
          名称在全部分类中唯一，员工看到的就是它。排序值越小越靠前，相同数值按创建先后排列；
          填 0 表示排在员工端下拉的最前面。
        </p>
        <p class="admin-hint">
          新建的分类默认是启用状态，保存后员工立刻可以在「新建工单」里选到。
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
      title="修改分类"
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
          label="分类名称"
          prop="name"
        >
          <el-input
            v-model="editForm.name"
            maxlength="100"
            placeholder="请输入分类名称"
          />
        </el-form-item>
        <el-form-item
          label="排序值"
          prop="sortOrder"
        >
          <el-input-number
            v-model="editForm.sortOrder"
            class="admin-form-control"
            :min="0"
            :precision="0"
            :step="10"
          />
        </el-form-item>
        <p class="admin-hint">
          改名不会改变工单与分类的归属关系：历史工单会跟着显示新名称。
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
