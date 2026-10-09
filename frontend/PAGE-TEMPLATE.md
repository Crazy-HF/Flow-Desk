# FlowDesk 前端页面模板与一致性契约

> **这份文件回答一个问题**：以后新加一个页面，怎么写才能和已有页面长得一样、行为一样、验收标准一样。
>
> **它不是规范的全部**。约束（设计 token、六态、安全、反 AI 味）在 `frontend/AGENTS.md` 与根 `AGENTS.md`；视觉决定与理由在仓库根 `.ui-craft/brief.md`、`.ui-craft/tokens.md`、`.ui-craft/surfaces/*`。
> **本文件只管一件事**：页面骨架与落地步骤。冲突时以 `AGENTS.md` 与 `.ui-craft/` 为准，并回来修本文件。

- 建立日期：2026-10-09
- 状态：**脚手架已实现；C1 / C3 / C4 已对齐**（分支 `flow-desk/page-template`，完成记录见 §9）
- 适用：`frontend/src/views/**` 的所有页面、以及以后新增的页面

---

## 0. 先读这一段（30 秒）

**模板来源**：`D:\Idea\item\YeJuZhi-Vue-CPY\plus-ui`（本机可读，RuoYi-Vue-Plus 5.6.2 前端）的**页面骨架**——它的一级/二级列表页是「筛选面板 + 数据面板（工具栏 / 表格 / 分页）+ 弹窗表单」，运行实例在 `http://localhost`。

**三个"不是"**：

| 不是 | 说明 |
| --- | --- |
| 不是搬 RuoYi 框架 | 根 `AGENTS.md`「项目采用原生开发，不使用若依」仍然有效。**不引入**动态菜单、`v-hasPermi` 指令、`useDict`、请求加解密、UnoCSS、SCSS scoped、TagView、`utils/request.ts`、`rows/total` 分页契约。 |
| 不是重做视觉 | 管理端五页（用户 / 角色 / 权限 / 用户角色授权 / 角色权限授权）在 2026-09-27～28 已按同一套语言打样并推广，2026-10-06 用户裁决视为已通过。**不改观感，只补结构一致性**。 |
| 不是新增功能 | `docs/api-design.md` 里没有后端实现的能力（数据概览、管理性交接、附件、工单关联、全局搜索）**不做**，也不新建"入口存在但接口不存在"的假页面。 |

**一句话**：flow-Desk 的版本是「**Ruoyi 风格的页面骨架 + flow-Desk 的 token / 六态 / 错误码 / 内存 token / 静态路由**」。骨架对齐，底座不动。

---

## 1. 模板对照表：借什么、不借什么

参照物：
- `plus-ui/src/views/system/user/index.vue`（691 行，进阶样板：左树 + 列显隐 + 导入弹窗）
- `plus-ui/src/views/system/role/index.vue`（503 行，纯框架样板）
- 生成器产物模板：`RuoYi-Vue-Plus/ruoyi-modules/ruoyi-gen/src/main/resources/fm/vue/index.vue.ftl`（FastCRUD 版，`useLoading`/`useFormDialog`/`useTableSelection` 等 hooks）——**结构参考，不要照抄其技术选型**

| # | 借（模板的做法） | 不借（flow-Desk 的替代） |
| --- | --- | --- |
| 1 | 列表页分三区：**筛选面板 / 数据面板 / 弹窗表单** | 已有：`.admin-filter-card`、`.admin-data`、`admin-dialog` |
| 2 | 工具栏顺序：**主操作（新增/修改/删除…）在左，工具操作（刷新/列设置）在右** | 已有 `.admin-action-bar` + `.admin-action-bar__end`，但**右侧内容尚未统一**（见 §9-C1） |
| 3 | 表格：`border` + 选择列 + 固定右侧操作列 + **表头/内容居中** + `show-overflow-tooltip` + 表头 56 / 行高 44–48 | 已有：`.admin-table`、`--fd-admin-row-height`(48px)、居中与单行省略已在 `main.css` 全局处理 |
| 4 | 分页：`total, sizes, prev, pager, next, jumper`，`total > 0` 才显示，`background` | 已有：`.admin-pagination` + `useCompactPagination()`（窄屏收敛为 `total, prev, pager, next`） |
| 5 | 弹窗：新增/修改共用同一个 `el-dialog`，标题随操作变，`append-to-body` | 已有：`class="admin-dialog"`；宽度限制由 `main.css` 管 |
| 6 | 按钮语义色：primary 新增 / success 修改 / danger 删除 / warning 导出 | 已有且更细（`AGENTS.md:14`，warning 保留给重置密码等需提醒的操作） |
| 7 | 行内操作：图标 + `el-tooltip` | 已有且更严：**只显示图标**，必须同时有 `aria-label` **和** `el-tooltip`（`AGENTS.md:15`） |
| 8 | 页面标题 + 简短描述，不制造大型欢迎区 | 已有：`AppPage` 的 `title` / `description` |
| 9 | 表格工具条里放**刷新** | 已有（`admin-action-bar__end`），另需补：**排序/范围切换不许放这里**（见 §4.4） |
| 10 | —— | **不使用**：`el-card shadow="hover"` 包页面、`p-2`/`mb-[10px]` 等 UnoCSS 原子类、SCSS `<style scoped>`、`:deep()`、`useDict`/`DictTag`、`RightToolbar`、`el-switch` 行内改状态（flow-Desk 用显式的启用/停用按钮 + `version` 乐观锁） |

---

## 2. 页面分类（先归类，再动手）

| 类别 | 一句话 | 现存页面 | 骨架 |
| --- | --- | --- | --- |
| **A 管理列表** | 查询 + 列表 + 分页 + 弹窗增删改 | 用户 / 角色 / 权限 / 分类管理 | §3.1 规范骨架，**已完成** |
| **B 关系授权** | 选主体 → 授予 → 关系行列表 | 用户角色授权 / 角色权限授权 | §3.1 的变体：筛选卡换成 `.grant-form`，数据区内容换成 `.grant-list` 关系行 |
| **C 业务列表** | 带"工作范围"的列表 | 工单列表 `/tickets`、IT 工作台 `/it/queue` | §3.1 同构；差异是**范围切换属于筛选条件**（§4.4） |
| **D 详情 / 时间线** | 主体 + 属性侧栏 + 时间线 + 动作区 | 工单详情 `/tickets/:ticketNo` | §5 详情版骨架 |
| **E 表单** | 单页提交，无列表 | 新建工单 `/tickets/new` | §6 表单版骨架 |
| **F 工作台 / 概览** | 入口聚合，无表格 | 首页 `/`；数据概览 `/dashboard`（**占位，后端未实现**） | §7（仅首页适用；dashboard 保持占位页直到后端有接口） |
| **G 认证 / 错误** | 居中卡片 | 登录 / 403 / 404 | 沿用 `.auth-page` + `.auth-card`，不在本文件约束范围 |

**新增页面第一步**：在表里找到它属于哪一类。**找不到就先停下来说明理由**，不要新开类别（`frontend/AGENTS.md` 目录分层同理）。

---

## 3. 页面骨架

### 3.1 列表版（A / B / C 类）

```
<AppPage layout="list" :title :description>
├── section.admin-filter-card                      ← 查询条件与"范围"；标题区 + 字段行 + 操作行
│   ├── h2（筛选区标题，可选）
│   ├── .admin-filter-fields → .admin-filter-field × N
│   └── .admin-filter-actions                      ← 查询(primary) / 重置(info plain)
├── section.admin-data                             ← 数据区，跟随工作区宽度
│   ├── .admin-action-bar
│   │   ├── 左：主操作（新增 primary / 编辑 success / 删除 danger …）
│   │   └── .admin-action-bar__end                 ← 右：只放工具类操作（刷新 info plain + 可选列设置）
│   ├── AdminListPanel                              ← 四态容器（loading / error / empty / ready）
│   │   └── el-table.admin-table                    ← 选择列 + 数据列 + 操作列（fixed right）
│   └── .admin-pagination → el-pagination
└── el-dialog.admin-dialog                          ← 新增/修改（A/B 类；C 类按需）
```

**顺序即契约**：筛选 → 工具栏 → 表格 → 分页，四者在 DOM 里就是这个先后，不允许把工具栏放进筛选卡、把分页挪到表格上方。

#### 3.1.1 真实代码引用（照抄改）

- 完整实现：`src/views/admin/RoleListView.vue`（最"标准"的一页，无额外业务分支）
- 四态容器：`src/components/AdminListPanel.vue`（只抽四态，**筛选控件与表格列留在页面里**，这是刻意的）
- 取数状态机：`src/composables/useAdminList.ts`
- 页面骨架：`src/components/AppPage.vue`（`layout="list"` 走 `.page__workspace`，跟随宽度；默认走 `.page__card`）
- 样式：`src/styles/main.css` 的 `.page*` / `.admin-*` 区块（**不要在页面里写 `<style>`**，见 §8.1）

#### 3.1.2 每页必须自己声明的四样东西

```ts
// 1) 列宽：无单位像素数，集中成 COLUMN 常量（el-table 内部用 parseInt，写 "7rem" 只会得到 7px）
const COLUMN = { name: 160, code: 200, description: 320, createdAt: 168, actions: 120 } as const

// 2) 授权动作（写操作按钮的显隐；后端始终是最终授权边界）
const canCreate = computed(() => auth.hasPermission('RBAC_MANAGE'))   // 按页面实际权限码

// 3) 四态 + 空态文案：区分"没有数据"与"筛选后没有结果"（两句话不一样）
const emptyTitle = computed(() => hasFilters.value ? '没有符合条件的结果' : '暂无数据')

// 4) 破坏性操作的保护原因（受保护对象的入口禁用 + ProtectedMark）
```

### 3.2 详情版（D 类）

```
<AppPage :title>                                     ← 注意：详情页不用 layout="list"
├── 页面级状态块 .ticket-state（无权限 / 加载失败 / 不存在）—— 优先于内容渲染
├── .ticket-detail
│   ├── .ticket-detail__main      ← 主体：信息块 → 时间线 → 动作区
│   └── .ticket-detail__side      ← 属性栏：键值对事实块（.ticket-facts）
└── 动作弹窗（ElMessageBox 动态确认框 + TicketAction*Field）
```

参考实现：`src/views/work/TicketDetailView.vue`。**相邻模块间距统一 `--fd-space-4`**（`AGENTS.md:110`）。

### 3.3 表单版（E 类）

```
<AppPage :title :description>
├── 页面级状态块（无权限 / 选项加载失败 / 没有可用选项）
└── el-form.ticket-form
    ├── label-position="top"，:disabled="submitting"
    ├── 字段（必填标红星，失焦校验当前字段，提交校验全部）
    └── 提交区：幂等键（useSubmissionGuard）+ 主按钮 + 取消
```

参考实现：`src/views/work/TicketCreateView.vue`。

### 3.4 工作台版（F 类）

入口聚合 + 状态块，**不套表格**。参考实现：`src/views/work/HomeView.vue`。

---

## 4. 数据区细则（最容易长歪的地方）

### 4.1 工具栏

| 位置 | 放什么 | 不许放什么 |
| --- | --- | --- |
| `.admin-action-bar` 左 | 会改变数据的操作：新增 / 编辑 / 删除 / 启用 / 停用 / 重置密码 / 授权 | 查询、重置（那两个属于筛选卡） |
| `.admin-action-bar__end` 右 | 只读的视图工具：**刷新**（`info plain` + `Refresh` 图标 + `aria-label` + tooltip）、将来的列设置/密度 | **排序下拉、范围切换、状态筛选**——它们是查询条件，属于筛选卡 |

主操作按钮：`图标 + 文字`；批量操作按钮的 `disabled` 由选择态驱动（`single` / `multiple` / `ids.length === 0`）。

### 4.2 表格

- `class="admin-table"`，`border`，`row-key` 必须是业务主键（`roleId` / `ticketNo`…）
- 选择列 `type="selection"` 仅在页面真有批量操作时加
- 操作列 `fixed="right"`，**只放图标**（`plain` + `circle`），每个按钮：`aria-label` + `el-tooltip`
- 列宽写无单位像素数，集中成 `COLUMN` 常量；表格所在网格容器要允许收缩（`min-width: 0`）
- **表格内不写 `size="small"`**（默认尺寸才是统一后的密度）；行高走 `--fd-admin-row-height`
- 数字列加 `font-variant-numeric: tabular-nums`（已在 `main.css` 统一处理，页面不要再造）
- 日期用 `formatDateTime()`（`src/utils/format.ts`）；**业务判断一律用后端返回的字段，不在前端做时间运算**

### 4.3 分页

```vue
<div class="admin-pagination">
  <el-pagination
    :current-page="pageNo"
    :page-size="pageSize"
    :total="total"
    :layout="compactPagination ? 'total, prev, pager, next' : 'total, sizes, prev, pager, next, jumper'"
    background
    @current-change="changePage"
    @size-change="changePageSize"
  />
</div>
```

`compactPagination` 来自 `useCompactPagination()`；其内部阈值 `52rem` 与 `main.css` 的媒体查询是**两处必须同步修改**的常量（改一处就要改另一处）。

### 4.4 筛选条件的归类（现行偏差）

判断标准：**改了它之后要不要重新发起请求** → 要，就是筛选条件。

- 属于筛选卡：关键词、状态下拉、多选、日期区间、**工作范围（scope）切换**、**排序方式**
- 属于工具栏右侧：刷新；（将来）列设置

> `TicketListView.vue` 目前把「排序下拉 + 刷新」一起放在 `.admin-action-bar__end`（`TicketListView.vue:449-477`），排序应回到筛选卡。这是 §9 的 C1 项。

### 4.5 空态与错误态

- 空态**必须复用** `src/components/EmptyState.vue`，不各造一套
- 空态文案区分「本来没有」与「筛选后没有」，后者要提示"重置筛选"
- 错误态文案走 `describeError(error, fallback)`（`src/api/errorMessages.ts`），**页面里不写错误字符串**
- 加载态用 `AdminListPanel` 的骨架（5 行矩形）；**刷新用 `retrying` 而不是把页面打回骨架**（`useAdminList.load({ silent: true })`）

---

## 5. 六态落点（每页必交）

| 状态 | 落点 | 验收方式 |
| --- | --- | --- |
| loading | 首屏 `phase='loading'` → 骨架；刷新 `retrying` | 慢网络或 mock 挂起 |
| empty | `EmptyState` + 区分两类空文案 | 空库 / 筛选无结果 |
| error | `AdminListPanel` 的 `role="alert"` 块 + 重试按钮（`#retry-action` 可覆盖） | 断开后端 |
| disabled | 无权限或对象受保护时的入口禁用 + **说明"为什么不能动"**的文案（不写接口名、编码常量、"后端裁决"） | `ProtectedMark` / `canXxx=false` |
| 无权限 | 路由守卫按 `meta.permission` 进 `/403`（任一命中即通过）；页面内按权限收口（列表页动作全隐藏时给状态块，如 `TicketCreateView` 的 `v-if="!canCreate"`） | 用 employee 账号访问管理页 |
| 窄屏 | `.admin-data` 跟随宽度、表格横向滚动、分页收敛、侧栏变抽屉 | 375×812 与 1920×1080 各截一张 |

**注意两种权限语义的区别**（两者都存在，别改错）：

- 路由 / 侧栏导航：`meta.permission` 与 `navigationEntries[].permission` —— **任一命中即通过**（`hasAnyPermission`）
- 工单动作登记表：`TicketActionMeta.permission: string | readonly string[]` —— **全部满足才通过**（`permittedActions` 用合取），与后端 `canClose = canProcess && TICKET_CLOSE` 对齐

---

## 6. 新增一个页面的标准步骤

1. **定位接口**：在 `docs/api-design.md` 找到它对应的章节与端点。没有契约就不写页面（`AGENTS.md:102`）。
2. **归类**：按 §2 选骨架；确认所属目录层（`views/work` / `views/admin` / `views/auth` / `views/error`）。
3. **写 API 模块**：`src/api/<领域>.ts`，用现有 `http`；分页返回 `PageResult<T>`（`items/page/size/totalElements/totalPages`）；错误码进 `errorMessages.ts`。
4. **写页面**：先 script 后 template——取数用 `useAdminList`，列宽常量 `COLUMN`，权限用 `auth.hasPermission/hasAnyPermission`，四态用 `AdminListPanel`。
5. **登记路由**：`src/router/index.ts` 加静态路由，`meta` 写 `title` 与 `permission`（数组=任一命中）；需要时补 `plannedTask`。
6. **登记导航**：需要出现在侧栏时，往 `src/constants/authorization.ts` 的 `navigationEntries` 加一条，并指定 `group` 与（子路由的）`activeRouteNames`。**侧栏与面包屑共用这一份声明**，不要各写一遍。
7. **补单测**：与页面同目录、同名 `*.test.ts`（mock API，不依赖后端）；列表页至少覆盖筛选、分页、四态、权限收口。
8. **跑闸门**：`pnpm typecheck` / `pnpm lint` / `pnpm build`；涉及身份或接口流程补 `pnpm test:unit --run`、`pnpm test:e2e`。
9. **截图自查**：1440 与 375 各一张，确认无溢出、无错位、无默认蓝残留；证据放 `.ui-craft/reviews/<日期>-<主题>/`。
10. **报告**：区分"已验证 / 未验证"，不把没跑的检查写成通过。

> 步骤 3～7 是本文件期望被脚手架固化的部分（见 §10）。

---

## 7. 明确禁止的做法

| 禁止 | 原因 / 替代 |
| --- | --- |
| 引入 UnoCSS、SCSS `<style scoped>`、`:deep()` | 六轴 token 闸门（`stylelint.config.js`）会直接红；覆盖 EP 只改 `tokens.css` 末尾的 `--el-*` 映射 |
| 写死颜色 / 间距 / 字号 / 圆角 / 阴影 / 动效时长 | 必须 `var(--fd-*)`；刻度不够先往 `tokens.css` 加档，再引用 |
| 用 `el-card` 把每个 section 包成卡片 | 卡片只用于同级集合项；列表页用「筛选面板 + 数据面板」两个面板 |
| 在页面里 `ElMessage.error('...')` 写中文错误串 | 走 `describeError` + `errorMessages.ts` |
| 把 Access Token 写进 localStorage / sessionStorage / Cookie | 只在 Pinia 内存；刷新靠 HttpOnly Refresh Cookie |
| 引入 `v-hasPermi` 指令、`useDict`、`utils/request.ts`、动态路由 | 属于 RuoYi 框架层，本项目不使用（根 `AGENTS.md`） |
| 为不存在的后端能力建页面 | 保持 `PlannedWorkView` 占位（`/dashboard`、`/admin/users/:userId`） |
| emoji 当功能图标 | 用 `@element-plus/icons-vue` |
| placeholder 冒充默认值 | 有默认值就显示真实默认值 |
| 新增依赖（图表、富文本、上传…） | 先确认（`AGENTS.md`）；MVP 无图表需求 |

---

## 8. 样式与 token 落点

### 8.1 样式写在哪

- **所有 `.vue` 文件没有 `<style>` 块**（现状即如此：全仓 0 个）。页面只写 class 名，样式进 `src/styles/main.css` 的全局选择器。
- 新增页面优先**复用已有 class**：`.page*`、`.admin-*`、`.grant-*`、`.ticket-*`、`.empty-state`、`.protected-mark`、`.sr-only`。
- 确实需要新 class 时：加在 `main.css` 相应区块，命名跟已有前缀（BEM：`块__元素--修饰符`），不要另起一套。
- **不要动 `:deep()` 或 EP 内部选择器**。`main.css` 里已有的 `.el-breadcrumb__inner` 一类后代选择器是历史遗留的例外，不要照着扩写。

### 8.2 token

- 数值唯一真源：`src/styles/tokens.css`（`--fd-*` + 末尾的 `--el-*` 映射）
- 颜色字面量唯一来源：`src/styles/element/var-override.scss`（EP 的 light-x / dark-2 色阶由 SCSS 编译期从主色算出，改主色只改这一个文件）
- `tokens.css` 必须在 Element Plus 样式之后引入（`main.ts` 已按此顺序排列）

---

## 9. 存量页面对齐清单

> 目的不是重做，而是把"模板"落成所有页面共有的既成事实。每项都不改变观感方向。

### 9.1 已完成（2026-10-09，分支 `flow-desk/page-template`）

| # | 项 | 做了什么 | 证据 |
| --- | --- | --- | --- |
| **C1** | 排序回到筛选卡 | `TicketListView` 的「排序」从 `.admin-action-bar__end` 移进 `.admin-filter-card` 的字段行（用通用 `.admin-filter-input`）；工具栏右侧只剩刷新。`main.css` 里 `.ticket-sort` 两条规则随之删除，相关注释改写为"排序是查询条件" | `typecheck`/`lint`/`build` 退出码 0 |
| **C3** | 弹窗类名统一 | `UserListView` 三个弹窗由私有的 `user-list-dialog` 改为 `admin-dialog`；`main.css` 的选择器列表随之收敛为一个类 | 同上 |
| **C4** | 分页只剩一处写法 | 新增 `components/AppPagination.vue`（显示条件、宽窄屏 layout、页大小都在组件内部），替换**七个列表页**里七份重复的 `el-pagination`；`useCompactPagination` 现在只被这个组件消费。新增 `AppPagination.test.ts`（9 项） | 上述三命令 + `test:unit` **24 套件 195 项**（基线 23 套件 186 项） |
| **C7** | 骨架固化成脚手架 | 新增 `scripts/scaffold.mjs` + `scripts/templates/page/`，见 §10 | 用生成的页面跑过三命令与 6 项单测 |

**改动文件**：`src/components/AppPagination.vue`(+test)、`src/views/{admin×5,work×1}/*.vue`、`src/styles/main.css`、`eslint.config.js`（只为 `scripts/**` 放行 Node 全局）。

### 9.2 仍待办

| # | 项 | 现状 | 动作 |
| --- | --- | --- | --- |
| **C2** | 业务列表复用同一套骨架 | `TicketListView` / `TicketQueueView` 已用 `AppPage layout="list"` + `.admin-*` | 保持；新业务列表页一律照此，不再自创构图 |
| **C5** | 筛选卡标题与描述口径 | 各页 `h2` 与 `page description` 有无不一 | 统一：`description` 写"这一页是干什么的、字段不可改的规则"，不写技术说明 |
| **C6** | 空态文案两类区分 | 列表页已做，关系授权页部分未做 | 补齐 |
| **C8** | 占位页与文档一致 | `/dashboard`、`/admin/users/:userId` 指向 `PlannedWorkView` | **保持占位**，直到后端实现 `docs/api-design.md` 8.3/8.5 |
| **C9** | `user-list-*` 私有样式前缀 | `UserListView` 仍带 `user-list-data` / `user-list-page`，`main.css` 里有 10 条 `.user-list-*` 规则 | 属独立一轮的样式收敛，不在本轮范围（C3 只处理弹窗） |

**已达标、不要再动**：五个管理页的四区结构、宽度策略（`--fd-admin-list-max` 跟随工作区）、按钮语义色、操作列纯图标 + tooltip + aria-label、分页宽窄屏两套 layout、表格列宽无单位像素数、六态落点。

---

## 10. 脚手架（已实现）

```bash
# 在 frontend/ 下执行
node scripts/scaffold.mjs \
  --layer admin --page NoticeListView --title 通知管理 \
  --api notices --permission RBAC_MANAGE --api-section 8.7
```

可选：`--description` / `--empty` / `--id-field` / `--force` / `--dry-run` / `--help`。

| 项 | 决定 | 理由 |
| --- | --- | --- |
| 形态 | `frontend/scripts/scaffold.mjs` + `frontend/scripts/templates/page/` | 用 Node 内置能力做占位符替换，**不新增依赖**（plop / hygen / yeoman 都会带 devDependency） |
| 模板 | 列表页一套（`ListView.vue.tpl` / `ListView.test.ts.tpl` / `api.ts.tpl`） | 覆盖最高频的 A 类；B/D/E/F 类差异大，留人工 |
| 生成物 | `<Xxx>View.vue`、`<Xxx>View.test.ts`、`src/api/<领域>.ts` | 与现有页面同构，生成即可跑闸门 |
| 不生成什么 | **不动** `router/index.ts`、**不动** `constants/authorization.ts` | 两处都需要人判断（权限是"任一"还是"全部"、`activeRouteNames`），自动改容易悄悄弄坏导航与面包屑——脚本只把该贴的片段打印出来 |
| 模板来源 | `RoleListView.vue` 一系 + 本轮新抽的 `AppPagination` | 模板必须来自**上过生产、过了闸门**的真实页面；改模板前先改那一页，再把改动搬回来 |

**验收标准（已实测）**：生成的页面在 `pnpm typecheck && pnpm lint && pnpm build` 下零告警，且自带的 6 项单测全绿——四区结构、六态、按钮语义、分页用法与 `RoleListView` 逐项一致。

**踩过的坑（写在这里避免重复）**：生成页面的用例必须先 `useAuthStore().user = <夹具>`，否则 `v-if` 把写操作按钮整块摘掉，失败会以"找不到按钮"的形式出现；筛选区与弹窗里的名称输入 placeholder 相同，取元素必须限定在 `.el-dialog` 内。

---

## 11. 提交前自检清单（打印出来用）

- [ ] 页面属于 §2 的哪一类？骨架与 §3 一致吗？
- [ ] 有 `docs/api-design.md` 的对应契约吗？没有 → 停。
- [ ] 四态齐：loading / error（含重试）/ empty（两类文案）/ ready
- [ ] 无权限：路由 `meta.permission` + 页面内收口，后端仍是最终边界
- [ ] 窄屏 375：不横向溢出、分页收敛、操作列可用
- [ ] 工具栏：左边只放写操作，右边只放刷新类（排序/范围在筛选卡）
- [ ] 操作列：只有图标 + `aria-label` + `el-tooltip`
- [ ] 列宽：无单位像素数，集中成 `COLUMN` 常量
- [ ] 数字：`tabular-nums`；日期：`formatDateTime`
- [ ] 错误文案：走 `describeError`，页面里没有中文错误串
- [ ] 所有值都是 `var(--fd-*)` / `var(--el-*)`，没有 `#hex`、没有魔法数字
- [ ] 没有 `<style>`、没有 `:deep()`、没有新依赖
- [ ] token 仍在内存（没碰 localStorage/sessionStorage/Cookie）
- [ ] `pnpm typecheck` / `pnpm lint` / `pnpm build` 退出码 0（贴命令与结果）
- [ ] 1440 与 375 截图各一张，看过并写进报告；无溢出、无错位、无默认蓝残留
- [ ] 报告区分"已验证 / 未验证"

---

## 12. 与其他文档的关系

| 文档 | 管什么 | 本文件与它的关系 |
| --- | --- | --- |
| `frontend/AGENTS.md` | 硬规则（token、六态、安全、反 AI 味、视觉决策契约） | **上级**。本文件不重复其条文，只把它落到页面结构上 |
| `.ui-craft/brief.md` | 产品身份、设计意图、learned constraints | **上级**。外观冲突以它为准 |
| `.ui-craft/tokens.md` | token 决定与理由 | 数值仍以 `tokens.css` 为准 |
| `.ui-craft/surfaces/*.md` | 各 surface 的 Craft Read / signature / 六态落点 | 页面级设计决定在那，本文件只管结构一致性 |
| `docs/api-design.md` | 接口契约 | 页面必须能指出对应章节 |
| `docs/implementation-plan.md` | 阶段与 backlog | 决定"现在该做哪一页" |
| 模板工程 `YeJuZhi-Vue-CPY/plus-ui` | 骨架来源 | **只读参考**，不构成依赖、不复制代码 |
