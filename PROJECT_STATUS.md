# FlowDesk 项目状态

> 本文件用于新机器、任务恢复和工作交接时快速定位项目，不替代详细设计文档。

## 快速定位

- 最后更新：2026-09-07
- 远程仓库：`git@github.com:Crazy-HF/Flow-Desk.git`
- 稳定分支：`main`
- 当前基线分支：`main`
- 当前工作分支：`flow-desk/implementation-plan`
- 下一次创建分支：`flow-desk/project-bootstrap`（任务拆分验收并完成当前分支交接后创建）
- 当前阶段：开发任务拆分已完成，等待分支交接
- 最新完成：开发任务拆分（7 个里程碑、20 项任务及其依赖、验收、测试和 Git 检查点）
- 下一步：完成当前分支交接，再从最新 `main` 创建 `flow-desk/project-bootstrap`
- 当前阻塞：无
- 环境前置：Node.js 24.20.0 与 pnpm 12.3.4 已下载，但当前终端仍生效 Node.js 24.4.0、pnpm 11.19.0；工程初始化前必须完成 PATH/Corepack 切换并复核

## 已完成里程碑

1. 项目目标、用户、v1 范围、业务流程和权限已经确认。
2. 核心业务概念、关系、生命周期和业务不变量已经确认。
3. 总体技术架构已经确认。
4. 数据库逻辑数据模型和 MySQL 物理模型已经确认。
5. v1 API 契约、权限映射、错误编码、并发和幂等语义已经确认。
6. 工程依赖、配置、迁移、测试、CI、启动、跨存储失败处理和页面/API 映射已经确认。
7. 开发任务拆分已经确认，全部 API 与后台任务均有实施归属。
8. 尚未创建 Spring Boot、Vue 或数据库工程。

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
- 密码使用 Argon2id 单向哈希；Docker Compose 只运行 MySQL 和 Redis；前端使用 Element Plus。

## 下一步任务

任务名称：FlowDesk M0 工程底座。

目标：

- 完成 `TASK-001`：初始化可重复运行的仓库骨架。
- 完成 `TASK-002`：实现 Flyway 数据基线与确定性测试数据。
- 完成 `TASK-003`：建立后端公共契约与 CI 基线。

本步骤暂不做：

- 当前分支交接及 M0 开工确认前不创建或初始化工程。
- M0 不实现认证、工单、管理或页面业务流程。
- 不引入已确认技术边界之外的基础设施。

## 当前任务必读

开始 M0 工程底座前，按以下顺序读取：

1. `AGENTS.md`
2. `PROJECT_STATUS.md`
3. `docs/implementation-plan.md` 的 M0、全局完成定义和集中确认点
4. `docs/engineering-readiness.md` 的版本、仓库结构、配置、迁移、测试、CI 和启动部分
5. `docs/database-design.md` 的 MySQL 物理模型、预置 RBAC、索引和事务不变量
6. `docs/technical-architecture.md` 的模块边界和测试架构

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
