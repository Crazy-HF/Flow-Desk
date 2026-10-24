# 管理性交接前端验收（2026-10-21）

## 范围与视觉决定

在现有用户管理页上增加交接弹窗与“调整角色”入口；不改列表的已确认构图。弹窗只展示工单编号、状态、当前负责人、候选接替人和原因，不展示工单正文。签名决定是“一张在办工单对应一行接替选择”，管理员能直接检查交接是否完整。

参照物：`02-admin-list-primary.png` 借紧凑的管理操作节奏，不借其业务字段；`01-admin-role-reference.png` 借工具栏按钮层级，不借标签栏；`05-freshservice-ticket-detail.png` 借负责人信息的清晰层级，不借工单详情正文。`03-flowdesk-before-rejected.png` 仅作负面基线。

| Before | After | Why |
| --- | --- | --- |
| 停用有在办工单的 IT 账号只发版本号，服务端返回缺交接方案 | 先查在办清单和逐单候选人，再带版本、接替人及原因提交 | 管理员可在同一流程完成账号变更和交接 |
| 页面没有调用完整角色替换接口的入口 | 选中用户后可调整全部角色，摘掉 IT 角色时进入同一交接流程 | 与后端已交付的替换角色契约一致 |
| 冲突时只有失败提示 | 弹窗保留选择与稳定错误码文案，管理员可取消、刷新列表后重试 | 不让过期版本静默覆盖 |
| 窄屏没有交接布局 | 交接行在窄屏改为单列 | 避免整页横向溢出 |

三抄测试：只显示交接所需的最小工单元数据；仅在停用或摘掉 IT 角色且确有在办工单时要求方案；提交方案携带账号与每张工单的独立版本。这三项由 FlowDesk 的权限与并发契约决定。

## 验证

- `pnpm typecheck`：通过。
- `pnpm lint`（ESLint + Stylelint）：通过。
- `pnpm exec vite build`：通过。`pnpm build` 第一次在 Windows 以 `3221226505` 异常退出；拆开执行类型检查与 Vite 构建均成功。
- `pnpm test:unit --run --maxWorkers=1`：28 套件 / 262 项通过；随后补一项角色交接测试，定向重跑两套件 / 13 项通过。
- 最终本地全量 Vitest 复跑：**28 套件 / 263 项**通过。
- `pnpm test:e2e e2e/admin-handoff.spec.ts`：Chromium 1/1 通过。接口在 Playwright 中模拟；覆盖登录、停用入口、必填校验、候选选择、提交体、状态刷新、1440 与 375 宽度。375 宽度 `documentElement.scrollWidth <= innerWidth`。
- 独立 MySQL/Redis 与后端 JAR 在 18093 成功启动，Flyway 验证 V1～V13。既有 `admin-rbac.spec.ts` 首轮因 Windows 资源错误 `3221226505` 中断；把后端限制为 384 MiB / 2 CPU 并限制前端工作线程后，真实栈复跑 **4/4 通过**（角色权限、用户角色、权限入口及窄屏回归）。当时新增交接弹窗的浏览器用例仍使用模拟接口；独立后端与容器已停止。后续 CI 真实栈结果见下一条。
- 后续新增 `admin-handoff-live.spec.ts`，在 [PR #26 CI run #66](https://github.com/Crazy-HF/Flow-Desk/actions/runs/39520667750) 的 demo MySQL/Redis 上首轮通过：真实停用、工单版本冲突后刷新重试、移除 IT 角色再次交接。该 run 的 `frontend-verify`、`backend-verify`、`core-e2e` 均成功；浏览器全量为 **26 passed / 1 flaky**，既有 `viewport-1920` 首轮在退出后 URL 断言超时，重试通过。本机 Docker Desktop 后来无法启动，故这项真实栈证据取自 CI。
- 最新 [PR #26 CI run #67](https://github.com/Crazy-HF/Flow-Desk/actions/runs/39521245457) 三个 job 均成功；新增真实栈用例仍首轮通过，浏览器全量 **27/27 首轮通过**。PR #26 随后合并为 `fd41981`。

截图已人工查看：桌面弹窗的编号与接替选择同行；375 宽度改为上下排列，按钮、原因输入与关闭入口均在视口内。缩略图的第一落点是工单交接选择；强调色集中在选择焦点和确认按钮。

- [桌面截图](handoff-desktop.png)
- [窄屏截图](handoff-mobile.png)

已知边界：`/admin/user-roles` 原有的单条撤销、批量撤销、清空入口仍可绕过 8.3 交接，后端契约已如实登记；本轮只接入用户管理页的停用与完整角色替换。真实后端交接规则的原验收证据仍是 `docs/acceptance/2026-10-14-admin-handoff.json`（51/51）。
