# Auth 模块实现说明

## 1. 目标与边界

**已确认**：Auth 模块实现 MVP 阶段 1（`TASK-010`）的身份认证、登录会话和当前用户密码安全能力。

它负责：登录、刷新 Access Token、退出当前会话、读取当前身份、修改本人密码，以及 Spring Security 对 Access Token 和 Redis 会话的校验。

它不负责：创建或编辑用户、分配角色、停用账号、管理员重置密码、动态 RBAC 和工单交接。这些属于完整版 IAM 管理能力。Auth 可以读取 IAM 中的用户、角色与权限，但 IAM 不依赖 Auth。

MVP 只识别 `EMPLOYEE`、`IT_SUPPORT`、`SYSTEM_ADMIN` 三种内置角色及其预置权限映射。本模块不得增加角色、权限或授权关系的管理端点。

当前状态：`TASK-010` 切片 1～3 已完成并验证，包括配置与 Argon2id、IAM 认证查询、登录时创建 Redis 会话、签发 JWT 和写入 Refresh Cookie。当前唯一实施目标是切片 4 的 JWT 请求认证过滤器；切片 5、切片 6 和 `TASK-011` 尚未开始。

旧版本只可按需查看设计思路，不作为恢复目标。公共安全基线位于 `common/config/FoundationSecurityConfiguration`；Auth 模块在其上叠加认证过滤器与认证路径放行规则。

## 2. 模块依赖与职责

```text
HTTP 请求
    ↓
AuthController
    ↓
Auth*Service（登录、刷新、退出、当前身份、改密）
    ├── IAM：查询用户、密码摘要、角色和权限
    ├── JWT：签发及校验 HS256 Access Token
    └── Redis：保存会话、Refresh Token 摘要和撤销状态
```

包与职责（以当前工作区为准）：

| 包 | 职责 | 当前状态 |
| --- | --- | --- |
| `auth.config` | `JwtProperties`、`AuthProperties` 类型安全配置 | 切片 1 已完成 |
| `auth.controller` | 暴露 `/fd/v1/auth/**` 接口、校验请求和写入 Cookie | 登录已完成；其余待后续切片 |
| `auth.service` | 编排认证用例，不承载 HTTP 细节 | 登录已完成；其余待后续切片 |
| `auth.domain` | 会话、JWT 声明与登录请求/响应对象 | 切片 1～3 所需部分已完成 |
| `auth.security` | JWT 签发/验证、Cookie 与安全过滤链 | JWT 签发和 Cookie 已完成；请求认证过滤器待切片 4 |
| `auth.infrastructure` | Redis 会话与 Refresh Token 摘要读写 | 登录建会话已完成；轮换与重用检测待切片 5 |

`common` 保留通用响应、异常、时钟和公共配置；`iam` 保留用户、角色、权限及其持久化模型。

会话键空间：

| 键 | 内容 | 有效期 |
| --- | --- | --- |
| `flowdesk:auth:session:{sessionId}` | 用户、登录名、当前摘要、角色与权限快照、创建与过期时间 | 与 Refresh 有效期一致（7 天） |
| `flowdesk:auth:refresh:{digest}` | 会话标识；轮换后改写为 `consumed:{sessionId}` 用于重用检测 | 同上 |
| `flowdesk:auth:user:{userId}` | 该用户的会话标识集合，供撤销全部会话使用 | 同上 |

已确认的实现取舍：会话只保存 Refresh Token 的 SHA-256 摘要；Access Token 的签名内容不含权限，
权限在每个请求中从 Redis 会话快照注入，因此账号停用或角色变化通过撤销会话立即生效；
`/auth/logout` 与 `/auth/refresh` 匿名放行并由精确 `Origin` 白名单加 `SameSite=Strict` 保护，
使重复退出保持幂等成功。

## 3. 用例清单

| 用例 | 接口 | 是否匿名 | 成功结果 |
| --- | --- | --- | --- |
| 登录 | `POST /fd/v1/auth/login` | 是 | 创建会话，返回 Access Token 和最小身份信息，设置 Refresh Cookie |
| 刷新令牌 | `POST /fd/v1/auth/refresh` | Refresh Cookie | 轮换 Refresh Token，返回新 Access Token |
| 退出登录 | `POST /fd/v1/auth/logout` | 当前会话 | 撤销当前会话，清除 Refresh Cookie |
| 当前身份 | `GET /fd/v1/auth/me` | Access Token | 返回用户、角色编码和权限编码 |
| 修改本人密码 | `POST /fd/v1/auth/change-password` | Access Token | 更新密码并撤销该用户全部会话 |

## 4. 登录：第一个实现用例

### 4.1 请求与响应

请求字段：

| 字段 | 规则 |
| --- | --- |
| `username` | 去除首尾空白后非空，最长 64 个字符 |
| `password` | 非空，最长 64 个字符；不得记录原文 |

成功响应的 `data` 包含：`accessToken`、固定值 `tokenType=Bearer`、`expiresIn` 和 `user`（用户标识、登录名、显示名称、角色编码、权限编码）。

Refresh Token 只写入 HttpOnly Cookie，不能出现在 JSON、日志或前端浏览器存储中。

### 4.2 主流程

1. 校验并规范化登录请求。
2. 从 IAM 查询登录名对应的用户及其密码摘要、角色和权限。
3. 使用已有的 Argon2id `PasswordEncoder` 校验密码，同时确认账号启用。
4. 任一校验失败统一返回 `401 / AUTH_INVALID_CREDENTIALS`，不能区分用户不存在、密码不正确或账号停用。
5. 生成会话标识和高强度随机 Refresh Token；Redis 只保存 Refresh Token 的安全摘要、会话状态、用户标识及过期时间。
6. 签发 15 分钟 HS256 Access Token；其中包含用户标识、会话标识与必要的令牌声明。
7. 写入 7 天 Refresh Cookie，并返回 Access Token 与当前身份信息。

### 4.3 登录验收与测试

- 正确账号密码可登录，并且响应不包含密码、密码摘要或 Refresh Token。
- 不存在用户、密码错误和停用账号都得到相同的 `401 / AUTH_INVALID_CREDENTIALS`。
- Access Token 有效期为 15 分钟；Refresh Cookie 为 HttpOnly、`SameSite=Strict`、路径 `/fd/v1/auth`。
- Redis 会话中只保留 Refresh Token 摘要，不保存原始 Refresh Token。
- 日志不记录密码、Token、Cookie 或 JWT 密钥。

## 5. 后续用例的关键规则

### 刷新令牌

- 从 Cookie 读取 Refresh Token，校验精确的 `Origin` 白名单；缺失或不匹配的来源拒绝。
- 依据摘要查询 Redis 会话；令牌无效、过期、撤销或会话不存在时返回 `401`。
- 成功刷新时旧 Refresh Token 立即失效，生成新的 Refresh Token 和 Access Token。
- 已轮换的旧 Refresh Token 被再次使用时，撤销整个会话。

### 退出登录

- 依据当前会话撤销 Redis 会话，并始终清除 Refresh Cookie。
- 同一会话重复退出保持幂等成功，不泄露会话是否存在过。

### 当前身份

- 从 Access Token 取得用户与会话标识。
- 校验 JWT 签名、有效期与 Redis 会话仍有效。
- 返回最小用户信息、角色编码和权限编码；这些信息只用于前端展示，不能替代后端授权。

### 修改本人密码

- 校验当前密码和新密码；新密码通过 Argon2id 哈希后写入 IAM 用户。
- 成功后撤销该用户全部 Redis 会话，使所有旧 Access Token 和 Refresh Token 立即失效。
- Redis 撤销失败时按已确认的保守失败顺序处理，不得在旧会话仍可能有效时返回改密成功。

## 6. 推荐学习顺序

1. **已完成**：配置绑定和 Argon2id 密码组件。
2. **已完成**：登录请求/响应、IAM 认证查询和登录服务。
3. **已完成**：登录创建 Redis 会话、生成 Refresh Token、签发 JWT 并写入 Cookie。
4. **当前步骤**：请求认证过滤器与安全上下文（切片 4）。
5. **后续步骤**：refresh 轮换、重用检测与 logout（切片 5）。
6. **后续步骤**：`/auth/me` 与本人改密（切片 6）；后端阶段验收后再接 Vue 登录外壳（`TASK-011`）。

## 7. 依据文档

- `docs/implementation-plan.md`：MVP/完整版边界、阶段 1、`TASK-010` 和验收标准。
- `docs/api-design.md`：认证接口契约。
- `docs/technical-architecture.md`：JWT、Refresh Token、Redis 会话与安全边界。
- `docs/engineering-readiness.md`：Argon2id 参数、Cookie/Origin 规则、环境变量和测试策略。
- `PROJECT_STATUS.md`：当前前置修复与工作分支状态。

## 8. 安全链与配置（实现记录）

### 8.1 不是微服务 Gateway，而是安全过滤链

FlowDesk v1 是模块化单体，不引入 API Gateway。安全链由两层组成：
`common/config/FoundationSecurityConfiguration` 提供基础链——关闭 CSRF、httpBasic、formLogin 与 logout，会话策略 `STATELESS`，
放行 `/actuator/health` 与 springdoc 路径，未认证请求经 `ApiErrorWriter` 统一返回 `401 / AUTH_REQUIRED` 错误信封。
Auth 模块在此基础上叠加 JWT 解析与 Redis 会话校验，并决定哪些认证路径匿名放行、哪些受保护请求必须带有效的 Bearer Access Token。

实际放行规则：

| 路径 | 放行原因 |
| --- | --- |
| `POST /fd/v1/auth/login` | 用户尚未取得 Access Token |
| `POST /fd/v1/auth/refresh` | 依赖 Refresh Cookie 取得新 Access Token |
| `POST /fd/v1/auth/logout` | 依赖 Refresh Cookie 撤销会话；按已确认契约保持重复退出幂等 |
| `/actuator/health`、本地 OpenAPI | 已有工程诊断与开发入口 |

`GET /fd/v1/auth/me` 与 `POST /fd/v1/auth/change-password` 需要有效 Access Token。受保护请求的执行顺序为：
Bearer 解析 → JWT 验签、有效期与 issuer 校验 → 查询 Redis 会话仍有效 → 把会话中的角色与权限写入 Spring Security 上下文。
401 按原因细分：Token 缺失或无法验证返回 `AUTH_REQUIRED`，Token 有效但会话已过期或被撤销返回 `AUTH_SESSION_INVALID`。

### 8.2 配置键（实现记录）

| 配置键 | 用途 |
| --- | --- |
| `jwt.secret`、`jwt.expiration`、`jwt.issuer` | HS256 密钥（Base64，解码后至少 32 字节）、15 分钟有效期和签发者 |
| `auth.refresh-expiration`、`auth.refresh-token-bytes` | Refresh Token 与会话有效期、随机字节数（不低于 32） |
| `auth.refresh-cookie-name`、`auth.refresh-cookie-path`、`auth.cookie-secure` | Refresh Cookie 名称、路径和 Secure 标志 |
| `auth.allowed-origins` | 依赖 Cookie 的刷新与退出的精确来源白名单，来自 `FLOWDESK_ALLOWED_ORIGINS` |

原 `flowdesk.allowed-origins` 与重复的 `flowdesk.cookie-secure` 已收敛到 `auth.*`，环境变量名不变。

### 8.3 当前测试与验证证据

- 19 项单元测试全绿。
- 4 项迁移集成测试全绿。
- 登录端到端验证：成功请求返回 `200`、Access Token 与 `Set-Cookie`；错误密码返回 `401`。
- Redis 验证：会话、Refresh 摘要索引和用户会话索引三类键均存在，TTL 为 7 天，Refresh Token 只保存摘要。
- 完整 `verify` 的 JaCoCo 门禁状态见 `PROJECT_STATUS.md`，需要在阶段交接前处理。

切片 4 必须补充与请求认证直接相关的自动化验证，至少覆盖：有效认证、无 Token、无效/过期 Token、Redis 会话失效，以及登录接口仍可匿名访问。

## 9. 切片 4 开工卡

新会话的唯一目标是完成 JWT 请求认证过滤器，不同时实现 refresh、logout、`/auth/me`、改密、Vue、工单、用户管理或动态 RBAC。

开工前按顺序执行：

1. 阅读 `AGENTS.md`、`PROJECT_STATUS.md`、本文和 `docs/implementation-plan.md` 第 2～5 节。
2. 检查 `FoundationSecurityConfiguration`、`AuthSecurityConfiguration`、`JwtTokenService`、`AuthSessionRepository`、`RedisAuthSessionRepository`、`AuthClaims` 和 `AuthSession` 的现有契约。
3. 运行 `./mvnw.cmd test`，确认 19 项基线测试通过；若数量已变化，以当次真实输出为准并记录原因。
4. 只实现下列请求认证链路，并补充对应测试：

```text
Bearer 解析
  → JWT 签名、有效期与 issuer 校验
  → 根据 sessionId 查询 Redis 会话
  → 构造 Authentication
  → 写入 SecurityContext
  → 继续过滤链
```

切片 4 验收标准：

- 已确认匿名路径不因过滤器而被阻断。
- 缺失或不可验证的 Access Token 返回 `401 / AUTH_REQUIRED`。
- JWT 有效但 Redis 会话不存在、过期或撤销时返回 `401 / AUTH_SESSION_INVALID`。
- 有效请求的 `SecurityContext` 包含用户标识、角色编码和权限编码，权限编码格式与后续 `hasAuthority(...)` 一致。
- 不把角色或权限重新塞进 JWT；它们继续以 Redis 会话快照为准。

阶段验收前至少运行：

```powershell
./mvnw.cmd test
./mvnw.cmd verify
```

若 `verify` 需要 Docker/Testcontainers，先确认 MySQL 和 Redis 测试容器可用。任何测试结果都以当次真实输出为准，不沿用第 8.3 节的历史证据。

## 10. 模块开发的通用工作方式

后续每个模块都采用“先前置、后用例；一次只完成一个可验收切片”的方式：

1. **定位边界**：先写模块说明，明确它负责什么、不负责什么、依赖谁，以及已确认的 API 和规则来源。
2. **列出前置条件**：把现有代码、配置、数据、依赖库、安全规则和测试环境逐项核对；缺一项先补基础，
   不在 Controller 内临时绕过。
3. **确定最小可验收用例**：选择一个接口，例如“登录”，写清输入、主流程、成功结果、异常和安全约束。
4. **由内向外实现**：先领域/存储与服务测试，再接 Controller 和安全链；页面在后端 API 稳定后接入。
5. **每一步只引入必要代码**：不为未来用例提前堆大量空类；新类必须能说明当前用例需要它的原因。
6. **立即验证并记录**：完成一个小步就运行对应单元、Web 或集成测试，将结果和已知限制写回模块说明或状态文件。
7. **到确认点暂停**：一个用例通过验收后，再确认是否进入下一个用例；遇到技术选型、跨存储一致性或范围变化时，先集中确认。

这个方式的目的不是放慢开发，而是让每一段代码都有明确前提、职责和验收证据；你也能解释为什么这样设计，而不只是把代码拼起来。
