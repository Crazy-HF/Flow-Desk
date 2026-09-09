# FlowDesk 项目状态

> 本文件用于新机器、任务恢复和工作交接时快速定位项目，不替代详细设计文档。

## 快速定位

- 最后更新：2026-09-09
- 远程仓库：`git@github.com:Crazy-HF/Flow-Desk.git`
- 稳定分支：`main`
- 当前基线分支：`main`
- 当前工作分支：`flow-desk/auth-foundation`
- 下一次创建分支：`flow-desk/employee-ticket-flow`（M1 验收并完成分支交接后创建）
- 当前阶段：M1 身份入口
- 已确认的范围调整：RBAC 支持在线维护自定义角色、权限及其授权关系；`SYSTEM_ADMIN` 为受保护内置角色，角色或权限被引用时禁止删除。
- 最新完成：M0 工程底座（`TASK-001`～`TASK-003`）已合并进入 `main`
- 下一步：先修正并验证当前 IAM 用户管理切片，再回到 `TASK-010` 认证、会话和密码安全
- 当前阻塞：IAM 用户管理仍有权限、排序、角色和测试上下文问题；当前终端 `JAVA_HOME=D:\java` 指向不存在的目录，无法在本机执行新增定向测试
- 环境前置：JDK 21、Node.js 24.20.0、pnpm 12.3.4、Docker 已验证

## 已完成里程碑

1. 项目目标、用户、v1 范围、业务流程和权限已经确认。
2. 核心业务概念、关系、生命周期和业务不变量已经确认。
3. 总体技术架构已经确认。
4. 数据库逻辑数据模型和 MySQL 物理模型已经确认。
5. v1 API 契约、权限映射、错误编码、并发和幂等语义已经确认。
6. 工程依赖、配置、迁移、测试、CI、启动、跨存储失败处理和页面/API 映射已经确认。
7. 开发任务拆分已经确认，全部 API 与后台任务均有实施归属。
8. **M0 工程底座已完成并合并**：`TASK-001`～`TASK-003`（骨架、数据基线、公共契约、CI）通过 PR #4 合并进入 `main`。

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
3. 创建用户需按既定契约处理至少一个角色；停用和重置密码需在会话组件完成后撤销目标用户全部会话。
4. 修复公共 Spring 测试的 IAM Mapper 依赖，恢复有效 JDK 21 环境并使全量单测重新通过；清理 `pom.xml` 中重复的 MyBatis-Plus Generator 依赖。

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

任务名称：IAM 用户管理切片纠错与测试恢复，然后继续 M1 `TASK-010`。

目标：

- 先修复上述 Controller、Service、权限、排序白名单和测试上下文问题，确保当前代码不会阻断应用启动与公共测试。
- 实现 Argon2id 密码封装、登录、HS256 Access Token、Redis 会话与 Refresh Token。
- 实现刷新轮换/重用检测、退出、`/auth/me` 和当前用户修改密码。

协作方式：

- 用户负责编写业务代码；除非用户明确授权代写，Codex 只提供小步目标、设计说明、验收标准、代码 review 与测试建议。

本步骤暂不做：

- 工单、管理或页面业务流程。
- 不引入已确认技术边界之外的基础设施。
- 密码和 Token 行为必须与已确认的 API 文档一致。

## 当前任务必读

开始 M1 身份入口前，按以下顺序读取：

1. `AGENTS.md`
2. `PROJECT_STATUS.md`
3. `src/main/java/com/flowdesk/iam/controller/IamUserController.java`
4. `src/main/java/com/flowdesk/iam/service/impl/IamUserServiceImpl.java`
5. `src/main/java/com/flowdesk/shared/web/PageQuery.java`
6. `src/test/java/com/flowdesk/iam/service/IamUserServiceImplTest.java`
7. `docs/implementation-plan.md` 的 M1、TASK-010、TASK-051 和全局完成定义
8. `docs/api-design.md` 的认证、会话、Token 和密码相关接口
9. `docs/database-design.md` 的用户表、会话表和 Redis 结构
10. `docs/technical-architecture.md` 的认证流程和安全机制

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
