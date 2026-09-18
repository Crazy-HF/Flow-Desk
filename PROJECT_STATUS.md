# FlowDesk 项目状态

> 本文件用于新机器、任务恢复和工作交接时快速定位项目，不替代详细设计文档。

## 快速定位

- 最后更新：2026-09-18
- 远程仓库：`git@github.com:Crazy-HF/Flow-Desk.git`
- 稳定分支：`main`
- 当前基线分支：`main`
- 当前工作分支：`flow-desk/auth-foundation`
- 下一次创建分支：`flow-desk/employee-ticket-flow`（MVP 阶段 1 验收并完成分支交接后创建）
- 当前阶段：求职 MVP 阶段 1 Auth 身份入口
- 已确认的范围调整：项目分为“求职 MVP”和“完整版”；MVP 固定 `EMPLOYEE`、`IT_SUPPORT`、`SYSTEM_ADMIN` 三种内置角色，不实现在线角色、权限及授权关系 CRUD。动态 RBAC 仅为完整版可选项，需另行确认。
- 最新完成：`TASK-010` 切片 1～3 的代码已落地并端到端验证（配置绑定、Argon2id、IAM 认证查询、登录会话 + JWT + Refresh Cookie）；`POST /fd/v1/auth/login` 实测返回 200 + `Set-Cookie` + JWT，错误密码返回 `401 / AUTH_INVALID_CREDENTIALS`，Redis 三个键、TTL 7 天、只存摘要
- 下一步：只实施切片 4——JWT 请求认证过滤器：Bearer 解析 → JWT 验签 → Redis 会话校验 → 角色与权限写入 `SecurityContext`。完成后再进入切片 5、切片 6 和 `TASK-011`
- 当前阻塞：无功能实现阻塞。已确认 19 项单元测试与 4 项迁移集成测试全绿；完整 `verify` 的 JaCoCo 门禁仍按既有记录待处理，不影响下次从切片 4 开始，但必须在阶段交接前解决
- 环境前置：JDK 21、Node.js 24.20.0、pnpm 12.3.4、Docker 29.7.2 已验证；本机已有 `redis:8.8.0`、`mysql:8.4.11` 镜像。本地启动 profile 用 `local` 即可（`spring.profiles.group.local=demo` 已配置）
- 待确认事项：JaCoCo 门禁如何处置（补测试达到原阈值，或另行确认调整门禁）；本项不扩大切片 4 的实现范围

## 已完成里程碑

1. 项目目标、用户、v1 范围、业务流程和权限已经确认。
2. 核心业务概念、关系、生命周期和业务不变量已经确认。
3. 总体技术架构已经确认。
4. 数据库逻辑数据模型和 MySQL 物理模型已经确认。
5. v1 API 契约、权限映射、错误编码、并发和幂等语义已经确认。
6. 工程依赖、配置、迁移、测试、CI、启动、跨存储失败处理和页面/API 映射已经确认。
7. 开发任务拆分已经确认，全部 API 与后台任务均有实施归属。
8. **M0 工程底座已完成并合并**：`TASK-001`～`TASK-003`（骨架、数据基线、公共契约、CI）通过 PR #4 合并进入 `main`。

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

任务名称：求职 MVP 阶段 1——`TASK-010` 切片 4：JWT 请求认证过滤器。

目标：

- 从 `Authorization: Bearer <token>` 解析 Access Token，并完成签名、有效期与 issuer 校验。
- 使用 JWT 中的会话标识查询 Redis；会话有效时将用户标识、角色和权限写入 `SecurityContext`。
- Token 缺失或不可验证返回 `401 / AUTH_REQUIRED`；JWT 有效但会话不存在、过期或撤销返回 `401 / AUTH_SESSION_INVALID`。
- 保持登录等已确认匿名接口可访问，且不破坏切片 1～3 的登录链路。

协作方式：

- 用户负责编写业务代码；除非用户明确授权代写，Codex 只提供小步目标、设计说明、验收标准、代码 review 与测试建议。

本步骤暂不做：

- refresh 轮换、旧令牌重用检测、logout（切片 5）。
- `/auth/me`、本人改密（切片 6）。
- 工单、IAM 管理或 Vue 页面业务流程（`TASK-011` 后续处理）。
- 角色、权限及授权关系的在线 CRUD。
- 不引入已确认技术边界之外的基础设施。
- 密码和 Token 行为必须与已确认的 API 文档一致。

## 当前任务必读

继续 `TASK-010` 切片 4 前，按以下顺序读取：

1. `AGENTS.md`
2. `PROJECT_STATUS.md`
3. `docs/modules/auth.md` 第 8～9 节（安全链规则与切片 4 开工卡）
4. `src/main/java/com/flowdesk/common/config/FoundationSecurityConfiguration.java`
5. `src/main/java/com/flowdesk/auth/security/AuthSecurityConfiguration.java`
6. `src/main/java/com/flowdesk/auth/security/JwtTokenService.java`
7. `src/main/java/com/flowdesk/auth/infrastructure/AuthSessionRepository.java` 与 `RedisAuthSessionRepository.java`
8. `src/main/java/com/flowdesk/auth/domain/AuthClaims.java` 与 `AuthSession.java`
9. `docs/api-design.md`、`docs/technical-architecture.md` 中认证失败和会话校验规则（仅在契约细节不清时查阅）

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
