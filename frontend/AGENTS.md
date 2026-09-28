# 前端规范（Vue 3 + Element Plus）

## 管理页统一视觉语言（2026-09-28 已推广到五页）

做管理页视觉改版前，必须读取仓库根 `.ui-craft/frontend-redesign-handoff.md` 和 `.ui-craft/references/README.md`，用图片工具查看已保存原图，无需用户再上传。主参考为 `02-admin-list-primary.png`，负面基线为 `03-flowdesk-before-rejected.png`。管理列表改为利用工作区宽度、明确筛选与数据分区、恢复合理控件尺度；旧70rem居中大卡片方案不再作为要求。下文“卡片只用于同级集合项”不得被误读成禁止有明确职责的筛选面板与数据面板。signature 不要求装饰或刻意布局突破；保护标记不能替代视觉验收。安全与契约规则继续有效。

**五页现状（2026-09-28）**：用户管理页 09-27 打样、09-28 按用户要求改成有色按钮与图标行操作；其余四页（角色、权限、用户角色授权、角色权限授权）经用户同日明确授权按同一套语言推广，全部改用 `AppPage layout="list"`、独立的筛选/授予面板 + 单一数据面板、默认尺寸控件、语义色按钮与统一分页。**用户逐项视觉反馈仍未取得**，本轮是"授权推广"而不是"审美验收"；证据见仓库根 `.ui-craft/reviews/2026-09-28-admin-pages/report.md`。

管理端五页的共享约定（改页面前先照此对齐，不要再各页自创）：

- 结构：`AppPage layout="list"`；筛选/授予区 `.admin-filter-card`（授权页加 `.grant-form`）；数据区 `.admin-data`，内部依次是 `.admin-action-bar`、`AdminListPanel`、`.admin-pagination`。工具栏、表格/关系行、分页同属一个面板。
- 宽度：`.admin-data` 与筛选卡跟随工作区宽度（`--fd-admin-list-max`），不再有居中上限。
- 按钮语义：primary = 查询 / 新增 / 授权；success = 编辑 / 保存 / 启用；danger = 删除 / 停用 / 撤销；warning = 重置密码等需提醒的操作；info = 重置筛选 / 刷新 / 取消 / 次级导航。次操作加 `plain`。
- 操作列：**只显示图标**（`plain` + `circle`），必须同时有 `aria-label` 与 `el-tooltip`；查询、工具栏、弹窗按钮保留图标配文字。
- 分页：宽屏 `total, sizes, prev, pager, next, jumper`，窄屏用 `useCompactPagination()` 收敛为 `total, prev, pager, next`；不要把断点写成两份（阈值常量与 `main.css` 的 52rem 媒体查询需同步改）。
- 弹窗加 `class="admin-dialog"`，宽度由 `main.css` 限制在视口内。
- 禁用态与保护原因不能省：受保护对象仍需 `ProtectedMark` 与禁用入口，文案写"为什么不能动"，不写接口、编码常量或"后端裁决"。
- 表格内不写 `size="small"`：默认尺寸才是本次统一后的密度（行高走 `--fd-admin-row-height`）。

本文件是 `frontend/` 目录的实现约束，只管"怎么写前端"。协作方式、阶段边界和 Git 流程以仓库根目录的 `AGENTS.md` 为准；
两者冲突时先服从根文件，再回来修正本文件。

## 技术栈

2026-09-28 用户明确要求管理页按钮使用 Element Plus 有色按钮与 icon；五页按操作采用 primary/success/warning/danger/info 语义色，次操作用 plain，优先于旧单强调色和中性行操作约定。操作列只显示图标，保留 tooltip 与 aria-label；查询、工具栏、弹窗仍图标配文字。

- Vue 3.5 + `<script setup>` + Composition API + TypeScript + Vite 8 + pnpm。
- Element Plus 2.14（组件用 `el-` 前缀）。禁止 Vue 2 语法，禁止 Element UI（Vue 2 版）的 API。
- 状态用 Pinia，路由用 vue-router 5，网络用 `src/api/` 下已有的封装，不要另起一套 request 工具。
- 测试：Vitest（单测/组件）、Playwright（E2E）。用例放在被测文件同目录，命名为 `*.test.ts`。

## 目录分层

页面按**路由/领域**分层，不要全部平铺在 `src/views/` 根下：

| 目录 | 放什么 | 例子 |
| --- | --- | --- |
| `src/views/` 根 | 只有确实跨领域或跨外壳的页面 | 当前为空 |
| `src/layout/` | 登录后的应用壳：顶栏 / 侧栏 / 面包屑 / 主体四层的摆位，不放业务内容 | `index.vue` |
| `src/views/auth/` | 登录与身份入口 | `LoginView.vue` |
| `src/views/error/` | 框架级错误页 | `ForbiddenView.vue`、`NotFoundView.vue` |
| `src/views/work/` | 员工工单主链路 + shell 入口页 | 以后放工单列表/详情/新建；当前 `HomeView.vue`、`PlannedWorkView.vue` |
| `src/views/admin/` | 系统管理端 | 以后的用户管理、分类管理 |

规则：

- 新页面先判断它属于哪个现有分层，不要新开目录；确实需要新分层时先说明理由。
- 页面文件与其用例**同目录同名**（`HomeView.vue` 与 `HomeView.test.ts` 一起移动）。
- 路由里的 import 用 `@/views/<层>/<页面>.vue`；同一目录内的相对引用保持 `./<页面>.vue`。
- 移动页面时用 `git mv`（未跟踪的文件用普通移动），保证重命名历史可追溯。

### `api/` 与 `components/` 暂不分层

刻意保持扁平，理由与触发条件如下：

- **`components/`**：已由 `App*` 前缀区分用途，但要注意 `App*` 内部其实有两类，别混着数：
  - **外壳组件**（由 `layout/index.vue` 直接消费；`App.vue` 只做"已登录进壳 / 未登录走路由"的分流）：
    `AppHeader`（顶栏账户区）、`AppSidebar`、`AppBreadcrumb`。约定：外壳组件必须用 `App*` 前缀。
  - **共享页面骨架**：`AppPage`（标题 + 内容槽），由页面消费，目前只有 2 个页面用它。
  - 其余为通用或功能性组件：`EmptyState`（3 处消费）、`ChangePasswordDialog`（当前仅 `AppHeader` 消费）。
  触发分层：当外壳组件超过 8 个、**或**通用组件超过 8 个（单层扫读开始失效）时，再拆
  `components/shell/` 与 `components/common/`，同时给 `AppPage` 定归属。现在拆的代价是：它会被同时
  归到两处，比现在更难找——目前一个 `ls` 就能看全 6 个文件，拆分只增加跳跃。
- **`api/`**：`http.ts` 被 6 个文件依赖，且它**自身承担认证语义**（Bearer 注入、401 单次刷新协调、会话失效回调）
  ——与 `auth.ts` 强耦合，所以不适合先抽 `api/core/`：那会把"HTTP 层懂认证"这个必须显眼的事实藏起来。
  契约类文件（`pagination.ts`）也不值得单独一层。
  触发分层：当出现**第二个领域模块**（如 `tickets.ts`）时，再拆 `api/`（基础设施）与 `api/<领域>.ts`，
  并同时决定 `errorMessages` 是否同步下沉——它当前已带认证语境的错误覆盖，是最可能先膨胀的一个。
  **触发已于 2026-09-22 发生并已裁定（`TASK-060`，RBAC 是第二个领域模块）：保持扁平，不拆。**
  只新增 `api/rbac.ts`，不建 `api/core/`——理由就是上面那条，拆分会把"HTTP 层懂认证"藏起来，
  而领域文件与基础设施文件已由命名区分。`errorMessages` **不下沉**，继续单一映射表；
  按领域拆会让"某个错误码的文案在哪"取决于你是否知道它属于哪个领域。决定与理由见 `docs/modules/rbac.md` 12.3。

## 设计 token（唯一真源）

- 所有颜色、间距、字号、圆角、阴影、动效时长，必须引用 `src/styles/tokens.css` 里的 `--fd-*` 变量。
- 禁止在页面与组件里写死十六进制色值，禁止 `13px` 这类"魔法数字"；刻度不够用时先往 tokens.css 加刻度，再使用。
- 品牌色与语义色的字面量只在 `src/styles/element/var-override.scss` 出现一次。EP 的 light-x / dark-2 色阶
  由 SCSS 在编译期用主色计算，所以改主色只改那一个文件，`tokens.css` 通过 `--el-color-primary` 自动跟随。
- 覆盖 Element Plus 主题只改 CSS 变量映射（`tokens.css` 末尾的 `--el-*` 段），不改组件内部选择器，不用 `:deep()`。
  确实无法用变量解决时，先在 review 里说明原因再改。
- `tokens.css` 必须在 `element-plus` 样式之后引入（`src/main.ts` 已按此顺序排列），否则 `--el-*` 覆盖不生效。
- 数字一律加 `font-variant-numeric: tabular-nums`，避免表格与指标跳动。
- 动效只动 `transform` 与 `opacity`，时长用 `--fd-duration-*`、缓动用 `--fd-ease-standard`；禁止 `transition: all`，必须列出具体属性。
- 以上不是口头约定：`pnpm lint` 已包含 `stylelint`（规则见 `frontend/stylelint.config.js`），
  颜色 / 间距 / 字号 / 圆角 / 阴影 / 动效六个轴的属性，值里必须出现 `var(--fd-*)` 或 `var(--el-*)`。
  字面量的合法归宿只有 `tokens.css` 与 `styles/element/var-override.scss` 两个文件；其余位置确实无法
  用变量解决时，用 `stylelint-disable-next-line declaration-property-value-allowed-list -- 理由`
  行内豁免并写明理由，**不要直接改配置放宽规则**（规则为何这样定见 `.ui-craft/tokens.md` 第 9 条）。

## 硬规则（违反即重做）

- 页面必须实现 loading / empty / error / disabled / 无权限 / 窄屏 六种状态；空态复用 `components/EmptyState.vue`，
  页面骨架复用 `components/AppPage.vue`，不要各自造一套。
- 前端权限只用于界面显隐：路由 `meta.permission` 决定入口是否展示、是否跳 `/403`；后端始终是最终授权边界，
  不能因为界面藏了入口就认为接口安全。
- Access Token 只放在 Pinia 内存里，禁止写入 localStorage / sessionStorage / Cookie；刷新身份依赖 HttpOnly Refresh Cookie。
- 接口错误文案统一走 `api/errorMessages.ts` 的稳定错误码映射，不要在页面里散写错误字符串。
- 每个页面必须能解释它对应 `docs/api-design.md` 里的哪个接口；没有契约就先别写页面，不要自己发明字段。
- 卡片只用于同级集合项；禁止把每个 section 都包一层圆角卡片。
- 一个强调色，首屏最多 3~5 处使用；禁止两个同饱和度强调色并列。
- 时序趋势用折线/面积图，禁止饼图和 3D 图表。当前 MVP 没有图表需求；将来要接图表时必须用真实 ECharts
  （新增依赖需先确认），禁止手写 SVG / CSS 柱形 / 静态截图冒充图表。
- 图标用 `@element-plus/icons-vue` 或统一的一套 SVG（新增依赖需先确认），禁止用 emoji 当功能区图标。
- 输入框 / 数字框 / 文本域用"请输入…"；选择器 / 日期 / 树选择用"请选择…"。有默认值就显示真实默认值，禁止用 placeholder 冒充。
- 必填项标红色星号；失焦校验当前字段，提交时校验全部并阻止无效提交。
- 详情页相邻模块间距统一用 `--fd-space-4`。
- 表格列宽写**无单位像素数**：`el-table` 内部用 `parseInt` 解析 `width` / `min-width`，写 `width="7rem"` 只会得到 `7px`——
  列被压成一条、行高被逐字换行撑到数百像素。需要贴合刻度时按 `1rem = 16px` 换算，并把列宽集中成一个 `COLUMN` 常量。
  另：表格所在的网格容器要允许收缩（`min-width: 0`），否则表格的 min-content 会在窄屏把整页撑宽。
- 顶部栏（`components/AppHeader.vue`）禁止用纯黑底；用背景色微差区分层级。若将来引入侧边栏，同样禁止全黑。

## 反 AI 味禁列表

- 紫青渐变铺满、深色玻璃拟态 + 霓虹描边。
- 完全相同的卡片网格重复 3~6 次。
- 标题、导航、按钮全大写。
- 弹性 / 回弹缓动曲线（`cubic-bezier` 带过冲）。
- 所有元素统一圆角；圆角要按层级区分（`--fd-radius-sm/md/lg/xl`）。
- 趋势百分比用彩色胶囊，改成普通次要文字。
- 卡片粗彩色左 / 上边框，改用阴影或背景微调。
- 模糊渐变色块 / 光斑 / 装饰性大圆。
- 每个 section 标题上都加全大写 eyebrow：三个 section 最多用 1 次。
- `div` 拼出来的"假产品截图"。

## 视觉决策契约

反 AI 味禁列表是地板：它保证页面不像 AI 生成的，不保证页面像被设计过。
**克制是地板，不是目标**——克制之上必须有一次明确的视觉决定，否则负向禁列只会把页面收敛到
"安全且平淡"。以下五项是每个新页面、以及每次重做页面的必交产出。

### 必须交出的五项（缺一即未完成）

1. **Craft Read（写代码之前，一行）**
   `[surface kind] for [受众], [product | marketing] 语言, [强调色], variance [N], signature bet: [选择]`
   它先于任何构图，不接受事后补写。
2. **DESIGN_VARIANCE（数值 + 落点）**
   登录 / 表格 / 设置 / 表单类 4；dashboard 类 4；营销页 7。
   声明了 4 就必须指出这一页唯一的一处布局突破是什么；一处都没有，说明实际方差是 1。
3. **one signature bet（每页有且仅有一个）**
   从 ui-craft `craft-intent.md` §3 的清单里选，并说明为什么它属于**这个**产品，而不是任何同类系统的
   默认产物。禁止用装饰性图形凑数。
4. **本轮唯一一次"正向视觉声明"**
   明确写出具体是哪一项：一个标记 / 一次排版尺度对比 / 一个非对称决定 / 一处结构分组。
   并说明这个产品为什么需要它。
5. **参照物（至少 3 个）**
   每个写成"借什么、不借什么"。借的是构图或排版判断，不是功能。找不到参照物就直说"没有参照物"，
   由用户定方向，不要自行发明。

### 同时必须列出

- 按页面对应的 ui-craft recipe 自检清单，逐条标注现状。
- **这一页最容易让它平淡的三个默认选择**（例：50/50 对称、"eyebrow + 标题 + 灰色小字"堆叠、
  胶囊标签、居中单卡），以及本轮放弃了哪一个。

### 交付时必须包含

- 一张 Craft Report 表：`Before / After / Why`，≤8 行。
- **三抄测试**：列出 3 个"同类别另一个产品不能原样照抄"的决定；答不出 3 个即未完成。
- 截图前自查两条：**缩略图测试**（眯眼看，视线第一落点是不是主操作）、
  **强调色计数**（首屏 ≤5 处）。
- 沿用「工作流」第 5 条的报告格式，区分"已验证 / 未验证"。

### 节流规则（防契约通胀）

- 只动文案或补测试的改动，不走本节。
- 沿用既有构图、仅新增一个模块，或只做响应式适配时，可只交第 5 项与三抄测试。
- 本节不覆盖任何已确认的硬规则与授权边界：Access Token 仍只在 Pinia 内存、刷新仍走 HttpOnly
  Refresh Cookie、权限只用于界面显隐、后端始终是最终授权边界。

### 两条自查

- **反 AI 味测试**：如果有人说"这是 AI 做的"，对方会不会立刻相信？会，就重来。
- **三抄测试**：把这一页和同类别另一个产品并排，哪 3 个决定是对方无法原样抄走的？

## 工作流

1. 先读现有代码与 `docs/` 相关契约，follow 项目已有约定，不要贴通用示例或重新发明目录结构。
2. 新增或重做页面前，先说明它对应的接口、权限和六种状态的落点。
3. 改完必须跑：`pnpm typecheck`、`pnpm lint`、`pnpm build`；涉及接口或身份流程时补 `pnpm test:unit --run`、`pnpm test:e2e`。
4. 涉及视觉的改动，必须用浏览器打开改后的页面截图自查（登录用例见 `PROJECT_STATUS.md` 的演示账号），
   确认无溢出、无错位、无默认蓝色残留后再报告完成，并在报告里说明截图看到的结果。
5. 报告时区分"已验证"与"未验证"：跑过的命令贴命令与结果，没跑的要明说。

## 设计参照（锚）

审美没有唯一答案，但要有参照物。做新页面前先对齐这两个项目的"克制感"，而不是对齐它们的全部功能：

- [`kailong321200875/vue-element-plus-admin`](https://github.com/kailong321200875/vue-element-plus-admin)（看 `mini` 分支）
  借：Element Plus 组件的常规用法、表格 + 查询表单 + 分页的布局节奏、主题变量的组织方式。
  不借：演示性质的密集卡片网格、多套布局切换、为了展示组件而堆的示例页。
- [`youlaitech/vue3-element-admin`](https://github.com/youlaitech/vue3-element-admin)
  借：路由与权限骨架的克制做法、页面容器和间距的一致性。
  不借：大而全的菜单堆叠。

组件 API 以 [Element Plus 官方文档](https://element-plus.org/zh-CN/) 为准；需要"更好看"时优先调整
`tokens.css` 的刻度（间距、字号、圆角、阴影），而不是给单个页面写特例样式。

参照物给的是地板：对齐以上两个项目的"克制感"即可，不要对齐它们的全部功能。
**克制之上必须有一次明确的形状、标记或排版决定**，具体走「视觉决策契约」；只有克制没有决定，
结果就是"简单、正常布局"。

## 设计上下文

**仓库根的 `.ui-craft/`（不在 `frontend/` 下）** 保存跨会话的设计上下文：`brief.md`（产品身份、设计意图、已记录的设计纠正）、
`tokens.md`（token 决定与理由）。**动手做页面前先读 `brief.md`**，其中的 learned constraints
与本节硬规则同级生效。注意：token 数值的唯一真源始终是 `src/styles/tokens.css`，
`.ui-craft/` 只记决定与理由，不复制数值，避免两处漂移。
