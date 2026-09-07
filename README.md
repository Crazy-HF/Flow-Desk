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

项目启动分析、业务模型、总体架构、数据库逻辑与物理模型、v1 API 契约、工程准备检查、开发任务拆分和 M0 工程底座已经完成并合并；当前进入 M1 身份入口开发。

M0 已建立 Spring Boot/Vue 工程、Flyway 数据基线、公共 API 契约和 CI 基线；M1 将实现认证、会话和密码安全。已确认的设计和实施顺序记录在 `docs/kickoff.md`、`docs/business-model.md`、`docs/technical-architecture.md`、`docs/database-design.md`、`docs/api-design.md`、`docs/engineering-readiness.md` 与 `docs/implementation-plan.md` 中。

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

后端健康检查位于 `http://localhost:8080/actuator/health`，前端开发入口位于 `http://localhost:5173`。前端将 `/fd` 请求代理到后端。普通停止使用 `docker compose down`，不要附加 `-v`，以免删除本地数据卷。

M1 身份入口开发中；现阶段首页只用于验证 Vue 工程能够构建和启动，尚未承载业务页面。
