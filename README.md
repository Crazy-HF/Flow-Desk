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

项目启动分析、业务模型、总体架构、数据库逻辑与物理模型、API 契约、工程准备检查和 M0 工程底座已经完成。后续分为“求职 MVP”和“完整版”两层；MVP 阶段 1 Auth 身份入口（`TASK-010`～`TASK-011`，PR #5）、前端外壳与页面骨架（PR #6）、已确认提前实施的完整动态 RBAC（`TASK-055`～`TASK-062`，含管理端五页，PR #8）、阶段 2 员工创建与查询（`TASK-020`～`TASK-023-MVP`，PR #9）与**阶段 3 IT 处理闭环（[PR #10](https://github.com/Crazy-HF/Flow-Desk/pull/10)，合并提交 `1bc2e4c`）**均已合并 `main`。阶段 3 的四步全部完成并通过真实栈验收：步骤①②③ 于 2026-10-06 通过真实栈验收（77/77），步骤④ IT/员工页面与 Playwright 端到端主链同日用真实栈两个账号跑通 `待受理 → 处理中 → 待员工确认 → 已完成`。当前在 `flow-desk/mvp-closeout` 推进 **阶段 4 MVP 验收与求职展示收口**，核心是补齐工单与分类模块的自动化测试（当前后端 35 个测试类中没有一个属于这两个模块），再做空库演示与文档收口。

MVP 主链固定使用普通员工、IT 支持人员、系统管理员三种内置角色，先完成认证，再交付“员工创建 → IT 领取和处理 → 员工确认”的核心工单闭环；附件、完整状态机、数据概览放入完整版（**RBAC 管理端页面、用户管理与已授权提前实施的分类管理除外**，两者已在阶段 3 交付：用户管理于 2026-09-24 登记为 `TASK-061`/`TASK-062`，同批合并）。动态 RBAC 后端能力已于 2026-09-21 确认提前到阶段 2 之前实施，并已于 2026-09-22 确认**计入 MVP 演示范围**（含管理端页面）；用户角色授权取消“至少一个角色”约束，**允许用户零角色**。详细顺序见 `docs/implementation-plan.md`。

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

MVP 阶段 1 Auth 身份入口已完成并通过 PR #5 合并进入 `main`：访问 `http://localhost:5173` 会被引导到 `/login`，用演示账号登录后进入受保护首页（显示当前身份与按权限展示的能力清单）。登录后的应用壳按四层职责组织——整幅顶栏（品牌位 + 搜索位 + 全屏 + 账号菜单）、按权限生成的侧栏、表达当前位置的面包屑、主体内容；导航条目与面包屑层级共用 `frontend/src/constants/authorization.ts` 的同一份声明。系统管理端 RBAC 已完成并通过 PR #8 合并 main：`TASK-055`～`TASK-062` 全部完成，验收标准第 4 条的四条手工真实栈链路已于 2026-09-28 在真实栈上执行并通过（逐条留 `traceId`，见 `docs/modules/rbac.md` 11.2）。验证证据：后端 **394** 单元/Web + **88** 集成全绿，前端 typecheck/lint/build、74 项单测和 13 项 E2E 全绿；`frontend/src/views/admin/` 下五页可走通真实授权闭环。`auth`、`iam` 生产代码已完成应用层重构，约定见 `docs/technical-architecture.md` 5.1；用户管理已登记为 `TASK-061`/`TASK-062`，零角色是合法终态。

阶段 2 已于 2026-09-29 验收通过并经 PR #9 合并 main（5b9a961）；后端 394 单元/Web + 88 集成、前端 124 单测、15 E2E 通过，证据见 `docs/acceptance/stage2-closeout-20260929.json`。阶段 3 在 `flow-desk/ticket-it-flow` 完成四步：IT 领取、追加处理记录、提交解决结果与员工确认已通过真实栈验收（77/77，证据 `docs/acceptance/2026-10-06-stage3-claim-process-resolution-confirm.json`）；IT 工作台（`/it/queue`）、详情页动作区与员工确认按钮已实现，端到端主链 `frontend/e2e/ticket-it-flow.spec.ts` 用 `employee` 与 `it` 两个真实账号跑通四态迁移（报告与截图见 `.ui-craft/reviews/2026-10-06-ticket-it-flow/`）。本阶段回归口径：后端 394 单元/Web + 88 集成、前端 147 单测、16 E2E 全绿。阶段 3 已经 [PR #10](https://github.com/Crazy-HF/Flow-Desk/pull/10) 合并 `main`（合并提交 `1bc2e4c`）。CI 在首次 PR 运行中发现并修正了一处 E2E 缺陷：用例把 IT 显示名写死为「IT 支持人员」，而种子数据是「演示 IT 支持人员」，干净库因此失败；已改为按当前登录身份读取显示名，修正后三个 job 全绿，完整记录见 `docs/acceptance/2026-10-06-stage3-git-handoff.json`。当前在 `flow-desk/mvp-closeout` 推进阶段 4，下一步见 `PROJECT_STATUS.md`。演示账号生成见 `src/main/resources/db/demo/R__seed_demo_data.sql`（仅 `demo`/`local` profile 生效，`prod` 不加载该目录）。
