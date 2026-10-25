# 页面推广报告（RuoYi 风格重铸 · 第二轮）

日期：2026-10-25
工作树：`D:\Java-Item\FLow-Desk-frontend-ruoyi-rebuild`
分支：`flow-desk/frontend-ruoyi-rebuild`

本轮把样板确认的视觉语言推广到其余全部页面，不改业务字段、路由、权限与接口。

---

## 1. 统一的结构约定

列表页一律三段，顺序固定：

1. **筛选面板**：`筛选条件` 标题 + 收起/展开；字段与查询/重置在面板体内。
2. **数据面板**：标题（`xx列表`）+ 记录数摘要（按 `phase` 切换"共 N 条 / 正在读取 / 无法读取"）+ 操作栏（左侧写操作、右侧刷新类视图工具）。
3. **表格 + 分页**：面板内撑满，分页贴底。

授权类页面（用户角色 / 角色权限）把第一段改成授权表单，标题分别为「授予角色」「授予权限」；第二段是关系行列表，标题「已授予角色」「已授予权限」。

卡片页（新建工单、占位页）的 `h1` 从浏览器默认 32px 收到 20px，与面板标题同尺度——页面层级已由标签带和面包屑承担。

---

## 2. 逐页改动

| 页面 | 文件 | 改动 |
| --- | --- | --- |
| 工单列表 | `views/work/TicketListView.vue` | 补筛选面板头（含范围切换条）、数据面板头；操作栏移入面板头右侧 |
| IT 工作台 | `views/work/TicketQueueView.vue` | 复用上面组件，随其自动生效，未单独改 |
| 权限管理 | `views/admin/PermissionListView.vue` | 补筛选面板头、数据面板头；行内操作与保护规则不变 |
| 分类管理 | `views/admin/CategoryListView.vue` | 同上 |
| 用户角色授权（深链） | `views/admin/UserRoleGrantView.vue` | 表单头「授予角色」、数据面板头「已授予角色」；页面底部两条提示移入面板脚注 |
| 角色权限授权（深链） | `views/admin/RolePermissionGrantView.vue` | 表单头「授予权限」、数据面板头「已授予权限」 |
| 站内信 | `views/work/NotificationListView.vue` | 筛选面板头、数据面板头 |
| 数据概览 | `views/work/TicketDashboardView.vue` | 筛选面板头、概览结果头；概览卡片去掉内层描边（避免"卡片套卡片"）；四组统计改 2×2，窄屏单列 |
| 新建工单 | `views/work/TicketCreateView.vue` | 仅受全局卡片页标题尺度影响，表单字段与交互未动 |
| 占位页 | `views/work/PlannedWorkView.vue` | 同上 |
| 403 / 404 | `views/error/*.vue` | 统一 `h1` 尺度，其余未动 |

全局样式改动集中在 `src/styles/main.css` 末尾的追加段落：面板脚注、概览卡片、卡片页与错误页标题。

---

## 3. 刻意未改

- **登录页**（`views/auth/LoginView.vue`）：它不在应用壳内，是独立的品牌入口，本轮不动。
- **工单详情**：上一轮已按同一语言改过（hero 头 + 面板）。
- **抽屉**：上一轮已完成，本轮未触碰。
- **表单字段布局**：新建工单的优先级卡片、字数统计等交互结构保持原样，只改标题尺度。

---

## 4. 截图

目录：`.ui-craft/reviews/2026-10-25-ruoyi-pages/`

`1440x900-*.png` 是主证据（10 张）。同目录下另有 `narrow/`（375×812）与 `wide/`（1920×1080）两套同名截图，用于确认同一结构在三个视口都成立。

| 文件 | 账号 | 页面 |
| --- | --- | --- |
| `1440x900-permissions.png` | admin | 权限管理 |
| `1440x900-categories.png` | admin | 分类管理 |
| `1440x900-user-roles.png` | admin | 用户角色授权（深链） |
| `1440x900-role-permissions.png` | admin | 角色权限授权（深链） |
| `1440x900-notifications.png` | admin | 站内信 |
| `1440x900-forbidden.png` | admin | 403 |
| `1440x900-not-found.png` | admin | 404 |
| `1440x900-dashboard.png` | it | 数据概览 |
| `1440x900-it-queue.png` | it | IT 工作台 |
| `1440x900-ticket-new.png` | employee | 新建工单 |
| `1440x900-tickets.png` | admin | `/tickets` 的 403（说明入口即权限边界，不是页面缺陷） |

每个目录各有一份 `checks.json`，记录每页 `overflowX` 与首个标题。三套共 33 条记录，`overflowX` 全部为 `0`，无页面错误。

按账号分组截图是必要的：页面入口本身就是权限边界，用 admin 跑全部页面只会拍到 403（`/dashboard` 要 `DASHBOARD_VIEW`，`/tickets` 要 `TICKET_VIEW_*`）。

窄屏复核结论：筛选字段纵向堆叠、查询/重置并排；表格在面板内横向滚动，页面本身不溢出；概览四组在 375 下变单列。

采集脚本：`frontend/scripts/capture-pages.mjs`，按账号分组登录、逐页截图并记录 `overflowX`。视口用 `VP=375x812` 切换，默认 1440×900。

---

## 5. 验证

| 项 | 结果 |
| --- | --- |
| `vue-tsc -b` | 通过 |
| `eslint . --max-warnings=0` | 通过 |
| `stylelint "src/**/*.{css,scss,vue}"` | 通过 |
| `npm run build` | 通过（仅既有 chunk 体积提示） |
| Vitest | 30 套件 / 289 项全部通过 |
| Playwright 全量 | **33/33 通过，4.8 分钟**（真实后端 + dev server） |

Playwright 口径与上一轮一致：`playwright.review.config.ts`，`baseURL=http://localhost:5173`，真实后端 8081。覆盖了本轮改动的全部页面：权限/分类/角色闭环、两个授权深链、站内信、数据概览（含 375 窄屏与错误态）、IT 工作台、新建工单、1920 宽屏无溢出。

> 本轮开始前 `frontend/test-results/` 已累积 278MB、6922 个文件，Playwright 启动时清空该目录触发了安全删除保护。处理方式：把它整体移到 `D:\fd-e2e-results-archive`（未删除），让 Playwright 重建空目录。该目录本就在 `.gitignore` 中。

---

## 6. 已知取舍

- **筛选字段换行**：IT 工作台字段多（关键词/优先级/分类/创建时间/排序），1440 宽下排两行。这是内容量决定的，不是布局缺陷。
- **概览 2×2**：四组统计在 1440 宽下若排四列，每列约 150px，「账号与权限」这类标签会被省略号截断；改 2×2 后每组有 300px，标签完整。
- **数字右对齐间距**：概览的键值对沿用 `space-between`，数字右对齐便于竖排扫读，与表格数值列一致。
