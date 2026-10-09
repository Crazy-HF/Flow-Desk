<script setup lang="ts">
import { Delete, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, onMounted, reactive, ref, shallowRef } from 'vue'

import { {{API_IMPORTS}} } from '{{API_MODULE}}'
import type { {{TYPE_DETAIL}} } from '{{API_MODULE}}'
import AdminListPanel from '@/components/AdminListPanel.vue'
import AppPage from '@/components/AppPage.vue'
import AppPagination from '@/components/AppPagination.vue'
import { useAdminList } from '@/composables/useAdminList'
import { useAuthStore } from '@/stores/auth'
import { describeError } from '@/api/errorMessages'

/**
 * {{TITLE}}（`docs/api-design.md` {{API_SECTION}}）。
 *
 * <p>手动生成，骨架与 `views/admin/RoleListView.vue` 一致；改动骨架请同步
 * `frontend/PAGE-TEMPLATE.md` 与 `frontend/scripts/templates/page/`。</p>
 */
const auth = useAuthStore()

/** 列表的写操作入口：只做显隐，后端仍是最终授权边界。 */
const {{PERMISSION_FLAG}} = computed(() => auth.hasPermission('{{PERMISSION}}'))

/**
 * 列宽：`el-table` 用 `parseInt` 解析 `width` / `min-width`，写 `7rem` 只会得到 7px，
 * 因此一律给无单位像素数；需要贴合刻度时按 `1rem = 16px` 换算。
 */
const COLUMN = {
  select: 44,
  name: 240,
  actions: 144,
} as const

// ---------- 取数 ----------
const keyword = ref('')

const { items, total, pageNo, pageSize, phase, errorMessage, retrying, search, load } =
  useAdminList<{{TYPE_DETAIL}}>((page) =>
    {{API_LIST}}({
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

/** 空态文案区分"本来没有"与"筛选后没有"：两句话不一样，人才知道下一步该做什么。 */
const hasFilters = computed(() => keyword.value.trim() !== '')
const emptyTitle = computed(() =>
  hasFilters.value ? '没有符合条件的数据' : '这里还没有数据',
)
const emptyDescription = computed(() =>
  hasFilters.value
    ? '换一个关键词再查一次，也可以重置筛选条件。'
    : '{{EMPTY_DESCRIPTION}}',
)

function resetFilters(): void {
  keyword.value = ''
  void search()
}

// ---------- 工具栏选择 ----------
const selectedRows = shallowRef<{{TYPE_DETAIL}}[]>([])
const selectedOne = computed(() =>
  selectedRows.value.length === 1 ? selectedRows.value[0] : null,
)

function onSelectionChange(rows: {{TYPE_DETAIL}}[]): void {
  selectedRows.value = rows
}

// ---------- 新建 ----------
interface CreateForm {
  name: string
}

const createVisible = ref(false)
const creating = ref(false)
const createError = ref('')
const createFormRef = ref<FormInstance>()
const createForm = reactive<CreateForm>({ name: '' })
const createRules: FormRules<CreateForm> = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: 100, message: '名称最大长度为 100', trigger: 'blur' },
  ],
}

function openCreate(): void {
  createError.value = ''
  createForm.name = ''
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
    await {{API_CREATE}}({ name: createForm.name.trim() })
    createVisible.value = false
    ElMessage.success('已创建')
    await search()
  } catch (error) {
    createError.value = describeError(error, '创建失败，请稍后重试')
  } finally {
    creating.value = false
  }
}

// ---------- 删除 ----------
async function removeRow(row: {{TYPE_DETAIL}}): Promise<void> {
  try {
    await {{API_DELETE}}(row.{{ID_FIELD}})
    ElMessage.success('已删除')
    await search()
  } catch (error) {
    // 删除失败不改列表：先把后端的稳定错误码翻译成人能读的一句话
    ElMessage.error(describeError(error, '删除失败，请刷新后重试'))
  }
}

onMounted(() => {
  void search()
})
</script>

<template>
  <AppPage
    class="admin-page"
    description="{{DESCRIPTION}}"
    layout="list"
    title="{{TITLE}}"
  >
    <section
      class="admin-filter-card"
      aria-label="{{TITLE}}查询条件"
    >
      <div class="admin-filter-fields">
        <span class="admin-filter-field">
          关键词
          <el-input
            id="{{KEBAB}}-keyword"
            v-model="keyword"
            aria-label="{{TITLE}}关键词"
            class="admin-filter-input admin-filter-input--wide"
            clearable
            placeholder="请输入名称"
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
      aria-label="{{TITLE}}数据"
    >
      <div class="admin-action-bar">
        <el-button
          v-if="{{PERMISSION_FLAG}}"
          :icon="Plus"
          type="primary"
          @click="openCreate"
        >
          新建
        </el-button>
        <el-popconfirm
          :disabled="!selectedOne"
          title="删除后不可恢复。确定删除？"
          cancel-button-text="取消"
          confirm-button-text="确定"
          width="17rem"
          @confirm="selectedOne && removeRow(selectedOne)"
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
        label="{{TITLE}}列表"
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
          :row-key="'{{ID_FIELD}}'"
          :row-style="{ height: 'var(--fd-admin-row-height)' }"
          border
          class="admin-table"
          @selection-change="onSelectionChange"
        >
          <el-table-column
            align="center"
            :width="COLUMN.select"
            type="selection"
          />

          <el-table-column
            label="名称"
            min-width="240"
            prop="name"
            show-overflow-tooltip
          />

          <el-table-column
            align="right"
            fixed="right"
            label="操作"
            :width="COLUMN.actions"
          >
            <template #default="{ row }">
              <div class="admin-row-actions">
                <el-tooltip
                  content="删除"
                  placement="top"
                >
                  <el-button
                    :icon="Delete"
                    aria-label="删除"
                    circle
                    plain
                    type="danger"
                    @click="removeRow(row)"
                  />
                </el-tooltip>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </AdminListPanel>

      <AppPagination
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
      title="新建{{TITLE}}"
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
          label="名称"
          prop="name"
        >
          <el-input
            v-model="createForm.name"
            class="admin-form-control"
            placeholder="请输入名称"
          />
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
          :loading="creating"
          type="primary"
          @click="submitCreate"
        >
          确定
        </el-button>
        <el-button @click="createVisible = false">
          取消
        </el-button>
      </template>
    </el-dialog>
  </AppPage>
</template>
