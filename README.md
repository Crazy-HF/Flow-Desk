# FlowDesk

FlowDesk 是一个企业工单协作平台，由开发者与 Codex 协作完成。

## 项目目标

- 理解一个 Java Web 项目从需求分析到开发交付的完整流程。
- 夯实 Spring Boot、MySQL、Redis 等 Java 后端开发能力。
- 完成一个业务流程完整、可测试、可说明的求职展示项目。
- 在协作开发过程中掌握核心设计与实现，而不是简单堆叠功能。

## 计划技术栈

### 后端

- Java 21
- Spring Boot 3
- MySQL
- Redis

### 前端

- Vue 3
- 仅实现支撑核心业务流程所需的基础页面

具体依赖、版本和工程规范已记录在 `docs/engineering-readiness.md`。

## 开发原则

- 原生开发，不使用若依等后台管理框架。
- 先确认业务，再设计架构、数据库和 API。
- 优先采用适合当前项目规模的简单方案。
- 未经论证和确认，不引入微服务、消息队列、Kubernetes、AI 等额外技术。
- 每个开发阶段都设置明确的验收标准，并对代码变更执行必要测试。

## 当前状态

项目启动分析、业务模型、总体架构、数据库逻辑与物理模型、API 契约、工程准备检查和 M0 工程底座已经完成。后续分为“求职 MVP”和“完整版”两层；MVP 阶段 1 Auth 身份入口（`TASK-010`～`TASK-011`，PR #5）、前端外壳与页面骨架（PR #6）、已确认提前实施的完整动态 RBAC（`TASK-055`～`TASK-062`，含管理端五页，PR #8）、阶段 2 员工创建与查询（`TASK-020`～`TASK-023-MVP`，PR #9）与**阶段 3 IT 处理闭环（[PR #10](https://github.com/Crazy-HF/Flow-Desk/pull/10)，合并提交 `1bc2e4c`）**均已合并 `main`。阶段 3 的四步全部完成并通过真实栈验收：步骤①②③ 于 2026-10-06 通过真实栈验收（77/77），步骤④ IT/员工页面与 Playwright 端到端主链同日用真实栈两个账号跑通 `待受理 → 处理中 → 待员工确认 → 已完成`。**阶段 4 MVP 验收与求职展示收口**（分支 `flow-desk/mvp-closeout`）已完成，主要工作包括：工单与分类模块的自动化测试从零补齐到 **10 个测试类 / 275 项用例**（后端回归口径由 394 + 88 升到 **638 单元/Web + 119 集成**，全部通过），README 补上核心流程、架构、测试命令、演示账号与已知限制，`docs/project-highlights.md` 新增四条可展示亮点，仓库跟踪文件中的机器绝对路径已清零；**空库 Flyway + 三角色登录 + 四态主链演示**用临时库跑通 66 项断言（证据 `docs/acceptance/2026-10-06-stage4-clean-db-demo.json`）。阶段 4 已完成并经 [PR #11](https://github.com/Crazy-HF/Flow-Desk/pull/11) 合并 `main`（合并提交 `f49b65b`）；**收口第一批**（工单/查询的 500 兜底与排序口径、测试稳定性、CI 并行化）经 [PR #12](https://github.com/Crazy-HF/Flow-Desk/pull/12) 合并 `main`（合并提交 `a0071a7`，CI 整轮由 6.2 分钟降到 **2.77 分钟**）；**收口第二批**（`useAdminList` / `useCompactPagination` 迁入共享层 `src/composables/`、列表请求竞态、四个 `api/` 模块的可空响应类型统一）经 [PR #13](https://github.com/Crazy-HF/Flow-Desk/pull/13) 合并 `main`（合并提交 `1f85c93`）。**至此收口两批全部结束**，工作区回到最新 `main`。原唯一长期遗留（**用户逐项视觉反馈**）已于 **2026-10-06 经用户裁决「不用管，直接当作已通过」**，不再是遗留项。**完整版第 1 项「完整工单状态机」分四片推进并已全部交付**：片 A 退回处理中与片 B 补充往返分别经 [PR #14](https://github.com/Crazy-HF/Flow-Desk/pull/14)、[PR #15](https://github.com/Crazy-HF/Flow-Desk/pull/15) 合并 `main`，片 C 调整与转交经 [PR #16](https://github.com/Crazy-HF/Flow-Desk/pull/16) 合并 `main`（合并提交 `9f07a0f`），片 D 结束路径（`close` + `cancel`）经 [PR #17](https://github.com/Crazy-HF/Flow-Desk/pull/17) 合并 `main`（合并提交 `95eee90`）；随后 2026-10-08 的**两阶段撤销**规则变更经 [PR #18](https://github.com/Crazy-HF/Flow-Desk/pull/18) 合并 `main`（合并提交 `2cf1bc1`），前端**页模板与页面契约**经 [PR #19](https://github.com/Crazy-HF/Flow-Desk/pull/19) 合并 `main`（合并提交 `95633f0`）。片 D 口径的九条 IT 动作与四条员工动作、外加两阶段撤销新增的四格（`approve-cancel` / `reject-cancel` / `request-cancel` / `withdraw-cancel-request`）至此全部实现。当前分支 `flow-desk/attachments-and-relations` 推进完整版 backlog 第 2 项「附件与关联」。清单与证据见 `PROJECT_STATUS.md` 与 `docs/implementation-plan.md` 9.3。

MVP 主链固定使用普通员工、IT 支持人员、系统管理员三种内置角色，先完成认证，再交付“员工创建 → IT 领取和处理 → 员工确认”的核心工单闭环；附件、完整状态机、数据概览放入完整版（**RBAC 管理端页面、用户管理与已授权提前实施的分类管理除外**，两者已在阶段 3 交付：用户管理于 2026-09-24 登记为 `TASK-061`/`TASK-062`，同批合并）。动态 RBAC 后端能力已于 2026-09-21 确认提前到阶段 2 之前实施，并已于 2026-09-22 确认**计入 MVP 演示范围**（含管理端页面）；用户角色授权取消“至少一个角色”约束，**允许用户零角色**。详细顺序见 `docs/implementation-plan.md`。

## 协作说明

本项目由开发者与 Codex 协作开发。详细协作规则见 `AGENTS.md`。

在新机器或新会话中继续项目时，先阅读 `PROJECT_STATUS.md` 获取当前阶段、下一步和当前任务必读文件，无需默认通读全部设计文档。

## 本地工程入口

### 环境要求

- JDK 21
- Node.js 24.20.0
- pnpm 12.3.4
- Docker 与 Docker Compose

### 首次准备

```powershell
Copy-Item .env.example .env
```

将 `.env` 中的占位值替换为仅供本机使用的随机密码、JWT 密钥和绝对附件目录，然后把变量加载到当前 PowerShell 进程：

```powershell
.\scripts\load-env.ps1
```

脚本只输出加载的变量数量，不输出变量值。`.env` 已被 Git 忽略。

### 启动基础设施和应用

```powershell
docker compose up -d mysql redis
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend dev
```

后端健康检查位于 `http://localhost:8081/actuator/health`，前端开发入口位于 `http://localhost:5173`。前端将 `/fd` 请求代理到后端。普通停止使用 `docker compose down`，不要附加 `-v`，以免删除本地数据卷。

### 演示账号

演示数据是 **Flyway repeatable 脚本** `src/main/resources/db/demo/R__seed_demo_data.sql`，只在加载了 `classpath:db/demo` 位置时执行——`application-demo.yml` 提供该位置，本地通过 `spring.profiles.group.local=demo` 自动包含，`prod`/`test` 都不加载。因此它不会进入生产环境（`DemoSeedProfileTest` 把这条边界写成断言）。

| 账号 | 角色 | 说明 |
| --- | --- | --- |
| `employee` | `EMPLOYEE` | 提交工单、确认解决结果 |
| `it` | `IT_SUPPORT` | IT 工作台：领取、追加处理记录、提交解决结果 |
| `admin` | `SYSTEM_ADMIN` | 用户管理、RBAC 四页、分类管理 |

三个账号的初始口令统一为 `123456`（仅演示用途，见脚本头部注释）。脚本可重复执行（`INSERT IGNORE`），不会覆盖真实数据。

### 测试命令

```powershell
# 后端：单元/Web（surefire）+ 集成（failsafe，Testcontainers 起真实 MySQL 8.4）
.\mvnw.cmd -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"

# 前端：类型检查、Lint（含 stylelint 设计 token 门禁）、构建
pnpm --dir frontend typecheck
pnpm --dir frontend lint
pnpm --dir frontend build

# 前端：单元/组件测试与端到端
pnpm --dir frontend test:unit --run --maxWorkers=1
pnpm --dir frontend test:e2e
```

- `-DargLine=-Djdk.attach.allowAttachSelf=true` 不是可选项：Mockito 的 inline mock maker 需要自附加，JDK 21 默认关闭该开关，缺少它会看到"几乎所有 Spring 测试一起失败"的假回归。CI（temurin 21）不需要该参数，因此没有写进 `pom.xml`。
- 集成测试需要 Docker；测试会自行拉起 MySQL 容器，**不碰本地演示库**。
- E2E 走 `vite preview`（端口 4173），前置条件是：MySQL/Redis 已启动、后端以 `local` profile 跑在 8081、`.env` 的 `FLOWDESK_ALLOWED_ORIGINS` 含 `http://127.0.0.1:4173`，并且**先执行 `pnpm --dir frontend build`**（否则预览的是旧包）。E2E 会向演示库写入数据，清理 SQL 写在各 spec 文件头部。
- 内存紧张的机器请串行执行：容器与 vitest 并行会互相挤掉。本机 `pnpm` 全局 shim 已损坏，此类环境下改用安装目录里的 `pnpm.cmd`（见 `PROJECT_STATUS.md`「环境前置」）。

MVP 阶段 1 Auth 身份入口已完成并通过 PR #5 合并进入 `main`：访问 `http://localhost:5173` 会被引导到 `/login`，用演示账号登录后进入受保护首页（显示当前身份与按权限展示的能力清单）。登录后的应用壳按四层职责组织——整幅顶栏（品牌位 + 搜索位 + 全屏 + 账号菜单）、按权限生成的侧栏、表达当前位置的面包屑、主体内容；导航条目与面包屑层级共用 `frontend/src/constants/authorization.ts` 的同一份声明。系统管理端 RBAC 已完成并通过 PR #8 合并 main：`TASK-055`～`TASK-062` 全部完成，验收标准第 4 条的四条手工真实栈链路已于 2026-09-28 在真实栈上执行并通过（逐条留 `traceId`，见 `docs/modules/rbac.md` 11.2）。验证证据：后端 **394** 单元/Web + **88** 集成全绿，前端 typecheck/lint/build、74 项单测和 13 项 E2E 全绿；`frontend/src/views/admin/` 下五页可走通真实授权闭环。`auth`、`iam` 生产代码已完成应用层重构，约定见 `docs/technical-architecture.md` 5.1；用户管理已登记为 `TASK-061`/`TASK-062`，零角色是合法终态。

阶段 2 已于 2026-09-29 验收通过并经 PR #9 合并 main（5b9a961）；后端 394 单元/Web + 88 集成、前端 124 单测、15 E2E 通过，证据见 `docs/acceptance/stage2-closeout-20260929.json`。阶段 3 在 `flow-desk/ticket-it-flow` 完成四步：IT 领取、追加处理记录、提交解决结果与员工确认已通过真实栈验收（77/77，证据 `docs/acceptance/2026-10-06-stage3-claim-process-resolution-confirm.json`）；IT 工作台（`/it/queue`）、详情页动作区与员工确认按钮已实现，端到端主链 `frontend/e2e/ticket-it-flow.spec.ts` 用 `employee` 与 `it` 两个真实账号跑通四态迁移（报告与截图见 `.ui-craft/reviews/2026-10-06-ticket-it-flow/`）。本阶段回归口径：后端 394 单元/Web + 88 集成、前端 147 单测、16 E2E 全绿。阶段 3 已经 [PR #10](https://github.com/Crazy-HF/Flow-Desk/pull/10) 合并 `main`（合并提交 `1bc2e4c`）。CI 在首次 PR 运行中发现并修正了一处 E2E 缺陷：用例把 IT 显示名写死为「IT 支持人员」，而种子数据是「演示 IT 支持人员」，干净库因此失败；已改为按当前登录身份读取显示名，修正后三个 job 全绿，完整记录见 `docs/acceptance/2026-10-06-stage3-git-handoff.json`。阶段 4 之后的收口两批（[PR #12](https://github.com/Crazy-HF/Flow-Desk/pull/12)、[PR #13](https://github.com/Crazy-HF/Flow-Desk/pull/13)）已完成；后续进度与当前分支见 `PROJECT_STATUS.md`。演示账号生成见 `src/main/resources/db/demo/R__seed_demo_data.sql`（仅 `demo`/`local` profile 生效，`prod` 不加载该目录）。

## 核心流程（MVP 四态主链）

```text
PENDING（待受理） → PROCESSING（处理中） → WAITING_FOR_CONFIRMATION（待员工确认） → COMPLETED（已完成）
```

1. **员工提交**：登录后从「新建工单」提交标题、描述、分类与优先级。创建请求带 `submissionKey`（UUID），同一用户同一提交键重复提交只会得到首次创建的那张工单；工单编号按业务日（`Asia/Shanghai`）生成 `FD-yyyyMMdd-NNN`，序号在同一事务内原子递增。
2. **IT 领取**：IT 在「IT 工作台」的待受理队列里打开工单并领取。只有有效 IT 支持人员能领取、不能领取自己提交的工单；两名 IT 同时领取时由带版本与状态条件的 `UPDATE` 决定唯一胜者，败者得到 `409` 与当前快照。
3. **IT 处理**：当前负责人在详情页追加处理记录（状态与负责人不变，只递增版本与记录序号），处理完成后提交解决结果，工单进入 `WAITING_FOR_CONFIRMATION` 并写入确认期限（`flowdesk.ticket.confirmation-window`，默认 7 天）。
4. **员工确认**：只有提交人能确认，确认后进入终态 `COMPLETED`（完成方式 `REQUESTER_CONFIRMED`，期限清空、写入结束时间、保留负责人）。

每一步都会追加一条**不可变时间线记录**（类型、操作者、状态迁移、正文或原因），详情页按 `sequenceNo` 升序分页展示。所有写操作都要求 `version`：冲突一律返回 `409/TICKET_CONFLICT` 并附当前 `version`/`status`，前端刷新后重试，不自动重放。

## 架构说明

- **模块**：`common`（统一响应、异常、分页、审计与 TraceId 过滤器）、`auth`（登录、JWT、刷新令牌轮换、会话）、`iam`（用户、角色、权限与两组授权）、`ticket`（工单、时间线、状态动作、查询范围）、`category`（分类与其管理端）。
- **依赖方向**：`ticket`/`category` → `iam` 通过 **port（接口在被依赖方的 `application.port`，由调用方的 `infrastructure` 实现）**；`auth` → `iam`。生产代码的数据访问集中在 `mapper` 的注解 SQL 与 MyBatis-Plus wrapper，业务编排在 `application.service.impl`。
- **分层约定（硬约束）**：`application.command` / `query` / `result` / `service`，实现入 `application.service.impl`；Controller 直接接收 Command/Query、直接返回 Result；`domain` 不放 BO/VO。完整约定见 `docs/technical-architecture.md` 5.1。
- **认证与授权**：Access Token 只在 Pinia 内存（15 分钟），刷新依赖 HttpOnly Refresh Cookie（7 天，轮换 + 重用检测）；授权是动态 RBAC——权限编码存库、随角色授予，接口用 `hasAuthority` 兜底、服务层按资源关系复核，无权查看与不存在统一 `404`。
- **数据**：MySQL 8.4（Flyway 管理 `V1/V2/V4/V5/V6/V7`，外加仅 demo 加载的演示种子）、Redis（会话快照与刷新令牌索引）。状态与期限、结束时间、完成方式之间的合法组合由数据库 CHECK 约束固定。
- **前端**：Vue 3 + Element Plus + Pinia + vue-router；页面按 `views/work`（工单与 IT 工作台）与 `views/admin`（管理端）分层，设计 token 单一真源在 `frontend/src/styles/tokens.css`，由 stylelint 门禁强制。

## 已知限制

- **完整版未实现**（不计入 MVP）：完整工单状态机的**动作**已全部交付（`docs/implementation-plan.md` 9.3 的片 A～片 D），并在 2026-10-08 追加了**两阶段撤销**的规则变更（`cancel` 收窄为待受理直接撤销，另加 `request-cancel` / `approve-cancel` / `reject-cancel` / `withdraw-cancel-request` 四个动作）；仍未实现的是**超时自动任务**（待确认超时自动完成、待补充超时关闭，属 backlog 第 3 项），此外附件上传下载、数据概览图表、管理性交接也未实现。界面只摆已实现的动作，不出现"按不动的按钮"。
- **撤销已改为两阶段**（2026-10-08 规则变更，`docs/kickoff.md` 4.7）：待受理没有负责人，提交人仍可直接撤销；处理中、待补充、待确认下提交人只能**发起撤销请求**，由**当前负责人批准或拒绝**，提交人也可以随时撤回自己的请求。请求期间工单状态与原期限不变，请求自带响应期限（默认 3 天，`flowdesk.ticket.cancel-request-window`）但**到期不自动处置**，期限只用于展示。**后端与前端动作区均已实现**（含真实 MySQL 集成用例、界面单测与 E2E 场景）；真实栈验收与分支交接见 `PROJECT_STATUS.md` 的当前结论。**到期与发现路径**：2026-10-10 用户裁决「过期不可批准」——期限届满后请求失效，批准 / 拒绝 / 撤回三个动作一起关闭，员工可重新发起覆盖且**不设终身次数上限**，过期请求保留显示为「已过期」（**尚未实现**，现行代码仍允许过期后批准）；**转交后**批准或拒绝的权利随新负责人转移，请求与响应期限保留、不重算。**IT 列表还没有「待我批准」筛选**，待决请求只在详情可见；该筛选与到期语义**同批实施**并**共用同一判定**（本人是当前负责人 + 有未过期待决请求），排在附件之后（完整版 backlog 第 7 项）。**推进顺序（2026-10-10 裁决）**：关联 → 附件 → 撤销语义收口（①③）→ 管理性交接 → 通知 / 通信通道 + 两个超时自动任务，逐条见 `docs/implementation-plan.md` 第 9 节。**待补充期间不裁决**（同日裁决，**已实现**）：工单在待补充时批准与拒绝都不开放（接口返回 `409`、详情不返回这两格），员工补充完、工单回到处理中再裁决；转交与撤回补充请求照常。
- **单节点设计**：未做多实例下的分布式协调；会话与刷新索引集中在一个 Redis 实例上。
- **前端打包**：Element Plus 目前全量引入（构建产物约 1.2 MB / gzip 约 379 KB），未做按需引入；构建会打印大 chunk 提示，不影响退出码。
- **测试策略**：不做覆盖率门禁（`jacoco` 只出报告），以行为断言为准；集成测试依赖 Docker。
- **已知问题**：
  1. 本机演示库中 `admin` 账号同时持有三个角色（种子只授予 `SYSTEM_ADMIN`），属长期存在的既有漂移，未改动。
- **已修缺陷（2026-10-08 片 D，均有测试与真实栈断言覆盖）**：
  1. **转交与「工单侧动作」并发时的交叉死锁**（2026-10-07 实测、2026-10-08 修复）：转交原先按 `user → ticket` 顺序加锁，而同一负责人对同一张工单的另一个动作会先拿工单行、再因 `ticket_record.actor_user_id` 外键去申请同一条 `iam_user` 行的共享锁，两条路径加锁顺序相反，InnoDB 回滚其中一个——数据一致，但调用方拿到 `500/INTERNAL_ERROR` 而不是可重试的 `409/TICKET_CONFLICT`。现在转交**先锁工单行并复核版本，再按 `user_id` 升序锁两行用户**，所有动作统一以工单行起手，环消失；集成用例的断言已收紧为"败者一定是 `409`"，真实栈两组真并发（撤销 vs 关闭、转交 vs 关闭）实测赢家唯一、无 5xx。机制与取舍见 `docs/project-highlights.md` HL-011。
  2. `close` 里 `Long actorId` 与 `visible.getAssigneeId() != actorId` 曾是 **`Long` 与 `Long` 的引用比较**，只有用户 ID 落在 `Long` 缓存区（−128～127）才恰好为真——超过 127 的负责人会关不掉自己的工单（误判 `409`）。已统一为基本类型 `long`。
- **已修缺陷（2026-10-06 收口分支 `flow-desk/mvp-hardening`，均有测试覆盖）**：
  1. `TicketServiceImpl` 的三处 null 安全不对称：`version` 为 null 现与"版本过期"同一处理（`409/TICKET_CONFLICT`），缺失或非 UUID 的 `submissionKey` 现为 `400/VALIDATION_FAILED`，`content` 为 null 现为 `400/VALIDATION_FAILED`；此前都可能表现为 `500`。HTTP 入口的 Bean Validation 会先挡住，只有非 HTTP 调用方会遇到。
  2. `TicketQuery` 与 `TicketRecordQuery` 的排序方向改用 `PageQuery` 的同一判定（忽略大小写、空值按升序、空 `orderBy` 视为未请求排序）；`desc`、非法方向与显式 `orderBy` 仍被拒绝。
  3. 创建工单的 `DuplicateKeyException` 兜底分支在冲突来自工单编号等其他唯一键时返回 `409/TICKET_CREATE_CONFLICT`（原始异常保留为 cause），不再回落 `500`。
