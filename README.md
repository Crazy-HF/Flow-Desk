# FlowDesk

FlowDesk 是一个企业工单协作平台，由开发者与 Codex 协作完成。

## 项目目标

- 理解一个 Java Web 项目从需求分析到开发交付的完整流程。
- 夯实 Spring Boot、MySQL、Redis 等 Java 后端开发能力。
- 完成一个业务流程完整、可测试、可说明的求职展示项目。
- 在协作开发过程中掌握核心设计与实现，而不是简单堆叠功能。

## 计划技术栈

### 后端

- Java 21
- Spring Boot 3
- MySQL
- Redis

### 前端

- Vue 3
- 仅实现支撑核心业务流程所需的基础页面

具体依赖、版本和工程规范已记录在 `docs/engineering-readiness.md`。

## 开发原则

- 原生开发，不使用若依等后台管理框架。
- 先确认业务，再设计架构、数据库和 API。
- 优先采用适合当前项目规模的简单方案。
- 未经论证和确认，不引入微服务、消息队列、Kubernetes、AI 等额外技术。
- 每个开发阶段都设置明确的验收标准，并对代码变更执行必要测试。

## 当前状态

项目启动分析、业务模型、总体架构、数据库逻辑与物理模型、API 契约、工程准备检查和 M0 工程底座已经完成。后续分为“求职 MVP”和“完整版”两层；MVP 阶段 1 Auth 身份入口（`TASK-010`～`TASK-011`）已经完成，当前先实施已确认提前的完整动态 RBAC，随后进入阶段 2 员工创建与查询。

MVP 主链固定使用普通员工、IT 支持人员、系统管理员三种内置角色，先完成认证，再交付“员工创建 → IT 领取和处理 → 员工确认”的核心工单闭环；附件、完整状态机、管理端页面和数据概览放入完整版。动态 RBAC 后端能力已于 2026-09-21 确认提前到阶段 2 之前实施，但是否计入 MVP 演示范围仍待确认。详细顺序见 `docs/implementation-plan.md`。

## 协作说明

本项目由开发者与 Codex 协作开发。详细协作规则见 `AGENTS.md`。

在新机器或新会话中继续项目时，先阅读 `PROJECT_STATUS.md` 获取当前阶段、下一步和当前任务必读文件，无需默认通读全部设计文档。

## 本地工程入口

### 环境要求

- JDK 21
- Node.js 24.20.0
- pnpm 12.3.4
- Docker 与 Docker Compose

### 首次准备

```powershell
Copy-Item .env.example .env
```

将 `.env` 中的占位值替换为仅供本机使用的随机密码、JWT 密钥和绝对附件目录，然后把变量加载到当前 PowerShell 进程：

```powershell
.\scripts\load-env.ps1
```

脚本只输出加载的变量数量，不输出变量值。`.env` 已被 Git 忽略。

### 启动基础设施和应用

```powershell
docker compose up -d mysql redis
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend dev
```

后端健康检查位于 `http://localhost:8081/actuator/health`，前端开发入口位于 `http://localhost:5173`。前端将 `/fd` 请求代理到后端。普通停止使用 `docker compose down`，不要附加 `-v`，以免删除本地数据卷。

MVP 阶段 1 Auth 身份入口已完成并通过 PR #5 合并进入 `main`：访问 `http://localhost:5173` 会被引导到 `/login`，用演示账号登录后进入受保护首页（显示当前身份与按权限展示的能力清单）。登录后的应用壳按四层职责组织——整幅顶栏（品牌位 + 搜索位 + 全屏 + 账号菜单）、按权限生成的侧栏、表达当前位置的面包屑、主体内容；导航条目与面包屑层级共用 `frontend/src/constants/authorization.ts` 的同一份声明。下一步先实施系统管理端 RBAC（角色、权限、用户角色与角色权限的在线管理）：阶段设计 2026-09-21 确认、2026-09-22 补充至 13 项设计决策，任务拆分见 `docs/implementation-plan.md` 9.1——`TASK-055`～`TASK-057` 与 `TASK-059`（安全链作用域修复）已完成，下一步 `TASK-058`（两组授权）→ `TASK-060`（**RBAC 管理端页面；本阶段计入 MVP 演示范围**）。随后是员工工单主链路；其余业务页面（工单、队列、数据概览、用户与分类管理）分批在后续阶段实现。演示账号见 `src/main/resources/db/demo/R__seed_demo_data.sql` 头部注释。
