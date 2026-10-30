# A1 续录停止与受限检索回退（2026-10-30）

## 续录范围与原始证据

- 延续用户此前授权的 ¥3 上限及减少调用要求，从 4 对小样本记录续录 A01～A10。累计发出 28 次模型请求：27 次解析后输出为 `OK`，A10 的 L1 为 `FAILED/INVALID_OUTPUT`。R15、S16 均未发出请求；脚本在第一次失败后停止，没有自动重试。
- [配对记录](2026-10-30-ai-a1-stopped-record.json) SHA-256：`aa3acc52d28a7cc03d9dc6b744fb657c3a9001f8426144ac087df09d13a4f135`；[机器回放](2026-10-30-ai-a1-stopped-machine.json) SHA-256：`5a83e6ba8c2ca138b678dcfa791ebd4a6f6e1c43d926a4d22e4018ced5bac33f`。记录仅保存解析后成功输出和稳定失败类别，不含供应商原始 JSON、结束原因或密钥。
- 平台人民币余额从 ¥9.96 到 ¥9.93，**可观察差额 ¥0.03**，低于授权上限。余额显示粒度与入账延迟不能代替最终账单。

## 门禁结果

- 固定集共 16 对；目前 13 对两侧为 `OK` 且通过机器检查，A10 的 L1 为 `L1_NOT_OK`，R15/S16 为 `MISSING_RECORD`。已成对样本输入 token 比值为 1.3021，仅反映这 13 对。
- A10 的 `INVALID_OUTPUT` 是适配器拒绝供应商响应的稳定类别；现有录制没有足够数据判断是截断、JSON 结构还是其他具体原因。保留原失败，不覆盖或重发后冒充首次通过。
- 原 A1 门禁要求 16/16 机器通过，再进行 16 对逐条人工评分。该门禁本轮已无法通过，`A1DecisionGate template/decide` 不执行，不发布完整 16 对的可用性提升或最终费用结论。继续调用 R15/S16 不会改变已发生的 A10 首次失败，因此停止付费评估。

## 回退范围

- 新预览与新生成默认不召回历史来源，模型输入恢复五项字段的 v4 路径；`FLOWDESK_AI_RETRIEVAL_ENABLED` 默认为 `false`。真实工单环境总 AI 开关仍默认为关闭。
- 已保存的 v5 建议继续按原来源快照和授权规则读历史；到期清理仍级联删除来源快照。未修改 V15 迁移或既有数据。
- 这是**未通过门禁后的保守回退**，不是完整 A1 的 `RETAIN_L1` / `DO_NOT_RETAIN_L1` 人工决策报告。若日后重新评估，须先明确如何处理首次失败、保留本证据，并另行授权付费测试；不得直接把开关打开视为验收通过。

## 回退验证

- `.\mvnw.cmd -B "-Dtest=A1EvaluationFixtureTest,A1PairedEvaluationTest,A1PairedReplayTest,A1DecisionGateTest" "-Dit.test=TicketAiSuggestionServiceIT" verify`：A1 Surefire 12/12、真实 MySQL Failsafe 12/12，0 失败/错误/跳过，`BUILD SUCCESS`。
- `.\mvnw.cmd -B verify "-DargLine=-Djdk.attach.allowAttachSelf=true"`：全量 Surefire 1333/1333、Failsafe 227/227，0 失败/错误/跳过，`BUILD SUCCESS`（10 分 57 秒）。日志末尾有测试进程退出阶段的连接拒绝与 Surefire 30 秒强制结束提示；最终 Maven 退出码为 0。S3 专项在本环境显示 0 项运行。
- 新增集成用例先生成带来源快照的建议，再在服务层关闭检索，验证预览为空、新模型输入无来源、旧建议和新建议均可读取、来源快照仍存在；既有保留期测试覆盖快照级联清理。
- 本轮未运行前端检查与真实浏览器专项；付费录制与集成测试均不能证明真实工单环境已启用 AI。
