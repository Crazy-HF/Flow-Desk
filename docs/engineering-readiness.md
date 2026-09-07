# FlowDesk 工程准备检查

## 1. 阶段目标与边界

本文把已经确认的架构、数据库和 API 设计转换为可执行的工程基线，使后续工程初始化、开发、测试和 CI 不需要临时发明环境与一致性规则。

当前状态：工程准备方案已形成，尚未创建 Spring Boot、Vue、Docker Compose 或数据库迁移文件，也未安装项目依赖。

本阶段只确定：

- 开发环境、构建工具和核心依赖版本。
- 配置、密钥、Profile 和日志边界。
- Flyway 迁移、RBAC 基线数据和 demo 数据策略。
- 测试分层、命令、覆盖率与 CI 门禁。
- MySQL、Redis 和附件文件系统之间的失败顺序与验证要求。
- 本地启动顺序，以及最小 Vue 页面与 API 的对应关系。

本阶段不执行：

- 不创建后端或前端工程骨架。
- 不编写业务代码、迁移 SQL、Compose 文件或 CI Workflow。
- 不安装或升级本机依赖。
- 不扩大 v1 基础设施范围。

## 2. 已确认的工程决策

| 决策 | 结论 | 主要理由与边界 |
| --- | --- | --- |
| 后端构建 | Maven Wrapper | CI 和开发机统一使用仓库内 Wrapper，不依赖全局 Maven 版本 |
| 持久层 | MyBatis-Plus + 自定义 SQL/XML | 通用 Mapper 处理简单 CRUD，工单权限、队列、统计和历史查询保留显式 SQL |
| 本地依赖 | Docker Compose 只运行 MySQL 和 Redis | 前后端在宿主机运行，便于断点调试；不把应用容器化纳入 v1 开发环境 |
| 密码存储 | Argon2id 单向哈希 | 不可解密；采用 OWASP 最低参数并在目标机器验证耗时 |
| 前端组件库 | Element Plus | 覆盖表格、表单、分页、对话框和管理页需求，不自建组件体系 |
| 数据库迁移 | Flyway | 数据库结构和固定 RBAC 数据版本化，禁止框架自动建表 |
| API 文档 | springdoc-openapi，代码优先 | 由 Controller、DTO 和注解生成 OpenAPI，并用契约测试防止偏离已确认 API |

MyBatis-Plus 只用于减少无业务含义的重复 CRUD。不得同时引入 `mybatis-spring-boot-starter`，不得使用 ActiveRecord，也不得让通用 Service 取代应用服务。涉及工单可见范围、乐观锁条件、状态迁移、管理性交接、时间线和数据概览的查询必须使用可审查的 Mapper SQL/XML。

## 3. 版本基线

### 3.1 版本管理原则

- Java 和 Node.js 固定 LTS 主版本；补丁版本允许在同一主版本内升级，但必须先通过完整 CI。
- Maven Wrapper、后端非 BOM 依赖、前端直接依赖和 Docker 镜像使用确定版本，不使用 `latest`。
- Spring Boot BOM 已管理的库不重复声明版本；不在 BOM 中的依赖必须显式声明。
- `pom.xml`、`package.json`、`pnpm-lock.yaml` 和 Compose 镜像标签是实际构建依据，本文是工程基线与升级入口。
- 版本升级作为单独变更处理，记录兼容性检查和测试结果，不在业务功能提交中顺带升级。

### 3.2 后端与基础设施

| 项目 | 固定基线 | 版本来源与使用方式 |
| --- | --- | --- |
| Java | 21 LTS；当前机器 `21.0.12` | Maven 编译目标为 21；CI 使用 JDK 21 |
| Spring Boot | `3.5.16` | 作为 Maven Parent/BOM；Java 21 在其支持范围内 |
| Maven Wrapper | `3.9.16` | 工程初始化时生成并提交 `mvnw`、`mvnw.cmd` 和 Wrapper 配置 |
| MyBatis-Plus | `3.5.17` | 显式引入 `mybatis-plus-spring-boot3-starter`；不引入 Boot 2/4 Starter |
| Flyway | `11.7.2` | `flyway-core` 与独立的 `flyway-mysql`，版本由 Spring Boot BOM 管理 |
| MySQL JDBC | `9.7.0` | `mysql-connector-j` 版本由 Spring Boot BOM 管理 |
| MySQL Server | `8.4.11` | Compose 使用确定镜像标签；InnoDB、`utf8mb4`、UTC |
| Redis Server | `8.8.0` | Compose 使用确定镜像标签；只存登录会话与撤销状态 |
| Spring Security | `6.5.11` | 由 Spring Boot BOM 管理 |
| springdoc-openapi | `2.8.17` | 显式引入 WebMVC UI Starter；Spring Boot 3.5.x 使用 2.8.x |
| Testcontainers | `1.21.4` | 版本由 Spring Boot BOM 管理；使用 JUnit Jupiter、MySQL 和 Redis 容器 |

Spring Boot `3.5.16` 是 3.5.x 最后一个开源维护版本。v1 为保持已经确认的 Spring Boot 3 基线暂时固定该版本；完成 v1 后单独评估升级，不在工程初始化时切换到 Spring Boot 4。

Argon2id 通过 Spring Security `PasswordEncoder` 使用。最低参数为内存 `19 MiB`、迭代 `2`、并行度 `1`；盐长度采用 16 字节、哈希长度采用 32 字节。实现所需 Bouncy Castle 版本跟随 Spring Boot BOM。保存值带算法标识，支持未来通过 `DelegatingPasswordEncoder` 平滑升级；禁止自行实现哈希或保存可逆密码。

### 3.3 前端

| 项目 | 固定基线 | 说明 |
| --- | --- | --- |
| Node.js | `24.20.0` LTS；最低 `24.12.0` | 当前机器 `24.4.0` 不满足最低版本，初始化前升级 |
| pnpm | `12.3.4` | 通过 Corepack 与 `packageManager` 字段固定；当前机器 `11.19.0` 需切换 |
| Vue | `3.5.42` | Composition API + `<script setup lang="ts">` |
| Vue Router | `5.3.1` | 路由鉴权只控制用户体验，不能代替后端授权 |
| Pinia | `4.0.3` | 只保存当前用户、Access Token 和必要会话状态 |
| Axios | `1.20.0` | 统一 API Client、Authorization 注入、401 刷新协调和错误转换 |
| Element Plus | `2.14.5` | 按需引入，页面不复制组件库能力 |
| Vite | `8.2.2` | 开发服务器代理 `/fd` 到后端 |
| TypeScript | `6.0.3` | 开启严格检查；处于 typescript-eslint 8.69.0 声明支持的 `<6.1.0` 范围内 |
| Vitest | `5.0.0` | 前端单元与组件测试 |
| Vue Test Utils | `2.5.0` | Vue 组件交互测试 |
| Playwright | `1.63.0` | 核心流程端到端测试，CI 固定浏览器版本 |
| Vite Vue Plugin | `6.0.8` | Vue 单文件组件编译支持 |
| vue-tsc | `3.3.11` | `.vue` 与 TypeScript 联合类型检查 |
| ESLint | `10.10.0` | 使用 Flat Config |
| ESLint JS Config | `10.0.1` | `@eslint/js` 的正式版本，与 ESLint 10 配合使用 |
| eslint-plugin-vue | `10.10.0` | Vue 官方 ESLint 规则 |
| Vue TypeScript ESLint Config | `14.9.0` | `@vue/eslint-config-typescript`，统一 Vue/TS 规则 |
| jsdom | `30.0.1` | Vitest 组件 DOM 环境 |
| Vitest V8 Coverage | `5.0.0` | 与 Vitest 保持同版本 |

以上版本是工程初始化的锁定组合。初始化验证发现 TypeScript 7.0.2 超出 typescript-eslint 8.69.0 声明的 `<6.1.0` 支持范围，因此按本节预先约定将 TypeScript 调整为 6.0.3；没有使用 `--force` 或忽略 peer dependency 错误。初始化任务仍必须实际执行 `pnpm typecheck`、`pnpm lint`、`pnpm test:unit --run` 和 `pnpm build` 验证组合兼容。

### 3.4 当前机器检查结果

核验日期：2026-09-07。

| 工具 | 当前结果 | 结论 |
| --- | --- | --- |
| Java | `21.0.12` | 满足 |
| Node.js | `24.20.0`（`D:\pnpm\bin\node.exe`） | 满足；Codex 命令显式优先使用安装目录，避免旧进程 PATH 缓存 |
| pnpm | `12.3.4`（`D:\pnpm\pnpm.cmd`） | 满足；`packageManager` 与 lockfile 已固定 |
| Docker | `29.7.2` | 满足本地容器运行需要 |
| Docker Compose | `v5.4.0` | 满足 |
| Git | `2.55.0.windows.3` | 满足 |

## 4. 目标仓库结构与构建入口

后续工程初始化采用以下最小结构：

```text
flow-Desk/
|- pom.xml
|- mvnw
|- mvnw.cmd
|- .mvn/
|- src/                         # Spring Boot 模块化单体
|- frontend/                    # Vue 3 SPA
|- compose.yaml                 # 仅 MySQL、Redis
|- .env.example                 # 变量名和非敏感示例
|- docs/
+- .github/workflows/
```

后端保持单 Maven 应用，不提前拆成多个 Maven Module。业务边界通过 Java 包和依赖方向表达；只有实际出现独立构建需求时才讨论多模块。

## 5. 配置、Profile 与密钥

### 5.1 配置分层

| 文件/Profile | 允许内容 | 禁止内容 |
| --- | --- | --- |
| `application.yml` | 公共非敏感默认值、配置键结构、日志格式 | 密码、Token、真实密钥、机器绝对路径 |
| `application-local.yml` | localhost 端口、开发日志、Swagger 开关 | 提交真实本地密码 |
| `application-test.yml` | 测试超时、测试文件目录、容器覆盖入口 | 依赖开发机 MySQL/Redis |
| `application-demo.yml` | demo 数据位置、演示日志和功能开关 | 加载生产数据或生产密钥 |
| `application-prod.yml` | 安全默认值、较低日志级别、Swagger 关闭 | 任何可工作的默认凭据 |
| 环境变量 | 数据库、Redis、JWT、Cookie、附件根目录等部署值 | 在日志或错误响应中回显 |

`.env.example` 只列变量名、格式和无敏感占位符。真实 `.env` 必须加入 `.gitignore`；Compose 可以读取根目录 `.env`，但宿主机运行的 Spring Boot 不会自动把 `.env` 当作应用配置，开发者必须通过 IDE、PowerShell 或进程管理器注入相同环境变量。

### 5.2 环境变量基线

| 变量 | 用途 | 要求 |
| --- | --- | --- |
| `FLOWDESK_DB_URL` | JDBC URL | 明确时区、字符集和目标库 |
| `FLOWDESK_DB_USERNAME` | 数据库账号 | prod 使用最小权限应用账号 |
| `FLOWDESK_DB_PASSWORD` | 数据库密码 | 必填，不提供 prod 默认值 |
| `FLOWDESK_REDIS_HOST`、`FLOWDESK_REDIS_PORT` | Redis 地址 | local 指向 Compose 暴露端口 |
| `FLOWDESK_REDIS_PASSWORD` | Redis 密码 | local/demo/prod 均不得提交真实值 |
| `FLOWDESK_JWT_SECRET` | HS256 签名密钥 | Base64 编码的至少 256 位随机值 |
| `FLOWDESK_ALLOWED_ORIGINS` | 浏览器来源白名单 | 精确来源，不使用 `*` 搭配凭据 |
| `FLOWDESK_ATTACHMENT_ROOT` | 附件持久目录 | Web 根目录之外、绝对路径、可写且持久化 |
| `FLOWDESK_COOKIE_SECURE` | Refresh Cookie Secure 标志 | prod/demo HTTPS 必须为 `true`；local HTTP 可为 `false` |

JWT Access Token 使用 HS256，有效期 15 分钟；Refresh Token 使用密码学安全随机值，有效期 7 天，只通过 HttpOnly Cookie 传递，Redis 只保存摘要和会话状态。Refresh Cookie 限定 `/fd/v1/auth` 路径并使用 `SameSite=Strict`；refresh/logout 等依赖 Cookie 的状态变更接口必须校验 `Origin` 是否在精确白名单内，拒绝缺失或非预期浏览器来源。Access Token 只保存在前端内存中，不写入 `localStorage`、`sessionStorage` 或普通 Cookie。

### 5.3 日志与错误边界

- 所有请求生成或透传 `traceId`，响应和日志使用同一标识。
- 允许记录：请求方法、规范化路径、HTTP 状态、耗时、操作者 ID、工单编号、动作编码和结果编码。
- 禁止记录：密码及哈希、Authorization、Access/Refresh Token、Cookie、JWT 密钥、数据库/Redis 密码、请求正文中的敏感内容、附件内容和真实磁盘路径。
- 登录失败统一记录安全事件和结果编码，不记录输入密码，也不向客户端区分用户不存在与密码错误。
- prod 不返回异常堆栈、SQL、类名或内部主键；详细异常只进入受控服务端日志。

## 6. Flyway 迁移与种子数据

### 6.1 目录与职责

```text
src/main/resources/
|- db/migration/
|  |- V1__create_schema.sql
|  +- V2__seed_rbac.sql
+- db/demo/
   +- R__seed_demo_data.sql
```

- `V1__create_schema.sql` 创建 `docs/database-design.md` 中确认的表、约束、外键和索引。
- `V2__seed_rbac.sql` 只写入三个预置角色、权限及其映射；编码必须与后端枚举和 API 权限一致。
- `R__seed_demo_data.sql` 只在 `demo` Profile 增加的 Flyway location 中启用，必须可重复执行且不覆盖真实数据。
- `local` 是否加载 demo 数据由启动时显式选择 `demo` Profile 决定；普通 `local` 不偷偷加载样例数据。
- `prod` 只扫描 `db/migration`，绝不扫描 `db/demo`。

### 6.2 迁移规则

- 禁止 MyBatis-Plus 或其他 ORM 自动创建、更新表结构。
- 已在共享环境执行的版本化迁移不可修改；变化通过下一个 `Vn__description.sql` 追加。
- 禁止启用 `baselineOnMigrate` 掩盖非空未知数据库；目标是从空库完整执行。
- 非开发环境禁用 Flyway `clean`。
- 应用启动先执行 `validate` 和 `migrate`；校验或迁移失败时应用不得对外提供服务。
- DDL/DML 必须兼容 MySQL 8.4.11，时间按 UTC 写入，字符集和排序规则与数据库设计一致。
- 迁移应避免依赖本机路径、系统时区或不稳定的当前时间。

### 6.3 数据验证

CI 至少验证：

1. 新建空 MySQL 8.4.11 容器。
2. 从零运行全部公共迁移。
3. 校验 Flyway schema history、表、外键、唯一约束和关键索引存在。
4. 校验 V2 的角色、权限和映射编码完整且无重复。
5. 在启用 demo location 的独立数据库运行两次 demo 迁移，证明其可重复且不会重复造数。

公共迁移不创建固定用户名或默认管理员密码。demo 用户只能存在于 demo location，使用明确标注的演示凭据和 Argon2id 哈希。生产环境首个管理员的安全引导方式必须在生产部署任务中补充并单独验收；不得把通用默认管理员写入 V2。

## 7. 跨存储失败处理

MySQL 是业务事实权威来源。Redis 和文件系统不参与 MySQL 事务，因此必须明确调用顺序、允许的保守失败和对账机制。

### 7.1 账号或角色变更后的会话撤销

适用场景包括停用账号、移除角色、管理员重置密码和当前用户修改密码。

```text
开始 MySQL 事务
  -> 校验用户版本、最后管理员和活动工单交接
  -> 修改用户/角色/密码及全部工单交接
  -> 在事务提交前撤销 Redis 中该用户的全部会话
     -> Redis 失败或结果不确定：抛出异常，回滚 MySQL
     -> Redis 成功：提交 MySQL
  -> 提交成功后返回成功
```

该顺序选择安全优先：

- Redis 明确失败时，MySQL 回滚，不能出现账号已停用但旧会话仍有效。
- Redis 已撤销但响应丢失时，MySQL 可能回滚，用户会被提前登出，但不会获得越权访问；客户端可重新登录并重试管理操作。
- Redis 成功后 MySQL 提交失败时，同样可能只留下会话失效，属于可接受的保守失败，不得尝试恢复旧 Refresh Token。
- API 只有在 Redis 成功且 MySQL 提交成功后才返回成功；日志记录 `traceId`、目标用户和阶段，不记录 Token。

必须覆盖的测试：

| 场景 | 期望结果 |
| --- | --- |
| 用户或工单版本冲突 | MySQL 不变，不调用 Redis 撤销 |
| Redis 连接失败/超时 | MySQL 全部回滚，接口失败 |
| Redis 已删除但客户端收到超时 | MySQL 可回滚，会话保持失效，重试安全 |
| Redis 成功、MySQL 提交失败 | 业务变更不生效，会话失效，记录可诊断错误 |
| 多张工单交接中任一冲突 | 账号、角色和全部交接均回滚，不撤销会话 |
| 正常成功 | MySQL 变更、交接记录和 Redis 撤销全部可观察 |

测试使用 Testcontainers Redis，并通过可控代理或测试替身注入超时、断连和异常；只用 Mock 验证调用次数不足以证明真实失败行为。

### 7.2 附件写入与数据库事务

暂存目录和最终目录必须位于同一文件系统/卷，启动时验证目录可写且支持原子移动。每个文件使用服务端随机存储名，客户端文件名只能作为元数据。

```text
流式写入受控暂存文件
  -> 校验数量、大小、扩展名、声明类型和服务端识别类型
  -> 开始 MySQL 事务
  -> 写工单/记录/附件元数据（尚未提交）
  -> 将全部暂存文件逐个原子移动到最终目录
     -> 任一移动失败：回滚 MySQL，删除暂存和已移动文件
     -> 全部移动成功：提交 MySQL
  -> 提交失败：删除已移动文件；删除失败交给对账任务
  -> 提交成功：返回成功
```

多文件操作只有单文件移动是原子的，整组文件不是一个文件系统事务。因此必须维护本次请求已移动文件清单，以便中途失败时逆向清理。进程在移动后、提交前崩溃可能留下没有数据库记录的最终文件，对账任务负责清除这类孤儿。

对账与清理规则：

- 定时清理超过约定保留时间且没有活动请求引用的暂存文件。
- 扫描最终目录时，以数据库 `storage_key` 为权威；不存在对应已提交附件记录的文件进入隔离/删除流程。
- 数据库存在元数据但文件缺失属于完整性事故：记录告警，不自动删除元数据或伪造成功文件。
- 清理任务必须防止路径穿越，只处理解析后仍位于配置根目录内的随机存储名。
- v1 单实例运行，上传和对账不得并发处理同一个暂存标识。

必须覆盖：校验失败、暂存写失败、第一/中间/最后一个移动失败、原子移动不支持、MySQL 回滚、提交失败、清理失败、进程中断后孤儿对账、数据库记录缺文件，以及成功写入后带权限下载。

## 8. 测试策略与命令

### 8.1 后端测试分层

| 层次 | 工具 | 必测内容 |
| --- | --- | --- |
| 单元测试 | JUnit 5、AssertJ | 状态机、权限规则、截止时间、Argon2 参数封装、文件名与路径校验 |
| Web 切片测试 | MockMvc、Spring Security Test | 认证、403/404 隐藏策略、输入校验、错误编码、Cookie 与来源校验 |
| 集成测试 | Spring Boot Test、Testcontainers | Flyway、MyBatis-Plus Mapper、自定义 SQL、MySQL 事务、Redis 会话 |
| 并发测试 | JUnit 5 + 真实 MySQL | 领取、转交、确认、超时和管理性交接只有一个更新成功 |
| 故障测试 | Testcontainers + 故障注入 | Redis 撤销失败、附件移动/提交失败和对账恢复 |

命名和 Maven 生命周期：

- `*Test` 由 Surefire 在 `test` 阶段运行。
- `*IT` 由 Failsafe 在 `integration-test`/`verify` 阶段运行。
- `./mvnw verify` 是本地提交前和 CI 的唯一完整后端入口。
- JaCoCo 在 `verify` 检查后端整体行覆盖率至少 70%、分支覆盖率至少 60%。不得通过排除业务包或空洞测试满足数字。

### 8.2 前端测试分层

| 层次 | 工具 | 必测内容 |
| --- | --- | --- |
| 单元测试 | Vitest | API 错误转换、权限判断、状态格式化、刷新协调 |
| 组件测试 | Vue Test Utils + Vitest | 表单校验、列表筛选、动作按钮和冲突刷新提示 |
| E2E | Playwright | 登录、员工创建、IT 领取处理、员工确认、附件、403 和并发冲突主路径 |

标准脚本：

```text
pnpm lint
pnpm typecheck
pnpm test:unit --run
pnpm build
pnpm test:e2e
```

前端初始化必须让这些脚本在 Windows 本地与 Linux CI 使用相同名称。测试不得依赖执行顺序或开发者浏览器状态。

## 9. CI 流程与质量门禁

GitHub Actions 在 Pull Request 和 `main` 推送时运行，使用干净检出、JDK 21、Node 24.20.0、pnpm 12.3.4 和 Docker。建议分为三个 Job：

1. `backend-verify`
   - 校验 Maven Wrapper。
   - 执行 `./mvnw --batch-mode verify`。
   - 上传测试报告和 JaCoCo 报告。
2. `frontend-verify`
   - Corepack 激活固定 pnpm。
   - 执行 `pnpm install --frozen-lockfile`。
   - 依次执行 lint、typecheck、unit test 和 build。
3. `core-e2e`
   - 在前两个 Job 成功后启动 MySQL、Redis、后端和前端预览服务。
   - 从空库执行 Flyway，再加载仅限 E2E 的确定性夹具。
   - 安装固定 Playwright 浏览器并运行核心 E2E。
   - 无论成功失败都上传 Playwright 报告；失败时保留必要日志但先脱敏。

合并门禁：

- Maven `verify` 成功，所有 Flyway 空库与故障测试通过。
- 后端整体行覆盖率不低于 70%，分支覆盖率不低于 60%。
- 前端 lint、类型检查、单元测试和构建全部成功。
- 核心 Playwright E2E 全部成功；不允许通过自动重试长期掩盖不稳定测试。
- lockfile 无未提交变化，构建不得依赖开发机缓存。
- CI 配置与依赖更新必须经过同一 Pull Request 检查。

## 10. 本地开发启动方式

以下是工程骨架创建后的目标步骤，不代表当前仓库已经具备这些文件。

### 10.1 首次准备

1. 安装 JDK 21、Node.js 24.20.0、Docker 和 Git。
2. 使用 Corepack 激活仓库 `packageManager` 指定的 pnpm 12.3.4。
3. 从 `.env.example` 创建不提交的 `.env`，生成独立本地密码和至少 256 位 JWT 密钥。
4. 创建附件根目录和同卷暂存目录。

### 10.2 启动顺序

```powershell
docker compose up -d mysql redis
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend dev
```

首次生成 lockfile 时使用普通 `pnpm install`；之后开发机和 CI 一律使用 `--frozen-lockfile`。前端开发服务器把 `/fd` 代理至后端，因此浏览器请求、Refresh Cookie 和 Origin 配置保持同源开发体验。

### 10.3 启动检查

- `docker compose ps` 显示 MySQL 和 Redis 健康。
- 后端日志显示 Flyway validate/migrate 成功，不包含密钥或密码。
- 后端健康检查可用，OpenAPI 只在 local/demo 开启。
- 前端可打开 `/login`，未登录访问受保护路由会回到登录页。
- 停止后重新启动时 MySQL、Redis 和附件目录数据仍存在。

停止应用时先停止前端和后端进程，再运行 `docker compose down`。普通停止不得使用 `-v` 删除数据卷；清理卷必须作为明确的本地重置操作单独执行。

## 11. 最小 Vue 页面与 API 映射

### 11.1 路由与职责

| 路由/全局入口 | 页面职责 | 主要角色 |
| --- | --- | --- |
| `/login` | 登录 | 匿名 |
| `/tickets` | 我的工单、公共队列、负责中和参与历史 | 员工、IT |
| `/tickets/new` | 创建工单、附件和后续工单关联 | 员工 |
| `/tickets/:ticketNo` | 详情、时间线、附件及全部合法动作 | 有查看权用户 |
| `/dashboard` | 权限范围内的数据概览 | IT |
| `/admin/users` | 用户列表与创建 | 管理员 |
| `/admin/users/:userId` | 用户详情、角色、启停、密码重置和工单交接 | 管理员 |
| `/admin/categories` | 分类增删改、排序与启停 | 管理员 |
| 全局账号菜单 | 修改当前用户密码、退出登录 | 已登录用户 |
| `/403` | 已知身份但无页面权限 | 全部 |
| `/:pathMatch(.*)*` | 未匹配路由 | 全部 |

### 11.2 API 到页面或后台任务

| API/操作组 | 消费者 |
| --- | --- |
| `POST /fd/v1/auth/login` | `/login` |
| `POST /fd/v1/auth/refresh` | 全局 Axios 刷新协调器 |
| `POST /fd/v1/auth/logout` | 全局账号菜单 |
| `GET /fd/v1/auth/me` | 应用启动、刷新恢复和路由守卫 |
| `POST /fd/v1/auth/change-password` | 全局账号菜单的修改密码对话框 |
| `GET /fd/v1/tickets` | `/tickets`；创建后续工单时由 `/tickets/new` 复用本人范围 |
| `GET /fd/v1/tickets?scope=REQUESTED_BY_ME` | `/tickets/new` 的后续工单选择器 |
| `POST /fd/v1/tickets` | `/tickets/new` |
| `GET /fd/v1/tickets/{ticketNo}` | `/tickets/:ticketNo` |
| `GET /fd/v1/tickets/{ticketNo}/records` | `/tickets/:ticketNo` 时间线 |
| `POST /fd/v1/tickets/{ticketNo}/actions/{action}` | `/tickets/:ticketNo`；按权限、关系、状态和版本展示动作 |
| `GET /fd/v1/tickets/{ticketNo}/attachments/{attachmentId}/content` | `/tickets/:ticketNo` 附件下载 |
| `GET /fd/v1/categories/options` | `/tickets/new`，以及详情中的分类调整 |
| `GET /fd/v1/tickets/{ticketNo}/transfer-candidates` | `/tickets/:ticketNo` 转交对话框 |
| `GET /fd/v1/admin/users` | `/admin/users` 用户列表 |
| `POST /fd/v1/admin/users` | `/admin/users` 创建用户对话框 |
| `GET /fd/v1/admin/users/{userId}` | `/admin/users/:userId` |
| `PUT /fd/v1/admin/users/{userId}` | `/admin/users/:userId` 基本资料编辑 |
| `POST /fd/v1/admin/users/{userId}/actions/enable` | `/admin/users/:userId` |
| `POST /fd/v1/admin/users/{userId}/actions/disable` | `/admin/users/:userId`，必要时进入交接流程 |
| `PUT /fd/v1/admin/users/{userId}/roles` | `/admin/users/:userId`，必要时进入交接流程 |
| `POST /fd/v1/admin/users/{userId}/actions/reset-password` | `/admin/users/:userId` 密码重置对话框 |
| `GET /fd/v1/admin/roles/options` | 用户创建与用户详情角色编辑 |
| `GET /fd/v1/admin/users/{userId}/active-ticket-assignments` | `/admin/users/:userId` 的管理性交接流程 |
| `GET /fd/v1/admin/tickets/{ticketNo}/handoff-candidates` | `/admin/users/:userId` 的逐工单接替人选择器 |
| `GET /fd/v1/admin/categories` | `/admin/categories` 列表 |
| `POST /fd/v1/admin/categories` | `/admin/categories` 创建对话框 |
| `PUT /fd/v1/admin/categories/{categoryId}` | `/admin/categories` 编辑对话框 |
| `POST /fd/v1/admin/categories/{categoryId}/actions/enable` | `/admin/categories` |
| `POST /fd/v1/admin/categories/{categoryId}/actions/disable` | `/admin/categories` |
| `DELETE /fd/v1/admin/categories/{categoryId}` | `/admin/categories`，仅未引用分类可用 |
| `GET /fd/v1/dashboard/tickets` | `/dashboard` |
| 待确认/待补充超时动作 | Spring 定时任务；无前端路由和 HTTP 入口 |
| 附件暂存/孤儿对账 | 后端定时维护任务；无前端入口 |

路由守卫根据 `/auth/me` 返回的角色和权限改善导航体验，但后端仍对每次请求执行 RBAC、资源关系、状态、负责人和版本校验。401 触发一次受控刷新；刷新失败清空内存状态并进入登录页。403 进入无权限页或就地提示，404 不泄露不可见资源，409 要求重新读取最新工单而不是自动重放写操作。

## 12. 工程准备验收

| 验收项 | 结论 | 证据/后续验证入口 |
| --- | --- | --- |
| 依赖版本明确且一致 | 通过 | 第 3 节；非 BOM 与前端直接依赖需锁定 |
| 敏感配置边界清晰 | 通过 | 第 5 节；仓库只保留 `.env.example` |
| 空数据库可完整迁移 | 通过 | `DatabaseMigrationIT` 在 MySQL 8.4.11 Testcontainers 空库执行 V1/V2、`validate`、关键约束与 demo 数据幂等性 |
| 测试工具与关键场景明确 | 通过 | 第 7、8 节 |
| CI 可从干净检出运行 | 已实现，待首次合并请求的 GitHub 运行记录 | `.github/workflows/ci.yml` 固定 JDK 21、Node 24.20.0、pnpm 12.3.4，并分为 backend、frontend、core E2E 三个 Job；同等本地命令已通过 |
| 新开发者可重复启动 | 通过 | Compose、Profile、`.env.example`、Maven Wrapper、pnpm 入口和 README 已建立并本地验证 |
| 跨存储失败顺序明确 | 通过 | 第 7 节；故障测试列明 |
| API 映射到页面或后台任务 | 通过 | 第 11 节 |

工程准备状态：**Ready，且 M0 工程底座已完成本地验证**。工程、迁移、公共契约和 CI Workflow 已创建；CI 的首次 GitHub 执行将在本分支创建合并请求后留下运行记录。下一阶段须在 M0 分支完成交接后，才开始 M1 身份入口实现。

## 13. 版本核验来源

- [Spring Boot 3.5.16 发布说明](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)
- [Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Spring Boot 3.5.16 依赖版本表](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html)
- [MyBatis-Plus Spring Boot 3 安装说明](https://baomidou.com/getting-started/install/)
- [springdoc-openapi 与 Spring Boot 兼容矩阵](https://springdoc.org/v2/)
- [OWASP 密码存储建议](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [Node.js 24.12.0 LTS 发布说明](https://nodejs.org/en/blog/release/v24.12.0)
- [MySQL 8.4.11 发布说明](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/news-8-4-11.html)
- [Redis 8.8.0 发布说明](https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/release-notes/redisce/redisos-8.8-release-notes/)
- [Vite 8.2.2 包信息](https://www.npmjs.com/package/vite)
- [Vue Router 5 安装说明](https://router.vuejs.org/installation)
- [Vue Test Utils 包信息](https://www.npmjs.com/package/%40vue/test-utils)
- [Vue TypeScript ESLint Config 包信息](https://www.npmjs.com/package/%40vue/eslint-config-typescript)
