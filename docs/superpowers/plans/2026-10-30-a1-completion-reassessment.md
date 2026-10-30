# A1 受限检索重新评估实施计划

> **For agentic workers:** 在当前任务内逐步执行；保留首次失败证据，不自动重试；付费重评须取得本轮授权。

**Goal:** 使受限检索达到可审计的质量结论；仅在完整门禁达标时重新启用新请求检索，否则保持安全回退。

**Architecture:** 原 A1 记录保持不可变。录制器仅在失败时保存白名单诊断类别，不保存供应商正文；新一轮独立录制同一固定 16 条样本。机器回放与 Codex 逐条审定决定是否保留 L1。

**Tech Stack:** Java 21、JUnit、Spring AI、DeepSeek、PowerShell、Testcontainers MySQL。

**Spec:** `docs/modules/ai-ticket-assistant.md` §8.5；原 A1 计划 `docs/superpowers/plans/2026-10-30-a1-evaluation-completion.md`。

## Global Constraints

- 不改动固定样本、原始失败记录、V15 或无关前端工作树。
- 原 A10 失败的具体子因不可由既有证据推知，不得把重新调用结果冒充原请求的诊断。
- 新一轮付费调用取得明确授权后才能运行；逐次检查余额，达到上限或任一调用失败立即停止。
- 不自动重试、不从不同轮次挑选成功侧拼成 16 对；仅完整同轮记录进入门禁。
- 新轮先录 A10 两侧以尽早验证原失败是否再现；通过后再录其他 15 对，最终仍按固定集原顺序回放与审定。
- 真实工单 AI 总开关与检索开关默认关闭，只有完整门禁 `RETAIN_L1` 才讨论开启检索。

## Review Focus

- 供应商异常消息可能含敏感内容：只保存内部白名单诊断码，未知原因记 `UNKNOWN`。
- `STARTED` 后进程中断：不能自动重发。
- 一轮评估某侧失败：保留失败并立即停下，不以另一轮成功覆盖。
- 余额入账延迟：显示差额仅为观察值，不冒充最终账单。
- 人工评分不能仅靠机器结构有效：逐条对照来源、注入文本和本单尚未执行的动作。

---

### Task 1：失败诊断最小化

**Files:** `src/test/java/com/flowdesk/devtools/A1PairedEvaluation.java`、`src/test/java/com/flowdesk/devtools/A1PairedEvaluationTest.java`

- [x] 先写测试：`INVALID_OUTPUT` 的已知解析原因可映射白名单类别，未知异常与任意消息只得 `UNKNOWN`。
- [x] 运行定向测试，确认因缺少诊断实现而失败。
- [x] 实现仅保存 `failureDetail` 白名单码，保持 `failureKind` 和现有调用保护不变。
- [x] 再跑定向测试及 A1 工具/适配器相关测试。

### Task 2：独立重评与证据

**Files:** `target/ai-eval/` 下独立轮次记录；`docs/acceptance/` 下结果摘要。

- [x] 校验原记录和仓库副本哈希一致；两个文件均为 `aa3acc52d28a7cc03d9dc6b744fb657c3a9001f8426144ac087df09d13a4f135`，工作文件已归档至忽略目录。
- [x] 用户本轮授权 ¥3 上限后，从空记录完成固定 16 对 32 次真实请求，均 `OK`，观察余额差额 ¥0.06。
- [x] 离线回放 16/16 机器通过；Codex 对 32 个输出逐条审定并运行决策门禁，结果 `DO_NOT_RETAIN_L1`（提升 0）。
- [x] 已记录旧失败不可溯源、新轮结果、费用观察值及哈希：`docs/acceptance/2026-10-30-ai-a1-reassessment.md`。

### Task 3：收口验证

**Files:** `PROJECT_STATUS.md`、`docs/modules/ai-ticket-assistant.md` 与涉及代码/测试。

- [x] 按 `DO_NOT_RETAIN_L1` 保持新请求检索默认关闭；旧 v5 历史兼容由集成测试覆盖，最终复跑待下项。
- [x] 定向测试 7/7、后端全量 `verify` Surefire 1334/1334 和 Failsafe 227/227 通过；未重新启用检索，本轮未改前端，前端与真实浏览器专项未运行。
- [x] 检查 Git 状态与主题边界；无关 `.ui-craft/` 和前端文档保留，本轮未提交、推送或合并。
