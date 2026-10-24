# 评审报告：工单主链路三页（2026-09-29）

## 最终实跑结论（2026-09-29）

**阶段 2 运行时验收通过。** 下方“无运行时证据 / 环境阻塞”段落保留为初始历史记录，已被本节取代；未取得用户逐项审美反馈不写成用户认可。

- 后端完整 verify：394 单元/Web + 88 集成，0 失败/错误。前端 typecheck/lint/build、23 套件 124 单测通过，完整 E2E 15 项通过；最终证据采集用例定向复跑 2 项通过。
- 防重复：延迟创建 POST 后双击，按钮禁用、仅一个创建请求与一个提交键；列表仅一匹配行、时间线一条创建；清理前 SQL 核对 9 次验收每张工单均一行、一条记录。
- 隔离：另建仅 EMPLOYEE 的真实账号，独立浏览器上下文登录；本人列表搜不到目标工单，直接详情 404/TICKET_NOT_FOUND，正文不展示。刷新后原员工身份、详情、时间线恢复。
- 分类：实际创建、修改名称、停用、启用、删除均成功，201/200 与 traceId 逐条保存。桌面与窄屏无整页横向溢出，表格内部允许水平滚动。
- 修复真实缺陷：日期范围控件固定 400px 将窄屏筛选网格撑宽 58px；限定工单页面网格为 minmax(0,1fr)，覆盖范围控件宽度并允许收缩后，375 溢出 0。没有调整业务 ServiceImpl。
- 浏览器 pageerror 0；有预期隔离 404。构建保留大 chunk 提示，不能写成所有控制台完全无提示。
- SQL 清理及基线已核对：5 分类、3 用户、3 角色、14 权限，工单/记录/参与者/日序号均 0，临时用户与分类 0、孤儿关系 0；临时 Redis 会话同步清理。
- 证据：[`stage2-closeout-20260929.json`](../../../docs/acceptance/stage2-closeout-20260929.json)、[`清理 SQL`](../../../docs/acceptance/stage2-cleanup-2026-09-29.sql)、[`基线核对`](../../../docs/acceptance/stage2-cleanup-evidence.json)、[`工单运行记录`](runtime-evidence.json)、[`分类运行记录`](category-runtime-evidence.json)。原始脚本 FAIL 不改写，最终汇总标明每个通过闸门的实际来源。
- 最后命令：`pnpm --dir frontend lint`、`pnpm --dir frontend build`、`pnpm --dir frontend test:e2e`；后续仅修改证据采集等待与 traceId 读取，`eslint e2e/tickets.spec.ts --max-warnings=0` 与 `test:e2e tickets.spec.ts` 再通过。后端/单测沿用同轮已通过的脚本输出，没有重复运行无关测试。

截图已实际查看：创建 [`1440`](ticket-create-1440.png) / [`375`](ticket-create-375.png)，列表 [`1440`](ticket-list-searched-1440.png) / [`375`](ticket-list-375.png)，详情 [`1440`](ticket-detail-1440.png) / [`375`](ticket-detail-375.png)，分类 [`1440`](category-list-1440.png) / [`375`](category-list-375.png)，[`另一员工详情 404`](ticket-isolation-404.png)。工单详情在窄屏上下排列、创建优先级竖排；截图中的测试分类与工单已清理。

## 以下为初始实现与阻塞历史记录


范围：阶段 2 `TASK-020`～`TASK-023-MVP` 的「新建工单 / 工单列表 / 工单详情」，以及被它们复用的
`api/tickets.ts`、`constants/tickets.ts`、`useSubmissionGuard.ts`。
代码：[`TicketListView.vue`](../../../frontend/src/views/work/TicketListView.vue) ·
[`TicketCreateView.vue`](../../../frontend/src/views/work/TicketCreateView.vue) ·
[`TicketDetailView.vue`](../../../frontend/src/views/work/TicketDetailView.vue) ·
设计契约与逐页 Craft Read 见 [`../../surfaces/work-tickets.md`](../../surfaces/work-tickets.md)。

> **本报告的核心结论：代码已实现，但没有任何运行时证据。**
> 执行环境里 shell 无法初始化进程（`pwsh` 全部调用返回 `[exit code: 3221225794]` = `0xC0000142`
> `STATUS_DLL_INIT_FAILED`，前台、后台任务、子 Agent 三条路径都实测失败，见第 3 节），
> 因此 `typecheck` / `lint` / `build` / 单测 / E2E / 截图**一次都没跑**。
> 下面的"已验证"一律只指**代码级静态核对**；按 ui-craft 的视觉证据门，**本页不宣称视觉通过**。

## 1. Craft Report：Before / After / Why

| Before | After | Why |
| --- | --- | --- |
| 三条工单路由指向 `PlannedWorkView` 占位页 | 三条路由指向真实页面 | 占位页只验证导航与权限，不解决任何真实工作 |
| 没有工单接口封装 | `api/tickets.ts` 收口四个端点 + 幂等键生成 | 契约只允许 `page`/`size` 与固定 `sort` 编码，散在页面里必然有人写错 |
| 列表没有"范围"概念 | 范围切换条按 `TICKET_VIEW_*` 显隐，只出现当前账号可用的范围 | `scope` 是必填参数，且后端**按范围分别鉴权**；混在一起会让人以为四种范围随时都能查 |
| 队列范围也给状态筛选 | 队列下隐藏状态筛选并在切换时清值 | 后端在队列上固定 `status='PENDING'`，留着它只会筛出空列表，是"能点但永远空"的假入口 |
| 优先级是下拉即可 | 三个带后果说明的选项块（窄屏竖排） | 优先级决定 IT 先看哪一张，用户需要看到后果才能选对 |
| 提交失败只能再点一次 | 提交条显示"提交编号 xxxxxxxx"并说明重试不会重复建单 | 创建没有可校验版本，防重全靠幂等键；不告诉用户，他不敢重试，只好刷新重填 |
| 时间线用页码翻页 | 时间线用"加载更多"向后追加 | 记录按 `sequenceNo` 正序，是业务事实的顺序；页码跳转会把已读上下文顶掉 |
| 详情按 `allowedActions` 预留按钮位 | 阶段 2 不渲染任何动作按钮 | 没有已实现的工单动作，摆出按不动的按钮比没有按钮更糟 |
| 新建工单没有"无权限"状态（只能靠路由守卫） | 页面自己也会显示"当前账号不能创建工单" | `frontend/AGENTS.md` 把"无权限"列为每页必做六态之一；守卫只在导航时执行，用户停在页面上被撤权时表单还能填、提交后才 403 |

## 2. 静态核对做了什么（可复核）

- **字段名逐一对照后端 record**：`TicketListItemResult`、`TicketDetailResult`、`TicketRecordResult`、
  `TicketUserSummaryResult`、`TicketCategorySummaryResult`、`TicketCreatedResult` 与
  `api/tickets.ts` 的六个接口逐字段一致（Jackson 按 record 组件名序列化，拼错即运行时 undefined）。
- **核对到一处"类型与响应不完全一致"的事实并记录（未改类型）**：后端配了
  `spring.jackson.default-property-inclusion=non_null`（`src/main/resources/application.yml:29`），
  值为 null 的字段在 JSON 里**根本不出现**，前端拿到的是 `undefined` 而不是 `null`。
  现有四个 `api/` 模块（`users.ts` / `rbac.ts` / `categories.ts` / 新增的 `tickets.ts`）
  都把这类字段写成 `| null`，属于"能用但不精确"：只要判空用真值判断或 `?.` 就安全，
  一旦有人写 `'assignee' in row` 就会拿到错误结论。本次**按既有约定保持一致**，
  只在 `api/tickets.ts` 的接口注释里写明这条规则，并把三个终态文案函数的入参放宽到
  `string | null | undefined` 以防万一。是否把四个模块统一改成可选类型，留给用户决定。
- **集合筛选参数**：确认 axios 1.20 默认 `paramsSerializer` 以 `indexes: false` 调 `toFormData`，
  数组会变成 `status[]=PENDING`
  （`node_modules/axios/lib/helpers/toFormData.js:223`），而 Spring 绑定的参数名是 `status`——
  用数组会让筛选**静默失效**（接口照常 200）。故拼成逗号分隔单值，靠 `StringToCollectionConverter` 拆分。
- **队列固定状态**：确认 `TicketMapper` 的 `PENDING_QUEUE` 分支是 `WHERE t.status = 'PENDING'`
  （`src/main/java/com/flowdesk/ticket/mapper/TicketMapper.java:79-81`）。
- **Element Plus 用法逐个查过 d.ts**：`el-radio` / `el-radio-button` 用 `value`（2.14 已把绑定值从
  `label` 迁到 `value`）；`el-date-picker` 的 `modelValue` 含 `string[] | null`；`el-select` 的
  `modelValue` 含数组；`el-input` 把 `aria-label` 透传到内部原生 `input`/`textarea`（测试按
  `aria-label` 定位依赖这一点）；`el-table` 的 `prop` 支持 `a.b` 路径（`getProp`）。
- **CSS 逐条过 stylelint 六轴规则**：所有颜色/间距/字号/圆角/阴影/动效值都引用 `var(--fd-*)`；
  无十六进制与具名颜色、无 `:deep()`、无 `transition: all`；`main.css` 大括号配平（1960 行收尾于媒体查询）。
- **静态自审改掉四处真实缺陷**：① 切换范围会发两次列表请求（`route.query` 的 watch 重复触发）；
  ② 详情正文用 `pre-wrap` 而插值被模板缩进包住，会多渲染出一段空白；③ 队列范围的一条用例断言了
  它从未进入的状态（IT 账号默认范围就是队列，状态筛选本来就是隐藏的）；④ 新建工单缺"无权限"状态
  ——`frontend/AGENTS.md` 把六态列为硬规则，而守卫只在导航时执行，用户停在页面上被撤权时仍能填表
  并在提交后才拿到 403。现已补上页面级状态（并在该状态下不发注定 403 的分类请求）与对应用例。
- **测试支撑代码**：`MockedPersistenceConfiguration` 补齐四个工单/分类 Mapper 替身。静态核对是
  `src/main/java` 恰好 9 个 `@Mapper`，而排除 `MybatisPlusAutoConfiguration` 的 9 个
  `@SpringBootTest` 上下文全部 `@Import` 该配置，三个跨模块 adapter 无 Mapper 依赖。

## 3. 实跑命令与结果

| 命令 | 结果 |
| --- | --- |
| `pwsh -c "Write-Output ..."`（前台，多轮多次） | `[exit code: 3221225794]`，无 stdout |
| 同上（`run_in_background`） | 同码失败 |
| 子 Agent 内 `Write-Output` / `node -v` / `git status`（各 2 次） | 全部同码失败 |
| `.\mvnw.cmd -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"` | **未运行** |
| `pnpm typecheck` / `pnpm lint` / `pnpm build` | **未运行** |
| `pnpm test:unit --run --maxWorkers=1` | **未运行** |
| `pnpm test:e2e` | **未运行** |
| 1440 / 375 截图 | **未生成** |

结论：本轮没有任何通过测试或构建的证据；"185 个上下文错误是否全部消除、是否还有进一步错误"
**仍未验证**，不得按"测试已修好"对待。

## 4. 下一轮的执行顺序（环境恢复后照抄；命令取自 `README.md` 第 71-80 行）

```powershell
# ① 先看工作区到底有多少改动，确认没有夹带别的主题
git status --short
git branch --show-current          # 应为 flow-desk/ticket-employee-flow

# ② 后端：补了四个 Mapper 替身后看是否还有进一步错误（新输出为准，不要沿用旧结论）
.\mvnw.cmd -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"

# ③ 前端四件套（本机 pnpm 若仍无法启动，用 package.json 里的等价 node 入口执行）
pnpm --dir frontend typecheck
pnpm --dir frontend lint           # 含 stylelint 六轴闸门
pnpm --dir frontend build
pnpm --dir frontend test:unit --run --maxWorkers=1

# ④ 真实栈 + E2E：MySQL/Redis 容器、后端 8081（playwright 的 preview 代理指向它）
docker compose up -d mysql redis
.\scripts\load-env.ps1             # 只输出变量数量，不打印值
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local     # 另开一个终端常驻
# 健康检查：http://localhost:8081/actuator/health
pnpm --dir frontend build          # playwright 的 webServer 只跑 preview，不跑 build
pnpm --dir frontend test:e2e       # 自己拉起 127.0.0.1:4173

# ⑤ 收尾：E2E 会写入工单并生成 5 张截图到本目录；清理 SQL 见
#    frontend/e2e/tickets.spec.ts 文件头，演示前按它恢复"工单 0 行"基线
```

`FLOWDESK_ALLOWED_ORIGINS` 必须含 `http://127.0.0.1:4173`（本地 `.env` 已加过），否则登录与刷新会被来源校验挡下。

若第 ② 步仍失败在当前 shell 上，注意 `PROJECT_STATUS.md`「环境前置」记的两条本机必备参数：
① Mockito 自附加需要 `-DargLine=-Djdk.attach.allowAttachSelf=true`；② Testcontainers 需要访问
Docker 命名管道，必须在放宽文件策略的会话里执行。

### 4.1 一条命令跑完 ⑧

上面第 ②～④ 步已收敛进 `scripts/stage2-acceptance.ps1`（一次跑完并把闸门证据写成
`docs/acceptance/stage2-acceptance-<时间戳>.json`）：

```powershell
powershell -NoProfile -File scripts/stage2-acceptance.ps1
```

## 5. 未验证项与已知风险

**未验证（不得当成已完成）**

- 三个页面从未被渲染过：优先级选项块的排布、时间线脊的视觉密度、1440 下 1096px 列宽之和是否
  留出合适余量、375 下详情是否真的上下排列，全部只有代码级判断，没有眼睛确认。
- 六态在真实栈上的表现（尤其"提交失败后保留原请求"这条端到端行为）只有组件用例覆盖，未在真实
  网络下验证。
- 工单链路的 E2E 从未执行过，选择器与断言的正确性未知。

**已知风险（本轮刻意未改，留给用户裁决）**

- `useAdminList` 的取数没有请求序号保护：连续两次 `search()` 时，先发后到的响应会覆盖后发先到的
  结果，表现为"筛选条件已经变了但列表还是旧的"。这是**既有共享件**的问题（管理端五页同样存在），
  改它会同时影响五个已验收页面，而本轮无法运行任何测试，因此只记录不修改。
- `views/admin/useAdminList.ts` 与 `useCompactPagination.ts` 已被工单列表复用，它们"只服务管理端
  五页"的理由失效，应迁到共享层。本次未移动：`git mv` 不可用，而新建文件又删不掉旧文件，
  硬做会留下两个同名实现。已登记在 `PROJECT_STATUS.md`。
- 分类筛选只能用启用中的分类选项接口，因此**筛不到"已停用分类的历史工单"**。这是当前契约的限制
  （员工端拿不到全量分类），不是界面取舍。

## 6. 阶段验收标准的证据映射

`docs/implementation-plan.md` 第 158 行写的阶段 2 验收是三条：**员工重复点击不会制造重复工单**、
**只能查看自己的工单**、**创建结果和时间线可在刷新后从后端恢复**。逐条对到证据来源：

| 验收标准 | 证据来源 | 状态 |
| --- | --- | --- |
| 重复点击不制造重复工单（前端行为） | `useSubmissionGuard` 的单测（同键同内容、失败不清理、改内容换键）+ 创建页用例（请求期间不重复发请求）+ `e2e/tickets.spec.ts` 断言提交中按钮 `disabled`、按唯一标题只搜到一张 | **已写，未运行** |
| 重复点击不制造重复工单（后端保证） | 步骤③ 真实栈验收：8 路同键并发全 201、仅一张工单/一条记录；依赖 `(requester_id, submission_key)` 唯一约束 | 已验收（2026-09-28 记录） |
| 只能查看自己的工单 | 步骤④⑤ 真实栈验收：本人行/总数隔离、范围隔离、EXISTS 防重复、伪造用户 ID 不改变范围、无关/纯管理员/零角色/不存在统一 404；`docs/acceptance/2026-09-29-category-ticket-scopes.json` | 已验收（144 项 0 失败） |
| 只能查看自己的工单（界面） | 列表页按 `TICKET_VIEW_*` 只出现可用范围；`e2e/tickets.spec.ts` 断言员工只有"我提交的"、地址里塞 `scope=PENDING_QUEUE` 被忽略 | **已写，未运行** |
| 创建结果与时间线刷新后可从后端恢复 | 步骤⑥ 真实栈验收：详情 121 项、时间线 111 项请求/断言 0 失败（`2026-09-29-ticket-detail.json`、`-timeline.json`）；`e2e/tickets.spec.ts` 在详情页 `reload()` 后重新断言标题、编号与创建记录 | 后端已验收；**前端刷新路径已写，未运行** |
| 阶段整体闸门（编译与全部测试） | `scripts/stage2-acceptance.ps1` 的后端 verify + 前端四件套 | **未运行** |

结论：**后端侧的三条标准都有已落盘的真实栈证据；缺的是前端侧的运行证据与整体闸门**。
因此本阶段不能标记通过，也不应把"页面已实现"当成"验收完成"。
