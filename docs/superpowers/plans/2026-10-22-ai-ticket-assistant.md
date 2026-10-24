# AI 工单处理辅助实施计划（2026-10-22）

> 执行方式：AI 功能代码由用户根据对话中给出的完整内容亲自写入；业务代码完成后，业务测试交给 WorkBuddy 执行，Codex 核对实际文件、Git 状态和验收证据。Codex 可按用户授权维护方案与状态文档。本文件同时记录已完成的准备工作和待办步骤。

**目标：** 当前负责人在处理中工单上主动生成一份可编辑的 AI 处理建议，保存受控历史，并可将建议预填到现有处理记录表单，最终仍由人决定和提交。

**架构：** 沿用现有 Spring Boot 单体与 `ticket` 模块分层。`application.service.impl` 负责身份、权限、输入白名单、额度和结果校验；模型调用通过 `application.port` 交给 `infrastructure` 的 DeepSeek 适配；建议历史单独持久化，不进入工单时间线。前端只从服务端取得授权后的数据。

**技术栈：** Java 21、Spring Boot 3.5.16、Spring AI 1.0.9、DeepSeek `deepseek-flash`、MyBatis、MySQL/Flyway、Vue 3。

**方案依据：** `docs/modules/ai-ticket-assistant.md` 与 `PROJECT_STATUS.md` 顶部。两者已有的“已确认”业务口径优先；本计划中的待决项在相关代码开始前确定。

## 全局边界与准备

- 首轮只发送虚构或脱敏工单；开发、演示环境可启用，真实工单环境默认关闭。真实数据启用另做数据规则与验收。
- 输入仅为标题、描述、分类、优先级、状态。负责人预览后可删改标题和描述并再次确认；附件、处理记录、账号及内部 ID 不送模型。
- 生成限 `PROCESSING`、当前负责人、工单可见且持有 `TICKET_PROCESS` 与独立 AI 权限的用户。历史读取还须检查工单可见性和当前/最后负责人关系。
- 成功建议保存确认后实际发送的输入快照及三部分输出。预填只是浏览器草稿；只有人工提交才写现有 `PROCESS` 记录。
- 每人每天最多 10 次（可配置），供应商失败不自动重试。首轮付费验证累计上限人民币 5 元。
- 本主题使用当前 `flow-desk/ai-ticket-assistant-analysis` 分支继续分析；进入生产代码阶段前，从届时最新 `main` 创建新的 `flow-desk/<主题>` 短期分支，并核对现有未提交文档和工作树，避免覆盖其他改动。

## 文件与职责地图

| 范围 | 预定职责 |
| --- | --- |
| `pom.xml`、`src/main/resources/application*.yml` | Spring AI 依赖、模型/密钥/开关/额度/超时配置；无密钥且功能关闭时可启动 |
| `src/main/java/com/flowdesk/ticket/application/{command,query,result,service,port}/` | 生成、历史查询契约及模型调用端口；延续现有 Command/Query/Result 约定 |
| `src/main/java/com/flowdesk/ticket/application/service/impl/` | 授权、输入白名单、额度、调用、结果验证、保存的业务编排 |
| `src/main/java/com/flowdesk/ticket/infrastructure/` | Spring AI DeepSeek 适配、持久化相关实现 |
| `src/main/java/com/flowdesk/ticket/{controller,mapper,domain}/` | HTTP 入口、独立建议历史读写与必要的数据类型 |
| `src/main/resources/db/migration/V14__*.sql`（实施前核对最大版本） | AI 权限、历史表与额度所需结构；不修改旧迁移 |
| `frontend/src/{api,views/work,constants}/` | 预览确认、建议展示/历史、加载和失败态、处理记录草稿预填 |
| `src/test/`、`frontend/src/**/*.test.ts`、`frontend/e2e/` | 手动探针由用户写入；后续业务测试在业务代码完成后交给 WorkBuddy，Codex 独立复核结果 |

## 第 0 步：冻结目标和运行准备

- [ ] 核对本计划与 `docs/modules/ai-ticket-assistant.md` 的“已确认”口径，明确首个闭环只包含单张工单辅助，不加入知识库、检索、自动动作或多轮对话。
- [ ] 用户在本机准备 DeepSeek API Key 与余额；仅以服务端环境变量提供密钥，确认仓库、前端包、日志及截图不出现密钥。
- [ ] 准备 1 张虚构连通样本；10 张固定质量样本与评分表由 Codex 后续提供，用户逐张判断可用性。
- [ ] 核对本机 Java 21、Maven Wrapper、MySQL/Redis 演示环境、当前 Git 状态和最新迁移号。

**通过标准：** 数据边界、预算、环境和分工可复述；不发送真实工单正文。虚构样本的付费调用已在后续第 1 步发生，见本文末的运行记录。

### Key 的确切位置与后续配置写法

用户在仓库根目录 `D:\Java-Item\FLow-Desk\.env` 中填写 `DEEPSEEK_API_KEY=实际密钥`。从 `.env.example` 复制得到 `.env`；它已被 `.gitignore` 忽略。不要把 Key 写进 `application.yml`、前端、测试文件或 Git，也不要在对话中发送。`local` profile 的 `application-local.yml` 已通过 `optional:file:.env[.properties]` 读取该文件；当前 PowerShell 可点源执行 `. .\scripts\load-env.ps1`，让单独启动的探针读取进程环境变量。手动探针只需临时设置 `FLOWDESK_AI_PROBE=YES`；`FLOWDESK_AI_ENABLED` 保持 `false`，待正式业务链路完成并验证后再考虑在开发/演示环境启用。

用户已把以下配置写入 `src/main/resources/application.yml` 的现有 `spring` / `flowdesk` 节点；`pom.xml` 也已加入 Spring AI 1.0.9 BOM 与 DeepSeek starter：

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY:}
      chat:
        enabled: ${FLOWDESK_AI_ENABLED:false}
        options:
          model: deepseek-flash
    retry:
      max-attempts: 1
flowdesk:
  ai:
    enabled: ${FLOWDESK_AI_ENABLED:false}
```

手动探针已通过 Spring AI DeepSeek 适配器调用虚构工单；尚无正式业务适配器。功能关闭时模型 Bean 与未来依赖它的业务适配器应一起按条件禁用；无 Key 关闭状态启动尚待验证。真实工单环境继续默认关闭。

## 第 1 步：最小技术验证（先于业务接入）

- [x] `pom.xml` 加入 Spring AI 1.0.9 BOM 与 `spring-ai-starter-model-deepseek`；用户运行手动探针时 Maven 返回 `BUILD SUCCESS`。依赖树与关闭状态应用启动另核对。
- [x] 手动探针从进程环境变量读取 Key，显式指定 `deepseek-flash`、最多尝试一次、输出上限 600 tokens；探针不启动 Spring Boot 应用。有限网络超时及无 Key 关闭状态应用启动另验证。
- [x] 用一张虚构投影仪工单手动发起同步调用；用户报告模型 `deepseek-flash`、3338 ms、输入 173 / 输出 440 / 合计 613 tokens。探针只有一处 `model.call`，重试最大尝试次数为 1；平台请求记录及实际扣费仍需另核对。
- [ ] 验证三部分输出可解析，并分别验证空响应、截断、拒绝和供应商错误的可处理结果。若适配器与模型/响应不兼容，记录证据后停在此步，不静默换模型或接入方式。

**通过标准：** 编译通过、确实只发送一次供应商请求、三部分可解析或明确报错、无 Key 且关闭功能时正常启动、累计费用未超人民币 5 元。此步不写工单表、不加 UI。

**当前结论（2026-10-22）**：一次虚构样本调用成功且回答含三部分，未把建议说成已执行事实。尚未验证空响应、截断、拒绝、供应商错误、应用无 Key 关闭启动、平台扣费与正式业务配置；因此第 1 步整体尚未验收。探针文件为 `src/test/java/com/flowdesk/devtools/DeepSeekManualProbe.java`，通过显式运行 `main` 方法触发，不随常规测试自动调用供应商。

## 第 2 步：定下接口、数据与失败契约

- [ ] 确定生成与历史查询 API 路径、请求/响应字段、字段长度、错误码；生成请求必须携带用户确认后的标题与描述，其他字段由服务端读取并校验，不能信任客户端传入的工单属性。
- [ ] 确定输入预览到发送之间的版本冲突口径；调用前重新读取工单，调用后保存前再检查状态、负责人及版本。明确冲突时是否占用日额度及是否留调用元数据。
- [ ] 确定每天额度的时区、失败是否计数和并发原子占额方式；确定同一用户多次点击时的前后端防重复策略。以“实际供应商请求数可控”为验收对象。
- [ ] 确定供应商成功但数据库保存失败时的响应与审计口径；确定模型超时、非法结构、配额耗尽、功能关闭和越权的错误映射。
- [ ] 确定历史表字段、索引、提示词版本、清理方式；以工单 `ended_at` 计算终态后 30 天，未结束工单保留。
- [ ] 更新 `docs/api-design.md`、模块设计文档和 `PROJECT_STATUS.md`，完成代码前的集中契约检查。

**通过标准：** 请求、返回、拒绝及并发结果均有唯一口径，之后的数据库/API/页面可按同一契约实现。

## 第 3 步：后端最小闭环

- [ ] 用户写新增 Flyway 迁移，加入独立 AI 权限、默认角色授权、历史结构及额度结构；先用空库迁移和既有库升级验证，不改已发布的 `V1`～`V13`。
- [ ] 用户写 Command/Query/Result、Service 与模型端口、DeepSeek 适配器；保持 Controller 薄、授权和编排在服务实现中，模型调用不处于持有工单写锁的长事务里。
- [ ] 用户写生成链路：登录身份 → AI 权限和 `TICKET_PROCESS` → 可见性 → `PROCESSING`/负责人 → 输入校验与额度 → 单次模型调用 → 结果结构/长度校验 → 工单状态重检 → 独立历史保存。
- [ ] 用户写历史查询链路：复用工单可见性规则并加当前/最后负责人限制；权限撤销后仍可按关系读旧历史，转交后原负责人失权。
- [ ] 用户写到期清理；不可把建议写入 `ticket_record`，也不可让任何生成结果自动执行工单动作。
- [ ] 业务代码完成后由 WorkBuddy 编写并执行对应的单元、Web 和 MySQL 集成测试；Codex 核对断言、实际文件、Git 状态和执行证据。至少覆盖成功、无权限、不可见、不在处理状态、转交竞态、额度并发、单次请求、非法输出、保存失败和清理边界。

**通过标准：** 可生成并读回历史；未授权请求不调用供应商；模型错误不影响普通工单；生成前后工单状态、负责人、记录和版本无额外业务变更。

## 第 4 步：详情页接入

- [ ] 用户先读 `frontend/AGENTS.md` 和现有 `TicketDetailView.vue`、`frontend/src/api/tickets.ts`；沿用现有 token、页面状态与动作约定。
- [ ] 用户写“预览五项内容 → 删改标题/描述 → 再次确认 → 生成中 → 结果/失败”的交互；权限和动作条件来自后端，前端隐藏按钮只是体验层。
- [ ] 用户写历史查看及“填入处理记录”：三部分以“AI 草稿”预填已有可编辑框；负责人可改或舍弃；提交继续调用现有 `add-processing-record` 接口。
- [ ] 业务页面完成后由 WorkBuddy 编写并执行组件与 E2E 测试；Codex 独立核对。覆盖加载、失败重试、无权限、转交后失权、再次确认以及预填但未提交不落库。

**通过标准：** 一次点击只触发一次后端生成；草稿未经人工提交不写时间线；窄屏与错误态可用。

## 第 5 步：固定样本评估与交接

- [ ] Codex 提供 10 张虚构或脱敏固定样本与评分表，检查结构、事实口径和调用证据；用户逐张判断是否“稍作修改即可用”。
- [ ] 达到 10/10 三部分完整、0 条把未执行建议写成已完成事实、至少 8/10 经用户判定可用。未达标先复盘失败样本和提示词，再决定是否评估 Pro。
- [ ] 执行后端相关测试、前端 typecheck/lint/build/相关单测、真实栈浏览器验收；逐项记录通过、失败与未运行，复核预算、密钥和日志。
- [ ] 更新 API、模块、状态和验收文档。用户若另行明确要求 Git 交接，再按仓库 AGENTS.md 检查、提交、推送及合并；本计划本身不授权 Git 写操作。

**通过标准：** 开发/演示环境闭环可复现，真实工单环境仍关闭；验收证据能逐项对应业务目标与失败边界。

## 当前集中待决项

这些事项都已有设计位置，无需现在一次决定。进入第 2 步前集中确认：日额度时区与失败计数、并发占额、版本变化冲突、调用成功但保存失败、API 字段/错误码和超时值。真实工单外发规则与持续预算留到真实环境启用阶段。

## 2026-10-22 本机环境核查（实测，非功能验收）

- Java 21.0.12.1、Maven Wrapper 3.9.16、Docker 29.7.2 / Compose 5.5.1 可执行。Node 24.11.1、npm 11.6.2、pnpm 11.25.0 可执行。按用户本轮裁决，前端已放宽到 Node 24.x，本地不强制 pnpm 版本，也允许 npm。`npm --prefix frontend run lint` 与 `npm --prefix frontend run build` 退出码 0；`pnpm --dir frontend install --lockfile-only --offline --frozen-lockfile --ignore-scripts` 在 pnpm 11.25.0 下退出码 0。npm 全新安装与前端测试尚未运行。
- `./mvnw.cmd -B -o -DskipTests compile` 退出码 0、`BUILD SUCCESS`；此时项目尚无 Spring AI 依赖，因此只证明现有后端基线可编译。
- 当前工作目录没有 `.env`；当前 shell 未设置 `DEEPSEEK_API_KEY`、`FLOWDESK_DB_URL`、`FLOWDESK_REDIS_HOST`。`.env` 已被 Git 忽略。未读取或打印任何密钥值。
- Docker 引擎可用，但 `docker ps` 无运行容器；项目默认的本机端口 3308/6380 未监听。`docker compose ps` 因必填的 MySQL/MinIO 变量缺失而在配置插值阶段失败。MySQL/Redis、迁移、真实栈、DeepSeek 调用和费用均未验证。
- 当前分支仍为 `flow-desk/ai-ticket-assistant-analysis`；原有 `PROJECT_STATUS.md` 修改、`docs/modules/ai-ticket-assistant.md` 未跟踪文件保留，本计划为新增未跟踪文档。未提交或推送。

**后续接续核查（探针运行前）**：用户已创建忽略提交的 `.env`，其中有 `DEEPSEEK_API_KEY` 与 `FLOWDESK_AI_ENABLED` 条目，并已将上面的 AI 配置写入 `application.yml`；未读取 Key 值。当时 `pom.xml` 尚无 Spring AI 依赖。`docker compose config --quiet` 退出码 0，只证明 `.env` 足以完成 Compose 配置解析，不代表容器或应用已启动。

**逐项配置说明（探针运行前）**：`.env.example` 的 25 个变量已补逐项中文注释，没有改示例值；真实 `.env` 保持原样。只核查到 Key 非空、AI 开关为 `false`，未读取 Key 内容。当时真实 `.env` 仍有 8 个 `replace-with-*` 占位值，不能据此认定本地真实栈可启动。用户随后已加入 Spring AI 依赖并完成虚构工单的单次手动调用；真实栈仍未验证。

## 2026-10-22 手动探针结果与下一步

- 用户写入 `pom.xml` 依赖和 `application.yml` 的 AI 配置，并在 `src/test/java/com/flowdesk/devtools/DeepSeekManualProbe.java` 保存手动探针。探针曾短暂位于 `src/main`，现已原样移至 `src/test`，不属于自动测试，也不进入生产产物。
- 用户运行结果：`deepseek-flash`、3338 ms、输入 173 tokens、输出 440 tokens、总计 613 tokens，三部分建议齐全，Maven `BUILD SUCCESS`。探针源码只有一处模型调用，显式最大尝试次数 1；未独立核对 DeepSeek 平台请求数与实际扣费。
- 下一步先完成第 2 步业务契约：API 字段和错误码、额度并发和失败计数、版本冲突与保存失败、历史结构和保留规则。第 1 步剩余的无 Key 关闭状态启动、异常/截断、网络超时与平台费用证据在业务接入前补齐或明确记录为未验证。
- 用户亲自写入后续 AI 业务代码。业务代码完成后才派发 WorkBuddy 编写和执行业务测试；Codex 在其回传后独立检查文件、Git 和验收证据。本次仅提交并推送当前分析/准备分支，按用户要求不合并。
