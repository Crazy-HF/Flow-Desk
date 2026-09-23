# IAM RBAC 管理模块实现说明（第 3 步）

> 本文件是「完整动态 RBAC」的阶段设计落地版，写给实现与 review 用。
> 契约上限是 `docs/api-design.md` 8.2.1，任务拆分与验收标准是 `docs/implementation-plan.md` 9.1；
> 两者与本文件冲突时，以已确认的契约文档为准，并先修正本文件。

## 1. 目标与边界

**目标**：让系统管理员可以在线维护角色、权限，以及"用户↔角色""角色↔权限"两组授权关系，并保证受保护对象不可被破坏、权限变更能立即对会话生效。

**做**：

- 四组接口共 21 个端点（见第 4 节：角色 5、权限 5、用户角色授权 6、角色权限授权 5），全部要求 `RBAC_MANAGE`。
- **2026-09-22 补充**：两组授权新增"批量撤销、清空某主体全部关系、一个角色授予多个用户"共 5 个端点；同时与用户确认取消"至少一个角色"不变量，**允许用户零角色**（见第 4、6 节）。
- 新增 Flyway 迁移 `V5`：补 `iam_role_permission` 的审计列、预置 `RBAC_MANAGE` 并只授予 `SYSTEM_ADMIN`。
- 会话撤销：授予/撤销用户角色、以及变更角色权限后，撤销受影响用户的全部会话。
- 管理端页面（`frontend/src/views/admin/`）：角色、权限与两组授权的在线维护页面，**2026-09-22 确认补做并计入 MVP 演示范围**（`TASK-060`）。

**不做**（越界即停）：

- 不扩展 `docs/api-design.md` 8.2.1 之外的权限模型：不做角色继承、不做数据级/组织级权限、不做权限与接口的自动映射。
- 不做 RBAC 之外的管理端页面：用户管理、分类管理、管理性交接仍属完整版 backlog。
- 不改已发布的历史迁移 `V1` / `V2` / `V4`。
- 不新增独立审计日志表（见第 9 节"已知缺口"）。

## 2. 模块依赖与职责

- 依赖方向保持现状：**`auth → iam`**，`iam` 不引用 `auth`。
- IAM 需要撤销会话，但会话存在 Redis、由 auth 模块负责，因此 IAM 定义一个端口：

  ```
  iam/application/port/SessionRevocationPort.java          void revokeAll(long userId)
  auth/infrastructure/IamSessionRevocationAdapter @Component，转发给 AuthSessionRepository.revokeAll
  ```

  IAM 的服务只依赖端口，不知道 Redis 的存在；auth 侧只做转发，不做业务判断。
- **为什么不让 IAM 直接调 `AuthSessionRepository`**：那会让 `iam → auth`，与现有单向依赖成环；端口属于"被调用方定义接口"，方向与 `auth → iam` 一致。
- IAM 写授权审计（`granted_by`）需要"当前操作人用户 ID"，身份却由 auth 侧写入 `SecurityContext`，因此同样用一个端口隔离：

  ```
  iam/application/port/CurrentOperatorPort.java            long currentUserId()
  auth/infrastructure/IamCurrentOperatorAdapter   @Component，从 SecurityContextHolder 取 AuthPrincipal 的 userId
  ```

  **为什么不用别的做法**（2026-09-22 确认方案 A）：
  - IAM 的控制器若直接 `@AuthenticationPrincipal AuthPrincipal` 或 `import com.flowdesk.auth.domain.AuthPrincipal`，就产生 `iam → auth`，与上面的单向依赖冲突。
  - `Authentication#getName()` 在本项目不可用：`JwtAuthenticationFilter` 装的是 record 型 principal，不是 `UserDetails`/`Principal`，`getName()` 会退化成 record 的 `toString()`。
  - 把 `AuthPrincipal` 上提到 `common` 会让 `common` 承载身份模型，改动面最大；让 `AuthPrincipal` 实现 `java.security.Principal` 并把用户 ID 塞进 `getName()` 语义偏松。
  - 两个端口（`SessionRevocationPort`、`CurrentOperatorPort`）形式一致，评审时不必重新论证依赖方向。
- 控制器放在 `iam/controller`，与 `auth/controller` 平级；异常仍走 `common/exception/GlobalExceptionHandler`。

## 3. 类清单（最终形态）

| 层 | 类 | 职责 |
| --- | --- | --- |
| controller | `IamRoleController`、`IamPermissionController`、`IamUserRoleController`、`IamRolePermissionController` | 路由、参数绑定、`@PreAuthorize("hasAuthority('RBAC_MANAGE')")`、HTTP 状态（创建用 `201`） |
| application.service（+ `impl`） | `IamRoleService`、`IamPermissionService`、`IamUserRoleService`、`IamRolePermissionService` | 业务规则、保护规则、事务、编排会话撤销 |
| application.command（写用例入参） | `CreateRoleCommand`、`UpdateRoleCommand`、`CreatePermissionCommand`、`UpdatePermissionCommand`、`GrantUserRolesCommand`、`RevokeUserRolesCommand`、`GrantRoleToUsersCommand`、`GrantRolePermissionsCommand`、`RevokeRolePermissionsCommand` | 字段校验（`jakarta.validation`） |
| application.query（读用例入参） | `RoleQuery`、`PermissionQuery`、`UserRoleQuery`、`RolePermissionQuery` | 筛选与分页，继承 `common.web.PageQuery` |
| application.result（响应/服务间） | `RoleResult`、`PermissionResult`、`UserRoleResult`、`RolePermissionResult` | 对外字段；两类授权结果的字段固定见 4.3、4.4，**不含**任何密码或内部存储细节 |
| application.port（端口） | `SessionRevocationPort`（iam 定义）+ `IamSessionRevocationAdapter`（auth 实现） | 撤销某用户全部会话 |
| application.port（端口） | `CurrentOperatorPort`（iam 定义）+ `IamCurrentOperatorAdapter`（auth 实现） | 取当前操作人用户 ID，用于写 `granted_by` |

命名沿用 2026-09-23 重构后的约定：写用例 `*Command`、读用例 `*Query`、出参 `*Result`，完整分层见 `docs/technical-architecture.md` 5.1。Controller 直接接收 Command/Query 并返回 Result，**不再有 BO/VO**（`iam/domain/bo`、`iam/domain/vo` 已删除）。
查询参数里的分页复用 `common/web/PageQuery`，返回值复用 `common/web/PageResult`。

## 4. 接口实现细则

前缀 `/fd/v1/admin`，全部要求 `RBAC_MANAGE`；响应统一 `R<T>`，`code` 为 `OK` 或稳定错误码。

### 4.1 角色

| 方法与路径 | 请求 | 成功响应 | 失败 |
| --- | --- | --- | --- |
| `GET /roles` | `keyword`（可选，匹配 `code` 或 `name`）+ `PageQuery` | `200 R<PageResult<RoleResult>>` | `400 VALIDATION_FAILED`（分页/排序非法） |
| `GET /roles/{roleId}` | 路径参数 | `200 R<RoleResult>`（含 `permissionIds`） | `404 ROLE_NOT_FOUND` |
| `POST /roles` | `CreateRoleCommand`：`code`、`name`、`description?`、`permissionIds?`（最多 100 个正整数） | `201 R<RoleResult>`（`permissionIds` 已升序） | `400 VALIDATION_FAILED`、`404 PERMISSION_NOT_FOUND`（整单回滚）、`409 ROLE_CODE_CONFLICT` |
| `PUT /roles/{roleId}` | `UpdateRoleCommand`：`name`、`description?`（**没有 `code`**） | `200 R<RoleResult>` | `400`、`404 ROLE_NOT_FOUND` |
| `DELETE /roles/{roleId}` | 路径参数 | `200 R<Void>` | `404 ROLE_NOT_FOUND`、`409 RBAC_CONFLICT` |

### 4.2 权限

与角色同构（`CreatePermissionCommand` / `UpdatePermissionCommand` / `PermissionQuery` / `PermissionResult`），路径 `/permissions`，错误码换成 `PERMISSION_NOT_FOUND`、`PERMISSION_CODE_CONFLICT`。详情返回 `roleIds`。

### 4.3 用户角色授权

| 方法与路径 | 请求 | 成功响应 | 失败 |
| --- | --- | --- | --- |
| `GET /user-roles` | `userId` 或 `roleId` **至少一个** + `PageQuery` | `200 R<PageResult<UserRoleResult>>` | `400 VALIDATION_FAILED`（两个都不给、分页非法） |
| `POST /user-roles` | `GrantUserRolesCommand`：`userId`、非空 `roleIds`（最多 100 个） | `200 R<List<UserRoleResult>>`（按 `roleId` 升序） | `400`、`404 USER_NOT_FOUND`、`404 ROLE_NOT_FOUND` |
| `DELETE /user-roles/{userId}/{roleId}` | 路径参数 | `200 R<Void>` | `404 GRANT_NOT_FOUND`、`409 LAST_ADMIN_PROTECTED` |
| `POST /user-roles/actions/revoke` | `RevokeUserRolesCommand`：`userId`、非空 `roleIds`（最多 100 个） | `200 R<Void>` | `400`、`404 GRANT_NOT_FOUND`、`404 ROLE_NOT_FOUND`、`404 USER_NOT_FOUND`、`409 LAST_ADMIN_PROTECTED`、`409 RBAC_CONFLICT` |
| `DELETE /user-roles/users/{userId}` | 路径参数 | `200 R<Void>`（目标无角色时幂等成功） | `404 USER_NOT_FOUND`、`404 ROLE_NOT_FOUND`、`409 LAST_ADMIN_PROTECTED`、`409 RBAC_CONFLICT` |
| `POST /user-roles/actions/grant-users` | `GrantRoleToUsersCommand`：`roleId`、非空 `userIds`（最多 100 个） | `200 R<List<UserRoleResult>>`（按 `userId` 升序） | `400`、`404 ROLE_NOT_FOUND`、`404 USER_NOT_FOUND`、`409 RBAC_CONFLICT` |

`UserRoleResult` 固定字段：`userId`、`username`、`roleId`、`roleCode`、`roleName`、`grantedBy`、`grantedAt`。其中 `grantedBy` 是授权人用户 ID，可为 `null`；本阶段不返回授权人的用户名或显示名称。

### 4.4 角色权限授权

| 方法与路径 | 请求 | 成功响应 | 失败 |
| --- | --- | --- | --- |
| `GET /role-permissions` | `roleId` 或 `permissionId` **至少一个** + `PageQuery` | `200 R<PageResult<RolePermissionResult>>` | `400 VALIDATION_FAILED` |
| `POST /role-permissions` | `GrantRolePermissionsCommand`：`roleId`、非空 `permissionIds`（最多 100 个） | `200 R<List<RolePermissionResult>>`（按 `permissionId` 升序） | `400`、`404 ROLE_NOT_FOUND`、`404 PERMISSION_NOT_FOUND` |
| `DELETE /role-permissions/{roleId}/{permissionId}` | 路径参数 | `200 R<Void>` | `404 GRANT_NOT_FOUND`、`409 RBAC_CONFLICT` |
| `POST /role-permissions/actions/revoke` | `RevokeRolePermissionsCommand`：`roleId`、非空 `permissionIds`（最多 100 个） | `200 R<Void>` | `400`、`404 GRANT_NOT_FOUND`、`404 ROLE_NOT_FOUND`、`404 PERMISSION_NOT_FOUND`、`409 RBAC_CONFLICT` |
| `DELETE /role-permissions/roles/{roleId}` | 路径参数 | `200 R<Void>`（目标无权限时幂等成功） | `404 ROLE_NOT_FOUND`、`404 PERMISSION_NOT_FOUND`、`409 RBAC_CONFLICT` |

`RolePermissionResult` 固定字段：`roleId`、`roleCode`、`permissionId`、`permissionCode`、`permissionName`、`grantedBy`、`grantedAt`。`grantedBy` 是授权人用户 ID；`grantedBy` 和历史预置关系的 `grantedAt` 可以为 `null`。所有授权时间按全局契约返回带 UTC 偏移的 ISO 8601 字符串。

两组 `POST` 都是**批量增量授予**，不是替换全部关系：服务端对目标 ID 去重并升序归一化，只插入缺失关系，保留已有关系及其审计字段；任一目标不存在时整批回滚。归一化后的关系全部已存在时不写库、不撤销会话；存在实际新增时，用户角色批次只撤销目标用户会话一次，角色权限批次只对该角色的用户快照执行一次会话撤销流程。两组 `DELETE` 继续只撤销路径中指定的一条关系。

**2026-09-22 新增的五个端点**（同一批次的第三种粒度）：

| 分组 | 端点 | 语义 |
| --- | --- | --- |
| 用户角色 | `POST /user-roles/actions/revoke` | 一个用户 × 多个角色：整批原子撤销，任一关系缺失即 `404 GRANT_NOT_FOUND` 且不写库；发生实际删除时只撤销该用户会话一次 |
| 用户角色 | `DELETE /user-roles/users/{userId}` | 清空该用户全部角色：无角色时幂等成功；清空后允许零角色；发生实际删除时只撤销该用户会话一次 |
| 用户角色 | `POST /user-roles/actions/grant-users` | 一个角色 × 多个用户：增量授予，只给缺少该角色的用户新增关系，每个实际新增的用户在写入前各撤销一次会话；全部已存在时不写库、不撤会话 |
| 角色权限 | `POST /role-permissions/actions/revoke` | 一个角色 × 多个权限：整批原子撤销，任一关系缺失即失败；`SYSTEM_ADMIN` 的 `RBAC_MANAGE` 落在批次内直接拒绝 |
| 角色权限 | `DELETE /role-permissions/roles/{roleId}` | 清空该角色全部权限：无权限时幂等成功；`SYSTEM_ADMIN` 仍含 `RBAC_MANAGE` 时拒绝 |

批量撤销与清空都在锁内重新读取现有关系并与请求/先前快照比对，不一致时返回 `409/RBAC_CONFLICT`；删除行数与预期不符时同样返回 `409/RBAC_CONFLICT`。

## 5. 校验与错误码

**请求校验**（`@Valid`，失败由 `GlobalExceptionHandler` 映射为 `400/VALIDATION_FAILED` + `fieldErrors`）：

| 字段 | 规则 |
| --- | --- |
| `code` | `@NotBlank`、`@Size(max = 50)`（权限为 100）、`@Pattern("^[A-Z][A-Z0-9_]*$")` —— 与 `V2` 预置编码（`SYSTEM_ADMIN`、`RBAC_MANAGE`）保持同一风格，避免写入中文/小写/URL 式编码 |
| `name` | `@NotBlank`、`@Size(max = 50)`（权限为 100） |
| `description` | 可选、`@Size(max = 255)` |
| 授予/撤销请求中的 `userId` / `roleId` | `@NotNull`、`@Positive` |
| `roleIds` / `permissionIds` / `userIds` | `@NotEmpty`、`@Size(max = 100)`；每个元素 `@NotNull`、`@Positive` |
| 创建角色时的 `permissionIds` | 可选；提供时 `@Size(max = 100)`，每个元素 `@NotNull`、`@Positive` |
| 列表筛选中的 `userId` / `roleId` / `permissionId` | 可选；提供时必须为正整数，且每组筛选至少提供一个字段 |

**路径变量**：非正整数按"资源不存在"处理，返回 `404`，**不**用 `@Validated` + 约束注解（那会抛 `ConstraintViolationException`，当前 `GlobalExceptionHandler` 不处理它，会变成 `500`）。与用户详情接口既有做法一致。

**错误码**（本次在 `docs/api-design.md` 10.2 中补齐）：

| HTTP | 编码 | 触发条件 |
| --- | --- | --- |
| `404` | `ROLE_NOT_FOUND` | 角色不存在，或 `roleId` 非正整数 |
| `404` | `PERMISSION_NOT_FOUND` | 权限不存在，或 `permissionId` 非正整数 |
| `404` | `GRANT_NOT_FOUND` | 撤销的授权关系不存在 |
| `404` | `USER_NOT_FOUND` | 授予用户角色时用户不存在（沿用既有编码） |
| `409` | `ROLE_CODE_CONFLICT` | 角色 `code` 已存在 |
| `409` | `PERMISSION_CODE_CONFLICT` | 权限 `code` 已存在 |
| `409` | `RBAC_CONFLICT` | 违反保护或引用约束（见第 6 节），或 RBAC 写事务锁等待超时/死锁 |
| `409` | `LAST_ADMIN_PROTECTED` | 撤销或清空角色会让用户失去最后一个启用管理员的 `SYSTEM_ADMIN` 角色 |
| `409` | ~~`USER_ROLE_REQUIRED`~~（已废弃） | 原用于“会移除用户最后一个角色”；现允许用户零角色，本模块不再产生该编码 |
| `403` | `ACCESS_DENIED` | 缺少 `RBAC_MANAGE`（既有编码，无需新增） |

## 6. 保护规则（不变量）

按 `code` 常量判定受保护对象（决策 5），不新增"内置"标记列：

1. `SYSTEM_ADMIN` 角色**始终禁止删除** → `409 RBAC_CONFLICT`。
2. `RBAC_MANAGE` 权限**禁止删除** → `409 RBAC_CONFLICT`。
3. 角色仍被 `iam_user_role` 或 `iam_role_permission` 引用时禁止删除 → `409 RBAC_CONFLICT`。
4. 权限仍被 `iam_role_permission` 引用时禁止删除 → `409 RBAC_CONFLICT`。
5. ~~撤销用户角色时，若该用户只剩这一个角色 → `409 USER_ROLE_REQUIRED`~~ **已取消（2026-09-22，用户确认）**：三条撤销路径（单条撤销、`actions/revoke` 批量撤销、`users/{userId}` 清空全部）都允许目标用户此后**零角色**；`USER_ROLE_REQUIRED` 已废弃，本模块不再产生该编码。零角色用户的后果是登录后没有任何业务权限（只能得到空结果或 `403/ACCESS_DENIED`），不再由授权接口兜底。
6. 撤销或清空用户角色时，若该用户是"最后一个启用的、拥有 `SYSTEM_ADMIN` 的用户" → `409 LAST_ADMIN_PROTECTED`。
7. 撤销 `SYSTEM_ADMIN` 的 `RBAC_MANAGE` 授权 → `409 RBAC_CONFLICT`。
8. `code` 创建后不可修改：`PUT` 的请求体里根本没有该字段，从类型上封死。

判定 6 的实现口径：统计"`status = ENABLED` 且拥有 `SYSTEM_ADMIN` 角色"的用户数；本次撤销会让该计数降到 0 时拒绝。

## 7. 事务与跨存储顺序

- 单个用例内的多表写入在同一个 `@Transactional` 方法里完成。角色或权限仍被授权关系引用时禁止删除，不自动级联清理授权行。
- 所有 RBAC 写用例对用于存在性判断、保护判断和引用判断的已有记录执行 `SELECT ... FOR UPDATE`；列表、详情等只读接口不加悲观锁。
- **全模块固定锁顺序：`iam_role` → `iam_permission` → `iam_user` → 授权关系行**。允许跳过当前用例不涉及的层级，但禁止反向获取；同表多行一律按主键升序。具体口径：

  | 写用例 | 必须先取得的锁 |
  | --- | --- |
  | 修改/删除角色 | 目标角色；随后在锁内重新检查用户角色、角色权限引用 |
  | 修改/删除权限 | 目标权限；随后在锁内重新检查角色权限引用 |
  | 授予用户角色（`POST /user-roles`） | 目标角色（一次 `IN ... FOR UPDATE`，按 ID 升序）→ 目标用户 → 目标用户现有授权关系 |
  | 一个角色授予多个用户（`POST /user-roles/actions/grant-users`） | 目标角色 → 目标用户（一次 `IN ... FOR UPDATE`，按 ID 升序）→ 这些用户在该角色上的现有授权关系 |
  | 撤销用户角色（单条、批量、清空全部） | 目标角色 → 目标用户 → 目标用户现有授权关系；涉及 `SYSTEM_ADMIN` 时，角色行同时作为“最后启用管理员”检查的串行化锁；清空全部角色时先按用户现有角色算出目标集合，再在锁内重查比对，不一致即 `409 RBAC_CONFLICT` |
  | 授予/撤销角色权限（含批量撤销与清空） | 目标角色 → 目标权限 → 目标授权关系；持有角色锁期间通过 `FOR UPDATE` 按 `user_id` 升序锁定并读取该角色的用户角色关系，保证受影响用户集合不会被并发的用户角色写操作改变 |

- 取得全部所需锁后，必须重新查询授权是否存在并重新校验不变量，不能复用加锁前的数据。创建角色/权限仍由唯一约束兜底；授权复合主键和外键是锁协议之外的最后防线。
- 用户角色的所有写操作都先锁角色、再锁用户，因此同一用户不同角色的并发撤销最终会在用户行上串行；后取得用户锁的事务必须看到前一事务结果，不能把同一份旧计数各自当真。所有会影响“最后启用管理员”的后续账号启停用例，也必须先锁 `SYSTEM_ADMIN` 角色，再锁目标用户。
- **会话撤销与 MySQL 提交的顺序（决策 3）：先撤销 Redis 会话，再让事务提交**。与改密的既有顺序一致，理由：若顺序相反而 Redis 失败，会出现"权限已变更但旧会话仍然有效"的窗口；反过来最坏结果是"用户被登出但变更失败"，用户重登即可。
- 触发撤销的三处：

  | 操作 | 撤销范围 |
  | --- | --- |
  | 授予用户角色（一个用户 × 多角色） | 该用户的全部会话；仅当本批实际新增了关系，且每批只执行一次 |
  | 授予一个角色给多个用户（`actions/grant-users`） | 每个**实际新增**该角色的用户各撤销一次，先撤该用户会话再插入该用户的关系 |
  | 撤销用户角色（单条、批量、清空全部） | 该用户的全部会话；仅在发生实际删除时执行一次 |
  | 授予/撤销角色权限（含批量撤销与清空） | **拥有该角色的全部用户的会话**；按已锁定的用户 ID 快照升序逐个撤销 |

- **关于角色权限变更也撤会话**：`docs/api-design.md` 8.1 已确认"用户、角色或账号状态变化成功后，撤销受影响用户的现有登录会话"；会话快照缓存的是**权限码**（`JwtAuthenticationFilter` 从 Redis 快照读权限），所以角色权限变化后不撤销会话，用户的旧权限最长会保留到会话过期（7 天）。撤销某个权限却仍能继续使用，属于安全缺口，因此这里按 8.1 的通用规则执行，8.2.1 只是没有重复写明。**代价**：该角色用户越多，Redis 调用越多（v1 单实例，先不做批量优化）。
- 幂等：重复授予直接返回成功且**不写库、不撤销会话**（没有发生变化），避免把幂等调用变成"把用户踢下线"。
- Redis 撤销发生在持有 MySQL 锁期间；除 `SessionRevocationPort` 外，事务内不得调用其他外部服务。任一用户撤销失败时抛错并回滚 MySQL。数据库报告死锁或锁等待超时时同样回滚并返回 `409/RBAC_CONFLICT`；服务层不自动重试，因为 Redis 撤销是不可回滚副作用。

## 8. 分页与排序

复用 `PageQuery.orderItems(Set<String>)`，白名单（决策 6）：

| 列表 | 白名单 |
| --- | --- |
| 角色 | `code`、`name`、`created_at` |
| 权限 | `code`、`name`、`created_at` |
| 用户角色授权 | `granted_at` |
| 角色权限授权 | `role_id`、`permission_id` |

不传 `orderBy` 时使用默认排序（角色/权限按 `id` 升序，授权按 `granted_at` 降序或主键顺序）。非法字段或方向 → `400/VALIDATION_FAILED`，报错信息不回显字段名（`PageQuery` 既有行为）。

## 9. 审计

- **落库的授权审计**：`iam_user_role` 沿用建表已有的 `granted_by` / `granted_at`；`iam_role_permission` 由 `V5` 补 `granted_by BIGINT UNSIGNED`、`granted_at DATETIME(6)`、授权人索引及指向 `iam_user(id)` 的外键。新授权必须同时写入两列，`granted_by` = 经 `CurrentOperatorPort` 取得的当前操作人 `userId`（见第 2 节）。
- **空值语义**：`granted_by IS NULL` 表示"没有具体操作人"（Flyway 预置或系统写入）；存量 `granted_at` 保持 `NULL`，不伪造时间。
- **请求级审计**：`common/web/filter/RequestAuditFilter` + `TraceIdFilter` 已记录 `method/path/status/durationMs/traceId`，且刻意不记录请求头、Cookie、查询串与请求体。
- **已知缺口**：被拒绝的操作（如"删除 `SYSTEM_ADMIN` 被拒"）不会在数据库留下痕迹，只能靠应用日志；"改了哪些字段"目前无处可查（日志不含请求体）。要覆盖这些，需要独立的只追加审计日志表——**不在本次范围**，需要单独确认。

## 10. 测试矩阵

Web 测试沿用 `AuthWebTest` 的既有做法：`@ActiveProfiles("test")` + 排除 DataSource/Flyway/Redis/MyBatis-Plus 自动配置 + `@AutoConfigureMockMvc` + `@Import({MockedPersistenceConfiguration})` + `@MockitoBean AuthSessionRepository`。四组端点的 401/403 矩阵用 `SecurityMockMvcRequestPostProcessors.user(...)` 构造身份，另由 `SecurityChainScopeWebTest` 用真实 Access Token 补真实过滤链证据（见 10.1）。

| 端点组 | 成功 | 400 | 401 | 403 | 404 | 409 | 幂等 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 角色 | 建/查/改/删 | `code` 非法、分页非法 | 无令牌 | 无 `RBAC_MANAGE` | 详情/改/删 不存在 | `code` 重复、删 `SYSTEM_ADMIN`、被引用 | —— |
| 权限 | 建/查/改/删 | 同上 | 同上 | 同上 | 同上 | `code` 重复、删 `RBAC_MANAGE`、被引用 | —— |
| 用户角色 | 授予/撤销/清空 | 缺筛选、参数非法 | 无令牌 | 无 `RBAC_MANAGE` | 用户/角色不存在、撤销不存在的授权 | 最后一个启用管理员（允许零角色） | 重复授予、清空无角色 |
| 角色权限 | 授予/撤销/清空 | 同上 | 同上 | 同上 | 角色/权限不存在、撤销不存在的授权 | 撤销或清空 `SYSTEM_ADMIN` 的 `RBAC_MANAGE` | 重复授予、清空无权限 |

补充证据要求：

- 迁移层：`DatabaseMigrationIT` 断言版本 `1,2,4,5`、权限数 14、`SYSTEM_ADMIN` 权限数 4，并断言 `RBAC_MANAGE` 恰好授予 `SYSTEM_ADMIN`。
- 会话撤销：断言用户角色变化只撤目标用户；角色权限变化撤销该角色全部用户且按用户 ID 升序；重复授予不撤销；并用 `InOrder` 证明需要时**先撤销后写库**（与 `AuthWebTest` 对改密的既有断言方式一致）。
- 并发：在 MySQL Testcontainers 集成测试中至少真实覆盖两条竞争路径：① 同一用户的全部角色被并发撤销（两个线程各撤一个角色）时无死锁，两次都成功，库内不再有该用户的授权（**零角色是合法终态**，2026-09-22 起不再期望 `409`）；② 两个启用管理员并发撤销各自 `SYSTEM_ADMIN` 时只能有一个成功。测试设置有限等待时间，证明无死锁且最终仍满足“至少一个启用管理员”。mock 返回 0 只能补充分支，不能替代这两条真并发证据。

**以上三条补充证据的落地情况（2026-09-22，`TASK-058` 测试补齐）**：

- 会话撤销：`IamUserRoleServiceImplTest` 断言授予只撤目标用户一次、重复授予不撤；`IamRolePermissionServiceImplTest` 用 `InOrder` 固定"角色锁 → 权限锁 → 用户快照锁 → 授权关系锁 → 撤会话 → 写库"；`IamRolePermissionServiceIT` 在真实库上断言按 `user_id` 升序逐个撤销、与角色无关的用户不被撤销。
- 顺序（先撤会话、后写库）：两组服务单测都用 `InOrder` 断言"撤销（`SessionRevocationPort`）先于 `insert` / `delete`"，等价于项目对改密的既有断言方式。
- 并发：两条竞争路径都在 `IamUserRoleServiceIT` 中用两个线程 + 起始闸门真实并发执行——`concurrentRevokeOfBothRolesSucceedsWithoutDeadlockAndLeavesNoGrant`（两个线程各撤一个角色，**两次都成功**且库内无授权，零角色为合法终态）与 `concurrentRevokeOfOwnAdminRoleLeavesAtLeastOneEnabledAdministrator`（失败方 `409/LAST_ADMIN_PROTECTED`），随后直接查库断言"该用户 0 个角色""启用管理员数 1"。两条都未出现死锁或锁等待超时。

### 10.1 覆盖矩阵（逐格证据）

上表只写"要覆盖什么"，本节写"哪一格由哪个用例负责"。阶段验收要求每一格都有据，因此按用例名逐格登记；两组授权的 **11 个端点**（`TASK-058` 原六个 + 本轮新增的批量撤销、清空全部、一个角色授予多个用户）测试已补齐，本节无空白格。用例数按静态方法计数，`verify` 汇总数字以 `docs/implementation-plan.md` 9.1 登记的最近一次复跑结果（单元/Web 278 + 集成 63）为准。

**关于身份构造（2026-09-22 更正）**：本文件此前描述为"用**真实 JWT** 构造身份"，与代码不符。实际是两类：
- `IamRoleControllerWebTest` / `IamPermissionControllerWebTest` 用 `SecurityMockMvcRequestPostProcessors.user(...)` 直接注入 `SecurityContext`，**不经过 `JwtAuthenticationFilter`**；
- `SecurityChainScopeWebTest` 用真实 Access Token + 会话快照，专门覆盖"过滤器是否装在这条链上"。

这正是 `TASK-059` 能长期躲过 Web 测试的原因（见 `docs/modules/auth.md` §8.1），所以后续新增端点的 401/403 矩阵沿用前者即可，但**每组端点至少要有一条真实过滤链用例**。

| 端点组 | 成功 | 400 | 401 | 403 | 404 | 409 | 幂等 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 角色 | `IamRoleControllerWebTest.rbacManagerCan{List,Get,Create,Update,Delete}Role*`（5） | `invalidPageParametersAreRejected…`、`invalidRoleCodeIsRejected…` | `everyEndpointRequiresAuthentication`（5 端点参数化） | `everyEndpointRequiresRbacManage`（5 端点参数化） | `IamRoleServiceImplTest.returnsNotFoundWhen…`（3）、`IamRoleServiceIT.missingRoleReturnsNotFound…` | `rejectsExistingRoleCode…`、`convertsConcurrentDuplicateKey…`、`alwaysProtectsSystemAdminFromDeletion`、`rejectsDeletionWhenRoleIs{AssignedToUser,HasPermissionGrant}`、`convertsDeleteIntegrityFailure…`；IT：`systemAdminCannotBeDeleted…`、`role{AssignedToUser,GrantedPermission}CannotBeDeleted`、`duplicateCodeReturnsBusinessConflict…` | 不适用（无幂等端点） |
| 权限 | `IamPermissionControllerWebTest.rbacManagerCan{List,Get,Create,Update,Delete}Permission*`（5） | `invalidPageParametersAreRejected…`、`invalidPermissionCodeIsRejected…` | 同上（5 端点参数化） | 同上（5 端点参数化） | `IamPermissionServiceImplTest` 对称用例、`IamPermissionServiceIT.missingPermissionReturnsNotFound…` | `IamPermissionServiceImplTest` 对称用例；IT：`rbacManageCannotBeDeleted…`、`permissionGrantedToRoleCannotBeDeleted`、`duplicateCodeReturnsBusinessConflict…` | 不适用 |
| 用户角色 | `IamUserRoleControllerWebTest.rbacManagerCan{ListUserRoles*,ListUserRolesByRoleIdOnly,GrantUserRoles*,RevokeSingleUserRole,BatchRevokeUserRoles,ClearAllUserRoles,GrantOneRoleToManyUsers}`（7）；`IamUserRoleServiceIT.{persistsOnlyMissingGrantsWithAudit…,pagesRealRowsForTargetUser…,revokeBatchRemovesRequestedRoles…,clearAllRemovesEveryRole…,grantUsersGrantsRoleToMissingUsers…}` | `IamUserRoleControllerWebTest.listWithoutAnyFilter…`、`invalidPageParameters…`、`emptyRoleIdList…`、`nonPositiveUserId…`、`emptyRoleIdListOnBatchRevoke…`、`emptyUserIdListOnGrantUsers…`；排序白名单 400 在服务层：`IamUserRoleServiceImplTest.rejectsOrderFieldOutsideWhitelist…` | `IamUserRoleControllerWebTest.everyEndpointRequiresAuthentication`（6 端点参数化）；真实过滤链：`SecurityChainScopeWebTest.everyAdminEndpointWithoutToken…` | `IamUserRoleControllerWebTest.everyEndpointRequiresRbacManage`（6 端点参数化）；真实过滤链：`SecurityChainScopeWebTest.businessPathWithTokenButWithoutPermission…` | `IamUserRoleServiceImplTest.rejectsMissing{Role,User}…`、`rejectsRevokeWhen{GrantDoesNotExist,RoleIsMissing,UserIsMissing}`、`rejectsBatchRevokeWhen{AnyGrantIsMissingWithoutWriteOrRevocation,AnyRoleIsMissing,AnyUserIsMissing}`、`clearAllRejectsMissingUser`、`rejectsGrantUsersWhen{AnyUserIsMissing,RoleIsMissing}`；IT：`rejectsWholeBatchWhenAnyRoleDoesNotExist…`、`rejectsMissingUser`、`rejectsRevokeWhenGrantDoesNotExist`、`revokeBatchRejectsWholeBatch…`、`grantUsersRejectsWholeBatch…` | `IamUserRoleServiceImplTest.protectsLastEnabledAdministratorFromLosingSystemAdminRole`、`protectsLastEnabledAdministratorDuringBatchRevoke`、`clearAllProtectsLastEnabledAdministrator`、`allowsRevokingSystemAdminRoleWhenAnotherEnabledAdministratorRemains`、`skipsLastAdministratorCheckForDisabledUser`、`{converts,revoke}LockWaitFailureIsConvertedToRbacConflict`、`convertsBatchRevokeLockFailureToRbacConflict`、`convertsGrantUsersLockFailureToRbacConflict`、`reportsBatchRevokeConflictWhenDeletedRowCountDiffers`、`clearAllReportsConflictWhen{GrantsChangedConcurrently,DeletedRowCountDiffers}`；IT：`protectsLastEnabledAdministratorButAllowsRevoke…`、两条真并发用例 | `IamUserRoleServiceImplTest.repeatedGrantOfExistingRolesIsIdempotentAndDoesNotRevokeSession`、`repeatedGrantUsersIsIdempotentAndDoesNotRevokeSessions`、`clearAllIsIdempotentWhenUserHasNoRoles`、`allowsRevokingUsersLastRemainingRole`、`allowsBatchRevokeThatLeavesUserWithoutAnyRole`；IT：`repeatedGrantIsIdempotentAndKeepsOriginalAudit`、`clearAllRemovesEveryRoleAndIsIdempotent`、`allowsRevokingUsersLastRemainingRole` |
| 角色权限 | `IamRolePermissionControllerWebTest.rbacManagerCan{ListRolePermissions*,ListRolePermissionsByPermissionIdOnly,GrantRolePermissions*,RevokeSingleRolePermission,BatchRevokeRolePermissions,ClearAllRolePermissions}`（6）；`IamRolePermissionServiceIT.{pagesRealRowsForTargetRole…,grantsOnlyMissingPermissions…,revokeBatchRemovesRequestedPermissions…,clearAllRemovesEveryPermission…}` | `IamRolePermissionControllerWebTest.listWithoutAnyFilter…`、`invalidPageParameters…`、`emptyPermissionIdList…`、`moreThanOneHundredPermissionIds…`、`emptyPermissionIdListOnBatchRevoke…`；排序白名单 400 在服务层：`IamRolePermissionServiceImplTest.rejectsOrderFieldOutsideWhitelist` | 同上（真实过滤链 4 组全覆盖） | 同上（真实过滤链 4 组全覆盖） | `IamRolePermissionServiceImplTest.rejectsMissing{Role,Permission}Before…`、`rejectsRevokeWhen{GrantDoesNotExist,RoleIsMissing,PermissionIsMissing}`、`rejectsBatchRevokeWhen{AnyGrantIsMissingWithoutRevocation,AnyPermissionIsMissing,RoleIsMissing}`、`clearAllRejectsMissingRole`；IT：`rejectsWholeBatchWhenAnyPermissionDoesNotExist…`、`rejectsMissingRole`、`rejectsRevokeWhenGrantDoesNotExist`、`revokeBatchRejectsWholeBatch…`、`clearAllRejectsMissingRole` | `IamRolePermissionServiceImplTest.protectsSystemAdminRbacManageGrantFromRevocation`（并证明其他权限可撤）、`protectsSystemAdminRbacManageDuringBatchRevoke`、`clearAllProtectsSystemAdminRbacManage`、`{grant,revoke}LockWaitFailureIsConvertedToRbacConflict`、`convertsBatchRevokeLockFailureToRbacConflict`、`reportsConflictWhenBatchRevokeDeletedRowCountDiffers`、`clearAllReportsConflictWhen{PermissionsChangedConcurrently,DeletedRowCountDiffers}`；IT：`protectsSystemAdminRbacManageGrantFromRevocation`、`revokeBatchProtectsSystemAdminRbacManage`、`clearAllProtectsSystemAdminRbacManage` | `IamRolePermissionServiceImplTest.repeatedGrantOfExistingPermissionsIsIdempotentAndDoesNotRevokeSessions`、`clearAllIsIdempotentWhenRoleHasNoPermissions`、`allowsRevokingOtherPermissionsFromSystemAdminRole`；IT：`repeatedGrantIsIdempotentAndDoesNotRevokeSessions`、`clearAllRemovesEveryPermissionAndIsIdempotent` |

两组授权的 11 个端点已补齐，本节无空白格。除上表外，`IamCurrentOperatorAdapterTest`（4 项）覆盖"审计操作人只能来自真实认证身份"，避免 `granted_by` 被匿名或替身身份伪造。

真实过滤链证据（`SecurityChainScopeWebTest`，任务 `TASK-059`）：`/fd/v1/admin/roles` 上无令牌 `401/AUTH_REQUIRED`、有 `RBAC_MANAGE` 令牌 `200/OK`、缺权限令牌 `403/ACCESS_DENIED`、会话已撤销 `401/AUTH_SESSION_INVALID`；另有真实栈写路径证据（`DELETE` 受保护对象返回 `409`，见第 11 节）。**2026-09-22 扩展**：`everyAdminEndpointWithoutTokenIsRejectedBeforeController` 与 `businessPathWithTokenButWithoutPermissionIsForbidden` 已参数化覆盖 `roles`、`permissions`、`user-roles`、`role-permissions` 四组，满足"每组端点至少一条真实过滤链用例"。

## 11. 验收命令与手工验证

```powershell
# 单元/Web + 集成（Testcontainers 需要 Docker）
$env:JAVA_HOME='D:\Idea\Jdk\Jdk21'; .\mvnw.cmd -B verify

# 真实栈
docker compose up -d mysql redis
$env:JAVA_HOME='D:\Idea\Jdk\Jdk21'; .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

手工链路。**清单与 `docs/implementation-plan.md` 9.1 阶段验收标准第 4 条一一对应**，每条都要留 `traceId` 与响应码：

| # | 链路 | 期望 |
| --- | --- | --- |
| 1 | `admin` 登录 → 给 `employee` 授予角色 → 用 `employee` 的**旧** Access Token 调 `/fd/v1/auth/me` | 授予前令牌可用；授予后 `401/AUTH_SESSION_INVALID` |
| 2 | 变更某角色的权限（授予或撤销一条）→ 用该角色某个用户的**旧** Access Token 调受保护接口 | `401/AUTH_SESSION_INVALID`；该角色全部用户的会话都失效，不只是操作人自己 |
| 3 | 撤销 `employee` 的全部角色（先用 `actions/revoke` 或 `users/{userId}` 清空） | `200/OK`；库内该用户零授权，**零角色是合法终态**；该用户旧 Access Token 变 `401/AUTH_SESSION_INVALID`，重新登录后没有任何业务权限（`403/ACCESS_DENIED`） |
| 4 | 删除 `SYSTEM_ADMIN` | `409/RBAC_CONFLICT` |
| 5 | 清理：撤销授权、删除临时角色，并把本机库恢复到与迁移一致的状态 | 迁移版本、权限数、`SYSTEM_ADMIN` 权限数与 `DatabaseMigrationIT` 断言一致 |

链路 4 之外，`DELETE /fd/v1/admin/permissions/15`（删 `RBAC_MANAGE` 本体）、`DELETE /fd/v1/admin/role-permissions/3/15`（撤销授权）、`POST /fd/v1/admin/role-permissions/actions/revoke`（批量撤销把该授权包在批次里）与 `DELETE /fd/v1/admin/role-permissions/roles/3`（清空 `SYSTEM_ADMIN` 全部权限）是同一族保护规则的不同入口，收口时一并留证——其中"清空"入口能同时证明批量/清空路径也走同一保护判定。

### 11.1 已留证的链路（2026-09-22，`local` profile + 演示账号）

链路 1、2 与链路 3 的"撤销 `RBAC_MANAGE`"部分依赖 `TASK-058` 的端点，尚未执行。链路 3 的期望已在 2026-09-22 由 `409/USER_ROLE_REQUIRED` 改为"允许零角色"，收口时必须按新期望留证。**已经执行并留证的部分**：

| 步骤 | 响应 | `traceId` |
| --- | --- | --- |
| `admin`/`123456` 登录取令牌 | `200/OK`，权限含 `RBAC_MANAGE` | `21a3d8f4-4dd2-41fa-9707-82134e7c04d2` |
| `DELETE /fd/v1/admin/roles/3` | `409/RBAC_CONFLICT`「系统管理员角色不能删除」 | `04cb2583-bc7d-4919-9aa6-a412c8cf9652` |
| `DELETE /fd/v1/admin/permissions/15` | `409/RBAC_CONFLICT`「RBAC_MANAGE 权限不能删除」 | `41e91d3c-658e-4894-8e1a-928ab9779d5e` |
| 拒绝后核对（`GET /roles/3`、`GET /permissions/15`） | 两行都仍在：`SYSTEM_ADMIN`(id=3)、`RBAC_MANAGE`(id=15) | —— |

第三条顺带构成**写路径**的真实过滤链证据：`DELETE` 同样经过 JWT 过滤器并到达控制器，不只是 `GET` 可用。

`TASK-059` 的完整真实验证矩阵（无令牌 `401`、`admin` `200`、`employee` `403`，各 2 个端点）记在 `PROJECT_STATUS.md` 的 2026-09-22 小节与 `docs/modules/auth.md` §8.1。

## 12. 管理端页面（`TASK-060`）实现细则

三个交互与落层决策由用户 **2026-09-22 一次确认**（原为 `PROJECT_STATUS.md` 的待确认事项），结论如下。

### 12.1 用户选择器：数字用户 ID + 候选下拉

**代码事实**：`GET /fd/v1/users` **未实现**——`IamUserController` 只有一个空的 `@RequestMapping("/fd/v1/iam/user")` 壳，一个方法都没有，连路径都与 `docs/api-design.md` 8.2 的 `/fd/v1/users` 不一致；用户管理按 8.2 与本文第 1 节属**完整版 backlog**。因此"给用户授角色"没有可用的用户检索数据源。

- 用户输入**数字用户 ID**，输入框旁明确说明"后端暂无用户查询接口，用户管理属完整版"。**不做可点击的假搜索框**——与 `.ui-craft/brief.md` 对顶栏搜索位的既有纠正同一条原则。
- 候选下拉只提供**当前列表里已经出现过的**用户（来自 `UserRoleResult.userId` / `username`）；它是便利，不是数据源，不能因此让首屏依赖它。
- 角色选择器正常实现，数据源是 `GET /fd/v1/admin/roles`（支持 `keyword` + `PageQuery`）。
- 权限选择器（角色权限授予页）数据源是 `GET /fd/v1/admin/permissions`（`keyword` 同时匹配 `code` 与 `name`），据此实现"权限码搜索"的远程检索。

### 12.2 批量授权语义：后端单事务增量授予

两组授权都由后端批量接口完成，前端不得循环发送单条 `POST`：

- 用户角色：一个用户 × 多个角色，提交 `userId + roleIds`；
- 角色权限：一个角色 × 多个权限，提交 `roleId + permissionIds`。

批量请求是**增量新增**而不是“先删除全部关系再重建”：已有关系保留原审计信息，只插入缺失关系；任一目标不存在时整批回滚，不会出现前端循环造成的部分成功。只有授权集合实际变化时才撤销会话，并且每个批次只执行一次对应范围的撤销流程。单条 `DELETE` 仍用于精确撤销一个用户角色或一个角色权限关系。

**撤销侧同样有批量入口**（2026-09-22 新增，页面按需使用，不必循环单条 `DELETE`）：

- 用户角色：`POST /user-roles/actions/revoke`（一个用户 × 多个角色）与 `DELETE /user-roles/users/{userId}`（清空该用户全部角色）；
- 角色权限：`POST /role-permissions/actions/revoke`（一个角色 × 多个权限）与 `DELETE /role-permissions/roles/{roleId}`（清空该角色全部权限）。

这些入口都是整批原子语义，且允许目标用户零角色；"一个角色授予多个用户"用 `POST /user-roles/actions/grant-users`（一个角色 × 多个用户）。页面应在多选场景下直接调用批量端点，不要用循环拼出"部分成功"。

### 12.3 `api/` 保持扁平，`errorMessages` 不下沉

`frontend/AGENTS.md` 的分层触发条件已满足（RBAC 是第二个领域模块），确认结论是**不分层**：

- 只新增 `api/rbac.ts`，**不建 `api/core/`**。理由沿用 `frontend/AGENTS.md` 自己的论证：拆分会把"HTTP 层懂认证"这个必须显眼的事实藏起来；`auth.ts` / `rbac.ts` 是领域文件，`http.ts` / `pagination.ts` / `errorMessages.ts` 是基础设施，命名已经区分，拆分只增加 import 改动而信息量为零。
- `errorMessages` 继续用**单一映射表**。按领域拆表会让"某个错误码的文案在哪"取决于你是否知道它属于哪个领域，反而更难找；补完后约 41 行，一张表一眼扫完。

### 12.4 页面开工前必须补的两处既有缺口

不补的话页面会直接露出裸编码或缺失标签：

| 文件 | 缺什么 |
| --- | --- |
| `frontend/src/api/errorMessages.ts` | 6 个 RBAC 错误码：`ROLE_NOT_FOUND`、`PERMISSION_NOT_FOUND`、`GRANT_NOT_FOUND`、`ROLE_CODE_CONFLICT`、`PERMISSION_CODE_CONFLICT`、`RBAC_CONFLICT`——这正是新页面会撞上的全部错误。**另需清理**：已有条目 `USER_ROLE_REQUIRED`（"至少需要保留一个角色"）自 2026-09-22 起已废弃，后端不再返回该编码，留在表里会误导页面文案 |
| `frontend/src/constants/authorization.ts` | `permissionLabels` 缺 `RBAC_MANAGE`；并且要按 `RBAC_MANAGE` 在侧栏 `admin` 组新增四组维护页入口 |

## 13. 依据文档

- `docs/api-design.md` 8.1、8.2.1、9、10.2：接口契约、权限映射、错误码。
- `docs/database-design.md` 17.2～17.5：表结构与审计列。
- `docs/implementation-plan.md` 9.1：决策、任务拆分与阶段验收标准。
- `docs/modules/auth.md`：会话、权限快照与撤销的既有实现。
- `PROJECT_STATUS.md`：阶段状态与已知环境问题（Flyway 历史表残留、Redis 残留键、本机 Argon2 耗时）。
