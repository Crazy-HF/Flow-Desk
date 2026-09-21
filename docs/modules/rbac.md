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

**不做**（越界即停）：

- 不扩展 `docs/api-design.md` 8.2.1 之外的权限模型：不做角色继承、不做数据级/组织级权限、不做权限与接口的自动映射。
- 不做管理端页面（`frontend/src/views/admin/` 留给后续主题）。
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
- 控制器放在 `iam/controller`，与 `auth/controller` 平级；异常仍走 `common/exception/GlobalExceptionHandler`。

## 3. 类清单（最终形态）

| 层 | 类 | 职责 |
| --- | --- | --- |
| controller | `IamRoleController`、`IamPermissionController`、`IamUserRoleController`、`IamRolePermissionController` | 路由、参数绑定、`@PreAuthorize("hasAuthority('RBAC_MANAGE')")`、HTTP 状态（创建用 `201`） |
| service | `IamRoleService`、`IamPermissionService`、`IamUserRoleService`、`IamRolePermissionService`（+ `impl`） | 业务规则、保护规则、事务、编排会话撤销 |
| vo（请求） | `IamRoleCreateVO`、`IamRoleUpdateVO`、`IamRoleQueryVO`、`IamPermissionCreateVO`、`IamPermissionUpdateVO`、`IamPermissionQueryVO`、`IamUserRoleGrantVO`、`IamUserRoleQueryVO`、`IamRolePermissionGrantVO`、`IamRolePermissionQueryVO` | 字段校验（`jakarta.validation`） |
| bo（响应/服务间） | `IamRoleBO`、`IamPermissionBO`、`IamUserRoleBO`、`IamRolePermissionBO` | 对外字段；**不含**任何密码或内部存储细节 |
| 端口 | `SessionRevocationPort`（iam）+ `IamSessionRevocationAdapter`（auth） | 撤销某用户全部会话 |

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
| `POST /user-roles` | `IamUserRoleGrantVO`：`userId`、`roleId` | `200 R<Void>`（首次与重复都是 `200`） | `400`、`404 USER_NOT_FOUND`、`404 ROLE_NOT_FOUND` |
| `DELETE /user-roles/{userId}/{roleId}` | 路径参数 | `200 R<Void>` | `404 GRANT_NOT_FOUND`、`409 USER_ROLE_REQUIRED`、`409 LAST_ADMIN_PROTECTED` |

### 4.4 角色权限授权

| 方法与路径 | 请求 | 成功响应 | 失败 |
| --- | --- | --- | --- |
| `GET /role-permissions` | `roleId` 或 `permissionId` **至少一个** + `PageQuery` | `200 R<PageResult<IamRolePermissionBO>>` | `400 VALIDATION_FAILED` |
| `POST /role-permissions` | `IamRolePermissionGrantVO`：`roleId`、`permissionId` | `200 R<Void>` | `400`、`404 ROLE_NOT_FOUND`、`404 PERMISSION_NOT_FOUND` |
| `DELETE /role-permissions/{roleId}/{permissionId}` | 路径参数 | `200 R<Void>` | `404 GRANT_NOT_FOUND`、`409 RBAC_CONFLICT` |

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
| `409` | `RBAC_CONFLICT` | 违反保护或引用约束（见第 6 节） |
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

- 单个用例内的多表写入在同一个 `@Transactional` 方法里完成（例如删除角色 = 检查引用 + 删除角色 + 删除其角色权限行）。
- **会话撤销与 MySQL 提交的顺序（决策 3）：先撤销 Redis 会话，再让事务提交**。与改密的既有顺序一致，理由：若顺序相反而 Redis 失败，会出现"权限已变更但旧会话仍然有效"的窗口；反过来最坏结果是"用户被登出但变更失败"，用户重登即可。
- 触发撤销的三处：

  | 操作 | 撤销范围 |
  | --- | --- |
  | 授予用户角色 | 该用户的全部会话 |
  | 撤销用户角色 | 该用户的全部会话 |
  | 授予/撤销角色权限 | **拥有该角色的全部用户的会话**（见下方说明） |

- **关于角色权限变更也撤会话**：`docs/api-design.md` 8.1 已确认"用户、角色或账号状态变化成功后，撤销受影响用户的现有登录会话"；会话快照缓存的是**权限码**（`JwtAuthenticationFilter` 从 Redis 快照读权限），所以角色权限变化后不撤销会话，用户的旧权限最长会保留到会话过期（7 天）。撤销某个权限却仍能继续使用，属于安全缺口，因此这里按 8.1 的通用规则执行，8.2.1 只是没有重复写明。**代价**：该角色用户越多，Redis 调用越多（v1 单实例，先不做批量优化）。
- 幂等：重复授予直接返回成功且**不写库、不撤销会话**（没有发生变化），避免把幂等调用变成"把用户踢下线"。

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

- **落库的授权审计**：`iam_user_role` 沿用建表已有的 `granted_by` / `granted_at`；`iam_role_permission` 由 `V5` 补同名列。新授权必须同时写入两列，`granted_by` = 当前操作人的 `userId`。
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
- 会话撤销：断言"授予/撤销时调用了撤销端口"，以及在需要时**先撤销后写库**（用 `InOrder`，与 `AuthWebTest` 对改密的既有断言方式一致）。
- 并发：现有仓库没有真并发用例。本阶段**不做**真并发，文档明确写"以 mock 返回 0 / 唯一键约束模拟"，不表述为"已覆盖"；真并发留给后续（需要在 MySQL IT 里跑多线程）。

## 11. 验收命令与手工验证

```powershell
# 单元/Web + 集成（Testcontainers 需要 Docker）
$env:JAVA_HOME='D:\Idea\Jdk\Jdk21'; .\mvnw.cmd -B verify

# 真实栈
docker compose up -d mysql redis
$env:JAVA_HOME='D:\Idea\Jdk\Jdk21'; .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

手工链路（每条留 `traceId` 与响应码）：

1. `admin` 登录 → 创建角色 `TEMP_AUDITOR` → 给 `employee` 授予该角色 → 用 `employee` 的旧 Access Token 调 `/fd/v1/auth/me`，期望 `401/AUTH_SESSION_INVALID`。
2. 撤销 `employee` 的唯一角色 → 期望 `409/USER_ROLE_REQUIRED`。
3. 删除 `SYSTEM_ADMIN` → 期望 `409/RBAC_CONFLICT`；撤销其 `RBAC_MANAGE` → 期望 `409/RBAC_CONFLICT`。
4. 清理：撤销授权、删除 `TEMP_AUDITOR`，并把本机库恢复到与迁移一致的状态。

## 12. 依据文档

- `docs/api-design.md` 8.1、8.2.1、9、10.2：接口契约、权限映射、错误码。
- `docs/database-design.md` 17.2～17.5：表结构与审计列。
- `docs/implementation-plan.md` 9.1：决策、任务拆分与阶段验收标准。
- `docs/modules/auth.md`：会话、权限快照与撤销的既有实现。
- `PROJECT_STATUS.md`：阶段状态与已知环境问题（Flyway 历史表残留、Redis 残留键、本机 Argon2 耗时）。
