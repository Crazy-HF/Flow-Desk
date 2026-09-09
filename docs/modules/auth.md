# Auth 模块实现说明

## 1. 目标与边界

**已确认**：Auth 模块实现 M1（`TASK-010`）的身份认证、登录会话和当前用户密码安全能力。

它负责：登录、刷新 Access Token、退出当前会话、读取当前身份、修改本人密码，以及 Spring Security 对 Access Token 和 Redis 会话的校验。

它不负责：创建或编辑用户、分配角色、停用账号、管理员重置密码和工单交接。这些属于 IAM 管理能力（`TASK-051`）。Auth 可以读取 IAM 中的用户、角色与权限，但 IAM 不依赖 Auth。

当前前置条件：现有 IAM 用户管理切片仍需先完成权限、排序、角色和测试回归修复；完成后再开始 Auth 的业务实现。

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

建议按当前项目的包约定逐步建设：

| 包 | 职责 | 当前状态 |
| --- | --- | --- |
| `auth.controller` | 暴露 `/fd/v1/auth/**` 接口、校验请求和写入 Cookie | Access Token 登录接口已接通；Refresh Cookie 待实现 |
| `auth.service` | 编排认证用例，不承载 HTTP 细节 | 登录查询、密码校验和 Access Token 响应已实现 |
| `auth.domain.vo` | 各接口请求参数 | 登录请求已实现 |
| `auth.domain.bo` | 服务内部或响应所需的身份、令牌数据 | 登录响应与最小身份信息已实现 |
| `auth.security` | JWT 签发/验证、Bearer 身份解析、当前用户上下文 | HS256 签发/校验和 Bearer 转换已实现；Redis 会话校验待实现 |
| `auth.infrastructure` | Redis 会话和 Refresh Token 摘要的读写 | 待实现 |

`shared` 保留通用响应、异常、时钟和配置；`iam` 保留用户、角色、权限及其持久化模型。

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

1. 先修复并验证 IAM 前置问题。
2. 只完成登录的请求/响应对象和 `AuthLoginService` 的单元测试，再写登录业务。
3. 加入 Redis 会话和 Refresh Token，再用集成测试验证轮换、过期与重用检测。
4. 接入 JWT 和 Spring Security，完成 `/auth/me`。
5. 完成退出与修改本人密码，最后接入 Vue 登录外壳（`TASK-011`）。

## 7. 依据文档

- `docs/implementation-plan.md`：M1、`TASK-010` 和验收标准。
- `docs/api-design.md`：认证接口契约。
- `docs/technical-architecture.md`：JWT、Refresh Token、Redis 会话与安全边界。
- `docs/engineering-readiness.md`：Argon2id 参数、Cookie/Origin 规则、环境变量和测试策略。
- `PROJECT_STATUS.md`：当前前置修复与工作分支状态。

## 8. 实现登录校验前必须具备的条件

### 8.1 不是微服务 Gateway，而是安全过滤链

FlowDesk v1 是模块化单体，不引入 API Gateway。这里需要搭建的是 Spring Security 的
`SecurityFilterChain`：它在请求进入 Controller 前决定哪些路径匿名放行、哪些路径必须带
有效的 Bearer Access Token。

当前 `FoundationSecurityConfiguration` 只放行健康检查和 OpenAPI，其余所有请求均要求已认证，
因此当前的 `POST /fd/v1/auth/login` 骨架也会被拦截为 `401`。实现登录前必须将下列匿名入口
明确加入放行规则：

| 路径 | 放行原因 |
| --- | --- |
| `POST /fd/v1/auth/login` | 用户尚未取得 Access Token |
| `POST /fd/v1/auth/refresh` | 依赖 Refresh Cookie 取得新 Access Token |
| `/actuator/health`、本地 OpenAPI | 已有工程诊断与开发入口 |

`logout`、`me`、`change-password` 不能匿名放行；它们分别需要当前会话或 Access Token。除放行规则外，
安全链还要在受保护请求中执行 Bearer Token 解析、JWT 验签、过期校验、Redis 会话校验，并把用户角色与权限写入 Spring Security 上下文。

### 8.2 已有基础与缺口

| 能力 | 当前事实 | 登录实现前需要补充 |
| --- | --- | --- |
| 密码 | 已有 Spring Security `PasswordEncoder`，参数为 Argon2id 最低标准 | 复用 `matches`，不得自行实现哈希 |
| Redis 客户端 | 已引入 `spring-boot-starter-data-redis`，Compose 已提供 Redis | 定义会话 Key、值结构、TTL 和摘要计算方式 |
| JWT 密钥 | `flowdesk.jwt-secret` 已由环境变量提供 | 增加 HS256 JWT 库、Token 签发与验证服务 |
| Spring Security | 已有 `SecurityFilterChain` 和统一 401/403 响应 | 配置匿名路径、Bearer 认证和 Cookie 来源防护 |
| IAM 数据 | 已有用户、角色、权限及关联表 | 提供一次读取登录身份、密码摘要、角色、权限的查询能力 |
| 测试 | 已有 Security Test、MySQL Testcontainers | 加入 Redis Testcontainers，并建立认证 Web/集成测试 |

**已确认的技术选择**：加入 `spring-security-oauth2-jose`，使用 Spring Security 集成的 Nimbus
实现 HS256 JWT 的签发和校验。它比自行拼接 JWT、或额外引入独立 JWT 框架更容易与
`SecurityFilterChain` 对接。

### 8.3 认证基础设施应按以下顺序建设

1. **先恢复 IAM 正确性和测试**：这是当前阶段硬前置。认证查询依赖用户状态、密码摘要、角色和权限，
   不能建立在仍有权限、角色和测试问题的 IAM 切片上。
2. **确定 Token 技术并补齐配置对象**：确认 JWT 库；将 JWT 有效期、Refresh 有效期、Cookie Secure、
   允许 Origin 等配置集中为类型安全的配置对象。密钥仍只从环境变量读取，绝不提交。
3. **建立 IAM 认证查询**：使用显式 Mapper SQL 一次读取用户、角色和权限；Auth 调用 IAM 暴露的查询能力，
   不在 Auth 中散落对多张 IAM 表的查询。查询结果仅在服务内使用，不能直接作为接口响应。
4. **建立 Redis 会话仓储**：使用 `StringRedisTemplate` 或等价的 Spring Redis 抽象；生成高强度随机 Refresh Token，
   只保存其摘要、会话 ID、用户 ID、状态、创建与失效时间，并让 Redis TTL 与 Refresh 有效期一致。
5. **建立 Token 服务**：只负责 HS256 Access Token 的签发和验证。Access Token 至少含用户 ID、会话 ID、
   签发/过期时间；不把密码、Refresh Token 或完整权限列表写入 Token。
6. **替换基础安全链**：配置登录与刷新匿名放行；为其他 API 加入 Bearer 认证；验签成功后仍查询 Redis 确认会话有效。
   对使用 Cookie 的刷新、退出和改密接口检查精确 `Origin` 白名单，并按已确认 Cookie 策略实现 CSRF 防护。
7. **最后实现登录用例**：此时再补 `LoginRequestVO`、登录响应、Controller 参数校验和 `AuthLoginService` 的业务编排。
   当前 Controller 骨架在上述工作完成前不应作为可用登录接口测试。
8. **同步补测试再进入下一个用例**：先通过密码、登录失败和登录成功测试，再做刷新轮换；不能先把全部认证接口堆完再测试。

## 9. 模块开发的通用工作方式

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
