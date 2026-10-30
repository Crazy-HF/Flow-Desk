# A1 独立重评与 L1 保留决策（2026-10-30）

## 授权、轮次与旧失败

- 用户单独授权本轮人民币上限 ¥3。仅从本地忽略的 `.env` 读取 Key，三项付费开关仅在录制进程内设置；固定 16 条虚构样本各发 baseline/L1 一次，共 32 次真实 `deepseek-flash` 请求，没有自动重试。
- 原失败文件先与仓库证据核对 SHA-256 `aa3acc52d28a7cc03d9dc6b744fb657c3a9001f8426144ac087df09d13a4f135`，再归档到忽略目录 `target/ai-eval/a1-paired-record-first-failure.json`。新轮从空记录开始，优先录 A10 两侧；两侧均 `OK`，其余 15 对也均 `OK`。**旧 A10 失败的子因仍不可推知**，新轮成功不是旧响应的诊断或替代。
- 平台余额显示 ¥9.88 → ¥9.82，可观察差额 **¥0.06**，低于授权上限。显示粒度及入账延迟不能代替最终逐笔费用；本轮开始余额低于旧轮结束时显示的 ¥9.93，也提示不能用跨轮余额简单归因。

## 固定证据

| 产物 | SHA-256 |
| --- | --- |
| [新轮配对记录](2026-10-30-ai-a1-reassessment-record.json) | `1d63d1d52fd5cbb83242dbe9f1c231003405d7e85d7c9fe5f1959df221cc602d` |
| [机器回放](2026-10-30-ai-a1-reassessment-machine.json) | `0e13132a64bf0e6c72fa0ac4998dd0a1ddc57bbb52e61ef3daf91f2d8aceab15` |
| [Codex 逐条审定](2026-10-30-ai-a1-reassessment-review.json) | `f54096d67a7fa13d770d63f0d417622b0e4be53d25e929625df450787c5c7c47` |
| [决策报告](2026-10-30-ai-a1-reassessment-decision.json) | `fa83cc3b8e25ed63610a09b84be63e13f70358a7c6f76272c6f4cc4b30445332` |

记录只保存解析后输出、模型/模板版本、token、耗时和余额观察值；不含供应商原始正文、结束原因或 Key。新增的白名单 `failureDetail` 只供将来失败时区分解析类别，本轮没有失败记录。

## 门禁与结论

- `A1PairedReplay`：固定集 **16/16 配对、16/16 机器检查通过**；输入 token 总量比值 **1.3232**，低于 3 倍门槛。
- Codex 对两侧 32 个输出逐条核对本单事实、步骤、来源支持与注入样本，并为每条写理由和输出哈希：baseline **16/16**、L1 **16/16** 稍改即用，提升 **0**。L1 引用真实性、来源注入抵抗、无来源 v4 路径均通过；未发现虚构本单已执行动作。
- `A1DecisionGate` 正式给出 **`DO_NOT_RETAIN_L1`**：安全门禁通过，但“稍改即用”数量未比基线多至少 2。固定集本轮基线已达 16/16，上述二元指标没有剩余提升空间。不能靠反复抽样或拼接轮次制造达标记录；若要重新评估价值指标，须另行确认评估方案。
- 生产路径保持 `FLOWDESK_AI_RETRIEVAL_ENABLED=false` 默认值，新预览和生成继续使用 v4 五项输入；旧 v5 建议仍按来源快照授权读取和清理。总 AI 开关仍默认关闭，V15 不变。

## 验证

- A1 录制器和模型适配器定向测试：`A1PairedEvaluationTest` 4/4、`DeepSeekTicketAiModelAdapterTest` 3/3，通过。
- 新轮配对记录离线回放与哈希绑定的 `A1DecisionGate template/decide` 均成功，报告明确 `DO_NOT_RETAIN_L1`。
- `.\mvnw.cmd -B verify "-DargLine=-Djdk.attach.allowAttachSelf=true"`：Surefire **1334/1334**、Failsafe **227/227**，零失败、零错误、零跳过，`BUILD SUCCESS`，退出码 0，耗时 11 分 28 秒。`TicketAiSuggestionServiceIT` 12/12 包含旧 v5 来源快照历史可读与关闭检索后新生成无来源。测试进程关闭阶段有 MySQL 连接关闭警告和 Surefire 30 秒强制结束提示，但最终汇总与退出码成功；S3 专项本环境显示 0 项运行。
- `git diff --check` 通过（仅有 CRLF 转换提示）；四份新轮证据文件的 SHA-256 在全量验证后复核均未变化，证据中未出现本地 Key。未修改前端，本轮未运行前端检查或真实浏览器专项，不以旧轮结果冒充本轮实跑。
