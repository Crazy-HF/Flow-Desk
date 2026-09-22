# IAM RBAC 管理模块实现说明（第 3 步）

> 本文件是「完整动态 RBAC」的阶段设计落地版，写给实现与 review 用。
> 契约上限是 `docs/api-design.md` 8.2.1，任务拆分与验收标准是 `docs/implementation-plan.md` 9.1；
> 两者与本文件冲突时，以已确认的契约文档为准，并先修正本文件。

## 1. 目标与边界

**目标**：让系统管理员可以在线维护角色、权限，以及"用户↔角色""角色↔权限"两组授权关系，并保证受保护对象不可被破坏、权限变更能立即对会话生效。

**做**：

- 四组接口共 16 个端点（见第 4 节），全部要求 `RBAC_MANAGE`。
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
  iam/service/SessionRevocationPort.java          void revokeAll(long userId)
  auth/infrastructure/IamSessionRevocationAdapter @Component，转发给 AuthSessionRepository.revokeAll
  ```

  IAM 的服务只依赖端口，不知道 Redis 的存在；auth 侧只做转发，不做业务判断。
- **为什么不让 IAM 直接调 `AuthSessionRepository`**：那会让 `iam → auth`，与现有单向依赖成环；端口属于"被调用方定义接口"，方向与 `auth → iam` 一致。
- IAM 写授权审计（`granted_by`）需要"当前操作人用户 ID"，身份却由 auth 侧写入 `SecurityContext`，因此同样用一个端口隔离：

  ```
  iam/service/CurrentOperatorPort.java            long currentUserId()
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
| service | `IamRoleService`、`IamPermissionService`、`IamUserRoleService`、`IamRolePermissionService`（+ `impl`） | 业务规则、保护规则、事务、编排会话撤销 |
| vo（请求） | `IamRoleCreateVO`、`IamRoleUpdateVO`、`IamRoleQueryVO`、`IamPermissionCreateVO`、`IamPermissionUpdateVO`、`IamPermissionQueryVO`、`IamUserRoleGrantVO`、`IamUserRoleQueryVO`、`IamRolePermissionGrantVO`、`IamRolePermissionQueryVO` | 字段校验（`jakarta.validation`） |
| bo（响应/服务间） | `IamRoleBO`、`IamPermissionBO`、`IamUserRoleBO`、`IamRolePermissionBO` | 对外字段；两类授权 BO 的字段固定见 4.3、4.4，**不含**任何密码或内部存储细节 |
| 端口 | `SessionRevocationPort`（iam）+ `IamSessionRevocationAdapter`（auth） | 撤销某用户全部会话 |
| 端口 | `CurrentOperatorPort`（iam）+ `IamCurrentOperatorAdapter`（auth） | 取当前操作人用户 ID，用于写 `granted_by` |

命名沿用项目既有约定：请求体 `*VO`、响应与服务间 `*BO`（对照 `auth/domain/vo/AuthLoginVO` 与 `auth/domain/bo/AuthUserBO`）。
查询参数里的分页复用 `common/web/PageQuery`，返回值复用 `common/web/PageResult`。

## 4. 接口实现细则

前缀 `/fd/v1/admin`，全部要求 `RBAC_MANAGE`；响应统一 `R<T>`，`code` 为 `OK` 或稳定错误码。

### 4.1 角色

| 方法与路径 | 请求 | 成功响应 | 失败 |
| --- | --- | --- | --- |
| `GET /roles` | `keyword`（可选，匹配 `code` 或 `name`）+ `PageQuery` | `200 R<PageResult<IamRoleBO>>` | `400 VALIDATION_FAILED`（分页/排序非法） |
| `GET /roles/{roleId}` | 路径参数 | `200 R<IamRoleBO>`（含 `permissionIds`） | `404 ROLE_NOT_FOUND` |
| `POST /roles` | `IamRoleCreateVO`：`code`、`name`、`description?` | `201 R<IamRoleBO>` | `400 VALIDATION_FAILED`、`409 ROLE_CODE_CONFLICT` |
| `PUT /roles/{roleId}` | `IamRoleUpdateVO`：`name`、`description?`（**没有 `code`**） | `200 R<IamRoleBO>` | `400`、`404 ROLE_NOT_FOUND` |
| `DELETE /roles/{roleId}` | 路径参数 | `200 R<Void>` | `404 ROLE_NOT_FOUND`、`409 RBAC_CONFLICT` |

### 4.2 权限

与角色同构（`IamPermissionCreateVO` / `UpdateVO` / `QueryVO` / `IamPermissionBO`），路径 `/permissions`，错误码换成 `PERMISSION_NOT_FOUND`、`PERMISSION_CODE_CONFLICT`。详情返回 `roleIds`。

### 4.3 用户角色授权

| 方法与路径 | 请求 | 成功响应 | 失败 |
| --- | --- | --- | --- |
| `GET /user-roles` | `userId` 或 `roleId` **至少一个** + `PageQuery` | `200 R<PageResult<IamUserRoleBO>>` | `400 VALIDATION_FAILED`（两个都不给、分页非法） |
| `POST /user-roles` | `IamUserRoleGrantVO`：`userId`、`roleId` | `200 R<IamUserRoleBO>`（首次与重复都是 `200`；重复时返回原记录） | `400`、`404 USER_NOT_FOUND`、`404 ROLE_NOT_FOUND` |
| `DELETE /user-roles/{userId}/{roleId}` | 路径参数 | `200 R<Void>` | `404 GRANT_NOT_FOUND`、`409 USER_ROLE_REQUIRED`、`409 LAST_ADMIN_PROTECTED` |

`IamUserRoleBO` 固定字段：`userId`、`username`、`roleId`、`roleCode`、`roleName`、`grantedBy`、`grantedAt`。其中 `grantedBy` 是授权人用户 ID，可为 `null`；本阶段不返回授权人的用户名或显示名称。

### 4.4 角色权限授权

| 方法与路径 | 请求 | 成功响应 | 失败 |
| --- | --- | --- | --- |
| `GET /role-permissions` | `roleId` 或 `permissionId` **至少一个** + `PageQuery` | `200 R<PageResult<IamRolePermissionBO>>` | `400 VALIDATION_FAILED` |
| `POST /role-permissions` | `IamRolePermissionGrantVO`：`roleId`、`permissionId` | `200 R<IamRolePermissionBO>`（重复时返回原记录） | `400`、`404 ROLE_NOT_FOUND`、`404 PERMISSION_NOT_FOUND` |
| `DELETE /role-permissions/{roleId}/{permissionId}` | 路径参数 | `200 R<Void>` | `404 GRANT_NOT_FOUND`、`409 RBAC_CONFLICT` |

`IamRolePermissionBO` 固定字段：`roleId`、`roleCode`、`permissionId`、`permissionCode`、`permissionName`、`grantedBy`、`grantedAt`。`grantedBy` 是授权人用户 ID；`grantedBy` 和历史预置关系的 `grantedAt` 可以为 `null`。所有授权时间按全局契约返回带 UTC 偏移的 ISO 8601 字符串。

## 5. 校验与错误码

**请求校验**（`@Valid`，失败由 `GlobalExceptionHandler` 映射为 `400/VALIDATION_FAILED` + `fieldErrors`）：

| 字段 | 规则 |
| --- | --- |
| `code` | `@NotBlank`、`@Size(max = 50)`（权限为 100）、`@Pattern("^[A-Z][A-Z0-9_]*$")` —— 与 `V2` 预置编码（`SYSTEM_ADMIN`、`RBAC_MANAGE`）保持同一风格，避免写入中文/小写/URL 式编码 |
| `name` | `@NotBlank`、`@Size(max = 50)`（权限为 100） |
| `description` | 可选、`@Size(max = 255)` |
| `userId` / `roleId` / `permissionId` | `@NotNull`、`@Positive`（授权与筛选） |

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
| `409` | `USER_ROLE_REQUIRED` / `LAST_ADMIN_PROTECTED` | 沿用既有编码，见第 6 节 |
| `403` | `ACCESS_DENIED` | 缺少 `RBAC_MANAGE`（既有编码，无需新增） |

## 6. 保护规则（不变量）

按 `code` 常量判定受保护对象（决策 5），不新增"内置"标记列：

1. `SYSTEM_ADMIN` 角色**始终禁止删除** → `409 RBAC_CONFLICT`。
2. `RBAC_MANAGE` 权限**禁止删除** → `409 RBAC_CONFLICT`。
3. 角色仍被 `iam_user_role` 或 `iam_role_permission` 引用时禁止删除 → `409 RBAC_CONFLICT`。
4. 权限仍被 `iam_role_permission` 引用时禁止删除 → `409 RBAC_CONFLICT`。
5. 撤销用户角色时，若该用户只剩这一个角色 → `409 USER_ROLE_REQUIRED`。
6. 撤销用户角色时，若该用户是"最后一个启用的、拥有 `SYSTEM_ADMIN` 的用户" → `409 LAST_ADMIN_PROTECTED`。
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
  | 授予/撤销用户角色 | 目标角色 → 目标用户 → 目标用户现有授权关系；涉及 `SYSTEM_ADMIN` 时，角色行同时作为“最后启用管理员”检查的串行化锁 |
  | 授予/撤销角色权限 | 目标角色 → 目标权限 → 目标授权关系；持有角色锁期间通过 `FOR UPDATE` 按 `user_id` 升序锁定并读取该角色的用户角色关系，保证受影响用户集合不会被并发的用户角色写操作改变 |

- 取得全部所需锁后，必须重新查询授权是否存在并重新校验不变量，不能复用加锁前的数据。创建角色/权限仍由唯一约束兜底；授权复合主键和外键是锁协议之外的最后防线。
- 用户角色的所有写操作都先锁角色、再锁用户，因此同一用户不同角色的并发撤销最终会在用户行上串行；后取得用户锁的事务必须看到前一事务结果，不能把同一份旧计数各自当真。所有会影响“最后启用管理员”的后续账号启停用例，也必须先锁 `SYSTEM_ADMIN` 角色，再锁目标用户。
- **会话撤销与 MySQL 提交的顺序（决策 3）：先撤销 Redis 会话，再让事务提交**。与改密的既有顺序一致，理由：若顺序相反而 Redis 失败，会出现"权限已变更但旧会话仍然有效"的窗口；反过来最坏结果是"用户被登出但变更失败"，用户重登即可。
- 触发撤销的三处：

  | 操作 | 撤销范围 |
  | --- | --- |
  | 授予用户角色 | 该用户的全部会话 |
  | 撤销用户角色 | 该用户的全部会话 |
  | 授予/撤销角色权限 | **拥有该角色的全部用户的会话**；按已锁定的用户 ID 快照升序逐个撤销 |

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

Web 测试沿用 `AuthWebTest` 的既有做法：`@ActiveProfiles("test")` + 排除 DataSource/Flyway/Redis/MyBatis-Plus 自动配置 + `@AutoConfigureMockMvc` + `@Import({MockedPersistenceConfiguration})` + `@MockitoBean AuthSessionRepository`，用**真实 JWT** 构造身份。

| 端点组 | 成功 | 400 | 401 | 403 | 404 | 409 | 幂等 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 角色 | 建/查/改/删 | `code` 非法、分页非法 | 无令牌 | 无 `RBAC_MANAGE` | 详情/改/删 不存在 | `code` 重复、删 `SYSTEM_ADMIN`、被引用 | —— |
| 权限 | 建/查/改/删 | 同上 | 同上 | 同上 | 同上 | `code` 重复、删 `RBAC_MANAGE`、被引用 | —— |
| 用户角色 | 授予/撤销 | 缺筛选、参数非法 | 同上 | 同上 | 用户/角色不存在、撤销不存在的授权 | 最后一个角色、最后一个管理员 | 重复授予 |
| 角色权限 | 授予/撤销 | 同上 | 同上 | 同上 | 角色/权限不存在、撤销不存在的授权 | 撤销 `SYSTEM_ADMIN` 的 `RBAC_MANAGE` | 重复授予 |

补充证据要求：

- 迁移层：`DatabaseMigrationIT` 断言版本 `1,2,4,5`、权限数 14、`SYSTEM_ADMIN` 权限数 4，并断言 `RBAC_MANAGE` 恰好授予 `SYSTEM_ADMIN`。
- 会话撤销：断言用户角色变化只撤目标用户；角色权限变化撤销该角色全部用户且按用户 ID 升序；重复授予不撤销；并用 `InOrder` 证明需要时**先撤销后写库**（与 `AuthWebTest` 对改密的既有断言方式一致）。
- 并发：在 MySQL Testcontainers 集成测试中至少真实覆盖两条竞争路径：① 同一用户仅剩两个角色时，并发撤销不同角色只能有一个成功；② 两个启用管理员并发撤销各自 `SYSTEM_ADMIN` 时只能有一个成功。测试设置有限等待时间，证明无死锁且最终仍满足“至少一个角色、至少一个启用管理员”。mock 返回 0 只能补充分支，不能替代这两条真并发证据。

### 10.1 覆盖矩阵（逐格证据）

上表只写"要覆盖什么"，本节写"哪一格由哪个用例负责"。阶段验收要求每一格都有据，因此按用例名逐格登记；`TASK-058` 的六个端点落地后，本节的空白格必须补齐才能进入阶段收口。

**关于身份构造（2026-09-22 更正）**：本文件此前描述为"用**真实 JWT** 构造身份"，与代码不符。实际是两类：
- `IamRoleControllerWebTest` / `IamPermissionControllerWebTest` 用 `SecurityMockMvcRequestPostProcessors.user(...)` 直接注入 `SecurityContext`，**不经过 `JwtAuthenticationFilter`**；
- `SecurityChainScopeWebTest` 用真实 Access Token + 会话快照，专门覆盖"过滤器是否装在这条链上"。

这正是 `TASK-059` 能长期躲过 Web 测试的原因（见 `docs/modules/auth.md` §8.1），所以后续新增端点的 401/403 矩阵沿用前者即可，但**每组端点至少要有一条真实过滤链用例**。

| 端点组 | 成功 | 400 | 401 | 403 | 404 | 409 | 幂等 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 角色 | `IamRoleControllerWebTest.rbacManagerCan{List,Get,Create,Update,Delete}Role*`（5） | `invalidPageParametersAreRejected…`、`invalidRoleCodeIsRejected…` | `everyEndpointRequiresAuthentication`（5 端点参数化） | `everyEndpointRequiresRbacManage`（5 端点参数化） | `IamRoleServiceImplTest.returnsNotFoundWhen…`（3）、`IamRoleServiceIT.missingRoleReturnsNotFound…` | `rejectsExistingRoleCode…`、`convertsConcurrentDuplicateKey…`、`alwaysProtectsSystemAdminFromDeletion`、`rejectsDeletionWhenRoleIs{AssignedToUser,HasPermissionGrant}`、`convertsDeleteIntegrityFailure…`；IT：`systemAdminCannotBeDeleted…`、`role{AssignedToUser,GrantedPermission}CannotBeDeleted`、`duplicateCodeReturnsBusinessConflict…` | 不适用（无幂等端点） |
| 权限 | `IamPermissionControllerWebTest.rbacManagerCan{List,Get,Create,Update,Delete}Permission*`（5） | `invalidPageParametersAreRejected…`、`invalidPermissionCodeIsRejected…` | 同上（5 端点参数化） | 同上（5 端点参数化） | `IamPermissionServiceImplTest` 对称用例、`IamPermissionServiceIT.missingPermissionReturnsNotFound…` | `IamPermissionServiceImplTest` 对称用例；IT：`rbacManageCannotBeDeleted…`、`permissionGrantedToRoleCannotBeDeleted`、`duplicateCodeReturnsBusinessConflict…` | 不适用 |
| 用户角色 | **待 `TASK-058`** | 待 `TASK-058`（缺筛选） | 待 `TASK-058` | 待 `TASK-058` | 待 `TASK-058` | 待 `TASK-058`（最后角色、最后启用管理员） | 待 `TASK-058`（重复授予） |
| 角色权限 | **待 `TASK-058`** | 待 `TASK-058`（缺筛选） | 待 `TASK-058` | 待 `TASK-058` | 待 `TASK-058` | 待 `TASK-058`（`SYSTEM_ADMIN`×`RBAC_MANAGE`） | 待 `TASK-058`（重复授予） |

真实过滤链证据（`SecurityChainScopeWebTest`，任务 `TASK-059`）：`/fd/v1/admin/roles` 上无令牌 `401/AUTH_REQUIRED`、有 `RBAC_MANAGE` 令牌 `200/OK`、缺权限令牌 `403/ACCESS_DENIED`、会话已撤销 `401/AUTH_SESSION_INVALID`；另有真实栈写路径证据（`DELETE` 受保护对象返回 `409`，见第 11 节）。

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
| 3 | 撤销 `employee` 的唯一角色 | `409/USER_ROLE_REQUIRED` |
| 4 | 删除 `SYSTEM_ADMIN` | `409/RBAC_CONFLICT` |
| 5 | 清理：撤销授权、删除临时角色，并把本机库恢复到与迁移一致的状态 | 迁移版本、权限数、`SYSTEM_ADMIN` 权限数与 `DatabaseMigrationIT` 断言一致 |

链路 4 之外，`DELETE /fd/v1/admin/permissions/15`（删 `RBAC_MANAGE` 本体）与「撤销 `SYSTEM_ADMIN` 的 `RBAC_MANAGE` 授权」是同一族保护规则的不同入口，收口时一并留证。

### 11.1 已留证的链路（2026-09-22，`local` profile + 演示账号）

链路 1、2 与链路 3 的"撤销 `RBAC_MANAGE`"部分依赖 `TASK-058` 的端点，尚未执行。**已经执行并留证的部分**：

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
- 候选下拉只提供**当前列表里已经出现过的**用户（来自 `IamUserRoleBO.userId` / `username`）；它是便利，不是数据源，不能因此让首屏依赖它。
- 角色选择器正常实现，数据源是 `GET /fd/v1/admin/roles`（支持 `keyword` + `PageQuery`）。
- 权限选择器（角色权限授予页）数据源是 `GET /fd/v1/admin/permissions`（`keyword` 同时匹配 `code` 与 `name`），据此实现"权限码搜索"的远程检索。

### 12.2 批量授权语义：一个用户 × 多个角色

`docs/api-design.md` 8.2.1 只有单条 `POST /user-roles` 与 `POST /role-permissions`，**没有批量端点**，所以"批量"只能是前端循环调用单条 `POST`。两个后果必须显式处理：

- **部分失败不可回滚**：循环到第 N 条失败时，前 N−1 条已经提交；
- **每条成功都会撤销会话**（第 7 节决策 3）。

因此批量限定为**一个用户 × 多个角色**：重复授予幂等，且对**同一个**用户反复撤会话没有额外代价，部分失败后重试最便宜。部分失败时给出"已成功 X 个、失败 1 个（原因）"，结束后刷新列表，用户重试即可。**不做"一个角色 × 多个用户"**：部分失败会在只授了一半的情况下踢掉一批人。

### 12.3 `api/` 保持扁平，`errorMessages` 不下沉

`frontend/AGENTS.md` 的分层触发条件已满足（RBAC 是第二个领域模块），确认结论是**不分层**：

- 只新增 `api/rbac.ts`，**不建 `api/core/`**。理由沿用 `frontend/AGENTS.md` 自己的论证：拆分会把"HTTP 层懂认证"这个必须显眼的事实藏起来；`auth.ts` / `rbac.ts` 是领域文件，`http.ts` / `pagination.ts` / `errorMessages.ts` 是基础设施，命名已经区分，拆分只增加 import 改动而信息量为零。
- `errorMessages` 继续用**单一映射表**。按领域拆表会让"某个错误码的文案在哪"取决于你是否知道它属于哪个领域，反而更难找；补完后约 41 行，一张表一眼扫完。

### 12.4 页面开工前必须补的两处既有缺口

不补的话页面会直接露出裸编码或缺失标签：

| 文件 | 缺什么 |
| --- | --- |
| `frontend/src/api/errorMessages.ts` | 6 个 RBAC 错误码：`ROLE_NOT_FOUND`、`PERMISSION_NOT_FOUND`、`GRANT_NOT_FOUND`、`ROLE_CODE_CONFLICT`、`PERMISSION_CODE_CONFLICT`、`RBAC_CONFLICT`——这正是新页面会撞上的全部错误 |
| `frontend/src/constants/authorization.ts` | `permissionLabels` 缺 `RBAC_MANAGE`；并且要按 `RBAC_MANAGE` 在侧栏 `admin` 组新增四组维护页入口 |

## 13. 依据文档

- `docs/api-design.md` 8.1、8.2.1、9、10.2：接口契约、权限映射、错误码。
- `docs/database-design.md` 17.2～17.5：表结构与审计列。
- `docs/implementation-plan.md` 9.1：决策、任务拆分与阶段验收标准。
- `docs/modules/auth.md`：会话、权限快照与撤销的既有实现。
- `PROJECT_STATUS.md`：阶段状态与已知环境问题（Flyway 历史表残留、Redis 残留键、本机 Argon2 耗时）。
