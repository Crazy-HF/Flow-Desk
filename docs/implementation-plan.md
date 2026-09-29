# FlowDesk 开发路线：求职 MVP 与完整版

## 1. 文档目标与当前结论

本文把 FlowDesk 拆成两个交付层级：先完成可演示、可测试、可讲清楚的**求职 MVP**，再按需要扩展为**完整版**。当前只执行 MVP 路线；完整版是后续 backlog，不得因为数据库结构已经预留就提前实现。

截至 2026-09-28（阶段 3 收口并合并 `main` 后）：

- `M0` 工程底座已完成并合并（PR #4）。
- P0 编译、测试、安全基线和端口问题已修复，当前无前置阻塞。
- **阶段 1 Auth 身份入口**（`TASK-010`、`TASK-011`）已完成，经 PR #5 合并 `main`。
- **前端外壳与页面骨架**已完成，经 PR #6 合并 `main`。
- **第 3 步 完整动态 RBAC**（`TASK-055`～`TASK-062`，本文件 9.1）已完成：后端四组接口与用户管理八个端点、`V5` 迁移、管理端五页（角色 / 权限 / 用户角色授权 / 角色权限授权 / 用户管理）、`frontend/src/views/admin/` 页面与真实闭环 E2E 全部交付；验收标准第 4 条的四条手工真实栈链路已于 2026-09-28 在 `local` profile 真实栈上执行并通过（逐条 `traceId` 见 `docs/modules/rbac.md` 11.2），经 PR #8 合并 `main`（合并提交 `f15468c`）。
- **当前阶段：阶段 2 员工创建与查询已验收通过、本次完整交接已授权**（`TASK-020`～`TASK-023-MVP`），分支 `flow-desk/ticket-employee-flow`。最终证据 `docs/acceptance/stage2-closeout-20260929.json`。下一大步骤为阶段 3 IT 处理闭环（第 7 节），尚未开工。
- 第 1 节原先"不提供角色、权限及授权关系的在线 CRUD"这一表述已被 2026-09-21 的确认取代：三种内置角色仍是权限基线，同时在 9.1 范围内开放了动态 RBAC 与用户管理的在线维护。

## 2. 两个版本的边界

| 能力 | 求职 MVP | 完整版 |
| --- | --- | --- |
| 登录、刷新、退出、当前身份、本人改密 | 必须 | 保留并强化 |
| 角色模型 | 固定三角色，可为用户分配一个或多个内置角色 | 可评估自定义角色、权限和授权关系管理 |
| 工单核心链路 | 创建、列表、详情、领取、处理、提交解决、员工确认 | 补充、退回、撤销、转交、异常关闭、超时等完整状态机 |
| 时间线与并发 | 必须；关键动作有记录，领取和状态变更防并发覆盖 | 覆盖全部动作和更完整故障矩阵 |
| 附件和工单关联 | 不做 | 本地受控附件、后续/重复工单关联、文件对账 |
| 管理端 | 在线 RBAC 已于 2026-09-21 确认实施并已完成交付（先于阶段 2），含用户管理页（2026-09-24 登记）；分类管理与管理性交接仍不做 | 用户、分类、管理性交接 |
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
  → 阶段 2 员工创建与查询（当前阶段，分支 flow-desk/ticket-employee-flow）
  → 阶段 3 IT 处理闭环
  → 阶段 4 MVP 验收与求职展示收口
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

目标：用最短但完整的状态链演示员工与 IT 协作。

MVP 状态主链：

```text
PENDING → PROCESSING → PENDING_CONFIRMATION → COMPLETED
```

### TASK-030-MVP：领取与处理

- IT 查看公共队列并领取工单；并发领取只有一个请求成功。
- 仅当前负责人可追加处理记录和提交解决结果。
- 每次状态或业务动作都追加不可变时间线，并使用版本条件更新防止覆盖。

### TASK-032-MVP：解决与确认

- IT 提交解决结果后进入 `PENDING_CONFIRMATION`。
- 仅提交人可确认，确认后进入终态 `COMPLETED`。
- 终态不能再次领取或处理；冲突返回 `409`，前端提示重新加载，不自动重放写操作。

### TASK-033-MVP：核心流程页面

- IT 页面提供队列、负责中列表、详情、领取、处理记录和提交解决。
- 员工详情页提供确认结果。
- Playwright 覆盖员工创建 → IT 领取 → 处理 → 提交解决 → 员工确认。

阶段 3 验收：主链从页面端到端可重复演示；RBAC、资源关系、状态和乐观并发都有后端测试证据。

## 8. 阶段 4：MVP 验收与求职展示收口

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

1. **完整工单状态机**：请求补充、员工补充、撤回请求、未解决退回、员工撤销、IT 转交和异常关闭。
2. **附件与关联**：受控上传/下载、类型与大小限制、临时文件原子移动、失败补偿、孤儿对账、后续/重复工单关系。
3. **自动化**：待确认自动完成、待补充自动关闭、停机恢复和幂等扫描。
4. **系统管理**：~~用户与固定角色分配、账号启停、管理员重置密码~~、活动工单管理性交接、~~分类管理~~。**其中用户管理（列表、详情、创建、改资料、启停、替换角色、重置密码）已于 2026-09-24 随第 3 步提前实施并计入 MVP 演示范围（`TASK-061` 后端 / `TASK-062` 管理端页面）；分类管理已于 2026-09-28 经用户当轮指示提前实施（`TASK-063` 后端 / `TASK-064` 管理端页面，见 9.2）；管理性交接仍留完整版。**
5. **数据概览**：IT 权限范围内的状态、优先级、分类和负责人统计。
6. **动态 RBAC（已于 2026-09-21 确认实施，先于阶段 2）**：**已完成并合并**（PR #8，合并提交 `f15468c`）；任务拆分、切片顺序与验收标准见第 9.1 节。

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

## 10. 全局完成与范围控制

任一任务只有同时满足以下条件才可完成：

1. 没有越过当前版本和阶段边界。
2. API、权限、状态、数据和错误行为与文档一致。
3. 成功、失败、越权和并发路径具备与风险匹配的测试。
4. `PROJECT_STATUS.md`、`README.md` 和 `AGENTS.md` 的当前阶段一致。
5. 用户明确授权前不提交、推送、创建或合并 PR。

遇到以下情况必须暂停确认：修改已执行的历史迁移、改变 MVP 四状态主链或固定三角色边界、引入新基础设施、扩大管理员数据权限，或发现安全/跨存储方案无法成立。

## 11. 当前会话开工入口（阶段 2）

新会话只需依次阅读：

1. `AGENTS.md`
2. `PROJECT_STATUS.md`
3. 本文第 2、4、6 节（MVP 边界与阶段 2 切片）
4. `docs/api-design.md` 的工单相关章节（创建、列表、详情与时间线）
5. `docs/business-model.md` 的工单状态与角色职责、`docs/database-design.md` 的工单相关表
6. `docs/technical-architecture.md` 5.1（应用层分层硬约束）与涉及事务/并发的部分
7. `frontend/AGENTS.md`（若本阶段要动前端页面）

接续第一步不是直接写代码，而是按 `PROJECT_STATUS.md` 与本节确认 `TASK-020`～`TASK-023-MVP` 的接口与数据模型落地顺序，再按切片实施；业务代码按 `AGENTS.md`「学习主导规则」由用户主导，测试由 Agent 负责。历史入口：Auth 会话开工卡见 `docs/modules/auth.md` 第 9 节（阶段 1 已完成，仅供参考）。
