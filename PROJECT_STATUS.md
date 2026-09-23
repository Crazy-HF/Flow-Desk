# FlowDesk 项目状态

> 本文件用于新机器、任务恢复和工作交接时快速定位项目，不替代详细设计文档。

## 快速定位

- 最后更新：2026-09-23
- 远程仓库：`git@github.com:Crazy-HF/Flow-Desk.git`
- 稳定分支：`main`
- 当前基线分支：`main`（阶段 1 与前端外壳工作项均已合并，最新合并提交 `2993a2a`）
- 当前工作分支：`flow-desk/dynamic-rbac`（从最新 `main` 的 `2993a2a` 创建，**尚未推送**——按仓库惯例，推送发生在该阶段完成检查、准备合并时）
- 下一次创建分支：本文档四步路线的第 4 步（阶段 2 `TASK-020`～`TASK-023-MVP`）在本阶段交接完成后从最新 `main` 创建，名称待定。已存在的 `flow-desk/employee-ticket-flow` 只含 `7f6c72c` 一条文档同步提交，不用于本轮开发
- 当前阶段：第 3 步 **系统业务：完整动态 RBAC**（四步路线的 ① 收口前端外壳与 ② 交接已于 2026-09-21 完成，工作项经 PR #6 合并进入 `main`）。**阶段设计 2026-09-21 确认、2026-09-22 补充 3 项**：13 项设计决策与 `TASK-055`～`TASK-060` 任务拆分、验收标准见 `docs/implementation-plan.md` 9.1。`TASK-055`、`TASK-056`、`TASK-057`、`TASK-059` 已完成；**`TASK-058` 的生产实现与测试已全部完成（11 个端点，2026-09-22 测试补齐，见下方记录），仅剩验收标准第 4 条的逐条手工真实栈链路**。**下一开发步骤为 `TASK-060`（RBAC 管理端页面）**，之后阶段收口。本阶段计入求职 MVP 演示范围并包含管理端页面。
- 最新完成（2026-09-23，**未提交**）：**用户管理模块测试补齐 + 测试代码对齐应用层重构**——用户管理 `/fd/v1/users` 八个端点（列表、详情、创建、改资料、启用、停用、替换角色、重置密码）现有完整测试：服务单测 `IamUserServiceImplTest`(58)、Web 契约 `IamUserControllerWebTest`(45)、真实 MySQL 集成 `IamUserServiceIT`(24，含两条真并发)；13 个 RBAC/auth 测试类同时对齐 `application.*` 新包并移动到镜像包，修好重构引入的 2 项 `AuthWebTest` 失败并补 13 项新测试。`./mvnw -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"` → 单元/Web **394** + 集成 **88**，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。见下方 2026-09-23 两条记录。
- 前一项完成（2026-09-23，**未提交**）：**auth、iam 生产代码应用层重构**——应用层统一为 `application.command` / `query` / `result` / `service`，实现入 `application.service.impl`，跨模块接口入 `iam.application.port`，Controller 直接收发 Command/Query/Result，`domain` 不再存放 BO/VO，且未新增重复的 Request/Response 类型。**接口地址、JSON 字段与业务行为不变**。原阻塞「13 个测试类引用已删除旧包导致 `test-compile` 失败」已由测试对齐解除。
- 已确认的范围调整：项目分为“求职 MVP”和“完整版”；三种内置角色 `EMPLOYEE`、`IT_SUPPORT`、`SYSTEM_ADMIN` 仍是权限基线，`SYSTEM_ADMIN` 始终受保护。**2026-09-21 用户确认把「完整动态 RBAC」定为第 3 步实施**：按 `docs/api-design.md` 8.2.1 开放角色、权限、用户角色授权、角色权限授权四组 CRUD，新增 Flyway 迁移 `V5` 预置 `RBAC_MANAGE` 并授予受保护的 `SYSTEM_ADMIN`，配套保护规则、会话撤销与审计；不得修改已发布的历史迁移。该能力已于 2026-09-22 确认计入求职 MVP 演示范围，并包含管理端页面。
- 前一项完成：**`TASK-058` 测试补齐与全量验证**（2026-09-22）——用户角色与角色权限两组授权现共 **11 个端点**（原 6 个 + 批量撤销 `POST /actions/revoke`、清空全部 `DELETE /users/{userId}` 与 `DELETE /roles/{roleId}`、一个角色授予多个用户 `POST /actions/grant-users`、建角色时带 `permissionIds`）。按用户 2026-09-22 确认，**保护规则 5「用户至少保留一个角色」废弃**，零角色成为合法终态（`USER_ROLE_REQUIRED` 不再产生），仅保留「最后一个启用管理员」与「`SYSTEM_ADMIN` 的 `RBAC_MANAGE` 授权」保护。测试侧新增/扩展 8 个测试类，`./mvnw -B clean verify` → 单元/Web **278** + 集成 **63**，`Failures: 0, Errors: 0, BUILD SUCCESS`（阶段起点 132 + 30，只增不减）。覆盖矩阵逐格证据见 `docs/modules/rbac.md` 10.1。此前的 `TASK-059` 成果：`AuthSecurityConfiguration` 的 `securityMatcher` 扩为 `/fd/v1/**`，`/fd/v1/admin/**` 不再恒为 `401`，并用真实 Access Token 补了回归测试。
- 下一步（四步路线，2026-09-21 用户指示）：① ~~收口并提交前端外壳与页面骨架工作项~~ **已完成**；② ~~完成该工作项交接（推送 → 合并请求 → 合并 `main` → 同步 → 建下一分支）~~ **已完成**；③ **系统业务：完整动态 RBAC**（分支 `flow-desk/dynamic-rbac`）——`TASK-055`、`TASK-056`、`TASK-057`、`TASK-059` 已完成，`TASK-058` 实现与测试均已完成（手工真实栈链路待补），**下一步 `TASK-060`（管理端页面）→ 阶段收口**；④ 阶段 2 员工创建与查询——先确认 `TASK-020` 到 `TASK-023-MVP` 的接口与数据模型落地顺序，再按切片实施。**2026-09-23 追加前置（已完成）**：~~先补齐 13 个测试类对新包的引用（恢复 `test-compile`）~~ **已由 Agent 完成**（单元/Web 394 + 集成 88 全绿）；~~待裁决用户管理在制品~~ **用户已实现、Agent 已补齐测试**（见「待确认事项」④⑤与同日记录）。下一步仍是 `TASK-060`。
- 当前阻塞：**无**。两条本机限制已定位并写入「环境前置」而非记为缺陷：① Mockito inline mock maker 需自附加，受限沙箱下必须加 `-DargLine=-Djdk.attach.allowAttachSelf=true`；② Testcontainers 集成测试需访问 Docker 命名管道，受限沙箱下需放宽文件策略。原先的阻塞「真实 HTTP 下 `/fd/v1/admin/**` 恒为 `401/AUTH_REQUIRED`」已由 `TASK-059` 修复并通过真实栈复核；`TASK-058` 已有完整测试证据，但**尚未做验收标准第 4 条的四条手工真实栈链路**，因此本阶段还不能收口。工作区在 `flow-desk/dynamic-rbac`（尚未推送）。
- 环境前置：JDK 21.0.12、Node.js 24.20.0、pnpm 12.3.4、Docker 29.7.2 已验证；本机已有 `redis:8.8.0`、`mysql:8.4.11` 镜像。**跑后端测试的两个必备参数（2026-09-23 实测，会复发）**：① 受限沙箱下 Mockito inline mock maker 无法自附加，必须加 `-DargLine=-Djdk.attach.allowAttachSelf=true`，否则所有 Spring 测试一起报 `Could not self-attach to current VM using external process`（看起来像代码回归）；② Testcontainers 集成测试需要访问 Docker 命名管道 `\\.\pipe\docker_engine`，受限沙箱会报 `Could not find a valid Docker environment` / `AccessDeniedException`，需在放宽文件策略的会话里执行。完整验收命令：`.\mvnw.cmd -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"`。本地启动 profile 用 `local` 即可（`spring.profiles.group.local=demo` 已配置）。演示账号 `employee` / `it` / `admin`，密码统一为 `123456`（见 `db/demo/R__seed_demo_data.sql` 头部注释，2026-09-20 由 `demo.*` 改名）。本机已有过两类运行障碍并已修复：① Flyway 校验失败——历史表残留已删除的 V3 迁移记录，处置为删除该行（等价 `flyway repair`）；② Redis 残留旧实现写入的 hash 类型会话键，会让"撤销全部会话"抛 `WRONGTYPE`，已清理。另需注意：本机 Argon2id 校验约 2 秒/次（并发登录可拖到十几秒），前端 e2e 因此串行执行并放宽超时；跑 e2e 需要 `FLOWDESK_ALLOWED_ORIGINS` 包含 `http://127.0.0.1:4173`（本地 `.env` 已加）
- 待确认事项：① Element Plus 目前是**全量引入**（打包约 1.07 MB / gzip 348 KB），是否改为按需引入（需新增 `unplugin-vue-components`、`unplugin-auto-import` 两个 dev 依赖）；② 登录页占位文案是「登录名」「密码」，与 `frontend/AGENTS.md` 新增的「请输入…／请选择…」约定不一致（E2E 定位依赖现文案，改文案需同时改用例）；③ 首页 `h1`「欢迎回来」用的是展示级字号 `clamp(1.75rem, 5vw, 2.5rem)`，是否收小到页面标题刻度；④ ~~越界的用户管理在制品如何处置~~ **2026-09-23 已由用户以行动裁决：保留并继续完成**——用户实现了 `disable` 与 `replaceRoles`，`/fd/v1/users` 现为 8 个端点，Agent 已补齐测试（单元/Web 394 + 集成 88 全绿）。**仍待确认**：它是否要正式登记为 `docs/implementation-plan.md` 的任务条目，以及 `docs/api-design.md` 8.1/8.2 与 `docs/modules/rbac.md` 第 1 节把用户管理写在「完整版 backlog」的表述是否同步改写；⑤ ~~`disable` 与替换角色是否属于本次补齐范围~~ **已包含**（两者均已实现并有测试）；⑥ ~~测试职责口径~~ **2026-09-23 已澄清：测试由 Agent（本会话执行者）负责**，`AGENTS.md`「测试代码职责」的措辞已按此更新，`Codex` 在文档中的其余用法未改；⑦ **替换角色路径单复数不一致**（2026-09-23 新增）：实现 `PUT /fd/v1/users/{userId}/role` vs 契约 `docs/api-design.md` 8.2 的 `/roles`——改契约还是改实现需用户决定；改实现时 Web 测试的 `USERS + "/" + USER_ID + "/role"` 需同步。`TASK-060` 的用户输入形态、批量授权、权限码搜索与 `api/` 落层均已确认，见下一节。历史处置：JaCoCo 覆盖率门禁已确认取消（2026-09-19），`pom.xml` 只保留 `jacoco:report` 供 CI 上传工件。

## 2026-09-23 用户管理模块测试补齐（单元/Web 394 + 集成 88 全绿）

- **来源**：用户 2026-09-23 在「测试代码对齐应用层重构」之后指示"测试补充"，并确认用户管理已写完（`disable`、`replaceRoles` 均已实现）。该模块**此前一行测试都没有**。
- **被测端点面（8 个，前缀 `/fd/v1/users`，全部要求 `USER_MANAGE`）**：`GET /`（分页 + keyword/status/roleId 筛选）、`GET /{userId}`、`POST /`（201）、`PUT /{userId}`、`POST /{userId}/actions/enable`、`POST /{userId}/actions/disable`、`PUT /{userId}/role`（替换完整角色集合）、`POST /{userId}/actions/reset-password`。
- **测试产出（Agent 负责，只新增/修改 `src/test/`）**：
  - `IamUserServiceImplTest`（单元，58 项）：`updatePassword` 的版本条件更新返回真值；`page` 的默认 `id asc`、白名单外排序 400、关键字 trim/空白视为无筛选、空页不查角色表、按用户分组角色且角色表只查一次；`getById` 的非正整数与缺失；`create` 的预检查冲突、唯一索引兜底转 `USERNAME_CONFLICT`、**先编码密码再加锁**的顺序、角色去重升序、任一角色缺失整单失败、锁超时转 `RBAC_CONFLICT`、零角色不查操作人、审计两列；`update` 的三分支（缺失/版本冲突/成功回读）；`enable`/`disable` 的幂等短路、版本冲突、**先锁 `SYSTEM_ADMIN` 再锁用户**的锁顺序、"最后启用管理员"保护与其放行条件；`replaceRoles` 的**锁集合必须无条件包含 `SYSTEM_ADMIN`**、差集增删、集合不变时零写入零撤会话、删除行数不符转 `RBAC_CONFLICT`、停用用户可移除管理员角色、零角色终态；`resetPassword` 的编码顺序与撤会话；六个写方法的 `@Transactional` 边界。
  - `IamUserControllerWebTest`（Web 契约，45 项）：8 个端点的**无令牌 401 / 缺 `USER_MANAGE` 403** 矩阵、查询参数绑定与响应信封、创建 201、`R<Void>` 响应不含 `data`（全局 `jackson.default-property-inclusion=non_null`）、10 组校验失败、以及 404/409 错误码映射。
  - `IamUserServiceIT`（真实 MySQL + Testcontainers，24 项）：注解 SQL `selectUserPage` 的 keyword/status/roleId 动态条件与分页总数、白名单排序 400、真实唯一索引、创建的角色缺失整单回滚、密码只落摘要、版本条件更新的真实影响行数、启停幂等、替换角色的增删与"未变关系保留原审计"、重置密码；**两条真并发**——① 并发创建同一登录名时恰好一个成功、另一个 `409/USERNAME_CONFLICT`；② 两个启用管理员并发停用各自账号时只放行一个（另一个 `409/LAST_ADMIN_PROTECTED`），终态仍保留 1 个启用管理员。
- **自动化证据（已验证，2026-09-23）**：`$env:JAVA_HOME='D:\Idea\Jdk\Jdk21'; .\mvnw.cmd -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"` → 单元/Web **394**、集成 **88**，`Failures: 0, Errors: 0`，`BUILD SUCCESS`（重构前基线 278 + 63，只增不减）。**集成测试本次已真实执行**（Docker Desktop 29.7.2 + `mysql:8.4.11` / `redis:8.8.0`），上一节"集成测试受沙箱限制未执行"的限制已消除。
- **发现的代码事实（只记录，未改任何生产代码；影响面与处置待用户决定）**：
  1. **路径与契约不一致**：实现是 `PUT /fd/v1/users/{userId}/role`（单数），`docs/api-design.md` 8.2 写的是 `/roles`（复数）。Web 测试按实现断言并在类注释里标注，登记为「待确认事项」⑦。
  2. `LAST_ADMIN_PROTECTED` 的文案固定为"不能停用最后一个启用管理员"，但替换角色路径也复用它，语义略窄（`IamUserServiceImpl.lastAdminProtected()`）。
  3. `UserQuery.keyword` 没有 `@Size` 上限，而 `PermissionQuery.keyword` 有 `@Size(max = 100)`；`IamUserServiceImpl.page` 用的是 `selectBatchIds`，同模块其他位置用 `selectByIds`。
  4. `disable` 与 `replaceRoles` 里的"管理性交接"（`handoffReason` + `handoffs`，见 `docs/api-design.md` 8.3）仍是 `TODO`，与工单模块尚未实现一致。
- **未做（不越界）**：未改动任何生产代码；未把用户管理写进 `docs/implementation-plan.md` 的任务表，也未改写 `docs/api-design.md` 8.1/8.2 与 `docs/modules/rbac.md` 第 1 节里"属完整版 backlog"的表述——这两处属于范围与契约口径，等用户裁决后再同步。
- **同步的文档**：本文件、`AGENTS.md`（「测试代码职责」的执行者措辞与当前阶段限制）。

## 2026-09-23 测试代码对齐应用层重构（单元/Web 291 全绿；集成测试受本机沙箱限制未执行）

- **来源**：用户 2026-09-23 指示「项目架构有些更改，进行测试功能的完善」——把测试代码对齐同日应用层重构，并补齐重构引入的新行为覆盖。
- **测试代码改动（只动 `src/test/`，未改任何生产代码）**：
  - 13 个测试类改引用新包与新类型名（`iam/domain/bo|vo`、`iam/service` → `iam/application.command|query|result|service`、`iam/application.port`）。其中 6 处旧类型名在早前的自动重命名里被改坏（如 `IamRoleQueryVO` → `IamIamRoleQueryVOVO`、`IamUserRoleQueryVO` → `IamIamUserIamRoleQueryVOVOVO`），按本文件「类名对照」表逐一还原，并核对用例语义未被改名带偏。
  - 8 个服务测试从 `src/test/java/com/flowdesk/iam/service/impl/` 移动到镜像包 `.../iam/application/service/impl/`，与生产代码目录结构保持一致。
  - `AuthPrincipal` / `AuthSession` 去掉 `displayName` 后的构造调用同步修正（`AuthWebTest`、`SecurityChainScopeWebTest`、`RedisAuthSessionRepositoryIT`、`IamCurrentOperatorAdapterTest` 与三个 `*ServiceIT`）。
- **修好的重构回归（2 项）**：`AuthWebTest.meReturnsIdentityAndSessionPermissions` 与 `refreshRotatesTokenAndReturnsNewAccessToken` 在重构后拿到 `401`——`/auth/me` 与登录/刷新响应改为经 `IamAuthService.findProfileById` 实时读取 IAM 资料，而测试的 `stubActiveSession()` 只提供了会话快照。修法是让它同时提供「用户存在且启用」的资料替身；这是重构后的真实前提，不是放宽断言。
- **新增测试（13 项，全部针对重构引入的新行为或既有缺口）**：
  - `AuthWebTest` +7：`/auth/me` 用实时资料里的显示名称（会话快照已不含该字段，改名后无需重登即可生效）；资料缺失或账号停用 → `401/AUTH_SESSION_INVALID` 且撤销该用户全部会话。另**首次为 `POST /fd/v1/auth/login` 补后端测试**（此前只有前端 E2E 覆盖）：成功响应的令牌与 Cookie 契约（Refresh Token 只进 HttpOnly Cookie、响应体不含口令与摘要）、落库会话快照的 userId/username/refreshDigest/角色权限/有效期，以及用户不存在、账号停用、密码错误三条失败路径都不留半成品会话。
  - `IamAuthServiceImplTest` +6（新增文件）：登录授权快照链的**零角色与零权限分支不得拼出空 `IN ()`**（用户零角色自 2026-09-22 起是合法终态）、跨角色权限编码去重且升序、`findProfileById` 的取值与用户缺失时返回 `null`。
  - `RedisAuthSessionRepositoryIT` +1：重构前写入的、仍带 `displayName` 的旧会话快照照样能读出——这是 `AuthSession` 上 `@JsonIgnoreProperties(ignoreUnknown = true)` 的唯一证据，删掉注解该用例即失败（否则升级后所有在线用户会被强制重登）。
- **验证证据（2026-09-23 实跑，JDK 21.0.12）**：`$env:JAVA_HOME='D:\Idea\Jdk\Jdk21'; .\mvnw.cmd -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"` → 单元/Web **291**、`Failures: 0, Errors: 0`（重构前最近基线 278，只增不减；阶段起点 132）。集成侧 6 个 IT 类因 Docker 命名管道被沙箱拒绝而报 `Could not find a valid Docker environment`，**未取得证据，不记为通过**。
- **本机跑测试必须加的参数（会复发，重要）**：Mockito 的 inline mock maker 需要"自附加"，而 `jdk.attach.allowAttachSelf` 自 JDK 9 起默认关闭，ByteBuddy 于是回退到"外部进程附加"；该回退路径在本机沙箱下被禁止（不能用管道 stdio 拉起子进程），报 `Could not self-attach to current VM using external process`，表现为**几乎所有 Spring 测试同时报错**（本次为 247 项，且报错文本与业务断言无关，很容易误判成代码回归）。加 `-DargLine=-Djdk.attach.allowAttachSelf=true` 即恢复。CI 用 temurin 21 不受影响，因此**没有改 `pom.xml`**；若 CI 以后也出现同样的报错，再按 Mockito 文档把 mockito 装成 javaagent。
- **未做（不越界）**：用户管理在制品（`/fd/v1/users`）一行测试都没写——它在 `docs/api-design.md` 8.1/8.2 与 `docs/modules/rbac.md` 第 1 节仍属完整版 backlog，处置方式待「待确认事项」④⑤裁决。用户 2026-09-23 17:00 已在继续实现 `replaceRole`（`IamUserService` 已声明、`IamUserServiceImpl` 尚未实现，主代码因此暂时编译失败），待其恢复编译并确认范围后再补测试。

## 2026-09-23 auth、iam 应用层重构（已落地、未提交；测试对齐见上一节）

- **来源**：用户 2026-09-23 指示按“中等复杂度”方案重构 `auth`、`iam` 生产代码。本轮只改包结构、类型名与依赖方向，**不改接口地址、不改 JSON 字段、不改业务行为**。
- **六条已落地规则**：
  1. 应用层统一为 `application.command` / `application.query` / `application.result` / `application.service`（约定已登记为 `docs/technical-architecture.md` 5.1）。
  2. 服务实现放入 `application.service.impl`。
  3. 跨模块接口放入 `iam.application.port`：`SessionRevocationPort`（`void revokeAll(long userId)`）与 `CurrentOperatorPort`（`long currentUserId()`）；实现仍在 `auth.infrastructure`（`IamSessionRevocationAdapter`、`IamCurrentOperatorAdapter`），依赖方向仍为 `auth → iam`。
  4. Controller 直接接收 Command/Query、直接返回 Result，不再经过 BO/VO。
  5. `domain` 中不再存放 BO/VO：`auth/domain/bo`、`auth/domain/vo`、`iam/domain/bo`、`iam/domain/vo` 四个包已删除，`iam/service/**`（含 `service/impl`）整包已删除。
  6. 没有额外制造重复的 Request/Response 类型。
- **类名对照（重构前 → 重构后）**：

  | 模块 | 重构前 | 重构后 |
  | --- | --- | --- |
  | auth | `auth.service.AuthService` / `auth.service.impl.AuthServiceImpl` | `auth.application.service.AuthService` / `auth.application.service.impl.AuthServiceImpl` |
  | auth | `auth.domain.vo.AuthLoginVO`、`auth.domain.vo.AuthChangePwdVO` | `auth.application.command.LoginCommand`、`auth.application.command.ChangePasswordCommand` |
  | auth | `auth.domain.bo.AuthUserBO`、`AuthLoginBO`、`AuthServiceBO` | `auth.application.result.AuthenticatedUserResult`、`LoginResult`、`IssuedSessionResult` |
  | 跨模块 | `iam.service.CurrentOperatorPort` | `iam.application.port.CurrentOperatorPort` |
  | iam | `iam.service.*Service` / `iam.service.impl.*ServiceImpl` | `iam.application.service.*Service` / `iam.application.service.impl.*ServiceImpl` |
  | iam | `iam.domain.bo.IamAuthBO`、`iam.service.IamAuthService` | `iam.application.result.AuthenticationSnapshot`、`iam.application.service.IamAuthService`（新增 `findProfileById` → `UserProfileSnapshot`） |
  | iam | `IamRoleCreateVO` / `IamRoleUpdateVO` / `IamRoleQueryVO`、`IamRoleBO` | `CreateRoleCommand` / `UpdateRoleCommand` / `RoleQuery`、`RoleResult` |
  | iam | `IamPermissionCreateVO` / `UpdateVO` / `QueryVO`、`IamPermissionBO` | `CreatePermissionCommand` / `UpdatePermissionCommand` / `PermissionQuery`、`PermissionResult` |
  | iam | `IamUserRoleGrantVO` / `IamUserRoleRevokeVO` / `IamRoleUserGrantVO` / `IamUserRoleQueryVO`、`IamUserRoleBO` | `GrantUserRolesCommand` / `RevokeUserRolesCommand` / `GrantRoleToUsersCommand` / `UserRoleQuery`、`UserRoleResult` |
  | iam | `IamRolePermissionGrantVO` / `RevokeVO` / `QueryVO`、`IamRolePermissionBO` | `GrantRolePermissionsCommand` / `RevokeRolePermissionsCommand` / `RolePermissionQuery`、`RolePermissionResult` |
  | iam | `iam.service.SessionRevocationPort` | `iam.application.port.SessionRevocationPort` |
- **契约不变的核对结论**：`docs/api-design.md` 3.2（`/fd/v1/auth/**` 五个端点）与 8.2.1（四组管理端点共 21 个）的路径、请求字段与响应字段逐项核对无变化；`R` / `PageResult` 信封与错误码不变。控制器方法签名等价替换为 Command/Query → Result。
- **顺带的身份快照瘦身（auth 域，仍不改契约）**：`AuthPrincipal` 去掉 `displayName`，只剩 `userId` / `username` / `sessionId`；`AuthSession` 同步去掉 `displayName` 并加 `@JsonIgnoreProperties(ignoreUnknown = true)` 兼容 Redis 中的旧快照。登录响应与 `GET /fd/v1/auth/me` 的 `displayName` 改由 `IamAuthService.findProfileById` 实时读取（`AuthServiceImpl.currentUserView`），字段与取值口径不变；额外收益是用户改名后无需等会话过期即可生效，停用用户仍撤销全部会话并返回 `401`。
- **编译证据（2026-09-23 本会话实跑，JDK 21）**：
  - 主代码：`.\mvnw.cmd -B compile` → `BUILD SUCCESS`。
  - 测试代码：`.\mvnw.cmd -B test-compile` 当时 **FAILURE**——13 个测试类仍 `import` 已删除的旧包（`com.flowdesk.iam.domain.vo`、`com.flowdesk.iam.domain.bo`、`com.flowdesk.iam.service`）：`SecurityChainScopeWebTest`、`IamPermissionControllerWebTest`、`IamRoleControllerWebTest`、`IamRolePermissionControllerWebTest`、`IamUserRoleControllerWebTest`、`IamPermissionServiceImplTest`、`IamPermissionServiceIT`、`IamRolePermissionServiceImplTest`、`IamRolePermissionServiceIT`、`IamRoleServiceImplTest`、`IamRoleServiceIT`、`IamUserRoleServiceImplTest`、`IamUserRoleServiceIT`。**该阻塞已于同日由 Codex 对齐完毕（单元/Web 291 全绿，集成测试受本机沙箱限制未执行），见上一节记录。**
- **另一项必须记录的代码事实（越界在制品，待确认）**：同一工作区里还有一批**与本次重构无关的新功能**——`IamUserController` 从原来的空壳 `@RequestMapping("/fd/v1/iam/user")` 改为 `/fd/v1/users`，实现六个端点（分页列表、详情、创建、改资料、启用、重置密码），配套 `CreateUserCommand` / `UpdateUserCommand` / `ResetUserPasswordCommand` / `UserStatusChangeCommand`、`UserQuery`、`UserResult` / `UserRoleSummaryResult`、扩展后的 `IamUserService(+Impl)`（原接口只有 `updatePassword`），以及 `IamUserMapper` 的 `selectByIdForUpdate` / `selectProfileById` / `selectByIdsForUpdate` / `selectUserPage`。缺口：`POST /fd/v1/users/{userId}/actions/disable` 位置只剩一个 `//TODO`（无方法体），`PUT /fd/v1/users/{userId}/roles`（替换角色）未实现；这批代码没有任何测试。用户管理在 `docs/api-design.md` 8.1/8.2 与 `docs/modules/rbac.md` 第 1 节属**完整版 backlog**，当前阶段（`TASK-055`～`TASK-060`）明确不做，因此本次**不把它写成已确认交付物**，只在「待确认事项」④⑤登记处置方式。
- **同步的文档（2026-09-23 本轮）**：`docs/technical-architecture.md` 新增 5.1 节（application 层与命名约定的唯一真源）；`docs/modules/auth.md` §2 包表与重构记录、§9 历史开工卡加更正标注；`docs/modules/rbac.md` §2 端口路径、§3 类清单与命名约定、§4.1～4.4 请求/响应类型、§12.4 引用；`docs/implementation-plan.md` 9.1 的 `TASK-056` 端口路径、`TASK-057`/`TASK-058` 产出描述与决策 9 措辞；`docs/project-highlights.md` 角色编码不可修改的设计方案类名；`README.md`、`AGENTS.md` 当前阶段限制、本文件。
- **本轮无需改动的文档**：`docs/api-design.md`、`docs/business-model.md`、`docs/database-design.md`、`docs/engineering-readiness.md`——重构不触碰 API 契约、业务模型、表结构与工程依赖，四份文件现有内容与重构后代码不冲突。

## 2026-09-22 TASK-058 测试补齐完成（自动化全绿，手工链路待补）

- **范围（用户 2026-09-22 确认）**：① 废弃保护规则 5「用户必须至少保留一个角色」，**零角色为合法终态**，`409/USER_ROLE_REQUIRED` 不再产生；② 为本轮新增端点一并补齐测试并写入覆盖矩阵。
- **当前端点面（11 个）**：授权列表 ×2、批量授予 ×2、单条撤销 ×2、批量撤销 ×2（`POST /user-roles/actions/revoke`、`POST /role-permissions/actions/revoke`）、清空全部 ×2（`DELETE /user-roles/users/{userId}`、`DELETE /role-permissions/roles/{roleId}`）、一个角色授予多个用户（`POST /user-roles/actions/grant-users`）；角色创建请求新增可选 `permissionIds`，与角色插入同一事务。
- **保留的保护规则**：最后启用管理员的 `SYSTEM_ADMIN` 角色不可撤销/清空（`409/LAST_ADMIN_PROTECTED`）；`SYSTEM_ADMIN` 的 `RBAC_MANAGE` 授权不可撤销/清空（`409/RBAC_CONFLICT`）；批量接口任一目标或关系缺失即整批失败且不写库。
- **测试产出（Codex 负责）**：单测 `IamUserRoleServiceImplTest`(40)、`IamRolePermissionServiceImplTest`(32)、`IamRoleServiceImplTest`(22)、`IamCurrentOperatorAdapterTest`(4)；Web `IamUserRoleControllerWebTest`(25)、`IamRolePermissionControllerWebTest`(21)、`IamRoleControllerWebTest`(19)、`SecurityChainScopeWebTest`(23，四组端点的读写请求各一条真实过滤链)；集成 `IamUserRoleServiceIT`(17，含两条真并发)、`IamRolePermissionServiceIT`(14)、`IamRoleServiceIT`(9，含建角色带权限与失败回滚)。
- **并发证据（真实 MySQL + Testcontainers）**：① `concurrentRevokeOfBothRolesSucceedsWithoutDeadlockAndLeavesNoGrant`——同一用户两个角色并发撤销，两次都成功、无死锁，终态 0 授权（零角色合法）；② `concurrentRevokeOfOwnAdminRoleLeavesAtLeastOneEnabledAdministrator`——两个启用管理员并发撤销各自 `SYSTEM_ADMIN`，只有一个成功（`409/LAST_ADMIN_PROTECTED`），终态仍有 1 个启用管理员。
- **自动化证据（已验证）**：`$env:JAVA_HOME='D:\Idea\Jdk\Jdk21'; .\mvnw.cmd -B clean verify` → 单元/Web **278**、集成 **63**，`Failures: 0, Errors: 0`，`BUILD SUCCESS`；相对阶段起点 **132 + 30** 只增不减。
- **尚未完成（不记为已验收）**：验收标准第 4 条的四条手工真实栈链路（每条留 `traceId` 与响应码），见 `docs/modules/rbac.md` 第 11 节；链路 3 的期望已随规则 5 废弃改为「清空全部角色后零授权、旧令牌失效、重新登录无业务权限」。
- **本轮踩到的两个测试侧坑（会复发）**：① 排序白名单的 `400` 由**服务层**（`PageQuery.orderItems`）产生，Web 测试里服务被 mock，因此在 Web 层断言该 `400` 必然得到 `200`——该格应在服务层断言；② Mockito 按**参数值相等**匹配桩，服务端把 ID 去重并升序归一化后再加锁，桩必须用排序后的列表，否则返回空列表并让用例以 `404` 而非预期错误码失败。

## 2026-09-22 TASK-060 开工规格确认（三项决策 + 一处硬约束）

- **一处硬约束（代码事实，改变了原问题的前提）**：任务原话把「授予时的用户/角色选择器形态」当作两个都能选，但 `GET /fd/v1/users` **未实现**——`IamUserController` 只有一个空的 `@RequestMapping("/fd/v1/iam/user")` 壳，一个方法都没有，路径还与 `docs/api-design.md` 8.2 的 `/fd/v1/users` 不一致；用户管理按 8.2 与 `docs/modules/rbac.md` 第 1 节属**完整版 backlog**。因此**角色选择器可以有**（数据源 `GET /fd/v1/admin/roles`），**用户选择器没有数据源**。（**2026-09-23 更正**：工作区里 `IamUserController` 已被改成 `/fd/v1/users` 并实现了六个端点，但该实现属越界在制品、未确认也未测试，处置方式见「待确认事项」④⑤与同日重构记录；在用户裁决前，`TASK-060` 仍按本条已确认结论执行，即用户选择器用数字 ID + 当前列表候选下拉。）
- **确认 1：用户选择器 = 数字用户 ID + 候选下拉**。不改后端契约、不扩大范围；输入框旁明确写"后端暂无用户查询接口，用户管理属完整版"，**不做可点击的假搜索框**。候选下拉只提供当前列表里已出现过的用户，它是便利而非数据源。曾评估「为 `TASK-060` 补一个最小只读 `GET /fd/v1/users`」，因扩大范围未采纳。
- **确认 2：两组授权均使用后端批量增量端点**。用户角色提交“一个用户 × 多个角色”（`userId + roleIds`），角色权限提交“一个角色 × 多个权限”（`roleId + permissionIds`）；前端不得循环单条 `POST`。后端去重排序后只新增缺失关系，任一目标不存在则整批回滚；实际无变化时不撤会话，有变化时每批只执行一次对应范围的会话撤销。单条 `DELETE` 继续用于精确撤销一条关系。
- **确认 3：`api/` 保持扁平，`errorMessages` 不下沉**。`frontend/AGENTS.md` 的分层触发条件已满足（RBAC 是第二个领域模块），裁定为只新增 `api/rbac.ts`、不建 `api/core/`，理由沿用该文件自己对 `api/core/` 的否定论证；错误码继续单一映射表。
- **页面开工前必须补的两处既有缺口**：`frontend/src/api/errorMessages.ts` 缺 6 个 RBAC 错误码（`ROLE_NOT_FOUND`、`PERMISSION_NOT_FOUND`、`GRANT_NOT_FOUND`、`ROLE_CODE_CONFLICT`、`PERMISSION_CODE_CONFLICT`、`RBAC_CONFLICT`——正是新页面会撞上的全部错误）；`frontend/src/constants/authorization.ts` 的 `permissionLabels` 缺 `RBAC_MANAGE`，且需在侧栏 `admin` 组新增四组维护页入口。
- **权限码搜索可行**：`GET /fd/v1/admin/permissions` 的 `keyword` 同时匹配 `code` 与 `name`，按远程检索实现即可。
- **同步的文档**：`docs/modules/rbac.md` 新增第 12 节「管理端页面（`TASK-060`）实现细则」（原第 12 节「依据文档」顺延为第 13 节）；`frontend/AGENTS.md` 的 `api/` 触发条件条目补记裁定结果；本文件「快速定位」的待确认事项已清空（其中另列 3 项纯 UI 问题——Element Plus 按需引入、登录页占位文案、首页 `h1` 字号——至今仍待确认，与本阶段无关）。
- **已知坑（尚未处理）**：真实闭环 E2E 会**写演示库**（建角色、授权限、给 `employee` 授角色）。测试侧按"临时角色 → 闭环 → 撤销授权 → 删除角色"并断言清理干净来实现。

## 2026-09-22 TASK-059 完成：安全链作用域修复验收（阻塞解除）

- **改动**：`AuthSecurityConfiguration` 的 `securityMatcher` 由 `/fd/v1/auth/**` 扩为 `/fd/v1/**`（决策 12 方案 A）。`JwtAuthenticationFilter` 只装在 `@Order(1)` 的 auth 链上，作用域不含 `/fd/v1/admin/**` 时，管理端请求落到没有认证过滤器的基础链，被 `anyRequest().authenticated()` 一律判成匿名。基础链继续负责 `/actuator/**`、springdoc 等非 `/fd` 路径。
- **新增测试**：`src/test/java/com/flowdesk/auth/security/SecurityChainScopeWebTest.java`（4 项）。**刻意不使用 `@WithMockUser`**——身份必须由真实 Access Token + Redis 会话快照产生，`@MockitoBean AuthSessionRepository` 提供快照，`IamRoleService` 用替身。四项分别断言：无令牌 `401/AUTH_REQUIRED` 且控制器未被调用；有令牌且有 `RBAC_MANAGE` 时 `200/OK` 并真的进到控制器；有令牌但缺权限 `403/ACCESS_DENIED`；令牌有效而会话已被撤销 `401/AUTH_SESSION_INVALID`。
- **自动化证据（已验证）**：`$env:JAVA_HOME='D:\Idea\Jdk\Jdk21'; .\mvnw.cmd -B clean verify` → `Tests run: 132, Failures: 0, Errors: 0`（单元/Web）+ `Tests run: 30, Failures: 0, Errors: 0`（集成），`BUILD SUCCESS`。相对阶段起点 **128 + 30 只增不减**。
- **真实栈证据（已验证，2026-09-22，`local` profile，MySQL 3308 + Redis，Flyway 已在 `v5`）**：
  - 登录：`admin`/`123456` 与 `employee`/`123456` 均 `200`，`admin` 权限含 `RBAC_MANAGE`。
  - `GET /fd/v1/admin/roles`：`admin` 令牌 → `200/OK`，`traceId=5a499939-f49e-440e-8971-308c0f5c12d3`；无令牌 → `401/AUTH_REQUIRED`，`traceId=cdbfe5a0-5263-401f-9a1a-ac1a80c36e01`；`employee` 令牌 → `403/ACCESS_DENIED`，`traceId=9af40cb8-754f-4975-9a56-8c1a2a71fb76`。
  - `GET /fd/v1/admin/permissions`：`admin` 令牌 → `200/OK`，`traceId=743ffd78-c110-4d53-bdad-0660056d1ae9`；无令牌 → `401/AUTH_REQUIRED`，`traceId=b39f974f-8c1c-4c3d-8251-8affd0c12156`；`employee` 令牌 → `403/ACCESS_DENIED`，`traceId=f06f9db3-f909-4e6a-b706-97cd00b17f29`。
  - 响应同时复核了 `V5` 的库内结果：`RBAC_MANAGE` 为 `id=15`、权限总数 14、`SYSTEM_ADMIN` 的 `permissionIds=[10,11,12,15]`。
- **排障记录（会复发，值得记住）**：用 `curl.exe` 验证时，PowerShell 5.1 下 `--data-binary "@(Join-Path ...)"` 里的 `@(...)` 在双引号中是**字面量**，curl 会去读一个不存在的文件名并返回 `400/VALIDATION_FAILED`；另外 `curl.exe` 的输出是行数组，直接喂 `ConvertFrom-Json` 会得到空对象，必须先 `-join ''`。这两点各制造了一次「令牌为空」的假象（`Authorization: Bearer ` 后面什么都没有），一度看起来像修复没生效。
- **文档同步**：`docs/modules/auth.md` §8.1 的「待实施」改为「已实施」并补真实栈复核结论；`docs/implementation-plan.md` 9.1 的 `TASK-059` 行标注已完成；本文件「当前阻塞」清空。
- **阶段起点基线（2026-09-22 本会话复跑，作为「只增不减」的比对基准）**：后端 `clean verify` → 单元/Web **132**、集成 **30**；前端 `typecheck`/`lint`/`build` 退出码 0、单元 **8 套件 33 项**、E2E **9 项**。
- **另有两条真实栈证据（2026-09-22）**：① `DELETE /fd/v1/admin/roles/3` → `409/RBAC_CONFLICT`（`traceId=04cb2583-bc7d-4919-9aa6-a412c8cf9652`）、`DELETE /fd/v1/admin/permissions/15` → `409/RBAC_CONFLICT`（`traceId=41e91d3c-658e-4894-8e1a-928ab9779d5e`），拒绝后两个对象仍在，且这两条走的是**写路径**的真实过滤链；② 直接查库核对 `V5`：迁移版本 `1,2,4,5`、权限 14、授权 14、`SYSTEM_ADMIN` 权限 4、`RBAC_MANAGE` 仅授予 `SYSTEM_ADMIN`、无伪造存量审计、审计列与索引外键齐备。
- **一处文档错误已更正**：`docs/modules/rbac.md` 第 10 节原写「用**真实 JWT** 构造身份」与代码不符——角色/权限 Web 测试用的是 `SecurityMockMvcRequestPostProcessors.user(...)`。已改为明文约束：每组端点至少保留一条真实过滤链用例。
- **后续状态**：该记录完成后已进入 `TASK-058`；目前其后端实现已写入但未测试验收，下一开发步骤为 `TASK-060`。

## 2026-09-22 三项范围确认（安全链修复、TASK-058 切片、管理端页面）

- **决策来源**：用户 2026-09-22 对上一轮列出的三项待定一次确认，三项均按推荐方案通过。
- **确认 1：安全链作用域修复采用方案 A**。`AuthSecurityConfiguration` 的 `securityMatcher` 由 `/fd/v1/auth/**` 扩为 `/fd/v1/**`；基础链继续负责 `/actuator/**`、springdoc 等非 `/fd` 路径。理由：真实 HTTP 下只有 `/fd/v1/auth/**` 经过 JWT 过滤器，`/fd/v1/admin/**` 落到没有认证过滤器的基础链，恒为 `401/AUTH_REQUIRED`；方案 B（把过滤器装到基础链）会引入 `common → auth` 的依赖方向问题，改动面更大。登记为 `TASK-059`，**执行顺序上先于 `TASK-058`**（编号按登记顺序，不表示执行顺序）。
- **确认 2：`TASK-058` 按四片推进**：① 两组授权列表；② 用户角色批量增量授予/单条撤销；③ 角色权限批量增量授予/单条撤销；④ 并发与真实栈收口。后续实现中，授予请求已确定为 `userId + roleIds` 与 `roleId + permissionIds`，由后端单事务去重、排序并只新增缺失关系。
- **确认 3：交付层级计入求职 MVP 演示范围，并在后端接口之外补 RBAC 管理端页面**。新增 `TASK-060`（`frontend/src/views/admin/` 的角色、权限与两组授权维护页），按 `frontend/AGENTS.md` 的六态与「视觉决策契约」交付。原设计决策 8「不做管理端页面」据此改写。
- **同步的文档**：`docs/implementation-plan.md` 9.1（决策表新增 12、13 并改写 8；任务表补 `TASK-059`、`TASK-060`，标出执行顺序与 `TASK-056`/`TASK-057` 已完成；阶段验收标准新增第 6、7 条）、`docs/modules/rbac.md` 第 1 节（管理端页面从"不做"移到"做"）、`docs/modules/auth.md` §8.1（标注 auth 链作用域待扩为 `/fd/v1/**`）、`AGENTS.md` 当前阶段限制、本文件、`README.md`。
- **待确认**：无。① ~~`TASK-060` 的页面交互细节~~ **2026-09-22 已定**；② ~~`api/` 是否分层、`errorMessages` 是否下沉~~ **2026-09-22 已裁定：保持扁平、只加 `api/rbac.ts`、`errorMessages` 不下沉**。两项细节见下方「`TASK-060` 开工规格确认」一节与 `docs/modules/rbac.md` 第 12 节。

## 2026-09-21 本机库同步 V5（清理 V3 残留数据）

- **问题**：`V5__enable_dynamic_rbac.sql` 已写入且空库迁移测试已通过，但**本机库**（`localhost:3308/flowdesk`）仍停在 V4 结构：`flyway_schema_history` 只有 `1,2,4`；`iam_role_permission` 没有 `granted_by`/`granted_at`，也没有 `idx_iam_role_permission_granted_by` 与 `fk_iam_role_permission_granted_by`。库里却已经有一行 `RBAC_MANAGE`（`iam_permission.id=14`）和一条 `SYSTEM_ADMIN×RBAC_MANAGE` 授权——来自**早前已删除的 `V3` 迁移残留**，不是 `V5` 的产物。
- **直接重启的后果（已规避）**：`V5` 第 2 句 `INSERT INTO iam_permission ... 'RBAC_MANAGE'` 会撞唯一索引 `uk_iam_permission_code`（`Duplicate entry`），第 3 句会撞 `PRIMARY(role_id, permission_id)`。MySQL 的 DDL 自动提交，一旦触发就会留下"列已加、迁移失败"的半应用状态，需要先删掉失败记录才能重跑。空库与 CI 不受影响（`V3` 从未进入迁移集）。
- **处置（2026-09-21 执行，用户授权）**：停掉 8081 后端（PID 16984）→ 在单个事务里删除残留的授权行与权限行（回到 `V2` 基线：13 权限 / 13 授权 / `SYSTEM_ADMIN` 3 项）→ 重启后端由 Flyway 应用 `V5`。
- **验证证据**：
  - 启动日志：`Current version of schema flowdesk: 4` → `Migrating schema flowdesk to version "5 - enable dynamic rbac"` → `Successfully applied 1 migration ... now at version v5`；`Started FlowDeskApplication in 6.839 seconds`，Tomcat 8081，profiles `local, demo`。
  - 库内核对：`flyway_schema_history` 新增 `5 | enable dynamic rbac | success=1`；`iam_role_permission` 为四列（`granted_by bigint unsigned NULL`、`granted_at datetime(6) NULL`）；授权人索引与外键 `fk_iam_role_permission_granted_by → iam_user(id)` 均在；`RBAC_MANAGE` 为新行 `id=15`；授权 `(SYSTEM_ADMIN=3, RBAC_MANAGE=15)` 的两列审计为 `NULL`；权限 14 / 授权 14 / 审计为空的授权 14，与 `DatabaseMigrationIT` 的断言一致。
  - 端到端：`admin` / `123456` 登录返回 `OK`，`GET /fd/v1/auth/me` 返回 `SYSTEM_ADMIN` 与 4 项权限（含 `RBAC_MANAGE`）。
- **本机启动方式（避免踩坑）**：`-Dspring-boot.run.profiles=local` 在 PowerShell 下会被拆成 `-Dspring-boot` 与 `.run.profiles=local`，Maven 报 `Unknown lifecycle phase`；改用环境变量 `SPRING_PROFILES_ACTIVE=local`。另外 `Start-Process` 起的后端会随命令结束一起被回收，需要以受管后台任务方式启动。
- **顺带观察（非阻塞）**：启动日志出现 `Can not find table primary key in Class: IamUserRole / IamRolePermission`——两张授权表是复合主键、实体没有 `@TableId`，MyBatis-Plus 因此不能对它们用 `xxById` 系列方法；`TASK-057`/`TASK-058` 读写这两张表时需用 wrapper 方式。

## 2026-09-21 第 3 步阶段设计确认（完整动态 RBAC）

- **决策来源**：用户 2026-09-21 先确认阶段设计的 8 项设计决策与其余 6 项默认项，随后补充确认两类授权返回字段、角色权限变化的会话撤销范围及并发锁协议。范围上限仍是 `docs/api-design.md` 8.2.1 的四组接口，不扩展到该节之外的权限模型。
- **11 项设计决策**（2026-09-22 追加为 13 项，见上方记录；完整理由见 `docs/implementation-plan.md` 9.1）：
  1. `iam_role_permission` 由 `V5` 补 `granted_by BIGINT UNSIGNED` / `granted_at DATETIME(6)`、授权人索引与外键，两列允许为空，存量行不伪造时间；新授权必须写入两列。
  2. 会话撤销经 IAM 定义的 `SessionRevocationPort`，由 auth 侧 adapter 实现，保持 `auth → iam` 单向依赖，IAM 不感知 Redis。
  3. 撤销会话与提交的顺序：**先撤 Redis 会话，后提交 MySQL 授权变更**（与改密一致；不让旧权限在旧会话里继续可用）。
  4. 授予接口首次与重复都返回 `200`；`201` 只用于创建角色、创建权限。
  5. 受保护对象按 `code` 常量判定（`SYSTEM_ADMIN`、`RBAC_MANAGE`），不新增“内置”标记列。
  6. 排序白名单：角色/权限 `code,name,created_at`；用户角色 `granted_at`；角色权限 `role_id,permission_id`。
  7. 两组授权列表必须至少给出一个筛选条件，都不给返回 `400/VALIDATION_FAILED`。
  8. 本阶段只交付后端接口、迁移与测试证据，**不做管理端页面**（`frontend/src/views/admin/` 留给后续主题）。→ **该条已于 2026-09-22 改写**：补做管理端页面并计入 MVP 演示范围，见上方 2026-09-22 记录与 `TASK-060`。
  9. 两类授权列表和授予响应使用固定授权 BO；`grantedBy` 返回可空的授权人用户 ID，重复授予返回原记录且不改写审计字段。
  10. 角色权限实际变化后撤销该角色全部用户会话；持有角色锁后通过 `FOR UPDATE` 按用户 ID 升序取得受影响用户快照，重复授予不撤销。
  11. RBAC 写操作统一使用 `SELECT ... FOR UPDATE`，固定锁顺序为角色 → 权限 → 用户 → 授权关系，同层按主键升序；锁后重查不变量，并以 MySQL 真并发测试验证至少一个角色与最后启用管理员保护。
- **任务拆分（按依赖顺序）**：`TASK-055`～`TASK-057` 与 `TASK-059` 已完成 → `TASK-058` 后端实现已写入但未测试验收 → `TASK-060` 管理端页面 → 阶段收口。
- **阶段验收标准**：见 `docs/implementation-plan.md` 9.1（`verify` 全绿且相对起点只增不减、覆盖矩阵逐格有据、迁移断言与 `V5` 一致、四条手工链路留 `traceId`、MySQL 真并发验证两项核心不变量、三份当前阶段文档一致）。
- **同步的文档**：`docs/implementation-plan.md`（§3.2、§3.3、§4、§9.1）、`docs/api-design.md`（§8.1 版本边界、§8.2.1 补充语义）、`docs/database-design.md`（§17.2 边界、§17.5 新增列）、`docs/modules/rbac.md`、本文件、`README.md` 与 `AGENTS.md`。
- **开工前置**：用 `compose.yaml` 起 MySQL/Redis，并在新分支上复现 `./mvnw -B verify` 的 **55 项单元/Web + 17 项集成**基线（起点不确定则后续“未退化”不可信）；本机 `gh` 仍未登录，只影响建 PR 效率。
- **本轮产出边界（2026-09-21）**：只产出**文档与数据库设计**——`docs/modules/rbac.md`（模块设计说明）、`docs/implementation-plan.md` 9.1（决策与任务拆分）、`docs/api-design.md` 8.2.1 与 10.2（语义与错误码）、`docs/database-design.md` 17.5（`V5` 计划新增的列）。**实现代码（迁移脚本、服务、控制器、测试）一行都还没写**；越界写出的版本已移出本分支，存放在本地分支 `wip/rbac-code`（未推送，可随时 `git branch -D wip/rbac-code` 丢弃，或经确认后 cherry-pick 取用）。

## 2026-09-21 PR #6 合并、main 同步与下一分支创建

- **合并**：PR #6 `flow-desk/frontend-shell` → `main`，合并提交 `2993a2a`，`merge_method=merge`（与 PR #5 的风格一致）；合并前分支头 `a7aa41f` 上 `backend-verify` / `frontend-verify` / `core-e2e` 三个 job 全绿。
- **同步**：本地切回 `main` 并 `git pull --ff-only` 快进到 `2993a2a`，与 `origin/main` 一致。
- **下一分支**：`flow-desk/dynamic-rbac`（第 3 步「系统业务：完整动态 RBAC」），从最新 `main` 创建，**尚未推送**——按仓库惯例，推送发生在该阶段完成检查、准备合并时。
- **环境事实**：本机 `gh` 未登录，本次建 PR 与合并都通过 GitHub REST API 完成，用的是 push 已使用的同一份 git 凭据（`git credential fill`），令牌未落盘、未打印；以后要在命令行直接建 PR / 合并，先在本机执行一次 `gh auth login`。
- **开工前置**：第 3 步的阶段设计（任务拆分、切片顺序、`V5` 迁移内容、保护规则、会话撤销与审计、验收标准）尚未产出，需先集中确认再动代码。

## 2026-09-21 前端外壳工作项：提交、推送与分支交接（第 2 步）

- **交接的分支**：`flow-desk/frontend-shell`，基准 `main` 的 `5c6cca0`；PR 合并前共 13 条提交（6 条既有 + 本轮 3 条功能/设计提交 + 1 条锁文件修复 + 3 条交接记录）。
- **本轮 3 条功能与设计提交**（用户 2026-09-21 指示：先更新文档，再提交并创建 PR）：
  - `72022e7` `feat(frontend): 重排应用壳为整幅顶栏 + 侧栏 + 面包屑 + 主体`
  - `9587c8d` `feat(frontend): 面包屑按侧栏层级显示`
  - `51fc87a` `docs: 同步应用壳重排与面包屑层级的状态与设计记录`
- **推送（已完成）**：`git -c http.proxy=http://127.0.0.1:12000 push -u origin flow-desk/frontend-shell` 退出码 0，本地分支已跟踪 `origin/flow-desk/frontend-shell`。
- **合并请求（已创建）**：**PR #6** `flow-desk/frontend-shell` → `main`，地址 <https://github.com/Crazy-HF/Flow-Desk/pull/6>；`open`、`mergeable=true`、`mergeable_state=clean`、56 files changed。分支头提交 `a0e1512` 上 **CI 三个 job 全绿**：`backend-verify` success、`frontend-verify` success、`core-e2e` success。
- **CI 首轮失败与修复（本轮最有价值的一条排障记录）**：首轮 `frontend-verify` 在 `vue-tsc` 阶段报 `TS2307: Cannot find module '@element-plus/icons-vue'`（`AppHeader` / `AppSidebar` 各一条），而同一个 job 的 `pnpm install --frozen-lockfile` 明确报告该包已安装、`core-e2e` 因此被 skip。根因不在本轮改动里：`frontend/pnpm-lock.yaml` 的 importer 段把这个依赖记成没有 peer 后缀的 `2.3.2`，snapshots 段却只有带后缀的键 `@element-plus/icons-vue@2.3.2(vue@3.5.42(typescript@6.0.3))`，pnpm 因此把顶层 `node_modules/@element-plus/icons-vue` 指向不存在的 `.pnpm/@element-plus+icons-vue@2.3.2/`，真实目录是 `...@2.3.2_vue@3.5.42_typescript@6.0.3_` —— **这正是本机 2026-09-21 那条"pnpm 符号链接缺陷、要用 junction 手工修"记录的真实原因（不是 Windows 专有，Linux CI 同样复现）**。`pnpm install --lockfile-only` 认为原文件已是最新、不会自行修正。处置为 `a0e1512`：把 importer 的 `version` 补成与 snapshots 一致的后缀形式（与 `element-plus` / `vue` / `pinia` 的记法相同），只改这一行。
- **该修复的验证方式（可复现）**：在 `%TEMP%` 下复制 `frontend/`（排除 `node_modules`、`dist` 等）做干净副本，避免本机已存在的 junction 干扰——对照组（原锁文件）`fs.realpathSync('node_modules/@element-plus/icons-vue')` 报 `ENOENT`，实验组（补后缀）指向 `...@2.3.2_vue@3.5.42_typescript@6.0.3_`；实验组整包 `pnpm install --frozen-lockfile` / `typecheck` / `lint` 退出码 0、`test:unit` 8 套件 33 项通过，随后远端 CI 复现为全绿。
- **PR 的创建方式（需要知道的环境事实）**：本机 `gh` 未登录（`%APPDATA%\GitHub CLI` 目录不存在，也没有 `GH_TOKEN` / `GITHUB_TOKEN`），所以没有走 `gh pr create`；改为用 push 已经使用过的同一份 git 凭据（`git credential fill`）调用 GitHub REST API `POST /repos/Crazy-HF/Flow-Desk/pulls` 建 PR，**令牌未落盘、未打印**。下次要让 Codex 直接建 PR，先在本机执行一次 `gh auth login` 更稳妥。
- **交接已完成（2026-09-21，用户确认后由 Codex 连续执行）**：PR #6 以合并提交 `2993a2a` 合并（`merge_method=merge`，沿用仓库既有风格，合并前分支头 `a7aa41f` 上 CI 全绿）；本地 `main` 仅快进拉取到 `2993a2a`、与 `origin/main` 一致；从最新 `main` 创建第 3 步分支 `flow-desk/dynamic-rbac`（尚未推送）。
- **提交前检查（已验证，2026-09-21）**：`pnpm exec vitest run` → 8 套件 33 项、`pnpm run typecheck`、`pnpm run lint`、`pnpm run build` 退出码 0、`pnpm test:e2e` → 9 项；后端本轮未改动。远端 CI 已全绿，但**合并前仍以 PR 页面的最新一轮结论为准**。

## 2026-09-21 应用壳重排：四层框架对齐对照项目

- **范围（用户指示）**：只抄参考项目 `YeJuZhi-Vue-CPY/plus-ui` 的**框架 / 顶栏 / 侧栏 / 面包屑**四部分版式，主体内容不读、不改；顶栏不要预警铃铛。用户已确认：新增 `frontend/src/layout/index.vue` 承载框架，顶栏右侧保留「搜索位 + 全屏 + 账号区」，搜索位放在右侧（同日二次调整）。
- **新增**：`frontend/src/layout/index.vue`（顶栏品牌位 + `AppHeader` / 侧栏 + 主体两行两列；窄屏抽屉与跳过链接随壳一起搬入）、`frontend/src/layout/index.test.ts`（3 项）、`frontend/src/components/AppBreadcrumb.test.ts`（5 项：首页单级、栏目→页面、同级切换不换栏目、子路由补父级、非导航路由退回两级）、`frontend/e2e/shell.spec.ts`（1 项管理员用例，锁住"用户管理 → 分类管理 不能被顶掉栏目"）。
- **修改**：`App.vue`（只剩「已登录进壳 / 未登录走路由」的分流）、`components/AppHeader.vue`（顶栏账户区：搜索位 + 全屏 + 身份 + 账号菜单；改密对话框仍在顶栏）、`components/AppSidebar.vue`（**条目结构、权限显隐与样式全部不变**：`.app-sidebar__navigation` 增加 sticky 定位以配合整列底色，栏目名改从 `navigationGroupLabels` 取，不再就地写死）、`components/AppBreadcrumb.vue`（改为按导航声明的层级）、`constants/authorization.ts`（抽出 `NavigationGroup` 与 `navigationGroupLabels`，供侧栏与面包屑共用）、`styles/main.css`（`app-shell__header/brand`、`app-header__*`、`.app-sidebar` 容器重写，新增 `.app-breadcrumb__group`）、`styles/tokens.css`（新增 `--fd-shell-header-height` / `--fd-shell-search-width`；删除已无引用的 `--fd-header-padding-y`）、`frontend/AGENTS.md`（目录表新增 `src/layout/`，外壳组件的消费者由 `App.vue` 改为 `layout/index.vue`）。
- **有意的观感决定**：品牌位固定占侧栏那一列，侧栏右缘的竖线在顶栏里延续；**侧栏底色占满整列高度**（底色属于"列"、sticky 属于导航，两者不互相绑死，所以侧栏下方不再露出页面底色）；侧栏选中态保留 FlowDesk 自己的左侧竖条（不抄参考项目的纯浅底选中），并且只允许出现在子项上——分组标题在"组内有当前位置"时只变字色，否则同一处会出现两个选中标记。
- **面包屑：先按要求回退，再按用户确认改成层级版**。曾把它改成纯标记的 "/" 层级，用户要求撤销，于是先回退到 `HEAD` 版（两级：首页 + 当前页，顺带删除 `--fd-shell-breadcrumb-height`）。随后用户报告实际问题：**在"系统管理"下点用户管理再点分类管理，"用户管理"被"分类管理"顶掉了**。已确认语义为 **面包屑 = 侧栏层级**：`首页 / 一级栏目 / 页面`（子路由补一级父页面并可点回列表；403/404 等不在导航声明里的路由退回 `首页 / 当前页`）；栏目是容器、没有目标页，所以不做成可点击的块，只用浅一档字色。**同级之间切换只换末级是面包屑的正常语义**，访问历史不在这里累加（若要累加访问过的页面，那是标签栏，属于另一件事）。
- **预留接口（无数据来源）**：`constants/authorization.ts` 的 `NavigationEntry` 新增 `icon?: string`，后端既无菜单接口也无 `icon` 字段，字段为空时侧栏不渲染图标位；搜索位是**明确禁用**的按钮（`title="搜索功能尚未接入"`），不是可点击的假输入框。
- **验证证据（2026-09-21，Codex 实跑）**：`pnpm exec vitest run` → **8 套件 / 33 项全通过**（原 25 项 + 布局 3 项 + 面包屑 5 项）；`pnpm run typecheck`、`pnpm run lint`、`pnpm run build` 退出码均为 0。**环境提醒**：本机默认沙箱会静默吞掉 `pnpm`/`node` 的子进程输出（exit 0 但无产物），上述结果是在放宽权限后复跑的；同一命令在受限模式下会给出"通过"的假象。
- **浏览器截图自查（已验证，2026-09-21）**：本机 MySQL / Redis 容器与 `8081` 上的后端常驻实例已在运行（本轮 `spring-boot:run` 因 `8081` 被占未启动成功，用的是既有实例）。① Vite dev `http://localhost:5173` 用 `employee` / `123456` 登录核对首页与 `/tickets`：顶栏整幅通栏、搜索位在右侧、品牌位与侧栏同宽且竖线在顶栏内延续、侧栏底色通到底、无横向溢出、无 Element Plus 默认蓝残留；② `pnpm test:e2e` → **9 项通过**，其中新增的管理员用例真实点击「用户管理 → 分类管理」并断言面包屑为 `['首页','系统管理','用户管理']` → `['首页','系统管理','分类管理']`，截图（`test-results/…/breadcrumb-admin.png`）可见三级并排、`系统管理` 为不可点文字、`分类管理` 为当前块。**注意**：本机 Vite dev server 由本轮重启，仍在 `5173` 后台运行，未关闭。
- **顺带解决**：待确认事项⑥（侧栏下拉三角换 `ArrowDown`、删除 `--fd-border-width`）经核对已完成，已在该条标注。

## 2026-09-21 第 3 步范围确认：完整动态 RBAC

- **决定（用户已确认）**：第 3 步「系统业务：RBAC」按**完整动态 RBAC** 实施，接口以 `docs/api-design.md` 8.2.1 为准——角色、权限、用户角色授权、角色权限授权四组 CRUD。此前四处「MVP 不实现在线角色/权限 CRUD、动态 RBAC 需再次确认」的边界，随本次确认解除。
- **已确认文档中随之生效的硬约束**：
  - 新增 Flyway 迁移（下一个可用版本号 **`V5`**，`V3` 已删除不可复用）预置 `RBAC_MANAGE` 并授予受保护的 `SYSTEM_ADMIN`；**不得修改已发布的历史迁移**（`V1`、`V2`、`V4`）。
  - 保护规则：`SYSTEM_ADMIN` 禁止删除；不得撤销 `SYSTEM_ADMIN` 的 `RBAC_MANAGE` 授权；不得删除 `RBAC_MANAGE` 权限本身；角色或权限仍被授权关系引用时返回 `409/RBAC_CONFLICT`；不得使用户失去最后一个角色，也不得使最后一个启用管理员失去管理员角色。
  - 错误语义：`404/ROLE_NOT_FOUND`、`404/PERMISSION_NOT_FOUND`、`404/GRANT_NOT_FOUND`、`409/ROLE_CODE_CONFLICT`、`409/PERMISSION_CODE_CONFLICT`；`code` 创建后不可修改。
  - 授权关系接口用「查询、授予、撤销」而非 `PUT`；重复授予幂等成功；用户角色的授予与撤销成功后撤销该用户全部会话。
  - 审计与测试：四组接口的成功、400、401、403、404、409、幂等与并发路径需要与风险匹配的自动化证据。
- **仍待确认**：① 该能力的交付层级归属（计入求职 MVP 演示范围，还是完整版能力提前实施）；② ~~阶段内的任务拆分、切片顺序与验收标准~~ **已于 2026-09-21 阶段设计确认**（见上方“第 3 步阶段设计确认”一节与 `docs/implementation-plan.md` 9.1）。
- **同步的文档**：`AGENTS.md` 当前阶段限制、本文件“快速定位”与待确认事项、`README.md`；`docs/implementation-plan.md` 的 MVP/完整版对照与 `docs/api-design.md` 8.2.1 中原「完整版可选、MVP 明确不实现」的表述已在 2026-09-21 阶段设计确认后重写为「确认提前实施」，任务拆分落在 `docs/implementation-plan.md` 9.1。

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
- **尚未做**：侧栏的 CSS 下拉三角**还没有换成** `ArrowDown` 组件，等确认后再改（届时可一并删掉只为它加的 `--fd-border-width` token）。**该条已过期**：`AppSidebar` 现已使用 `ArrowDown`，`--fd-border-width` 也已删除（见待确认事项⑥）。
- **2026-09-21 更正（CI 复现后，重要）**：上面把症状判断为"pnpm 在 Windows 上生成符号链接时少了 peer 后缀"的**本机缺陷**，**结论错了**。真实根因在锁文件：`frontend/pnpm-lock.yaml` 的 importer 段把 `@element-plus/icons-vue` 记成没有 peer 后缀的 `2.3.2`，而 snapshots 段只有带后缀的键 `...@2.3.2(vue@3.5.42(typescript@6.0.3))`；pnpm 于是把顶层链接指向不存在的 `.pnpm/@element-plus+icons-vue@2.3.2/`。**它不是 Windows 专有**：Linux CI 上 `pnpm install` 同样报"已安装"，`vue-tsc` 却报 `TS2307 Cannot find module`。已在 `a0e1512` 把 importer 的 `version` 补成与 snapshots 一致的后缀形式；本机此前手工建的 junction 与新锁文件可以并存，不需要再修。**推论**：以后遇到"装上了但解析不到"的链接问题，先对比锁文件 importer 段与 snapshots 段的键，而不是先归因于平台。

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
- 后端按业务模块组织，模块内适度分层，保持单向依赖；`application` 层的包结构与命名约定见 `docs/technical-architecture.md` 5.1（2026-09-23 重构后为唯一真源：Command/Query/Result + `application.service.impl` + `application.port`，`domain` 不放 BO/VO）。
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

任务名称：动态 RBAC 管理端页面（`TASK-060`）。

开工前置（2026-09-23 新增，按顺序）：

1. **由用户补齐 13 个测试类对新包的引用**，恢复 `.\mvnw.cmd -B test-compile` 与 `verify`；补齐前不能把「重构未退化」当作已证明。
2. **裁决用户管理越界在制品的处置**（保留并正式登记任务，还是先撤出工作区），见「待确认事项」④⑤与 2026-09-23 重构记录。
3. 前两项完成后再开始 `TASK-060` 的页面工作。

目标：

- 在 `frontend/src/views/admin/` 实现角色、权限、用户角色授权、角色权限授权四组维护页。
- 新增扁平的 `frontend/src/api/rbac.ts`，补齐 RBAC 错误码文案与 `RBAC_MANAGE` 标签，并按权限控制路由和侧栏入口。
- 用户角色页面一次提交一个用户的多个角色；角色权限页面一次提交一个角色的多个权限，直接调用后端批量端点，不做前端循环请求。

阶段验收：

- 四组页面均覆盖加载、空、错误、无权限、只读/禁用和成功反馈等已确认状态。
- 授予表单提交的请求体与 8.2.1 批量契约一致，成功后刷新授权列表。
- 路由和侧栏入口仅对拥有 `RBAC_MANAGE` 的用户显示。

协作方式：

- 默认由用户编写生产代码；仅在用户明确授权的范围内由 Codex 写入。

本步骤暂不做：

- 用户管理、分类管理、数据概览与通用用户搜索。
- 工单创建、处理、附件和完整状态机。
- 不引入已确认技术边界之外的基础设施。

## 当前任务必读

开始 `TASK-060`（RBAC 管理端页面）前按以下顺序读取；`TASK-058` 后端实现与测试已完成（手工链路待补），`auth`/`iam` 已于 2026-09-23 完成应用层重构但测试代码待用户补齐：

1. `AGENTS.md`
2. `PROJECT_STATUS.md`（尤其 2026-09-23 重构记录、「当前阻塞」、「待确认事项」④⑤与 `TASK-060` 开工规格）
3. `docs/technical-architecture.md` 5.1（重构后的包结构与命名约定，唯一真源）
4. `docs/implementation-plan.md` 9.1（13 项设计决策、`TASK-055`～`TASK-060` 与阶段验收标准）
5. `docs/modules/rbac.md`（第 3 节类清单、第 4.3/4.4 节批量契约与第 12 节管理端实现细则）
6. `docs/api-design.md` 8.2.1（两组授权的请求/响应与错误码）
7. `frontend/AGENTS.md`
8. `.ui-craft/brief.md`

`TASK-060` 的三项已确认决策是：用户输入使用数字 ID + 当前列表候选下拉；两组授权均调用后端批量增量端点；`api/` 保持扁平。页面开工前还需补 6 个 RBAC 错误码与 `RBAC_MANAGE` 标签。此前记录的起点基线为：前端 `typecheck` / `lint` / `build` 退出码 0，单元 8 套件 33 项、E2E 9 项；后端 132 项单元/Web + 30 项集成。按用户当前指示不执行测试，该数字仅作为历史基线，不代表 `TASK-058` 当前状态。

## 文档索引

| 文件 | 状态 | 用途 |
| --- | --- | --- |
| `AGENTS.md` | 生效中 | 协作规则、阶段顺序和当前限制 |
| `README.md` | 已同步 | 项目入口、目标、技术方向和总体状态 |
| `docs/kickoff.md` | 已完成 | v1 需求、流程、权限和验收依据 |
| `docs/business-model.md` | 已完成 | 业务概念、关系和不变量 |
| `docs/technical-architecture.md` | 已完成（2026-09-23 新增 5.1 分层与命名约定） | 总体架构、模块、代码组织与核心技术机制 |
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
