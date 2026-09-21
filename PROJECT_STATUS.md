# FlowDesk 项目状态

> 本文件用于新机器、任务恢复和工作交接时快速定位项目，不替代详细设计文档。

## 快速定位

- 最后更新：2026-09-21
- 远程仓库：`git@github.com:Crazy-HF/Flow-Desk.git`
- 稳定分支：`main`
- 当前基线分支：`main`（阶段 1 已合并，合并提交 `5c6cca0`）
- 当前工作分支：`flow-desk/frontend-shell`（本地分支，基于最新 `main`；本工作项改动已按主题提交，**尚未推送**）
- 下一次创建分支：本工作项交接完成后，从最新 `main` 创建第 3 步“系统业务：RBAC”的分支，名称待该阶段范围确认后确定。已存在的 `flow-desk/employee-ticket-flow` 只含 `7f6c72c` 一条文档同步提交，不用于本轮开发
- 当前阶段：第 1 步**前端外壳与页面骨架**收口与提交（2026-09-21 已提交，待第 2 步交接）；路线按用户 2026-09-21 指示调整为四步：① 收口并提交前端外壳 ② 完成交接（推送 → 合并请求 → 合并 `main` → 同步 → 建下一分支）③ **系统业务：RBAC**（范围待确认，见待确认事项⑤）④ 阶段 2 `TASK-020`～`TASK-023-MVP`
- 已确认的范围调整：项目分为“求职 MVP”和“完整版”；MVP 固定 `EMPLOYEE`、`IT_SUPPORT`、`SYSTEM_ADMIN` 三种内置角色，不实现在线角色、权限及授权关系 CRUD。动态 RBAC 仅为完整版可选项，需另行确认。**用户已把「系统业务：RBAC」定为第 3 步，但该阶段是否包含在线 CRUD 仍待确认，确认前不改动 `AGENTS.md` 的既有边界与 `docs/implementation-plan.md` 的阶段划分。**
- 最新完成：**阶段 1（Auth 身份入口）已通过 PR #5 合并进入 `main`**，CI 三个 job（后端 verify、前端 verify、核心 E2E）全绿。成果：后端 `TASK-010` 五个接口 + 前端 `TASK-011`；证据为后端 `./mvnw -B verify` 单元/Web 53 项 + 集成 17 项、前端 `test:unit` 14 项与 `test:e2e` 5 项、三类演示账号真实登录验证
- 下一步（四步路线，2026-09-21 用户指示）：① 收口并提交前端外壳与页面骨架工作项（`flow-desk/frontend-shell`，已提交，见下方记录）；② 完成本工作项交接：推送分支 → 创建合并请求 → 合并 `main` → 本地 `main` 仅快进拉取 → 从最新 `main` 创建下一分支；③ **系统业务：RBAC**——先确认范围（动态角色/权限/授权关系 CRUD，或仅用户与内置角色管理），再确定分支名、任务拆分与验收标准；④ 阶段 2 员工创建与查询——先确认 `TASK-020` 到 `TASK-023-MVP` 的接口与数据模型落地顺序，再按切片实施
- 当前阻塞：无。工作区改动已按主题提交到 `flow-desk/frontend-shell`（尚未推送）；本机启动后端前修复过两处环境问题（Flyway 历史记录、Redis 残留键，见记录）
- 环境前置：JDK 21、Node.js 24.20.0、pnpm 12.3.4、Docker 29.7.2 已验证；本机已有 `redis:8.8.0`、`mysql:8.4.11` 镜像。本地启动 profile 用 `local` 即可（`spring.profiles.group.local=demo` 已配置）。演示账号 `employee` / `it` / `admin`，密码统一为 `123456`（见 `db/demo/R__seed_demo_data.sql` 头部注释，2026-09-20 由 `demo.*` 改名）。本机已有过两类运行障碍并已修复：① Flyway 校验失败——历史表残留已删除的 V3 迁移记录，处置为删除该行（等价 `flyway repair`）；② Redis 残留旧实现写入的 hash 类型会话键，会让"撤销全部会话"抛 `WRONGTYPE`，已清理。另需注意：本机 Argon2id 校验约 2 秒/次（并发登录可拖到十几秒），前端 e2e 因此串行执行并放宽超时；跑 e2e 需要 `FLOWDESK_ALLOWED_ORIGINS` 包含 `http://127.0.0.1:4173`（本地 `.env` 已加）
- 待确认事项：① Element Plus 目前是**全量引入**（打包约 1.07 MB / gzip 348 KB），是否改为按需引入（需新增 `unplugin-vue-components`、`unplugin-auto-import` 两个 dev 依赖）；② 本机库中 `admin` 仍带 `RBAC_MANAGE`（早前已删除的 V3 迁移遗留数据），是否清理；③ 登录页占位文案是「登录名」「密码」，与 `frontend/AGENTS.md` 新增的「请输入…／请选择…」约定不一致（E2E 定位依赖现文案，改文案需同时改用例）；④ 首页 `h1`「欢迎回来」用的是展示级字号 `clamp(1.75rem, 5vw, 2.5rem)`，是否收小到页面标题刻度；⑤ **第 3 步「系统业务：RBAC」的范围**——是只做用户管理与内置角色分配（`TASK-051` 的 IAM 管理能力），还是同时开放动态角色/权限及其授权关系 CRUD（`docs/api-design.md` 8.2.1，需新 Flyway 迁移预置 `RBAC_MANAGE`），或两者分两步做；该决定会同时影响 `AGENTS.md` 当前阶段限制、`docs/implementation-plan.md` 阶段划分与数据库迁移；⑥ 侧栏当前的 CSS 下拉三角是否换成已安装的 `@element-plus/icons-vue` 的 `ArrowDown`（换掉后可一并删除为此新增的 `--fd-border-width` token）。历史处置：JaCoCo 覆盖率门禁已确认取消（2026-09-19），`pom.xml` 只保留 `jacoco:report` 供 CI 上传工件，不再保留 70% 行 / 60% 分支阈值（当时实测行覆盖 37.2%、分支 13.9%，阈值必定使 `verify` 失败）

## 2026-09-21 前端外壳工作项提交与路线调整

- **分支纠正**：此前“快速定位”把当前工作分支记为 `flow-desk/employee-ticket-flow`，与仓库实际不符。实际开发在本地分支 `flow-desk/frontend-shell`（基于 `main` 的 `5c6cca0`）上进行，此前既无自身提交也未推送；`flow-desk/employee-ticket-flow` 只含 `7f6c72c` 一条文档同步提交。已在“快速定位”更正当前工作分支与下一次创建分支。
- **本轮提交（`flow-desk/frontend-shell`，未推送）**：按单一意图拆为 5 个提交——前端规范与设计上下文（`docs`）→ 设计 token、应用外壳与页面骨架（`feat`）→ 演示账号改名与本地密码统一（`chore`）→ 请求开始行与本地 SQL 日志（`feat`）→ 状态文档同步（`docs`）。
- **一并提交的既有成果**：`views` 按 `frontend/AGENTS.md` 新增的目录分层移到 `views/auth`、`views/error`、`views/work`；`App.vue` 接线“顶栏 + 权限侧栏 + 面包屑 + 主体”四层；`HomeView` 重做（骨架屏、原位重试、三阶段入口）；新增 `frontend/AGENTS.md` 与 `.ui-craft/brief.md`、`tokens.md`；新增依赖 `@element-plus/icons-vue`、`sass`。
- **验证证据（Codex 实跑）**：前端 `vitest run` 12 套件 / 25 项全通过；`vue-tsc -b` 退出码 0；`eslint . --max-warnings=0` 退出码 0。
- **未验证（不得计入完成）**：`pnpm build`、`pnpm test:e2e`（需后端 + MySQL + Redis + 演示账号）与后端 `./mvnw -B verify` 本轮均未执行；后端 55 项单元/Web + 17 项集成来自同日“请求与 SQL 日志打通”记录，本轮未复现。**已提交不等于已验收。**
- **路线调整（用户 2026-09-21 指示）**：后续改为四步（收口前端外壳 → 交接 → 系统业务 RBAC → 阶段 2 `TASK-020`～`TASK-023-MVP`）。第 3 步 RBAC 的范围尚未确认，确认前不改 `AGENTS.md` 的既有边界（MVP 不实现在线角色/权限 CRUD）与 `docs/implementation-plan.md` 的阶段划分。

## 2026-09-21 前端新增 @element-plus/icons-vue（含本机 pnpm 链接缺陷的处置）

- **变更**：`frontend/package.json` 新增 `"@element-plus/icons-vue": "2.3.2"`（精确锁版本，与既有 `element-plus: 2.14.5` 惯例一致），lockfile 同步更新。用途是给侧栏一级栏目提供下拉三角图标——`frontend/AGENTS.md` 本来就要求图标走这个包，此前它并未安装，所以下拉三角一度是用 CSS 边框画的。
- **装前按环境约定停掉了 IntelliJ 的 JS 语言服务 JVM**（`-Xmx700m` + IntelliJ jna 路径的那两个），装完由 IDE 自行重启；未动 IDE 自己启动的 Spring Boot 进程。
- **本机缺陷（重要，会复发）**：pnpm 12.3.4 在 Windows 上为**带 peer 依赖的包**生成顶层符号链接时，链接目标名少了 peer 后缀。实测：
  - 真实目录 `.pnpm/@element-plus+icons-vue@2.3.2_vue@3.5.42_typescript@6.0.3_/`（存在、内容完好）
  - 链接却指向 `.pnpm/@element-plus+icons-vue@2.3.2/`（不存在）
  - 后果：`fs.realpathSync` / `statSync` 报 `ENOENT`，Node 与 Vite 都解析不到该模块；而 `pnpm install` 与 `pnpm install --frozen-lockfile` 都报 "Already up to date"，`--force` 重装也不会修。全量扫描 `node_modules` 的 21 个符号链接，**只有这一个坏了**（对照 `element-plus` 自身的链接是 `.pnpm/element-plus@2.14.5_vue@3.5.42_typescript@6.0.3_/`，带后缀、正常）。
  - **处置**：删除坏链接，改为指向真实目录的 **junction**（`fs.symlinkSync(storeAbs, link, 'junction')`）。用相对符号链接重建无效——即使目标字符串正确，Windows 上 Node 仍拒绝跟随；junction 立即可用。修复后 `--frozen-lockfile` 复跑链接仍完好。
  - **复发时的修复脚本**（`node` 执行，只重建链接、不动 store 内容）：把 `node_modules/@element-plus/icons-vue` 删掉，用 `fs.symlinkSync('<绝对路径>/.pnpm/@element-plus+icons-vue@2.3.2_vue@3.5.42_typescript@6.0.3_/node_modules/@element-plus/icons-vue', '<绝对路径>/node_modules/@element-plus/icons-vue', 'junction')` 重建。
- **验证证据（2026-09-21）**：`pnpm install --frozen-lockfile` 通过（363 条供应链策略校验）；`pnpm typecheck`、`pnpm lint`、`pnpm build`、`pnpm test:unit --run`（25 项）全通过；Node 模块解析该包成功，**导出 293 个图标**，`ArrowDown` / `ArrowRight` / `ArrowUp` 均存在。
- **尚未做**：侧栏的 CSS 下拉三角**还没有换成** `ArrowDown` 组件，等确认后再改（届时可一并删掉只为它加的 `--fd-border-width` token）。

## 2026-09-21 请求与 SQL 日志打通

- **背景**：本地开发看不清"一次请求里到底执行了哪些 SQL"。排查后发现链路已有一半：`TraceIdFilter`（`HIGHEST_PRECEDENCE+10`）已建立 traceId 并写入 MDC，`RequestAuditFilter`（`+20`）已在请求结束时打 `method/path/status/durationMs`，`application.yml` 的日志 pattern 已带 `[traceId=%X{traceId:-}]`。
- **缺口与处置**：
  - **请求开始行**：`RequestAuditFilter` 新增 `request start method= path=`，级别为 **DEBUG**（结束行保持 INFO）。理由：结束行的耗时与结果是任何环境都值得留的运维事实；开始行只是开发时把一次请求在控制台框出来，不该成为生产的默认 I/O 成本。
  - **SQL 日志**：`mybatis-plus.configuration.log-impl=Slf4jImpl`（`application-local.yml`）原本已就位，但级别必须落在 **Mapper 所在包** `com.flowdesk.iam.mapper` 才生效——SQL 的 logger 名是 Mapper 的全限定名。本地另加 `com.flowdesk.common.web.filter.RequestAuditFilter: DEBUG` 用于打开开始行。
  - **生产无需改动**（比原方案更省）：`application-prod.yml` 已是 `root: WARN` + `com.flowdesk: INFO`，SQL 与开始行天然不输出。已逐份核对 `application-demo.yml`（只有 flyway 与 springdoc）与 `application-test.yml`，**均不含 `logging.level`**，因此 `spring.profiles.group.local=demo` 不会顶掉 local 的 DEBUG。
- **安全边界**：`RequestAuditFilter` 依旧不读请求头、Cookie、查询串与请求体；SQL 日志由 MyBatis 输出"语句 + 参数"两行，不含明文口令（登录只绑定 username）。**已知未处理**：登录查询是全列 `SELECT`（`IamUserMapper` 的 `SELECT id,username,display_name,password,…`），日志可看到 `password` 列名；摘要值本身不打印。处置建议：阶段 2 写员工查询时改为显式列，并把"密码列不出现在任何查询里"作为验收项。
- **测试**：`RequestAuditFilterTest` 从 1 项扩到 3 项，且不再依赖日志事件顺序（原先用 `list.getLast()`，仅在"结束行恰好是最后一条"时成立，现已改为按内容筛选）。新增"开始行是 DEBUG 且不含敏感值"与"DEBUG 关闭时不出开始行、结束行仍在"两项——后者反过来印证了设计：测试环境没有 `local` profile、级别为 INFO，所以开始行默认不输出。
- **验证证据（2026-09-21）**：`./mvnw -B verify` → 单元/Web **55 项**（原 53 项 + 新增 2 项）、集成 **17 项**，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。真实栈实测 `POST /fd/v1/auth/login`（`employee` / `123456`）：同一个 `traceId=3684713b-…` 贯穿 `request start` → 5 组 `==> Preparing / ==> Parameters / <== Total`（iam_user、iam_user_role、iam_role、iam_role_permission、iam_permission）→ `request end … status=200 durationMs=520`；响应头 `X-Trace-Id` 与日志值一致。
- **排障记录**：用 `spring-boot:run` 且被包装进程包裹时，重定向只能拿到 Maven 自身输出，应用日志不进文件；包装进程退出后 fork 出的 `java` 会变成孤儿继续占用 8081（`job_kill` 杀不到它，症状是"服务没起却登录成功"）。要看应用输出用 `Start-Process -RedirectStandardOutput`，或按命令行里的 `-Dspring.profiles.active=local` 定位并清理孤儿进程。

## 已完成里程碑

1. 项目目标、用户、v1 范围、业务流程和权限已经确认。
2. 核心业务概念、关系、生命周期和业务不变量已经确认。
3. 总体技术架构已经确认。
4. 数据库逻辑数据模型和 MySQL 物理模型已经确认。
5. v1 API 契约、权限映射、错误编码、并发和幂等语义已经确认。
6. 工程依赖、配置、迁移、测试、CI、启动、跨存储失败处理和页面/API 映射已经确认。
7. 开发任务拆分已经确认，全部 API 与后台任务均有实施归属。
8. **M0 工程底座已完成并合并**：`TASK-001`～`TASK-003`（骨架、数据基线、公共契约、CI）通过 PR #4 合并进入 `main`。
9. **MVP 阶段 1（Auth 身份入口）已完成并合并**：`TASK-010`（后端认证、会话与密码安全）+ `TASK-011`（Vue 登录外壳与身份恢复）通过 PR #5 合并进入 `main`（合并提交 `5c6cca0`）。

## 2026-09-20 演示账号改名与密码调整

- 演示账号由 `demo.employee` / `demo.it` / `demo.admin` 改为 `employee` / `it` / `admin`，密码统一改为 `123456`；`display_name` 与角色映射不变。
- 变更文件：`db/demo/R__seed_demo_data.sql`（用户名、Argon2id 摘要、角色映射 CASE/WHERE）、`AuthWebTest` 的 `USERNAME` / `CURRENT_PASSWORD`、`DatabaseMigrationIT` 的 demo 用户断言、`frontend/e2e/auth.spec.ts` 的默认凭据。本机库用 `UPDATE` 改名以保留 `id` 与 `iam_user_role` 关联。
- 新摘要用 `common/config/PasswordConfiguration` 的同一组参数生成（salt 16、hash 32、m=19456,t=2,p=1），三个账号各一份并逐个验证 `matches` 为真、错误口令为假。
- **注意**：`123456` 只有 6 位，低于 `docs/api-design.md` 已确认的"密码长度 8～64 个字符"，而 `AuthChangePwdVO` 当前实现是 `@Size(min = 6, max = 64)`。演示库可正常登录（登录只比对摘要，不校验强度），但该口令不符合已确认策略，不得用于真实环境；文档与实现的口径差异需要确认后统一。
- 本文件中此前出现的 `demo.*` 账号名与 `Demo#FlowDesk2026` 属于变更前的当时事实，保留不改。

## 2026-09-20 前端设计 token 与 Element Plus 主题

- **新增**：`src/styles/tokens.css`（`--fd-*` 唯一真源 + 文件末尾把 Element Plus 的 `--el-*` 映射到 token）、`src/styles/element/var-override.scss`（品牌与语义色的唯一字面量来源，`@forward` 覆写 EP 的 SCSS 变量）、`frontend/AGENTS.md`（前端硬约束与设计参照，根 `AGENTS.md` 已挂钩要求改前端前先读）、`frontend/pnpm-workspace.yaml`（显式 `allowBuilds: {'@parcel/watcher': false}`）。
- **修改**：`src/main.ts`（EP 样式改用 `element-plus/theme-chalk/src/index.scss`，并把 `tokens.css` 放在 EP 样式之后保证 `--el-*` 覆盖生效）、`vite.config.ts`（`css.preprocessorOptions.scss.additionalData` 注入 `var-override.scss`，早于 EP 编译）、`src/styles/main.css`（所有色值、间距、字号、圆角、阴影改为 token 引用）、`package.json` + 锁文件（新增 devDependency `sass`）。
- **为什么两条路都要**：EP 的 `light-3/5/7/8/9` 与 `dark-2` 是编译期用 Sass `mix()` 从主色算出来的，只在 `:root` 覆盖 `--el-color-primary` 会让这些色阶停留在默认蓝；因此品牌色字面量放 SCSS，其余（圆角、字号、字色、描边、底色）走 `tokens.css` 的 CSS 变量映射。
- **生效结果**（编译产物核对）：主色 `#2563eb`，色阶 `light-3 #6692f1`、`light-5 #92b1f5`、`light-9 #e9effd`、`dark-2 #1e4fbc`，产物内 EP 默认蓝 `#409eff` / `#ecf5ff` 残留为 0。
- **有意的观感变化**：顶部导航选中态与身份标签由 indigo（`#e0e7ff`/`#3730a3`）统一为主色浅底，落实"一个强调色"。
- **验证证据（2026-09-20）**：`pnpm typecheck`、`pnpm lint`、`pnpm build` 通过；`pnpm test:unit --run` 22 项、`pnpm test:e2e` 7 项通过；浏览器实际登录（`employee` / `123456`）后截图自查登录页与首页，布局、圆角、阴影、主色均正常；`pnpm install --frozen-lockfile` 退出码 0（补齐 `allowBuilds` 之前该命令以 `ERR_PNPM_IGNORED_BUILDS` 失败，会直接挂掉 CI 的 frontend job）。
- **本机环境提醒（Windows）**：`pnpm add/install` 前必须先停掉 Vite dev server 和 IDE 的 JS/TS 语言服务，否则句柄被占会出现"拒绝访问"并在极端情况下把 `node_modules` 删到一半；本次已按此恢复，dev server 已重新启动（5173）。
- **未迁移的部分**：视图与组件内本来就没有硬编码色值或 px（样式集中在 `main.css`），因此本次只迁移了 `main.css`；后续新页面按 `frontend/AGENTS.md` 直接引用 token。

## 2026-09-20 前端外壳与页面骨架（阶段 2 之前的准备性工作项）

- **范围（已确认）**：只做外壳与骨架，不新增后端接口、不提前实现工单业务页面；已确认的实施顺序仍是"后端先行、页面随后"，本工作项完成后进入阶段 2。
- **新增**：`api/pagination.ts`（与后端 `PageQuery` / `PageResult` 对齐的分页契约）、`api/errorMessages.ts`（稳定错误码 → 界面文案，未知码回落）、`utils/format.ts`（UTC 时间格式化）、`constants/authorization.ts`（角色/权限中文名 + 顶部导航条目）、`components/AppPage.vue`、`components/EmptyState.vue`、`components/AppHeader.vue`（导航 + 账号菜单 + 改密对话框）、`views/PlannedWorkView.vue`（占位页）。
- **修改**：`router/index.ts`（补齐 `/tickets`、`/tickets/new`、`/tickets/:ticketNo`、`/dashboard`、`/admin/users`、`/admin/users/:userId`、`/admin/categories` 七条占位路由；`meta` 扩展 `title`、`plannedTask`、`permission` 支持多条"任一命中"；守卫按新语义判断）、`stores/auth.ts`（新增 `hasAnyPermission`）、`App.vue`（外壳抽到 `AppHeader`）、`HomeView.vue`（角色与权限用中文名展示、改用 `AppPage`）、`LoginView.vue`（用户名自动聚焦、错误文案走统一映射）、`ChangePasswordDialog.vue`（统一映射 + 改密语境覆盖）、`styles/main.css`（导航、页面容器、空态样式）。
- **行为**：导航入口按权限显隐；直接访问没有权限的路由进入 `/403`；占位页标注实现任务；同一个错误码在登录与改密语境下给出各自贴切的文案。
- **验证证据（2026-09-20）**：前端 `test:unit` **22 项**（新增守卫 4 项、错误码映射 3 项、`hasAnyPermission` 1 项）、`typecheck`、`lint`、`build` 全部通过；`test:e2e` **7 项**通过（新增"导航只显示有权限的入口""无权限路由 → 403"）。后端未改动。
- **文档**：`AGENTS.md` 更正两处过期信息（"六个后端接口"→ 五个；前端测试数量改为 22 项单元 + 7 项 E2E）。

## 2026-09-20 阶段 1 交接完成

- **合并请求**：PR #5 `flow-desk/auth-foundation` → `main`（7 个提交、123 个文件、+5833/−585），CI 三个 job 全绿。
- **合并与同步**：合并提交 `5c6cca0`；本地 `main` 仅快进拉取后与 `origin/main` 一致。
- **下一分支**：已从最新 `main` 创建 `flow-desk/employee-ticket-flow`；按仓库惯例，推送发生在阶段 2 完成检查、准备合并时。
- **说明**：PR 中包含 2026-09-07～09-09 的三条 IAM groundwork 提交，那批 IAM 管理代码后来按范围收敛清空重写，历史保留供追溯；阶段 1 的实际成果为 `40a1264`、`e571a5d`、`df80c86` 三条提交。
- **未开始的工作**：阶段 2（`TASK-020`～`TASK-023-MVP`）尚未动工，开工前先确认接口与数据模型的落地顺序。

## 2026-09-19 TASK-011 完成：Vue 登录外壳与身份恢复

- **新增**：`src/api/http.ts`（Bearer 注入、401 单次刷新协调、会话失效回调）、`src/api/auth.ts`（登录/刷新/退出/当前身份/改密的封装与信封处理）、`src/stores/auth.ts`（内存身份、权限判断、恢复与退出）、`src/views/LoginView.vue`、`HomeView.vue`、`ForbiddenView.vue`、`NotFoundView.vue`、`src/components/ChangePasswordDialog.vue`、`src/test-setup.ts`、`e2e/auth.spec.ts`。
- **修改**：`router/index.ts`（`/login` 公开、其余受保护、`/403`、404 兜底；守卫先尝试恢复身份，未登录带 `redirect` 回登录页、按 `meta.permission` 做界面级拦截）、`App.vue`（仅已登录时显示账号菜单：修改密码 / 退出登录）、`main.ts`（Element Plus 与中文本地化、网络层与 store 装配）、`styles/main.css`、`vite.config.ts`（补 `preview.proxy`——否则 `pnpm preview` 与 e2e 的接口请求不会代理到后端）、`vitest.config.ts`、`playwright.config.ts`（串行 + 放宽超时，原因见下）。
- **删除**：M0 占位页 `FoundationView` 及其用例、`e2e/foundation.spec.ts`（`/` 已成为受保护首页）。
- **已确认的行为**：Access Token 只保存在 Pinia 内存（用例断言 localStorage 与 sessionStorage 为空）；刷新页面靠 HttpOnly Refresh Cookie 恢复身份；并发 401 只触发一次 `/auth/refresh`；刷新失败清空身份并回登录页；能力清单按权限展示，后端仍是最终授权边界；改密成功后本地身份清空并回登录页。
- **验证证据（2026-09-19）**：前端 `pnpm test:unit --run` 14 项、`pnpm typecheck`、`pnpm lint`、`pnpm build` 全部通过；`pnpm test:e2e` 5 项通过（未登录重定向、登录后显示身份、刷新恢复、退出、错误密码提示）；三类演示账号经真实接口验证：`demo.employee` → `EMPLOYEE`、`demo.it` → `IT_SUPPORT`、`demo.admin` → `SYSTEM_ADMIN`，权限映射与迁移一致。
- **环境发现**：Argon2id 在本机约 2 秒/次，并发登录可被拖到 13 秒，故 e2e 串行并放宽超时；本机库中 `demo.admin` 仍带 `RBAC_MANAGE`（早前已删除的 V3 迁移留下的数据，迁移集不含它，CI 与新库不会出现）。
- **待办**：阶段 1 分支交接（提交 → 推送 → 合并请求 → 同步 `main` → 建下一分支）。

## 2026-09-19 切片 4～6 自动化用例补齐

- **新增用例**：`AuthWebTest`（22 项）、`RedisAuthSessionRepositoryIT`（13 项）、`RefreshTokenUtilsTest`（3 项）、`AuthCookieFactoryTest`（3 项）、`JwtTokenServiceTest`（6 项）。
- **覆盖范围**：请求认证四类结果（有效、无令牌、不可验证、会话失效）与权限注入到方法级授权；刷新成功（校验写回 Cookie 的是新令牌、响应体不含 Refresh Token）、无 Cookie、未知摘要、重放撤销整个会话、来源白名单与缺失来源；退出撤销并清 Cookie、无 Cookie 幂等、来源校验；改密成功（校验"先撤 Redis 再写 MySQL"的顺序）、原密码错误不动会话、新密码过短 400、版本冲突 409、无令牌 401；真实 Redis 上的 TTL、JSON 往返、脏值与异类型键、轮换、重放反查、撤销与按用户撤销。
- **测试证据（2026-09-19）**：`./mvnw -B verify` → 单元/Web `Tests run: 53`，集成 `Tests run: 17`（`RedisAuthSessionRepositoryIT` 13 + `DatabaseMigrationIT` 4），`Failures: 0, Errors: 0`，`BUILD SUCCESS`。
- **测试基建两处要点**：① 测试上下文排除了 MyBatis-Plus 自动配置，真实服务构建 `LambdaQueryWrapper` 之前需要 `MybatisPlusTestMetadata.initialize(...)` 注册实体元数据，否则抛 `MybatisPlusException`；② `MockedPersistenceConfiguration` 提供的是普通 Mock Bean，不会在用例之间自动重置，需在 `@BeforeEach` 中 `reset(...)`，否则前一个用例的调用记录会污染 `never()` 断言。
- **结果**：`docs/implementation-plan.md` 中 `TASK-010` 的最低测试证据（单元、Web、Redis 集成三类）已全部满足。

## 2026-09-19 切片 6 完成与真实栈端到端验证

- **新增/修改**：`AuthService.currentUser` 与 `changePassword` 及实现（抽出 `requireActiveSession`，`refresh` 复用）；`AuthController` 的 `GET /me`（`@AuthenticationPrincipal`）与 `POST /change-password`（校验原密码 → 先 `revokeAll` 再写新密码 → 响应清 Cookie）；`AuthChangePasswordVO`；`AuthSessionRepository.revokeAll` 与 Redis 实现；IAM 的 `IamUserService.updatePassword`（`id + version` 条件更新，显式写 `updated_at`）。
- **真实栈验证（本地 MySQL + Redis + `local` profile，2026-09-19）**：
  - 失败与边界：无令牌与乱写令牌访问 `/me` → `401 / AUTH_REQUIRED`；`/refresh` 无 Cookie → `401 / AUTH_SESSION_INVALID`；`/refresh` 与 `/logout` 非法来源 → `403 / ORIGIN_NOT_ALLOWED`；`/logout` 无 Cookie → `200` 且 `Set-Cookie: FLOWDESK_REFRESH=; Max-Age=0`（幂等）。
  - 成功路径：`demo.employee` 登录 `200`（角色 `EMPLOYEE`、三项权限）；带令牌 `/me` `200`；**连续刷新两次均 `200`**（验证轮换返回新令牌的修复）；用第一次登录的旧 Cookie 再刷 → `401 / AUTH_SESSION_INVALID`，且最新 Cookie 随之失效（**重放撤销整个会话**成立）；退出后原令牌访问 `/me` → `401 / AUTH_SESSION_INVALID`。
  - 改密：原密码填错 → `401 / AUTH_INVALID_CREDENTIALS` 且会话不受影响；改密成功 → `200` + 清 Cookie；旧密码登录 `401`、新密码登录 `200`；**改密前的令牌立即失效**。验证后已把演示密码改回 `Demo#FlowDesk2026`（摘要重新生成、`version` 递增，密码本身不变）。
- **排障记录**：启动失败于 `FlywayValidateException: Detected applied migration not resolved locally: 3` → 删除 `flyway_schema_history` 中该行（等价 `flyway repair`）；改密返回 `500 / RedisSystemException` → 根因是 Redis 残留旧实现的 hash 会话键，`revokeAll` 遍历时 `GET` 触发 `WRONGTYPE`，已清理 8 个残留键。
- **两处加固已落地并验证（2026-09-19）**：① `RedisAuthSessionRepository.findById` 现在捕获 `RedisSystemException`（Redis 拒绝执行该命令，例如键类型不对）并按会话无效处理 + WARN；只捕获这一类，不吞连接类异常，Redis 连不上仍显式失败。② `GlobalExceptionHandler` 兜底分支把异常对象传给日志，保留完整堆栈。验证方式：登录建立两个会话，手工把其中一个会话键改成 hash 类型，再用另一个会话的令牌改密——修复前是 `500 / RedisSystemException`，修复后 `200`，且日志出现 `会话键无法读取，按会话无效处理 sessionId=...`；随后旧密码 `401`、新密码 `200`，演示密码已改回 `Demo#FlowDesk2026`，脏键已清理。
- **仍未做**：`TASK-011` 前端（自动化用例已在同日补齐，见上一条记录）。

## 2026-09-19 切片 5 代码完成：刷新轮换、重放检测与幂等退出

- **新增**：`auth/domain/RefreshTokenLookup`（`ACTIVE` / `REUSED` / `UNKNOWN` 三态）、`AuthSession.withRefreshDigest`、`AuthCookieFactory.clearedRefreshTokenCookie`。
- **修改**：`AuthSessionRepository` 与 `RedisAuthSessionRepository` 增加 `findByRefreshDigest` / `rotate` / `revoke`（`consumed:` 标记、沿用剩余 TTL、撤销幂等）；`AuthService` 与实现增加 `refresh` / `logout`，并把签发响应抽成登录与刷新共用的私有方法；`AuthController` 增加 `POST /refresh` 与 `POST /logout`，两者都先校验 Origin 白名单；安全链为 login / refresh / logout 显式 `permitAll`。
- **关键决策**：检测到重放即撤销整个会话；轮换顺序为"先作废旧摘要、再启用新摘要、最后更新快照"（宁可让用户重登，也不让旧令牌继续可用）；轮换不延长会话寿命；退出基于 Refresh Cookie 且幂等，不依赖 `SecurityContext`；来源不在白名单返回新增编码 `403 / ORIGIN_NOT_ALLOWED`（已写入 `docs/api-design.md` 错误表与 3.3 节）。
- **代码审查修复**：`refresh` 原先把**旧** Refresh Token 写回 Cookie，会让第二次刷新命中重放检测并把用户踢下线；安全链中重复的 `login` permitAll 已删除。
- **验证状态**：`./mvnw -B verify` → 单元/Web 19 项、集成 9 项全绿，`BUILD SUCCESS`；切片 5 的行为用例与手工端到端尚未执行。
- **后续状态**：切片 4、5 已连同切片 6 和 `TASK-011` 完成验证，统一纳入阶段 1 分支交接。

## 2026-09-19 切片 4 完成：JWT 请求认证过滤器

- **新增**：`auth/domain/AuthPrincipal`、`auth/security/AuthEntryPoint`、`auth/security/JwtAuthenticationFilter`。
- **修改**：`AuthSecurityConfiguration`（在内 `new` 出过滤器 + `addFilterBefore` + `anyRequest().authenticated()`，入口点改用 `AuthEntryPoint` Bean）、`RedisAuthSessionRepository.findById`（脏值改为 WARN + 按会话无效处理）。
- **失败分流**：过滤器只确定身份、不判断放行。无凭据、JWT 验签/有效期/issuer 失败、以及签名有效但 Redis 会话不存在或快照 `expiresAt` 已过，三种情况都不设置身份；只有会话失效会额外写入 `AuthEntryPoint.SESSION_INVALID_ATTRIBUTE`，受保护路径被授权规则拦下时由入口点据此返回 `AUTH_SESSION_INVALID`，其余返回 `AUTH_REQUIRED`。角色与权限只取会话快照，令牌只提供会话标识。
- **测试证据**：`./mvnw -B verify` → 单元/Web `Tests run: 27, Failures: 0, Errors: 0`，集成 `Tests run: 9, Failures: 0, Errors: 0`（`RedisAuthSessionRepositoryIT` 5 项 + `DatabaseMigrationIT` 4 项），`BUILD SUCCESS`。`AuthWebTest` 覆盖无令牌、坏令牌、过期令牌、会话缺失、会话过期、有效令牌携带身份与权限，以及"过期令牌与会话失效令牌都不能阻断匿名登录"；`RedisAuthSessionRepositoryIT` 在真实 `redis:8.8.0` 上覆盖 JSON 往返、TTL、撤销与脏值。
- **会话失效不短路匿名路径（已确认）**：携带会话已失效的令牌调用刷新或退出不会被拦下，二者照常按 Refresh Cookie 处理；这也是切片 5 实现 logout 的依据——退出必须基于 Cookie 而不是 SecurityContext 中的身份。
- **验收状态**：切片 4 的 8 条验收标准全部满足；真正的 HTTP 端到端验证要等切片 6 的 `/auth/me` 提供受保护业务接口后补做。
- **工作区状态（2026-09-19 更新）**：本节代码已由用户按开工卡重敲完成并挂链；`AuthWebTest` 已补齐（22 项），撤回前那次 27 项的证据已由当前实现重新复现。

## 2026-09-19 切片 4 设计确认

- **已确认的 3 项实现决策**（切片 4，均取推荐方案）：
  1. Token 缺失，或头部存在但验签、有效期与 issuer 校验失败时，过滤器不设置身份、继续链；受保护路径由认证入口点返回 `401 / AUTH_REQUIRED`。理由是 `/auth/refresh`、`/auth/logout` 属匿名接口，浏览器可能仍带过期 Token，立即 401 会打断刷新与退出，而 `TASK-011` 依赖这两条路径。
  2. 401 出口收敛为一个 `AuthenticationEntryPoint`（放在 auth 安全包内）：默认 `AUTH_REQUIRED`，会话不存在、过期或撤销时为 `AUTH_SESSION_INVALID`；过滤器与安全链都委托它，不再各自拼错误响应。
  3. 新增 `auth/domain/AuthPrincipal`（`userId`、`username`、`displayName`、`sessionId`）作为 `SecurityContext` 的 principal，不直接使用 `AuthSession`（其中含 `refreshDigest` 等凭据相关字段）。authorities 只写权限裸码，角色编码留在 principal 供展示与菜单使用。
- **已核实的关键事实**：`AuthSecurityConfiguration` 当前只声明 `POST /fd/v1/auth/login` 的 permitAll；Spring Security 6.5.11 的 `AuthorizationFilter` 在授权管理器返回 null 决策时直接放行（已用 javap 核对字节码），即 `/fd/v1/auth/**` 下其余路径目前匿名可达。切片 4 必须在 auth 链补 `anyRequest().authenticated()`，否则切片 6 的 `/auth/me` 会成为匿名接口。
- **JaCoCo**：覆盖率门禁取消，`pom.xml` 只保留 `jacoco:report`；不再作为阶段交接的阻塞项。
- **本轮未改业务代码**：按切片 4 协作方式，业务代码由用户编写，Codex 提供小步目标、设计说明、验收标准与测试建议。

## 2026-09-18 TASK-010 切片 1～3 交接记录

- **切片 1：已完成**。完成 `JwtProperties`、`AuthProperties` 配置绑定与 Argon2id 密码组件。
- **切片 2：已完成**。完成 IAM 认证查询，按用户、用户角色、角色、角色权限、权限执行 5 次单表查询，得到用户及角色/权限编码。
- **切片 3：已完成**。登录成功后创建 Redis 会话、签发 JWT Access Token，并通过 HttpOnly Cookie 写入 Refresh Token。
- **端到端证据**：正确凭据返回 `200`、Access Token 与 `Set-Cookie`；错误密码返回 `401`；Redis 生成 3 类键，TTL 为 7 天，只保存 Refresh Token 摘要，不保存原文。
- **自动化证据**：19 项单元测试与 4 项迁移集成测试全绿。
- **基础设施修复**：V4 迁移将密码列重命名且保持幂等；`local` profile 自动包含 `demo`，本地启动无需重复声明。
- **下一切片边界**：只完成请求认证，不实现 refresh 轮换、旧令牌重用检测、logout、`/auth/me`、本人改密或 Vue 页面。

## 2026-09-18 范围收敛记录

- **交付层级**：后续路线改为“求职 MVP + 完整版”两层，当前只推进 MVP。
- **MVP 角色**：固定普通员工、IT 支持人员、系统管理员三种内置角色；用户可拥有多个固定角色，但不开放动态角色/权限管理。
- **MVP 主链**：Auth → 员工无附件创建/查询 → IT 领取/处理/提交解决 → 员工确认 → 演示与测试收口。
- **完整版后置**：完整状态机、附件和关联、超时任务、管理端、数据概览；动态 RBAC 仍是可选项而非必做项。
- **当前前置**：P0 问题和 `TASK-010` 切片 1～3 已完成；下一会话直接开始切片 4。
- **迁移说明**：此前动态 RBAC 尝试产生的未跟踪 `V3__seed_rbac_manage_permission.sql` 已清理；MVP 不预置 `RBAC_MANAGE`，如完整版后续启用动态 RBAC，必须另建迁移并重新确认。

以下日期记录仅用于保留历史过程；其中 IAM、动态 RBAC 或旧 Auth 的“已实现/已删除”描述不能覆盖上方最新交接记录，当前状态始终以“快速定位”和 `TASK-010` 切片 1～3 交接记录为准。

## 2026-09-08 工作记录

- 创建 IAM 模块的 domain、BO/VO、Controller、Service、Mapper 等基础结构，并接入 MyBatis-Plus。
- 增加通用 `PageQuery`、`PageResult` 和分页拦截器；本地环境启用 MyBatis SQL 日志。
- 完成用户分页查询、创建用户及密码 Argon2id 哈希的基础实现。
- 完成账号启用/停用的目标状态幂等设计与 `status + version` 条件更新基础实现。
- 完成管理员重置密码的请求校验、权限注解、Argon2id 哈希和乐观版本条件更新；Redis 全会话撤销尚待 `TASK-010` 接入。
- 管理员重置密码及 IAM Service 相关单测共 8 项通过；全量 `mvnw test` 共 21 项，其中 5 项因测试上下文排除 MyBatis-Plus 后缺少 Mapper Bean 而失败。
- 当前 IAM 用户管理属于后续 `TASK-051` 能力的提前基础搭建，不计为 M1 已完成成果。

## 2026-09-09 工作记录

- 用户详情现在校验正整数 ID，目标不存在时返回 `404/USER_NOT_FOUND`，不再返回空成功。
- 新增修改用户专用 `IamUserUpdateVO`，只接收并校验显示名称与版本。
- 修复修改用户逻辑：删除更新失败后的错误 `insert`，改为 `id + version` 条件更新，成功后版本递增，丢失并发竞争时返回 `409/USER_CONFLICT`。
- 创建用户处明确保留 `TASK-051` TODO：后续校验至少一个预置角色，并在同一事务中写入 `iam_user_role`。
- 新增用户不存在、修改成功、修改目标不存在和旧版本冲突单测；因当前终端 JDK 路径失效，本次尚未实际运行。

### Review 待修正

1. 为用户列表、详情、创建和修改接口补充 `USER_MANAGE` 权限；未实现的角色替换接口不得直接返回成功。
2. 为分页排序建立固定字段白名单，禁止把客户端 `orderBy` 原样拼入 SQL。
3. 创建用户需按既定契约处理至少一个角色；停用和重置密码需撤销目标用户全部会话。
4. ~~清理 `pom.xml` 中重复的 MyBatis-Plus Generator 依赖~~ **已完成**：工作区已收敛为单一的 `mybatis-plus-generator` + `freemarker` 依赖，两者均为 `test` scope。

## 2026-09-17 工作记录：Auth 模块重建

- **已确认**：HEAD（`40a1264`）里的实验性 Auth 实现全部废弃，按 `docs/modules/auth.md` 从零重建；不再从旧实现恢复文件。
- 重建内容：`JwtProperties`、`AuthProperties`、`AuthSession`、`AuthClaims`、`RefreshTokenUtils`、`JwtTokenService`、
  `AuthSessionRepository` 与 `RedisAuthSessionRepository`、`TokenService`、`AuthService`、`AuthCookieFactory`、
  `RefreshOriginFilter`、`AuthSecurityConfiguration`、`AuthController` 与请求/响应对象。
- 会话模型：Redis 只存 Refresh Token 的 SHA-256 摘要；`flowdesk:auth:refresh:{digest}` 在轮换后改写为
  `consumed:{sessionId}` 以检测旧令牌重放；`flowdesk:auth:user:{userId}` 支持撤销该用户全部会话；键 TTL 与会话剩余有效期一致。
- 安全链：修复了工作区此前完全没有 `SecurityFilterChain` 的状态；登录、刷新、退出匿名放行，刷新与退出另加精确
  `Origin` 白名单校验；401 细分为 `AUTH_REQUIRED` 与 `AUTH_SESSION_INVALID`；权限编码以裸码写入授权集合，与 IAM 的
  `@PreAuthorize("hasAuthority(...)")` 对齐。
- 配置收敛：`flowdesk.allowed-origins` 与重复的 `flowdesk.cookie-secure` 合并到 `auth.allowed-origins` 与
  `auth.cookie-secure`，环境变量名不变；新增 `@ConfigurationPropertiesScan`。
- IAM 侧新增 `IamUserService.changePassword(userId, newPassword)`：只负责哈希与条件更新，原密码校验与撤销全部会话由
  `AuthServiceImpl.changePassword` 在同一事务内完成（先撤 Redis 再提交 MySQL，符合跨存储失败顺序）。
- 测试基建：新增 `MockedPersistenceConfiguration` 提供 IAM Mapper 替身，修复了公共 Spring 测试因缺少 Mapper Bean
  无法启动的问题；`FlowDeskApplicationTest`、`ApiFoundationWebTest`、`AuthWebTest` 改用 `test` Profile。
- 测试证据：`JAVA_HOME=/d/Idea/Jdk/Jdk21 ./mvnw -B test` → `Tests run: 66, Failures: 0, Errors: 0`（此前为 25 项中 5 项错误）；
  集成测试 `RedisAuthSessionRepositoryIT` 使用真实 `redis:8.8.0` 容器覆盖 TTL、摘要索引、轮换与撤销。
- 仍未完成：`TASK-011` Vue 登录外壳与端到端登录；IAM 停用/重置密码的会话撤销（`TASK-051`，需先确认模块依赖方向）；
  ~~后端端口 `8081` 与前端代理、CI、README 的 `8080` 不一致~~（已于 2026-09-18 统一为 `8081`）。

## 2026-09-17 工作记录：IAM 模块整理与完善

- **整理**：删除未使用的空壳类（`IamPermissionController`、`IamRolePermissionController`、`IamPermissionService`、`IamRolePermissionService` 及其实现）和 4 个只含未使用 resultMap 的 Mapper XML；重命名 `IamCrateRoleVO`→`IamRoleCreateVO`、`GrantURVO`→`IamUserRoleGrantVO`、`IamUserVO`→`IamUserQueryVO`、`IamUserRoleVO`→`IamUserRoleQueryVO`、`IamUserRoleUpdateVO`→`IamUserRoleReplaceVO`，`IamRoleVO` 拆分为查询、创建、更新三个 VO；修正 `IamRoleBO` 字段大小写、`deleteRole` 返回值、`getIamRoleByIds` 中的死代码，并把 `IllegalArgumentException` 统一改为带错误编码的 `ApiException`。
- **修错**：`IamUserRoleMapper` 的自定义 SQL 写的是 `user_role` 表（实际表名为 `iam_user_role`）、插入漏了 `granted_at` 并使用 `INSERT IGNORE` 吞异常，现已改为 XML 中的正确语句；`getUserRolePage` 之前直接返回 `null`，现已实现。
- **权限注解**：用户详情、修改资料、替换角色补 `USER_MANAGE`；角色与用户角色授权接口按契约要求 `RBAC_MANAGE`（迁移 `V3__seed_rbac_manage_permission.sql` 预置该权限并授予 `SYSTEM_ADMIN`，未修改历史迁移）。
- **用例完善**：用户列表支持关键词、状态和角色筛选，详情返回角色；创建用户必须在同一事务内写入至少一个角色；新增替换角色用例（携带版本、完整集合、集合未变化时幂等）；停用账号、重置密码、替换角色、授予和撤销角色都会撤销目标用户全部会话。
- **保护规则**：内置 `SYSTEM_ADMIN` 角色禁止删除、仍被用户或权限引用的角色禁止删除（`409/RBAC_CONFLICT`）；禁止停用最后一个启用的管理员、禁止出现失去最后管理员或零角色（`409/LAST_ADMIN_PROTECTED`、`409/USER_ROLE_REQUIRED`）。
- **模块依赖方向**：IAM 定义 `SessionRevocationPort`，由认证模块的 `IamSessionRevocationAdapter` 实现，IAM 仍不依赖认证模块，没有形成模块环。
- **分页排序白名单**：`PageQuery.orderItems(Set<String>)` 只接受调用方声明的字段，非白名单字段与未知排序方向返回 `400/VALIDATION_FAILED`。
- **顺带修复的公共缺陷**：`HttpMessageNotReadableException` 之前会返回 `500`，现已按 `400/VALIDATION_FAILED` 处理，并在 `ApiFoundationWebTest` 增加对应用例。
- 测试证据：`JAVA_HOME=/d/Idea/Jdk/Jdk21 ./mvnw -B test` → 114 项通过；`verify` 集成测试 16 项通过，其中新增 `IamPersistenceIT` 6 项在真实 MySQL 上验证角色写入、认证快照映射、引用保护与最后管理员保护；真实栈验证覆盖用户列表筛选、排序白名单、角色 CRUD（201/409/400）、授权关系列表、403 权限与 409 保护。
- 仍未完成：权限 CRUD 端点、角色权限授权端点、停用账号的管理性交接方案（均属 `TASK-051` 剩余部分）。

## 2026-09-18 工作记录：清空 iam 与 auth 业务代码

- **用户要求**：删除 `iam`、`auth` 下的全部内容，`iam` 下按数据库表保留空壳。经确认采用“只留实体 + Mapper 骨架”方案，且不做备份。
- **保留**：`iam/domain/IamUser`、`IamRole`、`IamPermission`、`IamUserRole`、`IamRolePermission` 五个实体，`iam/mapper` 下五个 Mapper 接口与 `package-info.java`。
- **删除**：整个 `com.flowdesk.auth` 包；`iam` 的 controller、service（含 impl）、`domain/bo`、`domain/vo`、`IamUserStatus`、`IamRoleAssignmentRules`；`resources/mapper/iam` 下两个 Mapper XML；`src/test` 下 `iam`、`auth` 的全部测试。
- **可恢复性（2026-09-18 更正）**：原记录写「这批内容基本都未提交…删除后无法从 git 找回」，经核实**不准确**。
  `HEAD`（`40a1264`）完整包含上一轮 IAM 与 Auth 实现，且被删除的工作区版本与 `HEAD` 一致（`git status` 显示为未修改的删除），可用 `git checkout HEAD -- <路径>` 逐个取回：
  - `iam`：5 个 Controller、5 个 Service 及实现、`domain/bo` 4 个 BO、`domain/vo` 8 个 VO、`IamUserStatus`、`IamUserRoleMapper` 等
  - `auth`：`AuthProperties`、`AuthController`、`AuthSession`、`AuthSessionRepository`、`RedisAuthSessionRepository`、`SecurityConfig`、`AuthLoginService`、`TokenService` 及实现
  **真正不可恢复的**只有 2026-09-17 重建引入、尚未提交的那一批（`JwtProperties`、`AuthClaims`、`RefreshTokenUtils`、`JwtTokenService`、`AuthCookieFactory`、`RefreshOriginFilter`、`AuthSecurityConfiguration`、`SessionRevocationPort`、`IamSessionRevocationAdapter` 等）。
  当前并不需要恢复：`TASK-010` 要按 `docs/modules/auth.md` 重写，`HEAD` 版本只作为参考实现按需取用。
- **构建产物**：一并删除 `target/`，其中残留了已删除模块的 `.class` 文件，避免下次启动时被类路径扫描到。
- **遗留的编译错误**：~~保留的骨架引用了被删类~~ **已于 2026-09-18 修复**，详见下方同日工作记录。
- **其他待同步项**：`application.yml`、`application-test.yml` 中的 `jwt:` 与 `auth:` 配置块当前无绑定类——**有意保留**，配置键已确认，`TASK-010` 重建 `JwtProperties`、`AuthProperties` 后自动生效，不影响启动；`docs/modules/auth.md` 与 `AGENTS.md` 的阶段描述已于 2026-09-18 同步。

## 2026-09-18 工作记录：修复编译错误、补回安全基线、统一端口

- **编译错误修复（4 处）**：
  - `iam/domain/IamUserStatus.java`：从 `HEAD` 取回原文件（普通枚举，无 `@EnumValue`）。该文件此前处于 staged 删除状态，工作区缺失导致编译中断。
  - `iam/mapper/IamUserMapper.java`：方法 `selectAuthenticationByUsername` 由用户自行删除，本次只清理遗留的 `@Param` import。
  - `FlowDeskApplicationTest`、`ApiFoundationWebTest`：各删除 1 处指向已删 `AuthSessionRepository` 的 `@MockitoBean` 字段。
- **补回公共安全基线**：`common/config/FoundationSecurityConfiguration` 此前只有 `@EnableMethodSecurity`、没有 `SecurityFilterChain`，Spring Boot 因此回落到默认链。默认链开启 CSRF（POST 被拦成 `403` 而不是 `400`）且未认证响应体为空（`$.code` 断言失败）。现新增 `foundationSecurityFilterChain`：关闭 CSRF/httpBasic/formLogin/logout、会话策略 `STATELESS`、放行 `/actuator/health` 与 springdoc 路径、认证入口点经 `ApiErrorWriter` 输出 `401 / AUTH_REQUIRED` 错误信封。
- **架构取舍**：这条链放在 `common` 而不是 `auth`。它是 M0/`TASK-003` 的公共契约，也正是 `ApiFoundationWebTest` 断言的对象；此前的链随 auth 模块一起被删、公共契约测试立即变红，说明归属不当。`TASK-010` 只需在其上叠加 JWT 过滤器与 `/auth/**` 放行规则。
- **端口统一为 `8081`**：`README.md`、`.github/workflows/ci.yml` 健康检查、`frontend/vite.config.ts` 代理三处由 `8080` 改为 `8081`，与 `application.yml` 一致。
- **测试证据**：`JAVA_HOME=/d/Idea/Jdk/Jdk21 ./mvnw -B test` → `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- **本次未做**：`resources/mapper/` 目录仍缺失（复杂 SQL 存放位置），等 `M2` 用到时再建；`HEAD` 中的参考实现未取回，`TASK-010` 可按需 `git checkout HEAD -- <路径>` 查阅。

## 已确认的架构摘要

- 前端采用 Vue 3 SPA，后端采用 Java 21、Spring Boot 3 模块化单体。
- 后端按业务模块组织，模块内适度分层，保持单向依赖。
- MySQL 是持久业务事实的唯一权威来源。
- Redis 保存 JWT Token 会话、Refresh Token 状态和撤销信息，不保存工单事实，也不承担工单分布式锁。
- 身份认证使用 Spring Security、短期 JWT Access Token、可轮换 Refresh Token 和 Redis 会话校验。
- 工单状态变化使用 MySQL 事务、条件更新和乐观并发控制。
- 超时工单由 Spring 定时任务幂等处理。
- v1 附件保存在受后端保护的本地持久化目录，MySQL 保存元数据。
- v1 单实例运行，不引入微服务、消息队列、Kubernetes 或分布式任务平台。
- 后端使用 Maven、Spring Boot 3.5.16 和 MyBatis-Plus 3.5.17；简单 CRUD 使用通用 Mapper，复杂业务查询保留自定义 SQL/XML。
- JWT 使用 `spring-security-oauth2-jose` 提供的 Nimbus 实现，以 HS256 签发和校验 Access Token。
- 密码使用 Argon2id 单向哈希；Docker Compose 只运行 MySQL 和 Redis；前端使用 Element Plus。

## 下一步任务

任务名称：求职 MVP 阶段 2——员工创建与查询（`TASK-020`～`TASK-023-MVP`）。

目标：

- 实现启用分类选项，以及工单列表的 `own`、`queue`、`assigned`、`participated` 显式 scope。
- 员工使用 `submissionKey` 幂等创建无附件工单；工单快照、首条时间线和提交人参与事实同一事务写入。
- 实现员工工单列表、新建、详情与不可变时间线，并验证登录 → 创建 → 列表 → 详情完整路径。

阶段验收：

- 员工重复点击不会制造重复工单。
- 员工只能查看自己的工单，越权访问统一返回 `404/TICKET_NOT_FOUND`。
- 创建结果和时间线可在刷新后从后端恢复。

协作方式：

- 用户负责编写业务代码；除非用户明确授权代写，Codex 只提供小步目标、设计说明、验收标准、代码 review 与测试建议。

本步骤暂不做：

- IT 领取、处理和员工确认闭环（阶段 3）。
- 附件、完整状态机、管理端和数据概览。
- 角色、权限及授权关系的在线 CRUD。
- 不引入已确认技术边界之外的基础设施。

## 当前任务必读

开始阶段 2 前，按以下顺序读取：

1. `AGENTS.md`
2. `PROJECT_STATUS.md`
3. `docs/implementation-plan.md` 第 6 节（阶段 2 范围与验收）
4. `docs/business-model.md` 中工单、时间线、参与人与幂等约束
5. `docs/api-design.md` 中分类选项、工单列表、创建、详情与时间线契约
6. `docs/database-design.md` 中阶段 2 涉及的表、索引和事务约束

`docs/kickoff.md` 已完成并作为业务规则来源；只有在业务模型无法回答具体流程或权限问题时，才回查对应小节，不需要默认全文重读。

## 文档索引

| 文件 | 状态 | 用途 |
| --- | --- | --- |
| `AGENTS.md` | 生效中 | 协作规则、阶段顺序和当前限制 |
| `README.md` | 已同步 | 项目入口、目标、技术方向和总体状态 |
| `docs/kickoff.md` | 已完成 | v1 需求、流程、权限和验收依据 |
| `docs/business-model.md` | 已完成 | 业务概念、关系和不变量 |
| `docs/technical-architecture.md` | 已完成 | 总体架构、模块和核心技术机制 |
| `docs/database-design.md` | 已完成 | 已确认的逻辑模型、MySQL 物理模型、约束和索引依据 |
| `docs/api-design.md` | 已完成 | API 全局规范、接口契约、校验、错误与权限策略 |
| `docs/engineering-readiness.md` | 已完成 | 依赖、配置、迁移、测试、CI、启动、失败处理与页面映射 |
| `docs/implementation-plan.md` | 已完成 | 纵向里程碑、任务依赖、验收、测试和 Git 检查点 |
| `docs/project-highlights.md` | 仅追加 | 简历与面试可用的设计亮点；非阶段默认必读 |

## Git 约定

- `main` 只保存已经完成检查的稳定成果。
- 每项工作使用 `flow-desk/<主题>` 短期分支。
- 新机器开始开发时，先拉取最新 `main`，再从 `main` 创建本文件记录的“下一次创建分支”。
- 已完成的设计文档批次使用 `flow-desk/pre-development-design`；不得基于该旧分支继续后续开发。
- 提交按单一意图拆分，推荐使用 `docs:`、`feat:`、`fix:`、`test:`、`refactor:` 和 `chore:` 前缀。
- 每个阶段通过验收后，依次完成状态同步与检查、当前主题分支提交、分支推送、合并请求进入远程 `main`、本地 `main` 仅快进拉取，以及从最新 `main` 创建下一主题分支；不得强制推送 `main`。
- 阶段达到验收条件时，Codex 必须主动提示上述交接流程；未经用户明确授权，不执行提交、推送或合并请求操作。
- 可运行版本使用版本标签；设计阶段标签只在明确里程碑需要时创建。

## 更新时机

需要更新本文件：

- 一个阶段通过验收或进入下一阶段时。
- 关键业务或技术决策改变后续实施方案时。
- 当前任务被阻塞或暂停，需要跨会话、跨机器交接时。
- 当前工作分支准备交接、创建合并请求或合并到 `main` 前。

不需要更新本文件：

- 每次克隆、拉取或切换机器后。
- 同一任务中的普通小提交。
- 没有改变当前阶段和下一步的文档格式调整。
- 仅运行检查或测试且结论没有改变时。

更新后应再次确认本文件、`README.md` 和 `AGENTS.md` 对当前阶段的描述一致。
