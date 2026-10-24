# 阶段 3 步骤④：IT 页面 + 员工确认按钮 + Playwright 端到端主链

日期：2026-10-06
分支：`flow-desk/ticket-it-flow`
范围：`frontend/` 前端实现 + 一处后端 `allowedActions` 修正（用户当场授权）+ 端到端用例

---

## 1. 结论

阶段 3 步骤④ 的页面与主链**已在真实栈上跑通**：员工提交 → IT 领取 → 记录处理 → 提交解决 → 员工确认，
两个真实账号在真实浏览器里交替操作同一张工单，界面状态依次为
`待受理 → 处理中 → 待员工确认 → 已完成`。

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 后端回归 | `.\mvnw.cmd -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"` | 单元/Web **394**、集成 **88**，失败/错误 0，`BUILD SUCCESS`，退出码 0 |
| 前端类型 | `pnpm typecheck` | 退出码 0 |
| 前端 Lint | `pnpm lint`（含 stylelint 闸门） | 退出码 0 |
| 前端构建 | `pnpm build` | 退出码 0（仅有既有的大 chunk 提示） |
| 前端单测 | `pnpm test:unit --run --maxWorkers=1` | **23 套件 / 146 项**全绿（含本轮新增 7 项动作用例） |
| 端到端 | `pnpm test:e2e`（真实栈 MySQL 3308 / Redis 6380 / 后端 8081 / preview 4173） | **16 项**全绿，其中新增主链 1 项 14.6s |

## 2. Craft Read 与设计决定（`frontend/AGENTS.md` 视觉决策契约）

**Craft Read**：工单详情（IT 与员工两种视角），企业内部支持人员与普通员工，product 语言，
项目蓝（`--el-color-primary`）一个强调色，variance 4，
signature bet：**把"现在可以做的操作"做成详情页第一个面板，每个动作一行「按钮 + 这个动作的后果」。**

1. **DESIGN_VARIANCE = 4 的唯一落点**：动作区不是"又一个 panel"。正文与时间线都是先标题后内容，
   动作区反过来——**按钮在最左，后果说明吃掉剩余宽度**，两列网格。主操作的位置不随说明文字长短左右移动，
   一屏里视线第一落点固定是那个按钮。
2. **one signature bet 为什么属于这个产品**：FlowDesk 的动作都有后果（领取=我负责、记录=进时间线、
   提交解决=对方要确认、确认=终态）。同类系统默认把动作收进"更多"下拉或只给按钮文字；
   这里每个动作**必须**带一句后果说明才允许出现，因为 IT 点击前要判断的不是"这个按钮叫什么"，
   而是"点了之后谁欠谁一个动作"。
3. **本轮唯一一次正向视觉声明**：**同一个期限字段，对两种人给两个标签**。
   `actionDeadlineAt` 对负责人是"等员工确认到什么时候"（`当前期限`），
   对提交人是"我要在什么时候之前确认"（`确认期限`）。判定依据恰好是服务端返回的动作——
   看得见 `confirm-resolution` 的人就是提交人。这不是文案微调，是让一个字段对不同角色说不同的话。

**参照物（借什么 / 不借什么）**

| 参照 | 借 | 不借 |
| --- | --- | --- |
| Help Scout（`.ui-craft/references/04`） | 工单详情"主体 + 属性侧栏"两轨、动作在正文之前 | 彩色会话气泡与头像墙 |
| Freshservice（`.ui-craft/references/05`） | 属性栏用"标签 + 值"两列、窄屏改上下排列 | 密集的字段面板与状态胶囊 |
| Admin 列表主参考（`.ui-craft/references/02`） | 工具栏/表格/分页同属一个面板、利用工作区宽度 | 把每个 section 都套一张圆角卡 |

**这一页最容易平淡的三个默认选择**（本轮放弃了第 1 个）

1. ~~把动作收进行尾"更多"下拉~~ → 改成第一个面板里一行一个动作。
2. 给动作区配彩色标题或 eyebrow → 保留为普通 `h2` 加一句来源说明。
3. 把状态做成彩色胶囊标签 → 沿用已有的圆点 + 文字（`ticket-mark`）。

**三抄测试**（同类别另一个产品不能原样照抄的三个决定）

1. **按钮只由服务端 `allowedActions` 决定**，前端再用本账号权限收口一次；
   登记表里存在但服务端没放行的动作（如「提交解决结果」在修正前）**不会**变成一个按不动的按钮。
2. **每个动作必须能说清后果**：说明文案是登记表 `TICKET_ACTIONS` 的必填字段，不是可选的 tooltip。
3. **期限字段按视角改写主语**（见上面的正向视觉声明）。

**截图前两条自查**

- **缩略图测试**：IT 详情页眯眼看，第一落点是动作区左上角的蓝色主按钮（「记录处理过程」）。通过。
- **强调色计数**：IT 详情首屏强调色 2 处——主按钮（品牌蓝）+ 终态动作用 warning 黄；
  员工详情首屏 1 处（「确认已解决」）。均 ≤5。

## 3. Before / After / Why

| Before | After | Why |
| --- | --- | --- |
| 详情页注释写"阶段 2 没有已实现的工单动作，不做动作区" | 按 `allowedActions` 渲染动作区，空数组时整块不出现 | 动作已经实现；按钮该由服务端说了算，而不是前端硬编码"没有动作" |
| 队列与「我负责的」混在员工工单页的四个范围里 | 新增 `/it/queue`「IT 工作台」，默认停在「待受理」 | 员工与 IT 的第一件事不同：一个看"我提交的"，一个看"待处理队列" |
| 切换范围会把 `?scope=` 永久写进地址 | 回到该路由的默认范围时把参数去掉 | 否则 IT 工作台看过一次「我提交的」之后，页头与默认范围再也回不去 |
| 动作成功后页面闪回骨架屏 | 静默重取详情与时间线（`silent`） | 用户刚点完按钮，页面闪一下比没有反馈更像出错 |
| 版本过期（409）只提示失败 | 提示 + 自动重取详情对齐版本 | 不重取就留着旧 `version`，用户再点一次还是同一个 409 |
| 正文为空时请求打到后端换一个 400 | `beforeClose` 拦下并提示，弹窗不关 | 用户写好的其它内容还在原处，改完直接再点一次 |
| 后端 `allowedActions` 缺 `submit-resolution` | `canSubmitResolution` 复用 `canProcess` 判定 | 接口早已实现并验收，详情不返回动作名就等于界面永远没有这个入口 |

## 4. 实测过程与踩到的真实缺陷

1. **`ElInput` 的 `onUpdate:modelValue` 是 prop 不是事件**：在渲染函数里写
   `h(ElInput, { 'onUpdate:modelValue': fn })` 只会得到一个普通 prop，输入不回流——
   表现是"怎么填都提交空内容"。单测里表现为 `content: ''`。
2. **运行时模板在生产构建里不会被编译**：把输入框写成 `template` 字符串 + `defineComponent` 时，
   开发环境（含单测）正常，`vite build` 后 `<el-input>` 渲染成一个占位注释，
   **确认框里只剩一行标签、输入框整个消失**。真实浏览器里才暴露；单测无法发现。
   最终改为独立的单文件组件 `TicketActionContentField.vue`（构建期编译），与仓库其它组件写法一致。
3. **`allowedActions` 缺 `submit-resolution`**（后端，见 §1 与 `docs/api-design.md` 6.3 的修正段）：
   端到端主链在"提交解决结果"这一步 `element(s) not found` 暴露。修复前无法从界面完成这条链。
4. **测试自身的三处假失败**（均在用例侧修正，不改业务断言）：
   - 确认框替身原先"先 resolve 再让用例填内容"，与真人顺序相反；改为真实挂载弹窗，按"打开 → 写 → 确认"驱动。
   - `ElMessage` 是追加式的，`.el-message` 会命中多条，Playwright 严格模式直接判失败；改用 `.last()`。
   - 375 视口下侧栏收进抽屉，`主导航` 不在可访问树里，导致"换账号登录"被误判为失败；换账号前先还原桌面视口。

## 5. 产物

- 用例：`frontend/e2e/ticket-it-flow.spec.ts`（主链，含 5 条带 `X-Trace-Id` 的证据与 1440/375 截图）
- 组件：`frontend/src/views/work/TicketQueueView.vue`、`TicketActionContentField.vue`；
  改动 `TicketListView.vue`、`TicketDetailView.vue`、`api/tickets.ts`、`constants/tickets.ts`、
  `constants/authorization.ts`（新增「IT 工作台」入口）、`router/index.ts`、`styles/main.css`
- 截图（本目录）：`it-queue-1440/375`、`it-detail-claim-1440`、`it-detail-processing-1440`、
  `it-detail-waiting-1440`、`employee-detail-confirm-1440`、`employee-detail-completed-1440/375`
- 运行时证据：本目录 `runtime-evidence.json`（五个步骤的状态码与 `traceId`、界面状态迁移、时间线、溢出量、页面错误）

## 6. 未验证 / 未做

- **用户逐项视觉反馈未取得**：本轮是"实现 + 自查"，不是审美验收。
- 未跑 1920 视口（本轮要求 1440 与 375）。
- 四个动作仍无单元/Web/集成用例（按用户指示本阶段不新增测试类）；行为证据来自真实栈端到端。
- 「报告未解决」「转交」「调整」等 6 个动作仍未实现，服务端也不返回，界面因此没有对应入口。
- 演示库：实测留下的 E2E 工单已按 §7 清理，只保留主链那张已完成工单作为可查看样本。

## 7. 演示库清理（本轮实测后执行）

```sql
DELETE FROM ticket_participant WHERE ticket_id IN (SELECT id FROM ticket WHERE title LIKE 'E2E%' AND ticket_no <> 'FD-20261006-026');
DELETE FROM ticket_record      WHERE ticket_id IN (SELECT id FROM ticket WHERE title LIKE 'E2E%' AND ticket_no <> 'FD-20261006-026');
DELETE FROM ticket             WHERE title LIKE 'E2E%' AND ticket_no <> 'FD-20261006-026';
DELETE FROM iam_user_role WHERE user_id IN (SELECT id FROM iam_user WHERE username LIKE 'E2E%');
DELETE FROM iam_user      WHERE username LIKE 'E2E%';
```

清理后实测：工单 **1**（保留的主链样本）、记录 5、参与者 1、分类 5、用户 **3**、角色 3。
`FD-20261006-026` 是完整走完 `PENDING → PROCESSING → WAITING_FOR_CONFIRMATION → COMPLETED` 的那一张，
保留它是为了让结果可被打开检查；若要与 `PROJECT_STATUS.md` 记录的"工单 0"基线完全一致，
再执行一次同样的 SQL 并把 `<> '...'` 条件去掉即可。
