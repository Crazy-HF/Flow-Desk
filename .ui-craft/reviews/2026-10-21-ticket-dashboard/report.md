# 数据概览页视觉与实现记录（2026-10-21）

分支 `flow-desk/ticket-dashboard`，基线 `main@58a59ce`。契约见 `docs/api-design.md` 8.5，设计与验收矩阵见
`docs/superpowers/specs/2026-10-21-ticket-dashboard-design.md`。

## 1. Craft Read

`[运营看板] for IT 支持人员, product 语言, 单一品牌蓝, variance 4, signature bet: 总数做成左栏的排版锚点，四组是同一张账本里的四列`

- **DESIGN_VARIANCE = 4（dashboard 类）**，落点：左窄右宽的非对称分割（13–17rem 的总数列 + 自适应四组列），
  而不是四张等宽卡片。声明 4 就必须指出这一处布局突破，它就是这一处。
- **one signature bet**：把"总数"与"四组"放进**同一个面板**，用一条竖分隔线与同一条行基线连起来。
  它属于这个产品，因为 8.5 的核心不变式是"四组之和等于总数"——并排四张卡片会让这层关系消失，
  用户只会看到四个互不相干的数字块。别的工单系统用卡片网格展示统计，是因为它们没有这条约束。
- **本轮唯一一次正向视觉声明**：总数用 `--fd-font-size-display`（44px）并独占左栏，页面上再没有第二个
  大字。理由同上：这一屏要回答的第一个问题是"有多少"。

**参照物（借构图判断，不借功能）**

| 参照 | 借什么 | 不借什么 |
| --- | --- | --- |
| `.ui-craft/references/02-admin-list-primary.png` | 筛选卡与数据面板分区的节奏、控件尺度、跟随工作区宽度 | 表格化的行结构、彩色批量按钮 |
| `.ui-craft/references/01-admin-role-reference.png` | 筛选区一行铺开、主按钮在右 | 开关列、导出 |
| `.ui-craft/references/04-helpscout-ticket-list.webp` | "左栏给范围、右栏给数量"的分栏直觉 | 会话列表与标签胶囊 |

**这一页最容易平淡的三个默认选择**：① 四张等宽统计卡；② 居中单卡 + 大标题；③ 百分比胶囊标签。
本轮放弃了 ①，也拒绝了 ③（百分比在 8.5 里被明确排除）。

## 2. Before / After / Why

| 项 | Before | After | Why |
| --- | --- | --- | --- |
| 路由 | `/dashboard` 指向 `PlannedWorkView` 占位页 | 真实页面 `TicketDashboardView` | 8.5 要求页面与接口同批交付 |
| 主体结构 | 无 | 左栏总数 + 右栏四列，同一面板 | 让"四组之和 = 总数"看得见 |
| 筛选 | 无 | 范围（按权限显隐）+ 创建时间 + 分类 + 查询/重置 | 与 8.5 的三个可选参数一一对应 |
| 分类选项 | 无 | 取自当前范围与时间下**未按分类过滤**的 `byCategory` | `/categories/options` 只有启用分类，会让停用分类的历史工单筛不出来 |
| 状态 | 无 | 加载骨架 / 空态 / 失败重试 / 无权限 / 无范围 / 窄屏 | 六态硬规则 |
| 导航 | 只按 `DASHBOARD_VIEW` 显示 | 追加 `IT_SUPPORT` 角色要求 | 只有权限码的多角色管理员点进去也只会 403 |

## 3. 三抄测试

同类别产品（GLPI / osTicket 的统计页）无法原样抄走的三处：

1. **范围是"工作视角"而不是筛选器**：待受理 / 我负责的 / 我参与的三种范围各自一条权限，界面只列本人可用的，
   并且切换范围会清掉分类筛选——抄一个"全部统计"按钮就破坏了这套授权与对账关系。
2. **总数与四组同源同快照**：五个聚合在一个只读事务里读同一份快照，页面因此可以只显示数字、不做二次求和，
   也不需要"实时"字样。抄页面抄不到这层事务语义。
3. **分类选项来自统计结果本身**：没有新增分类选项端点，而是用未过滤的 `byCategory` 组成选项，因此停用但仍有
   历史工单的分类也能被选中；抄一个"分类下拉"会默认只列启用分类。

## 4. 截图

| 文件 | 内容 |
| --- | --- |
| `dashboard-desktop-1440.png` | 1440×900，待受理范围，7 张工单，四组齐全（负责人组只有"未分配"） |
| `dashboard-mobile-375.png` | 375×812，筛选区纵向排列、四组两列，整页无横向溢出 |

截图由 `e2e/ticket-dashboard.spec.ts` 在 `pnpm preview` 上生成，接口为路由拦截的模拟数据（不含真实工单内容）。

## 5. 实跑命令与结果

**后端**（仓库根，`JAVA_HOME` 指向 JDK 21）

```text
./mvnw -B -o clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"
→ surefire 1302 / failsafe 215，Failures 0 / Errors 0，BUILD SUCCESS
```

本批新增：`TicketDashboardServiceImplTest` 15、`TicketDashboardControllerWebTest` 22、`TicketDashboardServiceIT` 17（Testcontainers `mysql:8.4.11`）。
`S3AttachmentStorageIT` 仍按 `Assumptions.abort` 未执行（`Tests run: 0`），不计入 215，也不视为通过。

**EXPLAIN**（2000 行 + `ANALYZE TABLE`，来自 `TicketDashboardServiceIT` 的实跑输出）

```text
queue        ref  idx_ticket_status_deadline          rows=40
assigned     ref  idx_ticket_assignee_status_updated  rows=59
participated ref  idx_ticket_participant_user_ticket  rows=1  → eq_ref PRIMARY
```

结论：三个范围的谓词都已被现有索引覆盖，**未新增索引、未新增迁移**。

**前端**（`frontend/`）

```text
pnpm typecheck            → 退出码 0
pnpm lint                 → 退出码 0（eslint + stylelint）
pnpm build                → 退出码 0
pnpm exec vitest run --maxWorkers=1 → 29 个文件 / 275 项通过
pnpm exec playwright test e2e/ticket-dashboard.spec.ts → 3 passed
```

Playwright 在 `pnpm preview`（4173）上运行，接口为路由拦截的模拟数据；1440 与 375 的整页横向溢出均为 0。
运行需带 `--output=<临时目录>`：默认的 `test-results/` 在文件数超过 50 时会被本机安全护栏拦下批量删除，
那是环境限制，不是用例失败。

## 6. 未验证 / 已知限制

- 真实后端 + 真实浏览器的联调未做：本轮浏览器验收用模拟接口（不依赖演示库），后端统计口径由真实 MySQL
  集成测试覆盖。两者没有在同一次会话里串起来跑。
- 分组不做点击钻取（8.5 明确不做）：工单列表路由只从地址读取 `scope`，带不上时间与分类条件。
- 窄屏下 `ticket-filter-range` 的收缩修复只作用于本页（`.ticket-dashboard-page` 作用域）。工单列表页同样
  使用 24rem 固定宽度的时间范围控件，理论上在 375 下也会溢出，但不在本次范围内，未修改、未验证。
