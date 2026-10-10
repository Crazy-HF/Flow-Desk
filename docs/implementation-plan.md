# FlowDesk 开发路线：求职 MVP 与完整版

## 1. 文档目标与当前结论

本文把 FlowDesk 拆成两个交付层级：先完成可演示、可测试、可讲清楚的**求职 MVP**，再按需要扩展为**完整版**。当前只执行 MVP 路线；完整版是后续 backlog，不得因为数据库结构已经预留就提前实现。

截至 2026-10-10（完整版 backlog 第 1 项与两阶段撤销交付后）：

- `M0` 工程底座已完成并合并（PR #4）。
- P0 编译、测试、安全基线和端口问题已修复，当前无前置阻塞。
- **阶段 1 Auth 身份入口**（`TASK-010`、`TASK-011`）已完成，经 PR #5 合并 `main`。
- **前端外壳与页面骨架**已完成，经 PR #6 合并 `main`。
- **第 3 步 完整动态 RBAC**（`TASK-055`～`TASK-062`，本文件 9.1）已完成：后端四组接口与用户管理八个端点、`V5` 迁移、管理端五页（角色 / 权限 / 用户角色授权 / 角色权限授权 / 用户管理）、`frontend/src/views/admin/` 页面与真实闭环 E2E 全部交付；验收标准第 4 条的四条手工真实栈链路已于 2026-09-28 在 `local` profile 真实栈上执行并通过（逐条 `traceId` 见 `docs/modules/rbac.md` 11.2），经 PR #8 合并 `main`（合并提交 `f15468c`）。
- **阶段 3 IT 处理闭环已完成**（已经 [PR #10](https://github.com/Crazy-HF/Flow-Desk/pull/10) 合并 `main`；工作分支 `flow-desk/ticket-it-flow`）。步骤① IT 领取、步骤② 追加处理记录、步骤③ 提交解决结果与员工确认均已实现并**通过真实栈验收（77/77，2026-10-06）**，验收证据 `docs/acceptance/2026-10-06-stage3-claim-process-resolution-confirm.json`；步骤④ IT/员工页面与端到端主链**已于 2026-10-06 完成并在真实栈跑通**（`frontend/e2e/ticket-it-flow.spec.ts`，报告与截图见 `.ui-craft/reviews/2026-10-06-ticket-it-flow/`），阶段 3 四步全部完成并已合并。阶段 2（`TASK-020`～`TASK-023-MVP`）已验收并经 PR #9 合并 `main`（最终证据 `docs/acceptance/stage2-closeout-20260929.json`）。
- **完整版 backlog 第 1 项「完整工单状态机」已交付**：片 A～片 D 经 [PR #14](https://github.com/Crazy-HF/Flow-Desk/pull/14)～[PR #17](https://github.com/Crazy-HF/Flow-Desk/pull/17)，两阶段撤销规则变更经 [PR #18](https://github.com/Crazy-HF/Flow-Desk/pull/18)；前端**页模板与页面契约**经 [PR #19](https://github.com/Crazy-HF/Flow-Desk/pull/19) 合并 `main`。当前分支 `flow-desk/attachments-and-relations` 推进 backlog 第 2 项「附件与关联」。
- 第 1 节原先"不提供角色、权限及授权关系的在线 CRUD"这一表述已被 2026-09-21 的确认取代：三种内置角色仍是权限基线，同时在 9.1 范围内开放了动态 RBAC 与用户管理的在线维护。

## 2. 两个版本的边界

| 能力 | 求职 MVP | 完整版 |
| --- | --- | --- |
| 登录、刷新、退出、当前身份、本人改密 | 必须 | 保留并强化 |
| 角色模型 | 固定三角色，可为用户分配一个或多个内置角色 | 可评估自定义角色、权限和授权关系管理 |
| 工单核心链路 | 创建、列表、详情、领取、处理、提交解决、员工确认 | 补充、退回、撤销、转交、异常关闭、超时等完整状态机 |
| 时间线与并发 | 必须；关键动作有记录，领取和状态变更防并发覆盖 | 覆盖全部动作和更完整故障矩阵 |
| 附件和工单关联 | 不做 | 本地受控附件、后续/重复工单关联、文件对账 |
| 管理端 | 在线 RBAC 已于 2026-09-21 确认实施并已完成交付（先于阶段 2），含用户管理页（2026-09-24 登记）；分类管理已于 2026-09-28 提前实施（见 9.2）；管理性交接仍不做 | 用户、分类、管理性交接 |
| 数据概览 | 不做 | 权限范围内 dashboard |
| 自动任务 | 不做 | 待确认/待补充超时和附件清理 |
| AI、消息队列、微服务 | 不做 | 仍非默认范围，必须另行确认 |

MVP 的价值不是“功能数量少”，而是用一条完整主链证明：认证安全、RBAC、事务、状态流转、并发控制、时间线、前后端协作和自动化测试。

## 3. MVP 固定权限模型

### 3.1 三种角色

- `EMPLOYEE`：创建工单、查看本人提交的工单、确认处理结果。
- `IT_SUPPORT`：查看待领取队列、领取工单、记录处理过程、提交解决结果。
- `SYSTEM_ADMIN`：作为系统维护身份保留；MVP 不实现角色/权限 CRUD，也不默认授予普通工单内容访问权。

用户可以同时拥有多个内置角色，但多角色不会绕过资源关系、当前负责人、工单状态或版本校验。角色和权限编码固定，数据库表保留扩展性不等于 MVP 开放动态 RBAC。

### 3.2 MVP 权限编码

MVP 实现和演示优先使用：

- `TICKET_CREATE`
- `TICKET_VIEW_OWN`
- `TICKET_REQUESTER_ACTION`
- `TICKET_VIEW_QUEUE`
- `TICKET_CLAIM`
- `TICKET_VIEW_PARTICIPATED`
- `TICKET_PROCESS`

其余已有权限编码可保留在迁移中作为完整版预留，但不得因此增加 MVP 页面或接口。`RBAC_MANAGE` 不属于 MVP 主链；**但已于 2026-09-21 经用户确认提前实施（先于阶段 2），阶段设计与任务拆分见第 9.1 节。**

### 3.3 决策说明：为什么 MVP 固定三角色

- **选择**：固定三角色和权限映射，把开发时间集中在认证、资源级授权、工单状态机和并发一致性。
- **未选方案**：MVP 同时实现动态角色、动态权限和授权关系后台。
- **取舍**：MVP 暂时不能让管理员创建新角色，但减少了管理接口、保护规则、审计、会话撤销和大量测试成本；现有多对多表仍允许完整版扩展。
- **面试表达**：这是按风险和展示价值主动收敛范围，不是把授权写死在前端。后端仍基于稳定权限编码鉴权，并继续校验资源关系、状态和版本。

**2026-09-21 变更**：本节"未选方案"里的"同期实现动态角色、动态权限和授权关系后台"已由用户确认提前实施（先于阶段 2）。范围以 `docs/api-design.md` 8.2.1 为上限，任务拆分与验收标准见第 9.1 节；固定三角色与资源级授权仍是基线，不因本次变更而放宽。

## 4. MVP 总体顺序

```text
M0 工程底座（已完成，PR #4）
  → 阶段 1 Auth 身份入口（已完成，PR #5）
  → 前端外壳与页面骨架（已完成，PR #6）
  → 第 3 步 完整动态 RBAC（2026-09-21 确认提前实施，见 9.1；已完成，PR #8）
  → 阶段 2 员工创建与查询（已完成，PR #9）
  → 阶段 3 IT 处理闭环（已完成，PR #10）
  → 阶段 4 MVP 验收与求职展示收口（已完成，PR #11～#13）
  → 完整版 backlog 第 1 项「完整工单状态机」（已交付，PR #14～#18）
  → 完整版 backlog 第 2 项「附件与关联」（进行中，分支 flow-desk/attachments-and-relations）
```

每个阶段必须先完成后端业务规则和自动化测试，再接页面。阶段完成后同步 `PROJECT_STATUS.md`，经用户确认才执行提交、推送或 PR。

## 5. 阶段 1：Auth 身份入口（已完成，PR #5 合并）

目标：三类演示用户能够安全登录；前端刷新后可恢复身份；退出或改密后旧会话失效。

### TASK-010：后端认证、会话和密码安全

依赖与现状：

- 基于 `common/config/FoundationSecurityConfiguration` 的公共无状态安全链扩展，不重复创建互相竞争的过滤链。
- IAM 仅保留实体和 Mapper 骨架；Auth 通过 IAM 查询用户、密码摘要、角色和权限。
- `application.yml`、`application-test.yml` 已保留 `jwt.*` 与 `auth.*` 配置键。
- 固定三角色从迁移/测试夹具读取；本任务不实现 IAM 管理接口或动态 RBAC。

按以下切片实施，每个切片先测试再进入下一个：

1. **配置与密码**：实现 `JwtProperties`、`AuthProperties`、Argon2id `PasswordEncoder`，验证配置绑定和密码匹配。
2. **登录**：实现认证查询、统一失败响应、最小用户快照；错误账号、错误密码和停用账号都返回 `AUTH_INVALID_CREDENTIALS`。
3. **会话与令牌**：实现 15 分钟 HS256 Access Token、7 天 Refresh Token、Redis 会话和 Refresh 摘要索引；Redis 不保存 Refresh 原文。
4. **请求认证**：Bearer 解析、JWT 验签、Redis 会话有效性校验，将角色和权限快照写入 Spring Security 上下文。
5. **刷新与退出**：Refresh Cookie 轮换、旧 Token 重用检测、Origin 白名单、幂等退出和 Cookie 清理。
6. **当前身份与改密**：实现 `/auth/me` 和本人改密；改密成功必须撤销该用户全部会话。

接口范围：

| 方法与路径 | 结果 |
| --- | --- |
| `POST /fd/v1/auth/login` | 返回 Access Token 和最小身份信息，写入 HttpOnly Refresh Cookie |
| `POST /fd/v1/auth/refresh` | 轮换 Refresh Token，返回新 Access Token |
| `POST /fd/v1/auth/logout` | 撤销当前会话并清除 Cookie，重复调用幂等 |
| `GET /fd/v1/auth/me` | 返回当前用户、固定角色编码和权限编码 |
| `POST /fd/v1/auth/change-password` | 校验原密码、更新摘要并撤销全部会话 |

最低测试证据：

- 单元测试：密码、JWT、Refresh 摘要/轮换规则、Cookie 属性。
- Web 测试：五个接口的成功、400、401 和来源校验；响应与日志不泄露密码、Token、Cookie、密钥。
- Redis 集成测试：TTL、轮换、旧 Token 重用、退出、按用户撤销。
- 回归：`./mvnw.cmd test` 全绿；需要真实容器的测试进入 `verify` 生命周期。

完成定义：接口契约、安全失败路径和 Redis 会话行为有自动化证据；不包含用户管理、角色管理或任何工单代码。

### TASK-011：Vue 登录外壳与身份恢复

- 实现 `/login`、最小应用布局、Pinia 内存身份、Axios Bearer 注入、单次刷新协调、路由守卫、退出和本人改密。
- Access Token 不写入 localStorage、sessionStorage 或 Cookie；Refresh Token 对 JavaScript 不可见。
- 刷新失败清空内存并回到登录页；无权限菜单不展示，但后端仍是最终授权边界。
- Vitest 覆盖权限判断和并发刷新；Playwright 覆盖登录、刷新恢复和退出。

阶段 1 验收：`EMPLOYEE`、`IT_SUPPORT`、`SYSTEM_ADMIN` 三类演示账号均可登录并得到正确角色/权限；浏览器刷新可恢复；退出和改密后旧会话不可继续使用。

**阶段 1 已完成并验收**（2026-09-19 真实栈验证，后端 53 单元/Web + 17 集成、前端 22 单元 + 7 E2E 全绿），经 PR #5 合并 `main`；`auth`、`iam` 生产代码随后于 2026-09-23 按 `docs/technical-architecture.md` 5.1 完成应用层重构。

## 6. 阶段 2：员工创建与查询（当前阶段）

目标：员工可创建无附件工单，并查看自己的列表、详情和不可变时间线。

### TASK-020：查询与可见性基础

- 实现启用分类选项和工单列表的 `own`、`queue`、`assigned`、`participated` 显式 scope。
- 后端同时校验权限、资源关系和分页/排序白名单；无权访问统一返回 `404/TICKET_NOT_FOUND`。
- 详情返回 `allowedActions`，前端只据此展示按钮，不自行推导业务权限。

### TASK-021-MVP：幂等创建

- 员工创建标题、描述、分类、优先级和 `submissionKey`；不含附件和工单关联。
- 工单快照与首条时间线同一事务写入；提交人关系由 `ticket.requester_id` 表达，不向只记录历史 IT 负责人的 `ticket_participant` 写入提交人（2026-09-28 用户确认）。
- 同一用户重复提交相同 `submissionKey` 只能产生一张工单。

### TASK-022-MVP：详情与时间线

- 返回当前快照、允许动作和按 `sequenceNo` 稳定排序的不可变记录。
- 不暴露数据库内部主键、密码信息或无关用户资料。

### TASK-023-MVP：员工页面

- 实现工单列表、新建和详情页；支持创建、刷新查询和错误提示。
- 测试登录 → 创建 → 列表 → 详情的完整路径。

阶段 2 验收：员工重复点击不会制造重复工单，且只能查看自己的工单；创建结果和时间线可在刷新后从后端恢复。

### 6.1 阶段 2 小步实施（2026-09-28 用户确认）

已确认（2026-09-28 最新分工）：基础 domain、Controller、Mapper、Command/Query/Result、服务接口由 Agent 直接写入；ServiceImpl 业务逻辑先用文字和流程图说明，再提供完整代码供用户编写，不直接写入。本阶段按用户指示不新增测试类，使用编译和真实栈验收。沿用现有 Command/Query/Result 和 Mapper 约定，不修改历史迁移。每步通过对应验证再继续；Git 提交与交接另需明确授权。

- [x] ① 启用分类选项（2026-09-28 编译与真实接口验收通过；按用户指示不新增测试）：`GET /fd/v1/categories/options`，允许 `TICKET_CREATE` 或 `TICKET_PROCESS`；仅返回启用分类的 `id/name`，按 `sort_order ASC, id ASC` 排序，无数据返回空列表。
- [x] ② 创建基础（2026-09-28 真实栈验收通过）：校验标题、描述、分类、优先级和 UUID；提交人来自认证上下文；同一事务插入工单与创建记录。验收：`PENDING`、`version=0`、`record_seq=1`，异常时两表均回滚。
  - **编号方案已确认并实现（2026-09-28）**：`FD-yyyyMMdd-001`，按北京时间每日递增，与工单 ID 无关；V6 每日序号表 + 原子 upsert，在创建事务中领取序号并直接插入正式编号。编译及真实 MySQL 临时库验证通过（8 路并发不重复、回滚、第二天从 1、超过 999、旧编号回填）；未新增测试类。
  - **本轮真实栈验收**：用户已补齐认证适配器组件注册；在 8082 启动 local 服务（V6 自动迁移成功）。合法创建 201/`FD-20260928-001`；401/403；空白、超长标题/描述、非法 UUID/优先级、停用/不存在分类共 8 例 400；查库确认提交人、PENDING/version=0/record_seq=1 和首条 CREATE 记录；定向临时触发器注入记录写入失败，工单及序号均回滚，未写 participant。临时触发器、工单/记录/序号已清理。
- [x] ③ 创建幂等（2026-09-28 功能验收通过）：使用已有 `(requester_id, submission_key)` 唯一约束；同用户同键返回首次结果。用户已写入 ServiceImpl，编译与真实栈功能校验通过：8 路同键并发全 201，仅一张工单/一条记录/序号增加一次；顺序重试、用户隔离、UUID 规范化、首次结果稳定性、分类停用后重试、编号冲突不误判及回滚均通过。临时数据已清理。同轮用户已删除 create 方法多余的外层 `@Transactional`，重新编译通过；仍有未使用 import 可清理，Agent 未改写 ServiceImpl。本轮先解释事务机制，不提前开始步骤④。
- [x] ④ 我的列表（2026-09-28 真实栈验收通过）：先实现 `REQUESTED_BY_ME`，复用分页信封，固定排序编码、稳定 ID 次序、合法筛选；当前用户由后端确定。验收：不返回他人工单或正文。
  - 基础代码由 Agent 写入：TicketQuery、scope/status/priority/sort 枚举、列表与摘要 Result、TicketListRow SQL 投影、TicketQueryService、GET Controller 和分页 Mapper。Query 将契约 page/size 映射到现有 PageQuery；时间范围含起止端点；关键词按字面子串搜索，转义 LIKE 通配符；列表分类摘要保留停用分类。ServiceImpl 在对话提供、不写入，待用户补齐后真实栈验收；此前缺少查询服务 Bean，不能启动完整应用。
  - **基础验证（2026-09-28）**：`./mvnw.cmd -B -DskipTests compile` 通过；临时 JShell 探针验证 WebDataBinder 的 page/size、多值状态/优先级、带时区时间绑定，以及 MyBatis 四种排序动态 SQL 生成。未新增测试类；尚未运行 GET 真实接口验收。ServiceImpl 计划使用只读事务协调分页 count/items 查询。
  - **真实栈验收（同日接续）**：用户已完成查询 ServiceImpl；8082 local 启动与编译通过。定向临时数据验证本人行/总数隔离、最小字段与停用分类展示、四种排序/同时间 ID 次序、分页/超出末页、组合筛选、带时区时间边界、LIKE 字面通配符、伪造用户 ID 不改变范围、空集通过；13 例非法/缺少/未支持参数 400，401/403 通过。临时工单清理，验收用服务停止。用户当前使用普通 @Transactional（不是 readOnly），功能不受影响；下一步给出的 page 方法采用 readOnly。
- [x] ⑤ 其他范围（2026-09-29 真实栈验收通过）：补齐 `PENDING_QUEUE`、`ASSIGNED_TO_ME`、`PARTICIPATED_BY_ME` 的权限与资源关系验证，不增加 IT 写动作。
  - **最新验收**：分类六端点与工单四范围在真实 MySQL/Redis/8082 完整复跑，144 项请求/断言、0 失败；覆盖权限、8 路分类并发、当前/历史引用保护、启停联动、范围隔离/EXISTS/终态负责人、排序/筛选/UTC/LIKE/分页/非法参数。临时数据全部清理，证据 `docs/acceptance/2026-09-29-category-ticket-scopes.json`。下述 09-28 待验收为历史记录。
  - **当前实现状态（2026-09-28 提交检查）**：用户已补齐分类服务六项方法，生产代码编译通过；步骤⑤ page 统一查询已写入，使用只读事务和 selectScopedPage。真实栈验收待执行；详情只有预备 Mapper/Result/投影/权限适配，未接入 Controller 或服务接口。
  - Controller 已写 scope 精确权限表达式；Mapper 已写四范围分支（EXISTS 防止重复分页，缺少范围 WHERE 1=0）及队列默认排序；ServiceImpl 已接通 selectScopedPage。当前编译通过，SQL/权限探针属于此前检查记录，步骤⑤仍待真实接口验收。
- [x] ⑥ 详情与时间线（2026-09-29 后端真实栈验收通过）：外部 `ticketNo` 定位；共同校验可见性；无权与不存在统一 `404/TICKET_NOT_FOUND`；时间线按 `sequenceNo` 正序分页。阶段 2 没有已实现的工单写动作，`allowedActions=[]`；后续阶段实现动作时再开放，不能展示不可调用的操作。
  - **时间线已验收**：compile 通过，111 项真实栈请求/断言、0 失败，覆盖 15 种类型/字段白名单/系统操作者/UTC/权限/排序/分页/空集/参数；临时数据和 Redis 会话清理、日序号不变，证据 `docs/acceptance/2026-09-29-ticket-timeline.json`。下方详情刚验收时“时间线未实现”为历史状态。附件与关联不属于本阶段已实现的入口。
  - **详情已验收（2026-09-29）**：compile 与真实 MySQL/Redis/HTTP 验收通过，121 项请求/断言、0 失败；覆盖权限和关系、404/401、全部详情字段/UTC/最小暴露、停用分类。临时用户/会话/分类/工单清理完毕，证据 `docs/acceptance/2026-09-29-ticket-detail.json`。时间线未实现，本步骤保持未完成。
- [x] ⑦ 员工页面实现与验收（2026-09-29）：创建、列表、详情、时间线及重试提交键已验证；1440/375 截图已查看，无整页横向溢出。报告见 `.ui-craft/reviews/2026-09-29-tickets/report.md`。
- [x] ⑧ 阶段验收（2026-09-29，通过）：四项收尾全部完成。
  - **脚本闸门全部通过**：后端 verify 394 单元/Web + 88 集成；前端 typecheck、lint、build、124 单测与全套 15 E2E。复用脚本原始运行中已通过的 verify/typecheck/单测证据；修复窄屏 CSS 后重新跑完整 lint/build/E2E。最后的 traceId/截图等待调整又定向通过 2 项 E2E。原始 FAIL 不改写；各步来源与最终 PASS 见 `docs/acceptance/stage2-closeout-20260929.json`。
  - **真实栈三项证明**：双击时仅一次创建请求、同一 submissionKey、列表一行/创建记录一条，SQL 每个验收工单各一单一记录；第二个 EMPLOYEE 本人列表空集、直访详情 `404/TICKET_NOT_FOUND`；刷新后身份、详情和时间线恢复。带 traceId 的运行证据见 `.ui-craft/reviews/2026-09-29-tickets/runtime-evidence.json`。
  - **清理已执行**：`docs/acceptance/stage2-cleanup-2026-09-29.sql` 删除保留前缀的临时工单、记录、参与者、用户关系、用户、分类及本次日序号；会话同步清理。`stage2-cleanup-evidence.json` 证明分类 5、工单/记录/参与者/日序号 0、用户/角色 3、权限 14，孤儿关系 0。
  - **证据与截图已同步**：PROJECT_STATUS、README、AGENTS 与本步骤一致；1440/375 创建/列表/详情/分类截图以及隔离 404 截图已查看，报告列出路径。分类管理真实 CRUD 也通过。
  - **范围与交接**：只完成阶段 2，不启动阶段 3；用户授权前不提交、推送、创建或合并 PR。HTTP 切片证据与构建/浏览器/数据库证据各自保留，不能互相替代。
请求口径：无附件 MVP 仍使用 `multipart/form-data`，`ticket` 部分为 `application/json`；不传 `files` 或 `sourceTicketNo`。原 API 的附件与关联能力属于完整版，不在本阶段实现。

**步骤①文件与接口（本轮只指导用户创建生产文件）**：

- `src/main/java/com/flowdesk/category/domain/TicketCategory.java`：映射既有 `ticket_category` 的七列；自增 `Long id`、`String name/status`、`Integer sortOrder`、`LocalDateTime createdAt/updatedAt`、`Long version`。
- `src/main/java/com/flowdesk/category/mapper/TicketCategoryMapper.java`：`@Mapper`，继承 `BaseMapper<TicketCategory>`。
- `src/main/java/com/flowdesk/category/application/result/CategoryOptionResult.java`：record，仅 `Long id, String name`。
- `src/main/java/com/flowdesk/category/application/service/CategoryService.java`：`List<CategoryOptionResult> options()`。
- `src/main/java/com/flowdesk/category/application/service/impl/CategoryServiceImpl.java`：查询 `status=ENABLED`，按 `sortOrder/id` 升序并转换最小结果。
- `src/main/java/com/flowdesk/category/controller/CategoryController.java`：GET options，返回 `R<List<CategoryOptionResult>>`；`hasAnyAuthority('TICKET_CREATE','TICKET_PROCESS')`。
- Agent 测试：`src/test/java/com/flowdesk/category/application/service/impl/CategoryServiceImplTest.java`（结果转换、空集）；`src/test/java/com/flowdesk/category/controller/CategoryControllerWebTest.java`（200、空集、401、403、两种权限分别放行、最小字段）；`src/test/java/com/flowdesk/category/application/service/impl/CategoryServiceIT.java`（真实库启用过滤、同排序值按 ID 稳定排序）。
- 检查顺序：用户先完成实体/Mapper/Result，Agent review；再完成 Service/Controller，Agent补测试并执行。命令：`./mvnw.cmd -B test "-Dtest=CategoryServiceImplTest,CategoryControllerWebTest" "-DargLine=-Djdk.attach.allowAttachSelf=true"`，然后 `./mvnw.cmd -B verify "-Dtest=CategoryServiceImplTest,CategoryControllerWebTest" "-Dit.test=CategoryServiceIT" "-DargLine=-Djdk.attach.allowAttachSelf=true"`。不存在生产类时不提前创建不可编译的测试；测试通过前步骤①保持未完成。

## 7. 阶段 3：IT 处理闭环

**交付结论（2026-10-06）**：本节四步全部完成——步骤①②③ 通过真实栈验收（`claim`/`add-processing-record` 45/45，`submit-resolution`/`confirm-resolution` 77/77），步骤④ IT/员工页面与 Playwright 端到端主链在真实栈跑通四态；分支 `flow-desk/ticket-it-flow` 经 [PR #10](https://github.com/Crazy-HF/Flow-Desk/pull/10) 以 merge commit 合并 `main`（合并提交 `1bc2e4c`）。CI 首次运行（`37411614020`）在 `core-e2e` 暴露一处**测试代码**缺陷：E2E 把 IT 显示名写死而种子数据不同，干净库失败、开发机通过；已改为按当前登录身份读取，修正后运行 `37412295816` 三个 job 全绿。完整记录见 `docs/acceptance/2026-10-06-stage3-git-handoff.json`。

目标：用最短但完整的状态链演示员工与 IT 协作。

MVP 状态主链：

```text
PENDING → PROCESSING → WAITING_FOR_CONFIRMATION → COMPLETED
```

**契约对齐（2026-09-30）**：本节原写 `PENDING_CONFIRMATION`，与已发布编码不一致。以已发布实现为准——`V1__create_schema.sql` 的状态约束与 `ticket_record` 检查、`docs/database-design.md` 第 18 节、`docs/api-design.md` 6.3/6.4、后端 `TicketStatus` 与前端 `frontend/src/constants/tickets.ts` 全部使用 `WAITING_FOR_CONFIRMATION`。本次只改本节文字，**不修改已发布的历史迁移**。

### TASK-030-MVP：领取与处理

- IT 查看公共队列并领取工单；并发领取只有一个请求成功。
- 仅当前负责人可追加处理记录和提交解决结果。
- 每次状态或业务动作都追加不可变时间线，并使用版本条件更新防止覆盖。

### TASK-032-MVP：解决与确认

- IT 提交解决结果后进入 `WAITING_FOR_CONFIRMATION`（编码以已发布实现为准，见本节开头的契约对齐说明）。
- 仅提交人可确认，确认后进入终态 `COMPLETED`。
- 终态不能再次领取或处理；冲突返回 `409`，前端提示重新加载，不自动重放写操作。

### TASK-033-MVP：核心流程页面

- IT 页面提供队列、负责中列表、详情、领取、处理记录和提交解决。
- 员工详情页提供确认结果。
- Playwright 覆盖员工创建 → IT 领取 → 处理 → 提交解决 → 员工确认。

阶段 3 验收：主链从页面端到端可重复演示；RBAC、资源关系、状态和乐观并发都有后端测试证据。

### 7.1 阶段 3 小步实施（2026-09-30 起）

分工与验收口径沿用 6.1：基础 domain、Controller、Mapper、Command/Query/Result、服务接口由 Agent 写入，`ServiceImpl` 业务逻辑先以文字与流程图说明再提供完整代码供用户编写；按用户指示本阶段不新增测试类，使用编译、现有检查与真实栈验收。**代码完整性复核不等于运行验证**，每步只有在真实栈上跑过才可标为通过。

- [x] ① IT 领取（`POST /fd/v1/tickets/{ticketNo}/actions/claim`）**已通过真实栈验收（2026-10-06）**：
  - 契约与权限：按 `docs/api-design.md` 6.3 的 `claim` 行（仅 `PENDING`，请求体只带 `version`），要求 `TICKET_CLAIM`；动作结果复用统一动作结果口径（工单编号、最新状态、负责人摘要、`version`、动作时间）。
  - 服务端校验顺序（`TicketServiceImpl.claim`）：认证身份 → `TICKET_CLAIM` → `TicketClaimantPort.lockEligibleClaimant`（事务内锁当前用户行，确认账号启用且仍持有 `IT_SUPPORT`）→ 可见性（`selectVisibleDetail`，无权查看与编号不存在统一 `404/TICKET_NOT_FOUND`）→ 不能领取自己提交的工单 → 状态、负责人与版本（`409/TICKET_CONFLICT`）。
  - 并发判定：`TicketMapper.claimPending` 的条件 `UPDATE` 是唯一胜者判定，条件含 `id`、`expectedVersion`、`status = 'PENDING'`、`assignee_id IS NULL`、`requester_id <> 领取人`，同时 `version + 1`、`record_seq + 1`；影响行数不为 1 即按冲突处理，并用 `selectClaimConflictSnapshotForUpdate` 读回最新 `version`/`status`。
  - 同事务写入：`ticket_participant` 记录历史 IT 参与关系（`ON DUPLICATE KEY UPDATE last_assigned_at`），并追加不可变 `CLAIM` 记录（`PENDING → PROCESSING`，操作者为领取人，序号取递增后的 `record_seq`）。
  - 详情 `allowedActions`：仅当状态为 `PENDING`、负责人为空、当前用户不是提交人、具备 `TICKET_VIEW_QUEUE` 与 `TICKET_CLAIM` 且仍是有效 IT 时返回 `["claim"]`；按钮提示不代替动作时的重新校验。
  - 冲突响应：`ApiException` 增加可选 `resourceVersion`/`resourceStatus`，由 `GlobalExceptionHandler` 写入 `ErrorDetails.version`/`status`，对应 `docs/api-design.md` 6.6 要求的当前快照。
  - 已修问题：领取路径原先经 `IamRoleMapper.selectByCodeForUpdate("IT_SUPPORT")` 锁住所有 IT 共用的角色行，使不同账号、不同工单的领取请求也被迫串行；现改为普通读取角色 ID，保留用户行锁与角色授权校验。记录见 `docs/project-highlights.md`「IT 工单领取：移除全局角色行锁」。
  - **验收证据（2026-10-06）**：真实栈 `local` + MySQL 3308 / Redis 6380 / 后端 8081 上，匿名 `401`、员工 `403/TICKET_ACTION_FORBIDDEN`、编号不存在 `404/TICKET_NOT_FOUND`、版本过期 `409/TICKET_CONFLICT`、缺少 `version` `400`、首次领取 `200`（`PROCESSING`、version 0→1、负责人=it）、同版本重复领取 `409` 且冲突快照返回当前 `version`/`status`；并发领取由 `TicketMapper.claimPending` 的条件更新保证唯一胜者。逐条 `traceId` 见 `docs/acceptance/2026-10-06-stage3-claim-and-processing-record.json`。提交前的全量 `verify`（单元/Web 394 + 集成 88 全绿，证据 `docs/acceptance/2026-09-30-ticket-claim-backend-verify.json`）只说明构建通过、既有测试无回归。
- [x] ② 当前负责人追加处理记录（`add-processing-record`）**已实现并通过真实栈验收（2026-10-06）**：
  - 契约与权限：`POST /fd/v1/tickets/{ticketNo}/actions/add-processing-record`，要求 `TICKET_PROCESS` 且当前用户是当前负责人；请求体 `version` + `content`，正文在 `AddProcessingRecordCommand` 的紧凑构造器内去除首尾空白后为 1～10000 字符。成功时状态与负责人不变，`version` 与 `record_seq` 递增，追加不可变 `PROCESS` 记录（两侧状态均写 `PROCESSING`）。
  - 并发判定：`TicketMapper.advanceAssigneeAction` 以 `id`、`expectedVersion`、`status = 'PROCESSING'`、`assignee_id = 操作人` 为条件更新 `version + 1`、`record_seq + 1`、`updated_at`，**不触碰 `action_deadline_at`**（`ck_ticket_status_deadline` 要求非等待态必须为 NULL）；影响行数不为 1 时同样用 `selectClaimConflictSnapshotForUpdate` 读回快照并返回 `409`。
  - 详情 `allowedActions` 增加 `add-processing-record`（状态 `PROCESSING`、当前用户是负责人、具备 `TICKET_PROCESS`），动作名与路径末段逐字一致。
  - **本轮修掉的缺陷**：`TicketQueryServiceImpl.toDetail` 已构建 `allowedActions` 变量，但返回语句仍传入旧的三元表达式 `canClaim ? List.of("claim") : List.of()`，导致详情动作提示恒为空数组。首轮验收第 1 次执行即暴露（`detail.allowedActions.hasProcess` FAIL）；修复后重新执行全量 `verify` 并复跑验收。
  - **验收证据（2026-10-06）**：真实栈上非负责人 `403`、纯空白正文 `400/VALIDATION_FAILED`、版本过期 `409`、首次成功 `200`（状态与负责人不变、version 1→2）、同版本重复 `409`、正文 10000 字符 `200` / 10001 字符 `400`、**同版本并发恰好一个 `200` 一个 `409`**；时间线为 `CREATE,CLAIM,PROCESS,PROCESS,PROCESS`，`sequenceNo` 1..5 严格递增，正文首尾空白已裁剪，`CLAIM` 迁移 `PENDING→PROCESSING`。逐条 `traceId` 与原始响应见 `docs/acceptance/2026-10-06-stage3-claim-and-processing-record.json`（45/45 通过），汇总与缺陷记录见 `docs/acceptance/2026-10-06-stage3-summary.json`，修复前的失败原件保留为 `docs/acceptance/stage3-ticket-actions-20261006095519-pre-fix-fail.json`。
- [x] ③ 提交解决结果（`submit-resolution`）与员工确认（`confirm-resolution`）**已实现并通过真实栈验收（2026-10-06）**：
  - `submit-resolution`：`POST /fd/v1/tickets/{ticketNo}/actions/submit-resolution`，要求 `TICKET_PROCESS` 且当前用户是当前负责人；请求体 `version` + `content`（`SubmitResolutionCommand` 构造时去除首尾空白，1～10000 字符）。服务端按 `flowdesk.ticket.confirmation-window`（默认 `7d`，见 `TicketProperties`）计算确认期限，`TicketMapper.submitResolution` 以 `id`/`expectedVersion`/`status='PROCESSING'`/`assignee_id=操作人` 为条件写入 `status='WAITING_FOR_CONFIRMATION'` 与 `action_deadline_at`，递增 `version` 与 `record_seq`，追加 `RESOLUTION` 记录（正文 + 期限 + `PROCESSING→WAITING_FOR_CONFIRMATION`）。**不触碰 `ended_at`**（`ck_ticket_status_ended` 要求非终态为空）。
  - `confirm-resolution`：`POST /fd/v1/tickets/{ticketNo}/actions/confirm-resolution`，要求 `TICKET_REQUESTER_ACTION` 且当前用户是工单提交人（权限闸门先于身份闸门）。`TicketMapper.confirmResolution` 以 `id`/`expectedVersion`/`status='WAITING_FOR_CONFIRMATION'`/`requester_id=操作人` 为条件写入 `status='COMPLETED'`、`action_deadline_at=NULL`、`completion_method='REQUESTER_CONFIRMED'`、`ended_at`，追加 `COMPLETION` 记录。**不清空 `assignee_id`**（`ck_ticket_status_assignee` 要求终态保留负责人）。
  - 详情 `allowedActions`：`WAITING_FOR_CONFIRMATION` 且当前用户是提交人且具备 `TICKET_REQUESTER_ACTION` 时返回 `confirm-resolution`；待确认状态下 IT 侧不返回动作（`report-unresolved`/`transfer`/`change-*` 尚未实现，不暴露按不动的按钮）。
  - 动作结果统一为 `TicketActionResult`（原 `TicketClaimResult` 重命名，字段不变），对应 `docs/api-design.md` 6.2 的"最新快照摘要"口径。
  - **验收证据（2026-10-06）**：真实栈上员工 403、空白正文 400、版本过期 409、成功 200（`WAITING_FOR_CONFIRMATION`、version+1、期限=提交时刻+7 天）、重复提交 409、待确认下追加处理记录 409；提交人详情 `allowedActions=["confirm-resolution"]`、当前负责人 403、非提交人无法让工单完成（404 且工单仍待确认）、版本过期 409、成功 200（`COMPLETED`、期限清空、负责人保留）；终态再次确认/领取/处理全部 409；时间线 `CREATE,CLAIM,PROCESS,PROCESS,PROCESS,RESOLUTION,COMPLETION` 且序号 1..7；数据库直查 `COMPLETED | REQUESTER_CONFIRMED | 期限 NULL | ended_at SET`。逐条 `traceId` 见 `docs/acceptance/2026-10-06-stage3-claim-process-resolution-confirm.json`（**77/77 通过**），汇总与三处验收脚本修正见 `docs/acceptance/2026-10-06-stage3-step3-summary.json`。
- [x] ④ IT 与员工页面、端到端主链（`TASK-033-MVP`）**已完成并在真实栈跑通（2026-10-06）**：
  - **IT 工作台**（`frontend/src/views/work/TicketQueueView.vue`，路由 `/it/queue`）：复用 `TicketListView` 并以 `defaultScope="PENDING_QUEUE"` 让它默认停在「待受理」，同一页可切到「我负责的」；侧栏新增「IT 工作台」入口（`TICKET_VIEW_QUEUE`）。**刻意不用 `/tickets/queue`**：那条路径同时匹配 `/tickets/:ticketNo`，谁生效取决于路由数组顺序。
  - **详情页动作区**：按详情的 `allowedActions` 渲染，再用 `permittedActions`（`constants/tickets.ts` 的 `TICKET_ACTIONS` 登记表 + 当前账号权限）收口一次。覆盖 `claim`（领取）、`add-processing-record`（记录处理过程）、`submit-resolution`（提交解决结果）、`confirm-resolution`（员工确认已解决）；没有正文的动作不出现输入框，需要正文的动作在确认框里给多行输入（上限 10000，与后端 `@Size` 对齐）。
  - **动作后的状态维护**：成功即静默重取详情与时间线（不闪骨架屏）并回写 `version`；`409/TICKET_CONFLICT` 时也重取一次把版本对齐到服务端当前值，否则用户再点一次还是同一个 409。正文为空时由 `beforeClose` 拦下并提示，弹窗不关、已写内容不丢。
  - **一处后端修正（经用户 2026-10-06 当场授权）**：`TicketQueryServiceImpl.toDetail` 的 `allowedActions` 原先只有 `claim`、`add-processing-record`、`confirm-resolution` 三个分支，**缺 `submit-resolution`**。接口早已实现并验收，但详情不返回该动作名，而界面按 `allowedActions` 渲染按钮，于是负责人能写处理记录却交不出解决结果——端到端主链在这一步 `element(s) not found` 暴露。修正为 `canSubmitResolution = canProcess`（两者前置条件相同：处理中 + 本人是负责人 + `TICKET_PROCESS`），不复制表达式；`docs/api-design.md` 6.3 已同步。
  - **两处真实缺陷（前端）**：① 确认框正文输入框在**生产构建**里消失——整个应用按运行时版 Vue 打包，`template` 选项不会被编译，`<el-input>` 只渲染出占位注释；单测（开发版 Vue 含编译器）正常，只有真实浏览器能发现，最终改为单文件组件 `TicketActionContentField.vue`。② 渲染函数里给 `ElInput` 传 `onUpdate:modelValue` 只能得到普通 prop（`modelValue`/`update:modelValue` 都是 props），输入不回流，表现为"填了内容却提交空正文"。
  - **验收证据（2026-10-06）**：`frontend/e2e/ticket-it-flow.spec.ts` 在真实栈（MySQL 3308 / Redis 6380 / 后端 8081 / preview 4173）用 `employee` 与 `it` 两个真实账号交替操作同一张工单，五步各带 `X-Trace-Id`（create 201、claim 200、add-processing-record 200、submit-resolution 200、confirm-resolution 200），界面状态实测 `待受理 → 处理中 → 待员工确认 → 已完成`，时间线 5 条按序，1440/375 横向溢出 0、页面错误 0；另含一条"只填空格被界面拦下、弹窗不关、时间线不多记录"的断言。全量 E2E **16 项**、前端单测 **23 套件 147 项**、后端 `clean verify` 单元/Web **394** + 集成 **88** 全绿。报告与 9 张截图见 `.ui-craft/reviews/2026-10-06-ticket-it-flow/`。

**步骤①②③ 验收执行方式（2026-10-06）**：`scripts/stage3-ticket-actions-acceptance.ps1`（PowerShell 5.1 兼容，脚本本身必须保存为带 BOM 的 UTF-8；自行从仓库根 `.env` 读取数据库连接）在 `local` profile + 真实 MySQL/Redis + 后端 8081 上按 77 项断言逐条执行，结束时输出无 BOM 的 JSON 证据与清理 SQL，并包含一条数据库直查断言。演示库漂移已记录：`iam_user_role` 中 `admin` 同时持有 `EMPLOYEE`、`IT_SUPPORT` 与 `SYSTEM_ADMIN`，因此脚本对 `admin` 只记录实际状态、不做绝对值断言。本阶段按用户指示不新增测试类，四个动作的行为证据来自真实栈 HTTP 调用。

## 8. 阶段 4：MVP 验收与求职展示收口

**已完成（2026-10-06 起，分支 `flow-desk/mvp-closeout`；经 PR #11 合并，收口两批经 PR #12/#13）**。以下为当时的收口清单：

- [x] **工单与分类模块的自动化测试补齐（本阶段核心实现）**：已完成（2026-10-06）——新增 10 个测试类 / 275 项用例，`clean verify` 由 394 + 88 升到 **638 + 119**，`Failures 0 / Errors 0`；覆盖认证、权限、幂等、事务（含 `@Transactional` 边界断言）、真并发（2/6/8 线程）与时间线（含 context 白名单与未知类型）。测试类由 Agent 负责设计与执行（`AGENTS.md`「测试代码职责」），覆盖率不作为构建失败条件。
- [x] 从空库执行 Flyway、启动 MySQL/Redis、启动前后端并完成主链演示（2026-10-06：临时库 `flowdesk_stage4_clean` + 后端 8091，66/66 断言通过，证据 `docs/acceptance/2026-10-06-stage4-clean-db-demo.json`；演示库与 8081 上的既有后端未被触碰）。
- [x] 后端执行 `test`/`verify`；前端执行 lint、typecheck、unit、build 和核心 E2E（阶段 3 基线：单元/Web **394** + 集成 **88**、单测 **147**、E2E **16**，只增不减）。**已复跑（2026-10-06，收口分支 `flow-desk/mvp-hardening`）：`clean verify` → surefire **666** + failsafe **119**；`typecheck`/`lint`/`build` 退出码 0、单测 **23 套件 147 项**、E2E **17 项连续 3 轮全绿**；其中一处 E2E 溢出断言的真实竞态已按 `docs/acceptance/2026-10-06-hardening-stability.json` 修复。**
- [x] README 补充架构说明、启动步骤、演示账号生成方式、核心流程、测试命令和已知限制（2026-10-06：README 新增「核心流程 / 架构说明 / 测试命令 / 演示账号 / 已知限制」五节）。
- [x] 把真实实现亮点追加到 `docs/project-highlights.md`，不得把未实现的完整版能力写成成果；同时清理文档里的机器绝对路径（MVP 定义第 4 条）——总览更新到 2026-10-06 并新增 HL-007～HL-010；跟踪文件中的机器绝对路径已清零（历史证据只把路径替换为占位符并加 `_pathsMasked` 说明）。

**原计划条目（保持原文）**：

- 准备仅在 `demo`/`test` 生效的三角色演示账号和分类数据，不提交公共默认生产密码。
- 从空库执行 Flyway、启动 MySQL/Redis、启动前后端并完成主链演示。
- 后端执行 `test`/`verify`；前端执行 lint、typecheck、unit、build 和核心 E2E。
- README 补充架构说明、启动步骤、演示账号生成方式、核心流程、测试命令和已知限制。
- 把真实实现亮点追加到 `docs/project-highlights.md`，不得把未实现的完整版能力写成成果。

MVP 最终完成定义：

1. 干净检出可以按文档启动，不依赖开发机隐式状态。
2. 三角色登录及核心工单主链可重复演示。
3. 认证、权限、幂等、事务、并发和时间线均有对应测试。
4. 仓库不含真实密码、JWT 密钥、Token 或机器绝对路径。
5. 简历和面试只陈述已实现、已验证的能力。

## 9. 完整版 backlog

完整版在 MVP 通过验收后再排期，建议顺序如下：

1. **完整工单状态机**：请求补充、员工补充、撤回请求、未解决退回、员工撤销、IT 转交和异常关闭。**2026-10-08 追加规则变更**：员工撤销由「四种非终态直接撤销」改为**两阶段**（待受理直接撤销；处理中/待补充/待确认由提交人发起、当前负责人批准或拒绝，提交人可撤回），见 9.3 末的交付记录。
2. **附件与关联**（**当前进行中**）：受控上传/下载、类型与大小限制、临时文件原子移动、失败补偿、孤儿对账、后续/重复工单关系。**2026-10-10 用户裁决：先做关联（轻）、再做附件（重）**——`ticket_relation` 与 `ticket_attachment` 两张表都已在 `V1` 就位，关系一侧不涉及文件存储、暂存目录与失败补偿，先做可以把「新建工单引用原工单」与关系读写打通，附件随后按 `docs/engineering-readiness.md` 7.2 的本地受控目录方案落地。
3. **自动化**：待确认自动完成、待补充自动关闭、停机恢复和幂等扫描。**2026-10-10 用户裁决**：本项的**扫描与自动处置部分与通知 / 通信通道一并落地**（通道是其前置，见 9.4 第 1 项），不单独排期；在此之前期限只用于展示，界面必须写明"到期不会自动处理"。**同日另一条裁决**：原先记在本项的**撤销请求到期即失效移出本项**，与第 7 项合成同一批——它不需要定时扫描就能落地（三条条件更新加期限条件、覆盖过期请求时补写记录）；只有「到期前提醒」依赖通道，仍随本项的通道一起做。
4. **系统管理**：~~用户与固定角色分配、账号启停、管理员重置密码~~、活动工单管理性交接（**2026-10-10 补充**：撤销请求只由当前负责人批准，负责人账号被停用或失去 `IT_SUPPORT` 后请求会悬置在「待批准」，管理性交接是该场景的既有出口）、~~分类管理~~。**其中用户管理（列表、详情、创建、改资料、启停、替换角色、重置密码）已于 2026-09-24 随第 3 步提前实施并计入 MVP 演示范围（`TASK-061` 后端 / `TASK-062` 管理端页面）；分类管理已于 2026-09-28 经用户当轮指示提前实施（`TASK-063` 后端 / `TASK-064` 管理端页面，见 9.2）；管理性交接仍留完整版，**2026-10-10 用户裁决：排在撤销语义收口（第 7 项那一批）之后**。**
5. **数据概览**：IT 权限范围内的状态、优先级、分类和负责人统计。
6. **动态 RBAC（已于 2026-09-21 确认实施，先于阶段 2）**：**已完成并合并**（PR #8，合并提交 `f15468c`）；任务拆分、切片顺序与验收标准见第 9.1 节。
7. **撤销请求的待办发现与到期语义收口（2026-10-10 登记，同日裁决为同一批）**：IT 列表新增「待我批准」筛选，**语义固定为「本人是当前负责人、且工单上有未过期的待决撤销请求」**，与详情里的批准 / 拒绝决定权**共用同一判定**（再叠加 `TICKET_PROCESS` 与「待补充期间不裁决」），并做到期前提醒。**需要连同索引一起设计**——`V7` 的「不新增索引」以「待决请求不是查询维度」为前提（`docs/database-design.md` 的 `V7` 变更说明），本项让该前提失效。**用户 2026-10-10 裁决**：本项与**撤销请求到期即失效**（原记在第 3 项）合成**同一批**落地，理由是两者共用上面那一个判定，拆开做会出现「列表说可批准、详情说已过期」的分叉；同时要按契约扩展列表的**筛选参数**（`docs/api-design.md` 5.3/5.4；列表项响应暂不增加待决请求字段）。**批次顺序**：附件与关联（第 2 项）→ **本批** → 管理性交接（第 4 项）→ 通知通道与两个超时自动任务（第 3 项）。

### 9.1 完整动态 RBAC（2026-09-21 阶段设计确认，先于阶段 2；**2026-09-28 已完成并合并**）

**交付结论（2026-09-28）**：`TASK-055`～`TASK-062` 全部完成，经 PR #8 合并 `main`（合并提交 `f15468c`，分支 `flow-desk/rbac-admin-pages`）。阶段验收七条标准全部满足，其中第 4 条的四条手工真实栈链路于 2026-09-28 复跑通过（含清理还原，逐条 `traceId` 见 `docs/modules/rbac.md` 11.2）；最终证据为后端**单元/Web 394 + 集成 88** 全绿、前端 `typecheck`/`lint`/`build` 退出码 0、单测 **74 项**、E2E **13 项**。**唯一遗留**（不属验收标准）：用户管理页首轮视觉打样的逐项视觉反馈未取得、另外四个管理页未按新语言推广，转入下一分支继续。

范围上限是 `docs/api-design.md` 8.2.1 的四组接口，不扩展到该节之外的权限模型。**本阶段计入求职 MVP 演示范围**：除后端接口、迁移与测试证据外，还包含 RBAC 管理端页面（`frontend/src/views/admin/`，`TASK-060`，2026-09-22 确认）与用户管理端页面（同目录，`TASK-062`，2026-09-24 确认）。**2026-09-24 范围登记**：8.2 的 `/fd/v1/users` 八个端点经用户确认正式登记为 `TASK-061`（后端，已实现并有完整测试）与 `TASK-062`（管理端页面），用户管理因此从完整版 backlog 移出；替换角色路径按 8.2 契约统一为复数 `/roles`。

已确认的阶段设计决策：

| # | 决策 | 说明 |
| --- | --- | --- |
| 1 | `iam_role_permission` 在 `V5` 中补 `granted_by BIGINT UNSIGNED` / `granted_at DATETIME(6)` | 契约要求审计，而该表原本一行审计列都没有；两列允许为空，表示 Flyway 预置或历史授权没有具体操作人/时间；在线授权必须同时填写 |
| 2 | IAM 定义 `SessionRevocationPort`，auth 提供 adapter 实现 | 保持现有单向依赖 `auth → iam`，IAM 不感知 Redis |
| 3 | 撤销会话与提交 MySQL 的顺序：**先撤 Redis，后提交授权变更** | 与改密的既有顺序一致；宁可让用户重登一次，也不让旧权限在旧会话里继续可用 |
| 4 | 两组批量授予接口统一返回 `200`（全部已存在与实际新增都是） | 授予采用增量幂等语义；请求去重排序后只新增缺失关系，整批均已存在时不写库、不撤会话；`201` 只用于真正创建了新资源 |
| 5 | 受保护角色与权限按 `code` 常量判定（`SYSTEM_ADMIN`、`RBAC_MANAGE`） | `code` 已有唯一约束，不新增"内置"标记列 |
| 6 | 分页排序白名单：角色/权限为 `code,name,created_at`；用户角色为 `granted_at`；角色权限为 `role_id,permission_id` | 作为 `PageQuery.orderItems(...)` 的入参 |
| 7 | 两组授权列表必须至少给出一个筛选（`userId`/`roleId`、`roleId`/`permissionId`），都不给返回 `400/VALIDATION_FAILED` | 避免无筛选的全表分页 |
| 8 | 交付层级：**计入求职 MVP 演示范围**，并在后端接口之外补 RBAC 管理端页面（`frontend/src/views/admin/`） | 2026-09-22 用户确认；页面成为本阶段交付物，不再是“留给后续主题” |
| 9 | 两类授权列表使用固定授权结果对象（`UserRoleResult` / `RolePermissionResult`），批量授予响应返回同一结果的列表 | 用户角色返回用户 ID/用户名、角色 ID/编码/名称、授权人、授权时间；角色权限返回角色 ID/编码、权限 ID/编码/名称、授权人、授权时间；结果按目标 ID 升序，`grantedBy` 为可空的授权人用户 ID |
| 10 | 角色权限实际变化时撤销该角色全部用户会话 | 持有角色锁后通过 `FOR UPDATE` 按用户 ID 升序取得受影响用户快照；重复授予不写库、不撤会话；任一撤销失败则 MySQL 回滚 |
| 11 | RBAC 写操作使用固定悲观锁协议 | `SELECT ... FOR UPDATE`，顺序为角色 → 权限 → 用户 → 授权关系，同层按主键升序；锁后重查并校验，禁止反向加锁和带 Redis 副作用的自动重试 |
| 12 | 安全链作用域修复（方案 A）：`AuthSecurityConfiguration` 的 `securityMatcher` 由 `/fd/v1/auth/**` 扩为 `/fd/v1/**` | 2026-09-22 确认。修复前真实 HTTP 下 `/fd/v1/admin/**` 恒为 `401/AUTH_REQUIRED`——JWT 过滤器只装在 auth 链上，其余路径落到无认证过滤器的基础链；`@WithMockUser` 的 Web 测试覆盖不到该缺陷。见 `TASK-059`（已于 2026-09-22 实施并完成真实栈复核） |
| 13 | `TASK-058` 按 4 片推进：① 两组授权列表 ② 用户角色授予/撤销 ③ 角色权限授予/撤销 ④ 并发与真实栈收口 | 2026-09-22 用户确认 |

任务拆分（每个任务独立验收）。**执行顺序**：`TASK-059`（安全链修复，先做）→ `TASK-058`（四片）→ `TASK-060`（管理端页面）→ 阶段收口；编号按登记顺序，不代表执行顺序。`TASK-061`/`TASK-062`（用户管理）于 2026-09-24 登记，与 `TASK-060` 同批交付：

| 任务 | 内容 | 关键产出 |
| --- | --- | --- |
| `TASK-055`（已完成） | `V5` 迁移：新增 `RBAC_MANAGE` 权限、授予 `SYSTEM_ADMIN`、为 `iam_role_permission` 补审计列、索引与外键；同步 `DatabaseMigrationIT`（版本 `1,2,4,5`、权限/关系各 14、`SYSTEM_ADMIN` 权限数 4、授权范围与物理结构） | 迁移脚本 + 迁移集成测试；2026-09-21 空库验证通过 |
| `TASK-056`（已完成） | 会话撤销端口与 adapter：`iam/application/port/SessionRevocationPort`（`void revokeAll(long userId)`）+ `auth/infrastructure/IamSessionRevocationAdapter` | 端口、adapter、转发单测 |
| `TASK-057`（已完成） | 角色与权限两组 CRUD：`/fd/v1/admin/roles`、`/fd/v1/admin/permissions` | Controller/Service/Command/Query/Result + Web 测试；提交 `7dd5208`，`verify` 128 单元/Web + 30 集成全绿（2026-09-23 重构后类名见 `docs/modules/rbac.md` 第 3 节） |
| `TASK-059`（已完成） | 安全链作用域修复（决策 12 方案 A）：`AuthSecurityConfiguration` 的 `securityMatcher` 扩为 `/fd/v1/**`；同步 `docs/modules/auth.md` §8.1 的链职责描述；补一条走真实过滤链（不使用 `@WithMockUser` 绕过）的 Web 测试，证明无令牌 401、有令牌放行、非 admin 403 | 配置改动 + 测试 + 文档；真实栈用 `admin` 令牌调 `/fd/v1/admin/roles` 应返回 `200`，不再 `401` |
| `TASK-058`（**已完成**） | 用户角色与角色权限两组授权（**最终共 11 个端点**：四片设计里的 6 个 + 本轮追加的批量撤销、清空全部、一个角色授予多个用户、建角色带权限），四片：① 两组授权列表（筛选二选一否则 `400`、固定授权结果对象、排序白名单）② 用户角色批量增量授予（`userId + roleIds`）/单条撤销（**保护规则 6**、审计两列、实际新增时仅撤该用户全部会话一次；保护规则 5 已于 2026-09-22 废弃，允许零角色）③ 角色权限批量增量授予（`roleId + permissionIds`）/单条撤销（保护规则 7、持角色锁按 `user_id` 升序取用户快照、实际新增时按角色范围撤会话一次、先撤 Redis 后提交）④ 并发与真实栈收口 | Controller/Service/Command/Query/Result；批量列表非空、最多 100 个正整数，服务端去重排序，只新增缺失关系，任一目标不存在则整批回滚；两张授权表是复合主键、实体无 `@TableId`，只能用 wrapper 读写。**测试**：`IamUserRoleServiceImplTest`(40)、`IamRolePermissionServiceImplTest`(32)、`IamRoleServiceImplTest`(22，含建角色带权限)、`IamUserRoleControllerWebTest`(25)、`IamRolePermissionControllerWebTest`(21)、`IamRoleControllerWebTest`(19)、`IamCurrentOperatorAdapterTest`(4)、`SecurityChainScopeWebTest`(23)；集成侧 `IamUserRoleServiceIT`(17，含两条真并发)、`IamRolePermissionServiceIT`(14)、`IamRoleServiceIT`(9)、`IamPermissionServiceIT`(6)、`RedisAuthSessionRepositoryIT`(14)、`DatabaseMigrationIT`(4)、`IamUserServiceIT`(24)。**2026-09-28 最终证据**：`./mvnw -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"` → 单元/Web **394** + 集成 **88**，`Failures: 0, Errors: 0`。覆盖矩阵逐格证据见 `docs/modules/rbac.md` 10.1；第 4 条手工真实栈链路已于 2026-09-28 复跑并全部通过（`docs/modules/rbac.md` 11.2） |
| `TASK-060`（**已完成**） | RBAC 管理端页面（`frontend/src/views/admin/`）：角色、权限、用户角色授权、角色权限授权的在线维护页；路由与侧栏入口按 `RBAC_MANAGE` 显隐 | 四个页面 + `api/rbac.ts` 领域封装（`api/` 保持扁平，见 `docs/modules/rbac.md` 12.3）+ 共享件 `ProtectedMark` / `AdminListPanel` 与 `useAdminList` 取数状态机；单测与三条新增 E2E（RBAC 真实闭环、用户角色闭环、入口按权限显隐与窄屏）。落地结果与两个实测缺陷见 `docs/modules/rbac.md` 12.5、视觉语言见 12.6 |
| `TASK-061`（已完成） | 用户与账号管理后端接口（`docs/api-design.md` 8.2）：`GET /fd/v1/users`（分页 + keyword/status/roleId 筛选）、`GET /{userId}`、`POST /`（201）、`PUT /{userId}`、`POST /{userId}/actions/enable`、`POST /{userId}/actions/disable`、`PUT /{userId}/roles`（替换完整角色集合，复数路径按契约）、`POST /{userId}/actions/reset-password`；全部要求 `USER_MANAGE` | Controller/Command/Query/Result + `IamUserService` 用户管理方法；测试：`IamUserServiceImplTest`(58)、`IamUserControllerWebTest`(45)、`IamUserServiceIT`(24，含"并发创建同一登录名"与"并发停用只放行一个"两条真并发)。**2026-09-24 登记并确认；`/role` → `/roles` 同日按契约修正**。管理性交接（8.3）仍是 `TODO`，属完整版 |
| `TASK-062`（**已完成**） | 用户管理端页面（`frontend/src/views/admin/UserListView.vue`，路由 `/admin/users`）：列表与筛选、创建、改资料、启停、重置密码；入口按 `USER_MANAGE` 显隐 | 页面 + `api/users.ts` + 单测/E2E（含真实用户新增/编辑/启停/重置密码与翻页）。该页同时是前端视觉改版首轮打样页（`AppPage` 的 `layout="list"`），截图与真实交互证据见 `.ui-craft/reviews/2026-09-27-admin-redesign/report.md`；**逐项视觉反馈未取得，未推广到其他四页** |

阶段验收标准（可检查；**2026-09-28 全部满足**）：

1. `./mvnw -B verify` 全绿，且相对阶段起点只增不减（起点：单元/Web 55 项 + 集成 17 项；**最终 394 + 88**）。
2. 四组接口 × {成功、400、401、403、404、409、幂等} **每一格都有用例**；用户角色侧必须在 MySQL 集成测试中做真并发，证明固定锁顺序下无死锁：① 并发撤销同一用户的全部角色时两次都成功且终态零授权（**零角色是合法终态**，2026-09-22 起废弃 `409/USER_ROLE_REQUIRED`）；② 两个启用管理员并发撤销各自 `SYSTEM_ADMIN` 时只能有一个成功，终态仍有一个启用管理员。mock 返回 0 只能补充分支。
3. `DatabaseMigrationIT` 断言与 `V5` 一致，并能证明 `RBAC_MANAGE` 只授予 `SYSTEM_ADMIN`。
4. 手工验证四条链路各留 `traceId` 与响应码：授予用户角色后目标用户旧会话失效、变更角色权限后该角色全部用户旧会话失效、**清空某用户全部角色后该用户零授权（2026-09-22 起零角色为合法终态，不再期望 `409/USER_ROLE_REQUIRED`）**、删除受保护角色被拒。**2026-09-28 已在真实栈复跑并通过（另含清理还原），逐条证据见 `docs/modules/rbac.md` 11.2 与 `scripts/manual-rbac-acceptance.ps1`。**
5. `PROJECT_STATUS.md`、`README.md` 与 `AGENTS.md` 的当前阶段一致。
6. 真实 HTTP 可用性（`TASK-059` 完成后）：用 `admin` 令牌调 `/fd/v1/admin/roles` 与 `/fd/v1/admin/permissions` 返回 `200`（不再是 `401/AUTH_REQUIRED`）；无令牌 `401`、非 admin 令牌 `403`。
7. 管理端页面（`TASK-060` + `TASK-062`）：四组 RBAC 页面可完成一次真实闭环（建角色 → 授权限 → 给用户授角色），六态齐全、入口分别按 `RBAC_MANAGE` 与 `USER_MANAGE` 显隐，前端 `typecheck`/`lint`/`build`/单测/E2E 全绿。

动态 RBAC 已按 2026-09-21 的确认提前实施，实现范围以 `docs/api-design.md` 8.2.1 为上限，不扩展到该节之外的权限模型；`EMPLOYEE`、`IT_SUPPORT`、`SYSTEM_ADMIN` 三种内置角色继续保留且 `SYSTEM_ADMIN` 受保护。

### 9.2 分类管理（2026-09-28 用户指示插入实施）

**来源**：用户 2026-09-28 直接要求"分类管理页面实现"。分类管理原记在 9 节 backlog 第 4 项（完整版），本轮经用户指示提前实施，与阶段 2 同在 `flow-desk/ticket-employee-flow` 分支推进。**契约不新增**：全部按 `docs/api-design.md` 8.4 的六个端点落地，不扩展到该节之外。

**范围与交付层级**：与用户管理同口径——后端接口 + 管理端页面一起交付，计入 MVP 演示范围（管理端页面已是既定交付物，不做"入口存在但接口不存在"的假入口，见 `frontend/AGENTS.md`）。

**分工（沿用 2026-09-28 分工补充）**：基础 domain、Mapper、Controller、Command/Query/Result、服务接口由 Agent 直接写入；`CategoryServiceImpl` 的六项业务逻辑以文字与流程图说明后，在对话中提供完整代码供用户编写，不直接写入。本阶段按用户指示不新增测试类，使用编译、现有检查与真实栈验收。

| 任务 | 内容 | 关键产出 |
| --- | --- | --- |
| `TASK-063`（**已验收**） | 分类管理后端接口（`GET /fd/v1/admin/categories`、`POST /`、`PUT /{categoryId}`、`POST /{categoryId}/actions/enable`、`POST /{categoryId}/actions/disable`、`DELETE /{categoryId}?version=`），全部要求 `CATEGORY_MANAGE` | Agent 已写入：`CategoryQuery`、`CategoryResult`、`CreateCategoryCommand`、`UpdateCategoryCommand`、`CategoryStatusChangeCommand`、`CategoryService`（新增 6 个方法）、`AdminCategoryController`、`TicketCategoryMapper.selectByIdForUpdate`，以及 `GlobalExceptionHandler` 对缺失/类型不符请求参数的 `400/VALIDATION_FAILED` 映射。**六项实现与 HTTP 六端点验收通过，后端完整 verify 394+88 通过；见 docs/acceptance/2026-09-29-category-ticket-scopes.json 与 stage2-handoff-20260929.json** |
| `TASK-064`（**运行时已验收**） | 分类管理端页面（`frontend/src/views/admin/CategoryListView.vue`，路由 `/admin/categories`，要求 `CATEGORY_MANAGE`）：列表与筛选（名称、状态）、新建、改名与排序值、启停、删除；入口按权限显隐 | Agent 已写入：页面 + `api/categories.ts`；`router` 的 `/admin/categories` 由占位页改为真实页面；`main.css` 补上一直被三个页面引用却缺失的 `.admin-form-control` 规则。**typecheck/lint/build 与全套 15 E2E 通过，分类 CRUD 与 1440/375 截图已验证；用户逐项审美反馈仍待取得** |

已确认的落地口径（与 8.4 契约一致，需要时可在此处继续追加）：

1. **列表默认排序 `sort_order ASC, id ASC`**：与 `GET /fd/v1/categories/options` 的员工端下拉顺序一致，管理和被管理两侧看到的是同一个顺序；排序白名单只放 `name, sort_order, status, created_at, id`。
2. **创建即启用**：`CreateCategoryCommand` 不含 `status`，避免出现"建完就是停用"的入口；停用是业务动作，只走启停端点。
3. **写操作全部带 `version`**：先 `SELECT ... FOR UPDATE` 锁目标行，锁内判版本，再按 `id + version` 条件更新；影响行数不为 1 时返回 `409/CATEGORY_CONFLICT`。启停的版本校验放在幂等短路之前（与 `IamUserServiceImpl.enable/disable` 同序）。
4. **删除只靠外键判引用**：分类模块不跨模块读 `ticket` 表；`ticket.category_id` 的限制删除外键拒绝删除时转 `409/CATEGORY_IN_USE`。取到行锁后不会有新工单引用到该分类（插入子行要对父行取共享锁），因此这一条在并发下也成立。
5. **名称唯一由 `uk_ticket_category_name` 兜底**：预检查给出确定的 `409/CATEGORY_NAME_CONFLICT`，并发插入落库失败时由 `DuplicateKeyException` 转成同一错误码。

验收标准（可检查；2026-09-29 第 1～5 项均通过；前端 CRUD、1440/375 截图及清理证据见 stage2-closeout-20260929.json；证据见 `docs/acceptance/2026-09-29-category-ticket-scopes.json`）：

1. `./mvnw.cmd -B -DskipTests compile` 通过（2026-09-28 后端提交检查已通过；`CategoryServiceImpl` 已补齐）。
2. 真实栈（`local` profile + MySQL/Redis）逐条核对六个端点的成功与失败路径：`CATEGORY_MANAGE` 缺失时 `403/ACCESS_DENIED`、无令牌 `401`、非正整数 ID `404/CATEGORY_NOT_FOUND`、重名 `409/CATEGORY_NAME_CONFLICT`、过期 `version` `409/CATEGORY_CONFLICT`、删除被引用分类 `409/CATEGORY_IN_USE`、`DELETE` 缺 `version` 参数 `400/VALIDATION_FAILED`。
3. 停用某分类后 `GET /fd/v1/categories/options` 不再返回它，历史工单仍显示原分类；重新启用后恢复可选。
4. 前端 `pnpm typecheck` / `pnpm lint`（含 stylelint 闸门）/ `pnpm build` 退出码为 0；`/admin/categories` 在 1440 与 375 两个宽度下无整页横向溢出，六态齐全。
5. 临时分类与工单数据清理干净（演示库回到角色 3 / 权限 14 / 分类数不变）。

### 9.3 完整工单状态机（完整版 backlog 第 1 项；2026-10-06 用户确认切片）

**来源与现状**：9 个动作的契约（`docs/api-design.md` 6.3/6.4）与业务规则（`docs/kickoff.md` 4.5～4.12）早已确认，数据库与权限码也已预置——`V1` 的 7 状态、15 种记录类型与 8 条 CHECK 约束齐备，`V2` 已给 `IT_SUPPORT` 授予 `TICKET_TRANSFER`/`TICKET_CLOSE`、给 `EMPLOYEE` 授予 `TICKET_REQUESTER_ACTION`，因此**本项不需要新增迁移、不需要新增权限码**（2026-10-08 的两阶段撤销是其后的一次**业务规则变更**，另需迁移 `V7`，见本节末的交付记录）。阶段 3 已实现 4 个动作（`claim`、`add-processing-record`、`submit-resolution`、`confirm-resolution`），本项补完剩余 9 个。

**用户 2026-10-06 的四项裁决**：

1. **分 4 片**，每片独立验收并交接（见下表）；分支从最新 `main` 单建，片 A 为 `flow-desk/ticket-return-actions`。
2. **附件正文先行**：`supplement` 仍是 `multipart/form-data`（`ticket` part 携带 JSON），但**只接受正文**；携带文件 part 返回 `400/VALIDATION_FAILED`，不静默忽略。附件上传/下载、文件与数据库提交顺序、失败补偿与孤儿对账留 backlog 第 2 项。
3. **`close` 的 `DUPLICATE` 目标口径**：目标存在、属于同一提交人、非自身，且当前状态**不是 `CANCELED`/`CLOSED`**（`COMPLETED` 与仍在流转的工单都可作为重复目标）。
4. **超时自动任务本阶段不做**：`request-supplement` 仍写 7×24 小时期限（新增 `flowdesk.ticket.supplement-window`，默认 `7d`，与 `confirmation-window` 同口径）并在界面展示，但**到期不自动改变状态**；待确认自动完成与待补充自动关闭按 backlog 第 3 项单独设计（需先定扫描与幂等策略）。界面文案必须写明"到期不会自动处理"。

**统一实现口径**（沿用阶段 3 已验收的模式）：门禁顺序 = 认证 → 权限 403 → IT 资格 403 → 可见性 404 → 状态/负责人/版本 409 → 字段 400 → 条件更新（影响行数 ≠ 1 → 409 带当前快照）；状态迁移与不可变时间线同事务，`record_seq` 与 `version` 同源递增；`allowedActions` 与动作接口共用判定，动作名与路径末段逐字一致。

| 片 | 动作 | 交付要点 | 验收 |
| --- | --- | --- | --- |
| **A 退回处理中** | `report-unresolved`（员工）、`withdraw-supplement-request`（IT） | 两个"从等待态回到 `PROCESSING` 并清空期限"的条件更新；`UNSATISFIED_FEEDBACK` / `SUPPLEMENT_REQUEST_WITHDRAWN` 记录；`allowedActions` 两格 | 单元 + Web + 集成（含并发）+ 真实栈脚本 + 前端动作区与 E2E |
| **B 补充往返** | `request-supplement`（IT）、`supplement`（员工） | 新增 `supplement-window` 配置；进入/离开 `WAITING_FOR_REQUESTER` 的期限写入与清空；员工侧"需要补充什么 + 截止时间"展示 | 同上（含期限的精确断言） |
| **C 调整与转交** | `change-category`、`change-priority`、`transfer` + `GET /fd/v1/tickets/{ticketNo}/transfer-candidates` | 目标分类必须启用；转交的固定锁顺序（按 `user_id` 升序锁两名用户行）与 `ticket_participant` 历史；候选人排除提交人与当前负责人 | 同上（含互转并发不死锁的集成用例） |
| **D 结束路径** | `close`（三种原因 + `DUPLICATE` 关联）、`cancel`（员工） | `ticket_relation` 落库与目标口径校验；取消覆盖四种非终态；三条终态（已完成/已取消/已关闭）必须可区分 | 同上（含终态可区分性断言） |

**片 A 交付记录（2026-10-06，分支 `flow-desk/ticket-return-actions`）**：`report-unresolved` 与 `withdraw-supplement-request` 已实现并通过真实栈验收——后端全量 `clean verify` → surefire **722**（基线 666）+ failsafe **127**（基线 119），`Failures 0 / Errors 0`；真实栈脚本 `scripts/slice-a-return-actions-acceptance.ps1` 在**并行后端 8092**（用户自己的 8081 未被触碰）上 **78/78 断言通过、退出码 0**，证据 `docs/acceptance/2026-10-06-slice-a-return-actions.json`；前端全量 E2E **18 项**、单测 **149 项**、`typecheck`/`lint`/`build` 退出码 0。期间 E2E 抓到一处真实缺陷：详情页动作派发的 `else` 兜底会把未接分支的新动作发成 `submit-resolution`，已改为 `Record<TicketActionName, ...>` 穷尽式映射（漏接即编译失败）。`WAITING_FOR_REQUESTER` 目前只能由脚本 SQL 置位（片 B 的 `request-supplement` 落地后即可通过接口进入）。

**片 B 交付记录（2026-10-07，分支 `flow-desk/ticket-supplement-roundtrip`，经 [PR #15](https://github.com/Crazy-HF/Flow-Desk/pull/15) 合并 `main`）**：`request-supplement` 与 `supplement` 已实现并通过真实栈验收——新增配置 `flowdesk.ticket.supplement-window`（默认 `7d`，下限 1m，与 `confirmation-window` 同口径）；`supplement` 保持 `multipart/form-data`（`ticket` part 携带 JSON），但**只接受正文**，携带文件 part 显式返回 `400/VALIDATION_FAILED` 而不是静默忽略；`TicketMapper.requestSupplement` / `supplement` 把**状态与期限写在同一条 `UPDATE`** 里（进入与离开 `WAITING_FOR_REQUESTER` 都不会产生 `ck_ticket_status_deadline` 的中间态）；`TicketQueryServiceImpl.allowedActions` 增两格。`supplement` **刻意不判期限是否已过**：本版本没有超时自动关闭，期限只用于展示，拦住过期提交比不做更糟（理由写在方法注释里）。真实栈脚本 `scripts/slice-b-supplement-roundtrip-acceptance.ps1` 执行 **67/67 断言通过、退出码 0**，证据 `docs/acceptance/2026-10-07-slice-b-supplement-roundtrip.json`；CI 首轮 `core-e2e` 失败于 `ticket-it-flow.spec.ts` 断言旧标签「当前期限」（修正为按状态与角色给标签与提示），修正后运行 `37564858602` 三个 job 全绿。

**片 C 交付记录（2026-10-07，分支 `flow-desk/ticket-adjust-transfer`，远端分支 `flow-desk/ticket-adjust-transfer-2`）**：

- **实现**：`change-category`（`ChangeCategoryCommand`）、`change-priority`（`ChangePriorityCommand`）、`transfer`（`TransferCommand`）与 `GET /fd/v1/tickets/{ticketNo}/transfer-candidates`（`TicketAssigneeOptionResult`）。目标分类必须启用；转交按 `user_id` 升序锁住「原负责人 + 新负责人」两行并在锁后复核双方资格（跨表资格进不了条件更新的 `WHERE`，理由见 `docs/project-highlights.md` HL-011），成功后写入 `ticket_participant` 新负责人一行；`CATEGORY_CHANGE` / `PRIORITY_CHANGE` / `TRANSFER` 三类记录沿用 `V1` 已预置的类型与 CHECK 约束，**不需要新增迁移与权限码**。「处理中」与「待补充」都可调整与转交，状态与期限不变。转交只要 `TICKET_TRANSFER`，**不要求** `TICKET_PROCESS`——两条授权彼此独立，只给一个角色配转交权限也成立。
- **期间修掉一处真实缺陷**：`TicketServiceImpl.changeCategory` 漏了 `visible == null` 判空（同文件其余 10 处 `selectVisibleDetail` 都有），编号不存在或不可见时在 `visible.getStatus()` 上 NPE，HTTP 表现为 `500/INTERNAL_ERROR` 而不是契约要求的 `404/TICKET_NOT_FOUND`。新增的 404 用例在修复前实测为红（`NullPointerException: ... because "visible" is null`），补上判空后转绿；修复前的真实栈运行按惯例保留为 `docs/acceptance/2026-10-07-slice-c-adjust-transfer-pre-fix-fail.json`（46 项断言、2 红），**未改写为成功**。
- **一处并发发现（未修改业务代码，待决策）**：转交与「同一负责人对同一张工单的另一个动作」并发时可能出现 InnoDB 死锁——转交按 `user → ticket` 顺序加锁，而另一个动作在持有工单行锁时，会因为 `ticket_record.actor_user_id` 的外键去申请同一条 `iam_user` 行的共享锁，两条路径的加锁顺序首尾相接成环。InnoDB 回滚其中一个：数据一致（版本只 +1、只多一条记录），但调用方拿到的是 `500` 而不是可重试的 `409`。候选修法是让转交先锁工单行、再锁两行用户（所有动作便统一以工单行起手）；这属于业务代码改动，记在 `PROJECT_STATUS.md` 的已知问题里等待裁决。（**后续：该修法已于 2026-10-08 在片 D 内落地，见下方片 D 交付记录与 `PROJECT_STATUS.md` 的「已修」段。**）
- **测试**：`TicketServiceImplTest` 用例方法 111 → 164（执行 170 项，含 1 个参数化 6 例）、`TicketQueryServiceImplTest` 50 → 64、`TicketControllerWebTest` 39 + 13 → 50 + 16、`TicketServiceIT` 28 → 38（含**互转并发不死锁**、锁后资格复核、同版本「转交 vs 撤回」并发）；`TicketQueryServiceIT` 的 `allowedActions` 断言按新契约更新（处理中负责人六个动作、待补充四个）。
- **真实栈验收**：`scripts/slice-c-adjust-transfer-acceptance.ps1` 在**并行后端 8092**（用户自己启动的 8081 全程未被触碰）上执行 **127 项断言、127 通过、退出码 0**，证据 `docs/acceptance/2026-10-07-slice-c-adjust-transfer.json`；脚本自建临时 IT 用户作为转交目标（含登录自检）并在收尾删除，演示库运行前后的 7 项计数与工单号指纹逐字一致。
- **前端**：`TICKET_ACTIONS` 三格（分类/优先级/候选人选择 + 原因输入）、`api/tickets.ts` 四个函数、详情页穷尽式派发与选择器组件、E2E `frontend/e2e/ticket-adjust-transfer.spec.ts`（用管理接口临时创建第二名 IT 作为转交目标，跑完停用）。
- **既有验收脚本不改写**：片 A 与片 B 的脚本里有三处 `allowedActions`「恰好等于」断言，编码的是各自切片当时的契约。按项目惯例脚本原件不改写，重跑旧脚本前需先把这三处改成包含式断言（片 A 脚本第 914 行的期望值文本原本就写着「片 C/D 之前只有这两个」）。

**片 D 交付记录（2026-10-08，分支 `flow-desk/ticket-close-cancel`，基点 `9f07a0f`）**：

- **实现**：`close`（`CloseTicketCommand`、`TicketServiceImpl.close`、`TicketMapper.closeManually`、`TicketRelationMapper.recordDuplicate`）与 `cancel`（`CancelTicketCommand`、`TicketServiceImpl.cancel`、`TicketMapper.cancel`）；`allowedActions` 增两格（`close` 复用 `canProcess` 再叠 `TICKET_CLOSE`，`cancel` 为四种非终态 + 本人是提交人 + `TICKET_REQUESTER_ACTION`）。**不需要新增迁移与权限码**：三种人工关闭原因与「终态必须有结束时间」由 `V1` 的 `ck_ticket_close_semantics` / `ck_ticket_status_ended` 直接约束，`ticket_relation` 的唯一键 `uk_ticket_relation_source_type` 也已预置。
- **两条用户裁决（2026-10-08）**：① `close` **同时要求 `TICKET_PROCESS` 与 `TICKET_CLOSE`**——关闭结束整张工单，必须建立在处理权限之上（与片 C 的 `transfer` 只要求 `TICKET_TRANSFER` 是两条不同口径，两格权限分开各测一次）；② `close` **不做** `lockEligibleClaimant` 资格锁——那会先锁 `iam_user` 行，重新造出片 C 修掉的那个环（关闭方的资格已由 `ticket.assignee_id = actor` 的条件更新保证）。
- **转交交叉死锁的修法落在本片**（`TicketServiceImpl.transfer`）：先 `selectClaimConflictSnapshotForUpdate` 锁工单行并复核 `version`，再按 `user_id` 升序锁两行 `iam_user`。所有动作从此以工单行起手，`ticket → user` 与 `user → ticket` 的环消失。`TicketServiceIT.concurrentTransferAndWithdrawOnSameTicketHaveExactlyOneWinner` 的断言由「胜者唯一 + 败者可能是死锁回滚」**收紧为「败者一定是 `409/TICKET_CONFLICT`」**（用 `singleApiException` 收口：败者若是死锁异常，该断言先失败）。
- **期间修掉两处真实缺陷**：
  1. `close` 里 `Long actorId` 与 `visible.getAssigneeId() != actorId` 构成 **`Long` 与 `Long` 的引用比较**——只有用户 ID 落在 `Long` 缓存区（−128～127）才恰好为真，超过 127 的负责人会关不掉自己的工单（误判 `409`）。统一为基本类型 `long actorId` 并写明理由。
  2. **全量 `verify` 暴露测试支撑缺口**：`MockedPersistenceConfiguration` 少了 `TicketRelationMapper` 替身，导致 `IamUserControllerWebTest` 等与工单无关的 Web 测试一起挂在 `ticketServiceImpl` 的构造注入上（**228 个上下文错误**）。**单类运行不会暴露它**（`TicketControllerWebTest` 把两个工单服务都换成了替身，所以真实 `TicketServiceImpl` 根本没被实例化）——这正是"必须跑全量 verify 而不是只跑改动相关类"的实证。补上替身后全量转绿。
- **测试**（`.\mvnw.cmd -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"` → surefire **970**（基线 881）+ failsafe **154**（基线 140），`Failures 0 / Errors 0`，`BUILD SUCCESS`）：
  - `TicketServiceImplTest` 170 → **220**：`close` 的权限两格、六种非处理中状态、非负责人/无负责人/版本过期、**409 先于 400 的顺序陷阱**、原因码白名单（含 `REQUESTER_NO_RESPONSE` 与大小写）、说明长度与 null、跨字段规则四格、重复目标不可解析、成功写入（关闭字段与 `CLOSURE` 记录逐列断言）、关联写入与失败回滚、条件更新落败；`cancel` 的四种非终态参数化成功路径、三个终态、非提交人（**即使持有 `TICKET_REQUESTER_ACTION`**）、版本过期、空白/null/超长原因、记录里 `completion_method`/`close_method` 必须为空；另加 2 条把转交的新加锁顺序与「锁后版本已变」钉死的用例，以及 8 条既有转交用例补上工单行锁替身。
  - `TicketQueryServiceImplTest` 64 → **78**、`TicketQueryServiceIT` 的 `allowedActions` 断言按新契约更新（处理中负责人 6 → **7** 个动作；提交人在四种非终态上各多一格 `cancel`，待受理/处理中从"空集"变成"恰好一格"）。
  - `TicketControllerWebTest` 121 → **146**（两个端点纳入认证矩阵、`close` 的 8 组非法请求体、跨字段与重复单号去空白进入命令、两个动作的成功/403/404/409 信封）。
  - `TicketServiceIT` 38 → **51**：`close` 的终态字段逐列 + `CLOSURE` 记录、`DUPLICATE` 关联落库与目标未被改动、他人/自身/已取消/已关闭四种非法目标、**已完成的工单可以作为重复目标**、缺任一权限 403 与非负责人（历史参与者）409、版本过期；`cancel` 的期限在同一条 UPDATE 清空、待受理撤销后负责人仍为 NULL、**当前负责人撤不掉别人的工单**、终态再撤销、**三条终态可区分**；两组真并发（`close` vs `cancel`、`claim` vs `cancel`）断言唯一胜者且败者必为 409。清理段新增 `ticket_relation` 的删除（片 D 起工单之间才有引用）。
- **真实栈验收**：`scripts/slice-d-close-cancel-acceptance.ps1`（2535 行）在**并行后端 8092**（脚本不启动/不重启/不结束任何进程，用户自己的 8081 全程未被触碰）上执行 **182 项断言、182 通过、退出码 0**，证据 `docs/acceptance/2026-10-08-slice-d-close-cancel.json`；脚本自建 4 个临时主体（其中两个各自只持有一格关闭权限）与 15 张工单，收尾按主键删除，演示库运行前后的八项计数、工单/记录/参与/关联/用户/用户角色/角色权限逐行指纹一致。**首轮运行按惯例保留为 `docs/acceptance/2026-10-08-slice-d-close-cancel-pre-fix-fail.json`（181 项、2 红），未改写为成功**——两处红都是脚本自己的期望值写错，见下条。
- **验收脚本的两处期望值修正（不是产品缺陷）**：脚本原写「版本过期 + 非法原因码 + 空白说明 → 409」，但 `reasonCode` 的 `@Pattern` 与 `description` 的 `@NotBlank` 会在**进服务层之前**由 Bean Validation 返回 400，这个组合在 HTTP 层永远看不到服务层顺序。改为：`close` 用**跨字段规则**（非重复原因却传了重复单号——没有任何注解能表达，只能在服务层判定）配过期版本，实测 409，并另加一格「同一个过期版本 + 空白说明 → 400」作对照；`cancel` 侧则如实记录 **HTTP 层不存在可观察的「409 先于 400」**（它唯一的服务层 400 是原因长度，注解先把同一个输入拦下了），服务层顺序由 `TicketServiceImplTest.cancelReportsConflictBeforeValidatingReason` 直接覆盖。
- **前端**：`TICKET_ACTIONS` 两格（`close` 紧随 `transfer`、`cancel` 在表尾，两者 `destructive = true`——一次点击进终态且 v1 不支持恢复）、`api/tickets.ts` 的 `closeTicket` / `cancelTicket`、详情页穷尽式派发扩到第 4 个参数。**条件输入**是本次唯一的新交互：`close` 的重复单号只在 `reasonCode = DUPLICATE` 时出现，元数据槽 `conditionalText`（含 `whenValue`）把"隐藏即不发"写成契约，渲染 / 提交前校验 / 提交三处共用**同一个**可见性判定 `activeConditionalField(meta)`，因此"选过 DUPLICATE 又改回 INVALID"时已填的编号不会进请求体；单行输入另建 `TicketActionTextField.vue`（运行时版 Vue 不编译 `template`，在渲染函数里拼 `el-input` 会重演阶段 3 的"填了不回流"）。`select.source` 扩为 `... | 'reasonCode'`（本地静态刻度，与 `priority` 同一先例），未新造第二套"选项从哪来"的机制。单测 **172 → 186 项**（`typecheck`/`lint`/`build` 退出码 0），E2E **20 → 23 项**。**前端二次收口在同一轮按用户裁决改成合取**：`TicketActionMeta.permission` 由单码放宽为 `string | readonly string[]`，`permittedActions` 要求**全部满足**，`close` 登记为 `['TICKET_PROCESS', 'TICKET_CLOSE']` 与后端 `canClose = canProcess && TICKET_CLOSE` 对齐（原先只写 `TICKET_CLOSE`，差别只在"取详情后被撤掉 `TICKET_PROCESS`"的窗口里——按钮还摆着、点下去 403）。注意它与 `authorization.ts` 导航项的"任一满足"**语义相反**，两处注释都写明了取舍；改后重跑 `ticket-close-cancel.spec.ts` **3/3 通过**，证明真实演示账号（同时持有两条权限）仍能看到并执行「关闭工单」。
- **既有 E2E 断言按新契约更新（6 处，均为编码片 D 之前契约的精确集）**：处理中负责人的动作列表 6 → 7 格（`ticket-it-flow`、`ticket-return-actions`、`ticket-supplement-roundtrip` ×2、`ticket-adjust-transfer` ×2）；提交人在待确认/待补充/处理中/待受理上多出「撤销工单」（`ticket-return-actions`、`ticket-supplement-roundtrip`、`ticket-it-flow`、`tickets`）。**其中 `tickets.spec.ts` 与 `ticket-it-flow.spec.ts` 原来断言"员工在待受理上没有任何动作、动作区整块不出现"，现在改为断言"恰好一格撤销"——同一张工单上员工看到撤销、IT 看到领取，这反而是"按钮来自服务端 `allowedActions`"更强的证据。**
- **渲染自查（已实际查看截图）**：`.ui-craft/reviews/2026-10-08-slice-d-close-cancel/`（4 张，1440 与 375）——已取消页的终态语义色、负责人保留、时间线「撤销工单」的原因与 `处理中 → 已取消`，已关闭页的「关闭方式 人工关闭 / 关闭原因 超出支持范围」与属性栏一致，关闭弹窗在选到「重复工单」时才出现单号输入且字段标签、字数计数正常，375 下无横向溢出（E2E 另有断言）。弹窗沿用既有 `.ticket-action-dialog__field` 结构，**未新增 CSS 或 token**。

**明确不在本项范围**：附件（backlog 2）、超时自动任务（backlog 3）、管理性交接（backlog 4，`IamUserServiceImpl` 两处 `//TODO 工单模块未实现`）、数据概览（backlog 5）。

**决策记录：撤销的「两阶段」方向（2026-10-08 登记；同日经用户确认五个设计点后实施，交付记录见本节末）**

登记时的口径是 v1 保持 `docs/kickoff.md` 4.7 已确认的「四种非终态下提交人可以直接撤销」（片 D 实现）。提出该问题的理由是治理层——IT 已经领取并投入处理的工单可以被提交人单方面终止，IT 没有否决权。经分析这不影响正确性与数据：撤销是业务终止而不是删除，负责人、参与关系与全部处理记录都保留，且 4.7 已明确"已取消**不代表问题由 IT 成功解决**"，因此它不会被计入 IT 的解决成果；收窄它反而会让"处理中"的提交人失去唯一出口（误报、问题自行消失、重复提交都只能线下找 IT）。因此登记时只记录方向、未改状态机；用户随后在同一天确认了下列五个设计点并授权实施：

- **目标形态**：`PENDING`（无人负责）仍直接取消；`PROCESSING` / `WAITING_FOR_REQUESTER` / `WAITING_FOR_CONFIRMATION` 下提交人只能**发起**撤销请求，由当前负责人批准或拒绝；批准后进入 `CANCELED`，拒绝则保留原状态并记录原因。（**2026-10-10 变更**：待补充期间不再裁决撤销请求，见本节末「规则变更登记」的 ⑦；本行保留 2026-10-08 的原始记录，不追改。）
- **五个设计点的确认结果（2026-10-08 用户确认，均已落地）**：① 中间态建模取**给 `ticket` 加"待批准撤销请求"的三列**，状态机保持 7 个状态，不新增第 8 个状态；② 批准与拒绝共用 **`TICKET_PROCESS`**，**不要求** `TICKET_CLOSE`（撤销不是关闭）；发起与撤回用 **`TICKET_REQUESTER_ACTION`**，与补充、确认、未解决、直接撤销同码；③ 请求设响应期限（新增配置 `flowdesk.ticket.cancel-request-window`，默认 `3d`、下限 `1m`），但**只写库与展示，到期不自动处置**——超时处理仍属 backlog 第 3 项；④ 请求待批准期间 IT 的其它动作**照常可用**，不冻结工单，竞态继续由条件更新裁决唯一胜者；⑤ 提交人**可以自行撤回**撤销请求，撤回不需要理由。
- **实施时要一并改的影响面**：`docs/kickoff.md` 4.7 的表格/状态图/并发清单、`docs/api-design.md` 6.4 与权限表、新迁移（新列或新状态，以及 `ticket_record` 的 `ck_ticket_record_type` 追加 `CANCELLATION_REQUEST` / `CANCELLATION_REJECTED`）、`allowedActions`、前端动作区与文案、真实栈验收脚本、`PROJECT_STATUS.md`。
- **代价提示**：这是一次业务规则变更而不是补实现，必须与 4.7 的既有条款一起改，不能只改代码；验收标准里"三条终态可区分"仍然成立，但"撤销"的动作名会从一个变成三个（`request-cancel` 与 `approve-cancel` / `reject-cancel`，`cancel` 只剩"待受理直接取消"）。

**两阶段撤销交付记录（2026-10-08，分支 `flow-desk/ticket-two-phase-cancel`，基点 `95eee90`）**

- **范围**：业务规则变更，不是补实现。`docs/kickoff.md` 4.7 的表格、规则清单与状态图，以及 4.8/4.9/4.11/4.12/5.2 的交叉引用一并与代码同步（"撤销"从一个动作名变成四个，`cancel` 收窄到待受理）。
- **数据变更**：`V7__add_cancel_request.sql`——`ticket` 三个可空列（发起时间、说明、响应期限）+ `ck_ticket_cancel_request_pair` + `ck_ticket_cancel_request_status`；`ticket_record` 的 `ck_ticket_record_type` 在同一条 `ALTER` 里重建，追加 `CANCELLATION_REQUEST` / `CANCELLATION_APPROVED` / `CANCELLATION_REJECTED` / `CANCELLATION_REQUEST_WITHDRAWN`。**不新增权限码、不新增索引**；详见 `docs/database-design.md` 的 `V7` 变更说明。
- **基础件（Agent 写入，用户编写 ServiceImpl 期间暂停）**：`Ticket` 三个字段、`TicketProperties` 第三分量 `cancelRequestWindow`（默认 `3d`、下限 `1m`）与 `application.yml`、四个 Command、`TicketCancelRequestResult`（详情新增 `cancelRequest`）、`TicketDetailRow` 三列、`TicketService` 四个方法签名、`TicketController` 四个端点、`TicketMapper` 四条条件更新、`TicketQueryServiceImpl` 的四格 `allowedActions` 与三个判定、`toRecordContext` 的四个 `case`。
- **业务实现（用户编写，Agent 复核并修正）**：`TicketServiceImpl` 的 `requestCancel` / `approveCancel` / `rejectCancel` / `withdrawCancelRequest`；门禁顺序与既有动作逐字对齐（认证 → 权限 403 → 可见性 404 → 状态/身份/版本 409 → 字段 400 → 条件更新）。复核时发现并修正：`requestCancel` 的权限判断写反（有权限反而 403、无权限反而放行）、`approveCancel` 引用了不存在的权限码 `TICKET_CLAIMANT_ACTION`、`TicketMapper` 两条 SQL 的 `update_at` 列名与 `SET status = #{CANCELED}` 占位符错误及多余逗号。
- **三条约束驱动的陷阱（本轮的主要设计内容）**：① `approveCancel` 必须在同一条 UPDATE 里清 `action_deadline_at`——待补充与待确认本身带着期限，只改 `status` 会撞 `ck_ticket_status_deadline`；② `closeManually` 与 `confirmResolution` 必须一并清空请求三列，否则终态残留待决请求会撞 `ck_ticket_cancel_request_status`；③ `toRecordContext` 的 `default` 分支会抛异常，四个新记录类型都必须有 `case`，否则整条时间线 `500`。
- **验证（实跑）**：`.\mvnw.cmd -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"` → surefire **1070** + failsafe **158**，`Failures 0 / Errors 0`，`BUILD SUCCESS`（本分支早期基线为 967 + 155，收口轮净增 +103 / +3）。`DatabaseMigrationIT` 在**空库**上通过 `flyway.migrate()` + `validate()`（迁移基线含 `7`）；两条新 CHECK、收窄后 `cancel` 的 `WHERE` 与四个新记录类型由 `TicketServiceIT` 在真实 MySQL 上实际撞过；新增 `approveCancelClearsDeadlineAndKeepsTerminalStatesDistinguishable`、`rejectAndWithdrawCancelRequestKeepTicketUntouched`、`confirmResolutionClearsThePendingCancelRequestAndCompletesTheTicket`，并把原 `cancelClearsDeadline…` 改写为两阶段、原「关闭 vs 撤销」并发用例改写为「关闭 vs 批准撤销」（仍断言唯一胜者 + 败者必为 `409`）。
- **测试补强（收口轮，65 个方法 / +106 项执行）**：`TicketServiceImplTest` 217 → **279**（四个方法的"权限 403 先于可见性"、404、状态与终态 409、身份 409、版本 409、**409 先于 400**、reason 的 null / 空白 / 1001 / 恰好 1000、成功路径的 mapper 入参与记录逐字段、配置窗口 1h 的精确期限、`approveCancelNeedsNoCloseAuthority`，以及新增的 `cancelReportsConflictOnEveryStatusThatRequiresApproval`）；`TicketQueryServiceImplTest` 78 → **87**（三种状态下"有待决申请 / 没有"的 `allowedActions` 逐格与完整顺序）；`TicketControllerWebTest` 146 → **178**（四端点 401/403/404/409 参数化 + 12 例字段校验 + 四个成功信封与命令去空白）；`TicketQueryServiceIT` 9 → **10**（真库三状态 × 两角色，`cancelRequest` 三字段含 UTC 转换）；`TicketServiceIT` 51 → **54**。
- **为让全量构建可说清而就地修正的既有测试**：`TicketServiceImplTest` 的 `cancel` 用例样本由四种非终态改为 `PENDING`（其中两条原本"因为状态被拒"而**碰巧通过**的用例改回用版本门触发，否则名不副实）、8 处 `TicketProperties` 构造补第三参数；`TicketQueryServiceImplTest` / `TicketQueryServiceIT` 的 `allowedActions` 期望按新规则更新；`TicketControllerWebTest` 的详情构造补 `cancelRequest`；`DatabaseMigrationIT` 迁移基线补 `7`。
- **真实栈验收（实跑）**：`scripts/slice-e-two-phase-cancel-acceptance.ps1`（3104 行、UTF-8 with BOM）在**并行后端 8092**（用户自己的 8081 全程未被触碰）上执行 **178 项断言、178 通过、退出码 0**（28.8 秒），证据 `docs/acceptance/2026-10-08-slice-e-two-phase-cancel.json`（295,387 字节、无 BOM、可 `ConvertFrom-Json` 回读；`invocation` 含 `-BaseUrl`、`evidencePath` 为仓库根相对路径、全文 0 处机器绝对路径）。覆盖：主链（待补充 → 申请 → 批准）、期限来自配置（`3d` ±5 秒）与"到期不自动处置"、驳回后再发起、撤回、`cancel` 收窄的正反两面、403/404/409 三类权限与身份、**三组真并发**（approve vs reject / approve vs withdraw / close vs approve，全部"唯一胜者 + 败者 409 + 0 个 5xx"）、库层约束探针（两条新 CHECK 与重建后的记录类型白名单）、幂等与版本。脚本自建 11 张工单与 2 个临时主体，收尾按主键删除，八项计数与七组逐行指纹运行前后逐字一致。**未发现产品缺陷。**
- **验收首轮的一处红与"不补造证据"的处置**：第一次运行 1 红，原因是**脚本自己的快照锚点取宽**（拿的是权限组之后的快照，而那之后还有一次成功的 `request-cancel`，于是这次合法写入被算成"被拒请求改动过"）；把锚点改为"成功请求之后、拒绝组之前"后连跑两次全绿。失败那次写在默认证据路径并被后续绿色运行覆盖，**按"不伪造证据"的原则没有补造 `-pre-fix-fail` 归档**——片 C/片 D 的归档是原始首跑文件，人为复现出来的不算，本次失败明细与修正理由以 `PROJECT_STATUS.md` 的当前结论段为准。
- **一处已知的实测覆盖缺口（如实记录）**：`close vs approve`（`a11c`）在 4 次运行中**全部由「关闭」获胜**，"批准赢"那一支只有对称断言、没有实测样本；另两组三次运行赢家各不相同，两个分支都被覆盖过。该竞争在集成层由 `TicketServiceIT.concurrentCloseAndApproveCancelOnSameTicketHaveExactlyOneWinner` 稳定覆盖（断言不要求特定赢家），故未额外加压重跑。
- **前端与 E2E（同轮收口）**：动作登记表四格（`request-cancel` / `withdraw-cancel-request` / `approve-cancel` / `reject-cancel`，位置在 `supplement` 与 `cancel` 之间）+ 详情页「撤销申请待处理」块（按角色给决策人 / 发起人 / 旁观者三种文案）+ `ticketRecordTypeLabels` 补 4 个记录类型（漏了时间线会显示英文编码）；前端单测 186 → **200 项**，`typecheck` / `lint` / `build` 退出码 0；E2E 的 5 处旧精确集断言改为「申请撤销工单」，`ticket-close-cancel.spec.ts` 场景一改写为两阶段，新增场景四「申请 → 驳回 → 再申请 → 撤回 → 再申请 → 同意」，全量 **24 项通过（1.8 分钟）**（`FLOWDESK_API_TARGET` 指向 8092 上的新构建后端）。**真实 E2E 抓到的唯一一处红是我自己写错的期望值**：场景一里我以为负责人在申请期间只剩两个决策按钮，实际他仍有全部处理动作（收到的顺序正是 7 + 2 格），已修正后复跑通过。
- **有意保留的未覆盖格**：`reject-cancel` 对未知编号的 404 未单独断言（另三个端点各有一格）；"期限已过"只在**批准**路径上正向验证（该列不进拒绝 / 撤回的 `WHERE`（**已被 2026-10-10 的到期即失效裁决推翻**：三条 `WHERE` 都要加期限条件，该探针改为反向断言，见下方待改清单 ⑤））；`cancel` 的 409 覆盖 `PROCESSING` 与终态，未覆盖两个等待态；并发只做任务要求的三组（approve vs reject、approve vs withdraw、close vs approve）。

- **2026-10-10 补充登记（文档同步，未改代码）**：把已实现但规则未写的四条交互边界补进 `docs/kickoff.md` 4.7 的规则正文（期限过期后请求仍有效；请求不因工单被推进而失效；转交后决策权随当前负责人转移、期限不重算；终态失效不写时间线事件），并新增「负责人失效后请求悬置」的能力缺口说明。其中**转交后决策权转移**与**请求跨状态存活**这两条目前**只有实现层面的事实、没有用例**（本片验收脚本不含 `transfer` 组合），用例待补；另两条分别有集成断言与真实栈探针。（**注意**：这里的第一条「期限过期后请求仍有效」已被同日的裁决取代——见下条的到期即失效与第 ⑦ 条的待补充不裁决；本行保留当时的补充登记原文。）
- **规则变更登记（2026-10-10 用户裁决，待实现）：撤销请求到期即失效**。用户裁决「过期不可批准」，取代 2026-10-08 五个设计点里 ③ 的后半句（工单仍**不**因到期自动取消，这一点不变；变的是请求到期即失效、不再可批）。**待改清单（2026-10-10 集中裁决后固定，实现落地前不变；①～⑥ 与 ⑧ 为待实现，⑦ 已于同日落地）**：① `approveCancel` / `rejectCancel` / `withdrawCancelRequest` 三条条件更新加入期限条件（请求未过期，即 `cancel_request_deadline_at > now`），过期一律 `409` 并带最新快照；② `requestCancel` 放开「已过期即可覆盖」（`cancel_requested_at IS NULL OR cancel_request_deadline_at <= now`），不需要先人工清理，**且不设终身次数上限**（同一时刻只允许一个有效请求，每次到期后都可再发起一次）；③ 覆盖时先写一条新的时间线记录（**裁决确定采用** `CANCELLATION_REQUEST_EXPIRED`，需一条迁移重建 `ck_ticket_record_type`），保存原发起时间、说明与期限——在定时任务（第 3 项）出现之前，这一步是「已过期」唯一会落库留痕的时机；④ `allowedActions` 与详情 `cancelRequest` 增加「已过期」判定，前端详情块显示「已过期」并隐藏批准 / 拒绝 / 撤回三个入口，同时改掉该块里按旧口径写的文案（`frontend/src/views/work/TicketDetailView.vue` 的"到期不会自动处理：工单不会因此自动取消，这次申请也不会自动失效"）；⑤ 用例与验收脚本：现有「期限回填到过去后仍可批准」的探针必须**反向断言**，`docs/acceptance/2026-10-08-slice-e-two-phase-cancel.json` 的 178/178 需重跑出新证据；⑥ 规则文本已在 2026-10-10 的文档同步里改为新口径并标注「待改」，实现落地后去掉该标记；顺带核实两处按旧口径写的代码注释：`frontend/src/api/tickets.ts`（"申请也不会自动失效""只展示，到期不自动处置"两处）与 `TicketServiceImpl` 响应期限落库处的注释（"只落库展示，到期不自动处置"）；`V7` 迁移里的同类 `COMMENT` 属已发布历史迁移，按惯例不改。**代码侧注释落地（2026-10-10，用户授权「代码侧进行注释、文案落地」；只改注释与引用，未动任何判定）**：ⓐ 失效引用「`docs/kickoff.md` 4.7 未来方向」共 4 处已改指 4.7（`TicketProperties`、`TicketServiceImpl.requestCancel`、`RequestCancelCommand`、`TicketCancelRequestResult`）——4.7 早已是正式规则节，该子节名不存在，`V7` 迁移与 `PROJECT_STATUS.md` 里的同类字样属历史记录，按惯例保留；ⓑ 在按旧口径断言的位置加「2026-10-10 裁决已改口径、实现待落地」指针共 **11 处 / 7 个文件**（`TicketProperties` 2、`TicketCancelRequestResult` 1、`TicketServiceImpl` 2、`TicketMapper` 2、`TicketQueryServiceImpl` 1、`frontend/src/api/tickets.ts` 2、`frontend/src/views/work/TicketDetailView.vue` 的 `deadlineFact` 注释 1），每处都指回本条 ①④（`TicketProperties` 类注释与 `deadlineFact` 注释另提 ②）；ⓒ `TicketDetailView.vue` 撤销块的**界面文案**与其组件 / E2E 断言**保持原样**——它们描述的是当前实现，改文案必须与 ① 的判定同批，否则界面会承诺一个还不存在的「重新发起」入口（用户 2026-10-10 选择「现在只落注释、文案随 ①③」）。⑦ **待补充期间不裁决撤销请求（同日按推荐方案裁决，独立于到期语义）**：`TicketMapper.approveCancel` / `rejectCancel` 的状态白名单去掉 `WAITING_FOR_REQUESTER`，`TicketQueryServiceImpl.canApproveCancel` / `canRejectCancel`（共用同一判定）收窄为处理中与待确认；前端只渲染服务端返回的 `allowedActions`，动作登记表无需改动。受影响的既有验证是 `TicketQueryServiceImplTest` / `TicketQueryServiceIT` 里「待补充 + 有待决请求」的两格期望（当前期望为返回）、`slice-e` 验收脚本里「待补充 → 申请 → 批准」的主链（第一步要改成「待补充发起 → 员工补充 → 回到处理中 → 批准」），以及 `ticket-supplement-roundtrip` 等 E2E 里负责人动作集合的精确断言。**转交、撤回补充请求、提交人撤回撤销请求三条路径不变**（用户在裁决时明确保留 4.11 的既有能力）。**⑦ 已落地（2026-10-10，用户授权「直接修改」）**：`TicketMapper` 两条 SQL 的状态白名单、`TicketQueryServiceImpl.canDecideCancelRequest`（新引入 `isCancelDecidableStatus`）与 `TicketServiceImpl.isCancelDecidableStatus`（`approveCancel` / `rejectCancel` 的服务层闸门）三处同步收窄；用例侧把两个"没有待决请求"的参数化从三态收到两态、各加一条"待补充 + 有待决请求 → 409 且不打 SQL"的用例，`TicketQueryServiceImplTest` 的负责人期望拆成"可裁决两态"与"待补充少两格"两条，`TicketServiceIT.approveCancelClearsDeadlineAndKeepsTerminalStatesDistinguishable` 的入口改到「待确认」（保住"批准必须清期限"的真库证据），新增 `approveCancelWaitsUntilTheRequesterSupplements` 与 `withdrawCancelRequestOnWaitingForRequesterKeepsTheSupplementDeadline`；前端详情块的「撤销申请待处理」块补第四种文案（待补充的负责人读到"等提交人补充、补充回来再由你决定"）。**验证**：`.\mvnw.cmd -B test "-Dtest=TicketServiceImplTest,TicketQueryServiceImplTest,TicketControllerWebTest"` → **544 项全绿**；`.\mvnw.cmd -B verify "-Dit.test=TicketServiceIT,TicketQueryServiceIT" ...` → **66 项全绿**（Testcontainers 真库）；前端 `pnpm test:unit`（该组件 42 项）+ `typecheck` + `lint` 退出码 0。**未跑**：全量 `clean verify`、全量前端 E2E 与 8092 真实栈验收（用户明确要求跳过）。`scripts/slice-e-two-phase-cancel-acceptance.ps1` 的主链已改为「待补充发起 → 两次裁决被 409 挡住 → 员工补充 → 回到处理中 → 批准」，因此 `docs/acceptance/2026-10-08-slice-e-two-phase-cancel.json`（178/178）**已不是当前脚本的证据，需重跑后再引用**。

- **规则变更登记（2026-10-10 用户集中裁决，与上条同一批；待实现）⑧：IT 列表「待我批准」筛选与到期语义同批**——列表筛选语义固定为「本人是当前负责人 且 工单上有未过期的待决请求」，与详情里的 `approve-cancel` / `reject-cancel` 两格**共用同一判定**（其上再叠加 `TICKET_PROCESS` 与「待补充期间不裁决」两条既有条件），不允许列表与详情各写一份判定。实现时要连**索引**一起设计（`ticket(assignee_id, cancel_request_deadline_at, id)` 一类，是否带 `status` 与列顺序按真实执行计划确定——`V7` 的「不新增索引」前提就此失效），并按 `docs/api-design.md` 5.3 扩展列表的筛选参数（列表项响应**不**增加待决请求字段）。**验证口径**：因为与到期语义同批，「详情不返回两格」与「列表不返回该工单」必须同时断言，另加一条「请求过期后不再出现在筛选结果里、员工补充回到处理中后重新出现」。**批次顺序**：关联 → 附件 → 本批（①～⑧）→ 管理性交接 → 通知通道 + 两个超时自动任务。

### 9.4 对标成熟工单系统的差距与候选方向（2026-10-10 分析登记，待用户裁决）

用户 2026-10-10 要求"搜索 GitHub 成熟工单项目，对比当前已实现的功能还有哪些不足"。本轮以 11 个知名开源项目核对官方功能页后登记如下（star 为 2026-10-10 检索值：Chatwoot ~37.7k、GLPI ~6.4k、Redmine ~6.0k、Zammad ~6.0k、FreeScout ~4.6k、osTicket ~4.0k、Frappe Helpdesk ~3.4k、MantisBT ~1.8k、iTop ~1.2k、Znuny ~0.6k、OTOBO ~0.3k）。**全部只是候选方向：不改变 MVP 与完整版的既有边界，未确认前不实施。**

对照结论：成熟系统在"外部协同面"普遍领先——**通知、SLA、知识库、报表、自动化、多渠道接入**；本项目已实现的能力（认证与会话、动态 RBAC、用户/分类管理、工单全状态机与时间线、列表多维筛选、乐观锁与 409 契约）在成熟系统中同样普遍，不构成差距；本项目的优势在核心链路深度（状态机、并发唯一胜者、库层约束、验收证据纪律）。**最关键的单一差距是"没有任何通知通道"**——backlog 第 3、7 项的"到期前提醒"正建立其上。

| # | 候选方向 | 对标落差（普遍具备的项目） | 现状与依赖 | 建议 |
| --- | --- | --- | --- | --- |
| 1 | 通知与提醒通道（站内 / 邮件；Webhook 可选） | osTicket、Zammad、GLPI、FreeScout、MantisBT | 本项目无任何通知通道；backlog 第 3、7 项的"到期前提醒"依赖本项 | **优先裁决**——第 3/7 项的共同前置 |
| 2 | 邮件 / 多渠道接入（收信建单、回信出站） | osTicket、Zammad、FreeScout、Chatwoot | 只有站内提交；引入邮件服务属新基础设施，须按 `AGENTS.md` 另行确认 | 评估（展示价值高、成本高） |
| 3 | SLA 与升级提醒 | osTicket（SLA 计划与超时提醒）、Zammad、GLPI、iTop | 现只有"期限展示"；依赖通知通道 | 评估 |
| 4 | 报表与统计仪表盘 | GLPI、Zammad、FreeScout、Chatwoot | 与 backlog 第 5 项「数据概览」重合 | 并入第 5 项一并定范围 |
| 5 | 知识库 / FAQ | osTicket、Zammad、GLPI、OTOBO、FreeScout | 无 | 候选（独立模块，成本中） |
| 6 | 自动化规则 / 触发器 | osTicket（Ticket Filters）、Zammad（Automation）、GLPI（规则引擎） | 现只有计划中的三个固定超时规则（backlog 3） | 与第 3 项一并评估 |
| 7 | 保存筛选与自定义视图 | osTicket（Custom Queues）、Zammad（Overviews）、GLPI（Saved search） | 有即时筛选，无保存视图 | 低成本候选 |
| 8 | 满意度（CSAT） | GLPI、FreeScout | 无 | 低成本候选 |
| 9 | 快捷回复 / 模板 | osTicket、Zammad、Frappe Helpdesk、FreeScout | 无 | 低成本候选 |
| 10 | 批量操作 / 标签 / 自定义字段 / 时间记录 / i18n / 实时刷新 / 对外 API 令牌与 Webhook | 成熟系统普遍具备 | 各有成本、演示收益递减 | 低优先，暂不单独立项 |
| — | 多品牌 / 多租户、SSO、CMDB/资产、语音与社交渠道 | GLPI / iTop / Chatwoot 的另一半方向 | 超出求职 MVP 与既定技术边界 | 明确不考虑 |

**顺序（2026-10-10 用户裁决，取代本节原先的建议顺序）**：backlog 第 2 项（**关联 → 附件**）→ backlog 第 7 项与撤销请求到期语义（**同一批**，含索引）→ backlog 第 4 项管理性交接 → **通知 / 通信通道（本表第 1 项）+ backlog 第 3 项的两个超时自动任务**（通道是它们的共同前置）→ 在 backlog 第 5 项内一并决定报表范围 → 其余逐个裁决（本表第 2、5～9 项仍未裁决）。**完整对比数据、文档一致性清单与本轮修正记录见 `PROJECT_STATUS.md` 的 2026-10-10「对标分析与一致性修正」与「五项集中裁决」两段；本表未裁决项在确认前不进入任何实现。**

## 10. 全局完成与范围控制

任一任务只有同时满足以下条件才可完成：

1. 没有越过当前版本和阶段边界。
2. API、权限、状态、数据和错误行为与文档一致。
3. 成功、失败、越权和并发路径具备与风险匹配的测试。
4. `PROJECT_STATUS.md`、`README.md` 和 `AGENTS.md` 的当前阶段一致。
5. 用户明确授权前不提交、推送、创建或合并 PR。

遇到以下情况必须暂停确认：修改已执行的历史迁移、改变 MVP 四状态主链或固定三角色边界、引入新基础设施、扩大管理员数据权限，或发现安全/跨存储方案无法成立。

## 11. 当前会话开工入口（完整版 backlog）

新会话只需依次阅读：

1. `AGENTS.md`
2. `PROJECT_STATUS.md`
3. 本文第 9 节（完整版 backlog 与各片交付记录）与第 2、4 节（MVP 边界）
4. `docs/api-design.md` 的工单与分类章节（6.3/6.4 动作契约、5.3～5.5 查询、8.4 分类管理）
5. `docs/business-model.md` 的工单状态与角色职责、`docs/database-design.md` 的工单相关表与 CHECK 约束
6. `docs/technical-architecture.md` 5.1（应用层分层硬约束）与涉及事务/并发的部分
7. 现有测试形态参照：`src/test/java/com/flowdesk/iam/application/service/impl/IamUserServiceIT.java`（Testcontainers 真并发）与 `src/test/java/com/flowdesk/iam/controller/IamUserControllerWebTest.java`（Web 契约 + `MockedPersistenceConfiguration`）
8. `frontend/AGENTS.md`（若本阶段要动前端页面）

接续第一步不是直接写业务代码，而是按 `PROJECT_STATUS.md` 与本文第 9 节（或当前主题对应章节）确认范围、交付记录与测试覆盖矩阵，再分片实施。`ServiceImpl` 业务逻辑仍按 `AGENTS.md`「分工补充」由用户编写，**测试类由 Agent 负责**；补齐测试时若发现生产代码缺陷，先报告证据与影响，取得授权后再改。历史入口：阶段 2 的工单切片见本文 6.1，阶段 3 见 7.1，Auth 会话开工卡见 `docs/modules/auth.md` 第 9 节（均已完成，仅供参考）。
