# AI 评估门禁与受限检索 Implementation Plan

> **For agentic workers:** 后续经用户指令开始实施时使用 `superpowers:executing-plans`，逐项核对本计划。用户负责 ServiceImpl 业务编排代码；Codex 负责测试及经授权的基础代码。未经本次交接授权，不提交、推送或合并。

**Goal:** 在现有单轮 AI 工单建议之上，先建可复现评估基线，再试点有限的历史工单检索，并用质量与安全 gate 决定保留或回退。

**Architecture:** 维持 `ticket` 模块的 Controller → application service/port → infrastructure/Mapper 分层。来源只能通过受授权的召回查询进入 `TicketAiModelPort.Input`，DeepSeek 适配器仍是唯一外发边界。建议和来源快照独立于普通工单时间线。

**Tech Stack:** Java 21、Spring Boot 3.5.16、Spring AI 1.0.9、DeepSeek `deepseek-flash`、MyBatis、MySQL 8.4/Flyway、Vue 3、JUnit/Testcontainers、GitHub Actions。

**Spec:** `docs/modules/ai-ticket-assistant.md` §8；现有接口见 `docs/api-design.md` §7.8。

## Global Constraints

- 顺序固定为 A0 → B-L1 → A1 → 决策，每步 gate 失败即停。
- 当前生产提示词 `v4`、模型 `deepseek-flash`、问题/步骤各 1～10 条；A0 不暗改契约。
- V1～V14 已发布，不修改；V15 只有在来源表与共享权限获确认后才新增。
- 只用虚构或脱敏数据进行付费验证；真实工单环境的 `FLOWDESK_AI_ENABLED` 继续默认关闭。
- 单次确认至多一次模型调用；`requestId` 幂等、日额度、五态请求机和 30 天清理不得回归。
- 用户写 ServiceImpl 业务编排；基础件及测试按 `AGENTS.md` 分工推进。用户完成对应业务实现前，不预写依赖该实现的测试。
- 本计划已获用户授权按步骤实施；2026-10-29 另获授权仅提交并推送主题分支，不合并 `main`。真实 A1 付费调用仍未获授权或运行。

## Review Focus

1. 转交后，新负责人读到含旧来源自由文本的历史建议时是否越权。
2. 同一 `requestId` 的来源集合在两次请求之间变化时是否错误地复用旧结果。
3. 检索筛选与可见性判断之间的竞态是否导致外发已不可见来源。
4. 来源中含提示词注入、裸工单号、伪造 `[S7]` 时是否写入错误引用。
5. 无 `PROCESS` 正文、空召回或清理子表时是否改变现有生成/保留行为。

## 文件与职责

| 文件 | 后续职责 |
| --- | --- |
| `src/test/resources/ai-eval/`、`src/test/java/com/flowdesk/ticket/ai/` | 虚构 fixture、录制响应、判定器、回放报告 |
| `src/test/java/com/flowdesk/devtools/DeepSeekBatchEvaluation.java` | 显式付费 record 模式；预算与余额检查，不进入默认测试 |
| `.github/workflows/ci.yml` | 无 Key 的 replay gate，上传报告 |
| `src/main/java/com/flowdesk/ticket/mapper/TicketMapper.java` 或独立来源 Mapper | 同类、终态、可见性过滤和最新 `PROCESS` 摘录；核对 `EXPLAIN` |
| `src/main/java/com/flowdesk/ticket/application/port/TicketAiModelPort.java` | 有界来源输入契约 |
| `src/main/java/com/flowdesk/ticket/application/service/impl/TicketAiSuggestionServiceImpl.java` | 用户负责：召回授权、调用前复核、来源指纹、保存与清理编排 |
| `src/main/java/com/flowdesk/ticket/infrastructure/DeepSeekTicketAiModelAdapter.java` | v4 空来源路径、非空来源 v5 提示词、标签解析 |
| `src/main/resources/db/migration/V15__add_ticket_ai_suggestion_source.sql` | 经裁决后保存来源快照与关联 |
| `src/main/java/com/flowdesk/ticket/application/result/TicketAiSuggestionResult.java`、`docs/api-design.md` | 经裁决后返回受授权的结构化来源引用 |
| `frontend/src/views/work/TicketAiSuggestionPanel.vue`、`frontend/src/api/ticketAi.ts` | 来源外发预览、引用展示及不可见/空来源状态 |

## 0. 开工裁决

- [x] 召回范围：只取当前操作者按工单详情等价规则可见的同类已结工单。
- [x] 转交语义：读取者若无权看某建议的任一来源，隐藏整条建议；其他建议正常显示。
- [x] V15：保存实际送模型的来源 ID、标签、标题和摘录快照；读取时仍逐源重查权限。
- [x] 演示外发只用虚构或已脱敏来源，负责人看完来源预览再确认；真实工单环境仍关闭。
- [x] L2/L3 暂不做；A1 的“稍改即用”由 Codex 逐条评分并作为最终保留门禁。

**Gate 0：** 六项口径已写入模块文档 §8.2 和 API 契约 §7.8；A0 的离线回放证据见下节，B-L1/A1 的运行 gate 仍未通过。

## 1. A0：可复现的当前基线

- [x] 将 A01～A10 输入及评分依据固定在 `src/test/resources/ai-eval/`；SHA-256 锁定解析后输出，不伪装成原始供应商响应。
- [x] 建立结构反例/正例：缺字段、非数组、空条目、超长、`LENGTH`、否定句和历史引文；虚构本单已执行事实由逐条人工审定并可否决 gate。
- [x] 实现零网络 replay 报告（逐样本、失败原因、结构率、人工评分），明确 `PARSED_OUTPUT_ONLY` 证据级别。
- [x] 在 CI 的 backend-verify 加显式 replay 步骤并上传 JSON/Markdown；默认 `mvn verify` 不会调用 DeepSeek。远端 CI 尚未运行。
- [x] 手动付费工具加双开关、AI 启用、Key、余额与显式剩余预算保护；本轮未运行 record，也未采集原始供应商响应。

**Gate A0：** 10/10 录制样本结构通过、0 条经审定的虚构本单已执行事实、同版本 replay 报告可复现；Codex 逐条评分和理由齐全。

**A0 本轮判定**：基于仓库中已保存的 `v4` 解析后输出，10/10、0 条、9/10；离线报告两次生成字节一致。该 gate 只准许进入 B-L1 实施，不代表已取得原始供应商响应或完成新的付费质量评估。

## 2. B-L1：来源召回与引用

- [x] 在真实 MySQL 上验证候选 SQL：同类、三种终态、`ended_at`、排除当前工单、最多 5 条、最后一条非空 `PROCESS`，并套用已裁决的可见性/共享谓词。`EXPLAIN` 证据见 `PROJECT_STATUS.md` 顶部；本次规模下未新增索引。
- [x] 加来源输入和服务端分配的 `[S1]`～`[S5]`；总摘录 ≤ 2000 字、单条 ≤ 500 字。不向模型发真实工单号、数据库 ID、附件或人员信息。
- [x] 增加只读来源预览接口，按生成权限返回来源标题/摘录及 `sourceDigest`，不占额、不调用模型；前端展示实际来源。生成请求带摘要，服务端在占额前重新召回/授权，来源集合或摘录与预览不一致就拒绝并要求重确认。
- [x] `ServiceImpl` 编排已获用户逐轮直接写入授权：旧 `requestId` 不按当前召回集合重发模型；新请求占额前固定来源快照，模型调用后复核工单与来源，通过才保存建议和来源；来源变动转 `STALE`，不重试模型。
- [x] 空来源走 v4 旧路径；非空来源才启用 v5。适配器严格校验合法标签与裸号泄露；无效输出走现有 `AI_INVALID_OUTPUT`，不新增供应商重试。
- [x] 新增 V15；来源子表与建议同事务写入，级联清理已在真实 MySQL 验证。历史列表与计数在 SQL 层排除任何来源不可见的**整条**建议，成功请求的幂等重放也执行同一来源可见性闸门。无来源旧建议保持原读法。
- [x] 用户完成编排后，由 Codex 补 Web、服务、真实 MySQL、适配器与前端专项测试，覆盖 Review Focus 中的权限、竞态、注入、幂等和清理。另有 `TicketAiBrowserAcceptanceManual` + Playwright 在临时 MySQL/Redis、真实 HTTP 和固定模型替身上跑通来源预览→删敏生成→刷新后历史读取，**1/1**；不等同供应商质量验收。

**Gate B-L1：** 转交授权矩阵通过；空召回的**请求入参与解析路径**与 v4 一致；标签可映射、裸号无泄露；单次调用、额度和幂等不回归；`EXPLAIN` 可接受。模型回答是随机的，不要求与 A0 答案逐字段相同。

## 3. A1：配对质量评估

- [x] 在原 10 条外增加 S/R/N/C 四类虚构样本，总计 16 条，并固定来源集及干扰项。`a1-l1-cases.json` 复用 A01～A10 的原五项输入，另增 S11/R12/N13/C14/R15/S16；SHA-256 由 `A1EvaluationFixtureTest` 锁定。此项只固定输入，不代表已完成模型配对调用。
- [ ] 同一输入分别跑无检索与 L1；记录模型、模板、解析器版本、合法引用、输入 token、耗时和余额差额。**代码已备、真实运行待做**：`A1PairedEvaluation` 每次只录一条样本的一侧，三重显式开关与预算检查后才允许调用；`STARTED` 先落盘，任何已有记录都拒绝自动重调。保存解析后输出、模型/模板版本、token、耗时和余额前后值；原始供应商 JSON 与结束原因未被适配器返回，报告证据级别为 `PAID_PARSED_OUTPUT`。离线 `A1PairedReplay` 核对配对输入、结构、标签、裸号、空召回模板及 token 比率。**尚未调用供应商**。
- [ ] 自动判结构、标签、空召回和裸号；Codex 逐条审定来源是否支持回答、是否服从注入、步骤能否执行，并附理由给出“稍改即用”评分。机器判定代码已写；无真实 A1 输出可判，人工审定与总评分待做，机器报告始终不自动置 `gateA1Passed=true`。

**人工门禁代码已备、真实评分待做**：`A1DecisionGate` 在机器检查 16/16 通过后生成绑定两侧输出 SHA-256 的审阅模板；要求 Codex 逐条填写可用性、虚构本单动作、可执行性、引用真实性、注入服从情况与理由。输出逐条指标，只有审定的来源安全、注入、空召回与必需引用通过，且可用性提升 ≥2、平均输入 token 倍率 ≤3，才给出 `RETAIN_L1`；报告不自动改变生产路径。

**Gate A1：** 审定引用真实性 100%、注入抵抗 100%、空召回成功 100%；Codex 逐条评分、配对报告和实际费用记录齐全，方可进入保留决策。

## 4. 保留或回退

- [ ] 对同一固定样本计算“稍改即用”提升及平均输入 token 倍率；全部安全 gate 通过且提升 ≥ 2、token 增幅 ≤ 3 倍才保留 L1。
- [ ] 未达标则关闭/回退检索生成路径，保留评估证据；已发布 V15 不改写，旧建议的读取、授权和清理继续兼容。
- [ ] 更新 `PROJECT_STATUS.md`、模块文档和 API 文档，记录实测、未跑项与最终决策。若用户另行授权完整 Git 交接，再按 `AGENTS.md` 检查、提交、推送及合并。
