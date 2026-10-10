# FlowDesk API 设计

## 1. 阶段目标与边界

当前状态：API 契约设计已完成并通过最终集中验收。

本阶段把已确认的业务用例映射为前后端共同遵守的接口契约，包括路径、请求、响应、校验、错误、权限、并发和审计影响。

本阶段不生成 Controller、DTO、OpenAPI 文件或其他业务代码，不创建 Spring Boot 或 Vue 工程。

## 2. 全局接口约定

### 2.1 路径与数据格式

**已确认**：

- 所有 v1 业务接口统一使用 `/fd/v1` 前缀。
- 普通请求和响应使用 JSON，时间使用带 UTC 偏移的 ISO 8601 字符串。
- 查询使用资源式路径；领取、转交、确认解决等状态变化使用明确的业务动作路径，前端不能直接修改工单状态。

### 2.2 统一响应 `R<T>`

**已确认**：普通 JSON 响应统一使用 `R<T>`：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `code` | 字符串 | 稳定的机器可读结果编码 |
| `message` | 字符串 | 面向当前调用方的简短说明 |
| `data` | 泛型或 `null` | 成功数据；失败时通常为空 |

示例：

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {}
}
```

**已确认**：

- `code` 使用可读字符串，不使用需要额外对照表的纯数字编码。
- HTTP 状态码仍表达请求结果，不采用“所有响应均返回 200”的方式。
- 文件流、下载响应和 `204 No Content` 不套用 `R<T>`。
- 列表分页数据放入 `R<PageResult<T>>`，分页结构在查询接口组统一设计。

### 2.3 基础 HTTP 状态语义

| HTTP 状态 | 主要场景 |
| --- | --- |
| `200` | 查询或业务操作成功 |
| `201` | 资源创建成功 |
| `204` | 无响应体的操作成功 |
| `400` | 请求格式、字段校验或非法筛选条件错误 |
| `401` | 未登录、Access Token 无效或会话已失效 |
| `403` | 已登录但缺少角色权限或资源权限 |
| `404` | 资源不存在，或基于安全策略不应暴露其存在性 |
| `409` | 工单状态、负责人或版本发生并发冲突 |
| `413` | 上传内容超过限制 |
| `415` | 不支持的媒体类型 |
| `500` | 未预期的服务端错误 |

错误编码必须稳定，`message` 可以调整，前端不得依赖错误文案进行业务判断。

## 3. 认证接口草案

### 3.1 接口总览

| 用例 | 方法与路径 | 认证要求 | 主要结果 |
| --- | --- | --- | --- |
| 登录 | `POST /fd/v1/auth/login` | 匿名 | 创建会话，返回 Access Token 并设置 Refresh Token Cookie |
| 刷新令牌 | `POST /fd/v1/auth/refresh` | Refresh Token Cookie | 轮换 Refresh Token，返回新的 Access Token |
| 退出登录 | `POST /fd/v1/auth/logout` | 当前会话 | 撤销当前会话并清除 Refresh Token Cookie |
| 获取当前用户 | `GET /fd/v1/auth/me` | Access Token | 返回当前用户、角色和权限能力 |

### 3.2 登录

请求字段：

| 字段 | 必填 | 规则 |
| --- | --- | --- |
| `username` | 是 | 去除首尾空白后非空，最长 64 个字符 |
| `password` | 是 | 非空且最长 64 个字符，不记录原文 |

**已确认**：成功响应的 `data` 包含：

- `accessToken`：短期 JWT。
- `tokenType`：固定为 `Bearer`。
- `expiresIn`：Access Token 剩余有效秒数。
- `user`：当前用户的标识、登录名、显示名称、角色编码和权限编码。

Refresh Token 只通过 `HttpOnly` Cookie 返回，不进入 JSON，不允许 JavaScript 读取。

登录失败统一返回 `401` 和 `AUTH_INVALID_CREDENTIALS`，不区分账号不存在、密码错误或账号停用，避免泄露账号状态。

### 3.3 刷新令牌

- 请求体为空，Refresh Token 从受保护 Cookie 读取。
- 成功后旧 Refresh Token 立即失效，同时返回新的 Access Token 并覆盖 Cookie。
- Refresh Token 无效、过期、已撤销或会话不存在时返回 `401`。
- 检测到已经轮换过的 Refresh Token 被再次使用时，撤销整个会话。
- 该接口必须校验允许的请求来源并落实 CSRF 防护，具体 Cookie 属性和防护方案在工程准备阶段确定；来源不在白名单时返回 `403 / ORIGIN_NOT_ALLOWED`。

### 3.4 退出登录

- 撤销当前会话，使该会话关联的 Access Token 和 Refresh Token 均不可继续使用。
- 无论 Cookie 是否仍存在，响应都清除 Refresh Token Cookie。
- **已确认**：对已经退出的同一会话重复调用保持幂等，不泄露会话历史状态。

### 3.5 当前用户

返回前端初始化权限界面所需的最小身份信息：用户标识、登录名、显示名称、角色编码和权限编码。

角色与权限只用于界面展示和路由控制；后端仍对每次请求重新执行 RBAC、资源关系和工单状态校验。

## 4. 认证接口验收结论

**已确认**：

1. `R<T>.code` 使用字符串业务编码，同时保留正确 HTTP 状态码。
2. 登录响应同时返回 Access Token 和最小用户权限信息，减少前端首次加载请求。
3. Refresh Token 只存放于 `HttpOnly` Cookie，Access Token 只由 JSON 返回并保存在前端内存。
4. 退出接口按当前会话幂等处理。

认证接口组就绪状态：**Ready**。

## 5. 工单查询接口草案

### 5.1 设计原则

- 列表、详情、时间线、附件和关联信息执行相同的工单可见性规则。
- 列表接口使用显式查询范围，不根据用户角色暗中切换含义；多角色用户可以选择其当前工作场景。
- 工单详情返回当前快照，历史记录单独分页读取，避免详情响应随工单生命周期持续膨胀。
- 系统管理员的管理性交接只读取必要元数据，后续使用独立管理接口，不复用普通工单详情。

### 5.2 接口总览

| 用例 | 方法与路径 | 主要权限 |
| --- | --- | --- |
| 查询工单列表 | `GET /fd/v1/tickets` | 按 `scope` 校验角色和数据范围 |
| 查询工单详情 | `GET /fd/v1/tickets/{ticketNo}` | 提交人、可见公共队列的 IT、当前或历史参与 IT |
| 查询工单时间线 | `GET /fd/v1/tickets/{ticketNo}/records` | 与工单详情相同 |

### 5.3 列表查询

`scope` 为必填参数：

| `scope` | 含义 | 所需权限与数据约束 |
| --- | --- | --- |
| `REQUESTED_BY_ME` | 当前用户提交的工单 | 普通员工能力；提交人必须为当前用户 |
| `PENDING_QUEUE` | 公共待受理队列 | `TICKET_VIEW_QUEUE`；状态固定为 `PENDING` |
| `ASSIGNED_TO_ME` | 当前或最后负责人是当前用户 | `TICKET_VIEW_PARTICIPATED`；负责人必须为当前用户 |
| `PARTICIPATED_BY_ME` | 当前用户曾经负责过的工单 | `TICKET_VIEW_PARTICIPATED`；必须存在参与关系 |

`ASSIGNED_TO_ME` 与 `PARTICIPATED_BY_ME` 可以有重叠：前者适合当前负责人工作台，后者提供完整参与历史。前端不得通过传入用户 ID 查询他人的范围。

**2026-10-10 裁决（待实现）**：`ASSIGNED_TO_ME` 增加「待我批准」筛选，语义为「本人是当前负责人、且工单上有**未过期**的待决撤销请求」。它与详情里的批准 / 拒绝决定权**共用同一判定**（见 5.4），补上 v1 缺失的 IT 侧发现路径；`V7` 的「不新增索引」以「待决请求不是查询维度」为前提，本筛选让该前提失效，实现时需连同索引一起设计（`docs/database-design.md` 的 `V7` 变更说明与第 23 节）。筛选参数的具体命名在实现时按本节既有惯例确定，本节只固定语义；列表项响应不因此增加待决请求字段。

**已确认**：支持以下公共筛选参数：

- `status`：一个或多个合法状态编码。
- `priority`：一个或多个合法优先级编码。
- `categoryId`：有效分类标识。
- `keyword`：按完整或部分工单编号、标题进行受控搜索。
- `createdFrom`、`createdTo`：按 UTC 创建时间范围筛选。
- `page`：从 1 开始，默认 1。
- `size`：默认 20，最大 100。
- `sort`：使用固定编码 `UPDATED_DESC`、`CREATED_DESC`、`CREATED_ASC` 或 `PRIORITY_DESC_CREATED_ASC`，不接受任意字段名。

`PENDING_QUEUE` 默认使用 `PRIORITY_DESC_CREATED_ASC`，先显示高优先级，再显示较早创建的工单；其他范围默认使用 `UPDATED_DESC`。优先级排序必须显式映射高、中、低顺序，不能按字符串字母顺序排序；所有排序最后追加内部 ID 作为稳定次序。

**收口补充（2026-10-06，`flow-desk/mvp-hardening`）**：列表与时间线都不接受客户端自选排序字段——传入非空 `orderBy` 一律 `400/VALIDATION_FAILED`；`orderDirection` 只接受升序（`asc`，比较忽略大小写，空值按默认升序处理），`desc` 与非法值同样 `400`。`TicketQuery` 与 `TicketRecordQuery` 复用 `PageQuery` 的同一判定，不再各自比较字符串字面量。

分页响应为 `R<PageResult<TicketListItem>>`。`PageResult<T>` 至少包含 `items`、`page`、`size`、`totalElements` 和 `totalPages`。

列表项只返回识别和筛选所需字段：工单编号、标题、分类、优先级、状态、提交人摘要、负责人摘要、当前有效截止时间、创建时间、更新时间和版本号，不返回问题正文或完整时间线。

### 5.4 工单详情

详情以外部业务编号 `ticketNo` 定位，不向前端暴露内部工单主键。响应包含：

- 原始标题、问题描述、提交人、分类和优先级。
- 当前状态、当前或最后负责人、当前有效截止时间和版本号。
- 适用时的完成方式、关闭方式、关闭原因和结束时间。
- 创建时间和最近更新时间。
- **已确认**：返回后端根据当前用户、角色、工单关系和状态计算的 `allowedActions`，用于前端正确展示可用按钮；它不能替代操作接口再次鉴权。
- **2026-10-08 补充（两阶段撤销）**：待批准的撤销请求通过 `cancelRequest` 返回（`requestedAt`、`deadlineAt`、`reason`；没有待决请求时该字段不出现）。请求存在期间工单状态不变，只看 `status` 判断不出"IT 正在等批准"，因此这份数据必须单独返回，界面才能渲染待批准提示与批准 / 拒绝 / 撤回三个入口。
- **2026-10-10 规则变更（用户裁决，待实现）**：`deadlineAt` 将进入判定——**请求过期后批准、拒绝、撤回都不再允许**（`409`，界面刷新后三个入口消失，请求显示为「已过期」），员工可以重新发起并直接覆盖已过期的那一份（覆盖时服务端补写一条「已过期」记录）。**现行代码尚未实现**：期限目前不进任何 `WHERE`，过期仍可批准；落地清单见 `docs/implementation-plan.md` 9.3（①～⑧）。**「已过期」的请求不靠一条新记录表达**，只在详情里以该状态呈现；它一直留在工单上，直到被提交人重新发起的请求替换（替换时服务端补写「已过期」记录）或工单进入终态；「重新发起」不设终身次数上限，同一时刻只允许一个有效请求。**IT 侧发现路径（2026-10-10 裁决，待实现）**：列表新增「待我批准」筛选（见 5.3），与到期语义**同批实施**，并与详情里的批准 / 拒绝决定权**共用同一判定**——「本人是当前负责人 且 工单上有未过期的待决请求」（既有的 `TICKET_PROCESS` 与「待补充期间不裁决」叠加其上，列表与详情必须同时成立或同时不成立）。列表响应**暂不**增加待决请求字段，该判定只用于筛选；将来界面若要在列表上直接显示倒计时，再单独评估响应契约。

无查看权限时统一返回 `404` 和 `TICKET_NOT_FOUND`，不向调用方确认该编号对应的工单是否存在。系统管理员仅有管理员角色时同样不能调用普通详情接口。

### 5.5 工单时间线

- 按 `sequenceNo` 正序分页，保证业务事实顺序稳定。
- 每条记录返回类型、操作者摘要、发生时间及该类型允许公开的结构化上下文。
- 创建记录和员工补充记录可以携带附件元数据；附件内容通过独立下载接口获取。
- 不把数据库中的大量可空字段原样暴露给前端，应按记录类型组织稳定的响应模型。
- 工单关联摘要可随产生关联的记录返回，但查看目标工单仍需独立通过目标工单权限校验。

### 5.6 查询接口验收结论

**已确认**：

1. 使用一个列表接口和必填 `scope` 区分四种工作范围。
2. 采用从 1 开始的页码分页，默认 20、最大 100，暂不引入游标分页。
3. 详情与时间线分开；详情返回 `allowedActions` 辅助前端展示。
4. 无权查看与确实不存在统一返回 `404/TICKET_NOT_FOUND`。

工单查询接口组就绪状态：**Ready**。

## 6. 工单创建与动作接口草案

### 6.1 统一动作约定

- 创建工单使用 `POST /fd/v1/tickets`，成功返回 `201`。
- 已有工单的业务动作统一使用 `POST /fd/v1/tickets/{ticketNo}/actions/{action}`，不提供直接修改 `status` 或 `assigneeId` 的通用接口。
- 除创建外，每个写操作都必须携带客户端最后读取到的 `version`；成功后工单版本递增。
- 版本、状态或负责人已变化时返回 `409`，不静默覆盖，也不自动替用户重试业务动作。
- 成功动作统一返回 `R<TicketActionResult>`，至少包含 `ticketNo`、最新 `status`、负责人摘要、当前期限、最新 `version` 和动作发生时间。
- 每个成功动作都在同一数据库事务内更新工单快照并追加对应的不可变记录；失败时二者均不写入。

### 6.2 创建工单

**阶段 2 MVP 补充（2026-09-28 用户确认）**：继续采用下表的 multipart 契约，仅发送 JSON `ticket` 部分，不发送文件与 `sourceTicketNo`。创建事务写 `ticket` 和首条 `ticket_record`，提交人关系使用 `requester_id`，不写历史 IT 负责人表 `ticket_participant`。阶段 2 详情保留 `allowedActions` 字段并返回空数组；后续阶段实现工单动作后再按权限、关系与状态开放对应动作。

| 项目 | 契约 |
| --- | --- |
| 方法与路径 | `POST /fd/v1/tickets` |
| 权限 | `TICKET_CREATE` |
| 内容类型 | `multipart/form-data` |
| JSON 部分 | `ticket`，类型为 `application/json` |
| 文件部分 | 可选的重复 `files` |
| 成功结果 | 新工单编号、`PENDING` 状态、版本及创建时间 |

`ticket` 部分包含：

- `submissionKey`：客户端为本次提交生成的 UUID，用于防止网络重试产生重复工单。
- `title`：必填，去除首尾空白后长度为 1～200。
- `description`：必填，去除首尾空白后长度为 1～10000。
- `categoryId`：必填且必须指向启用分类。
- `priority`：必填，`LOW`、`MEDIUM` 或 `HIGH`。
- `sourceTicketNo`：可选；必须是当前提交人有权查看的原工单，且不能形成自引用。

**已确认**：`submissionKey` 由 Vue 在用户开始一次提交时生成，同一用户使用同一键重复请求时返回首次创建结果。`ticket` 表使用对应字段及 `(requester_id, submission_key)` 唯一约束，不增加独立幂等表。

**收口补充（2026-10-06）**：唯一约束命中且能查到本次提交的原工单时，按幂等返回首次创建结果；若 `DuplicateKeyException` 来自其他唯一键（工单编号等），返回 `409/TICKET_CREATE_CONFLICT`，客户端可安全重试——此前该分支回落为 `500/INTERNAL_ERROR`。

附件类型、单文件大小、总数量及失败补偿在工程准备阶段确定。v1 直接随创建请求上传，不提前引入临时文件资源或分片上传。

### 6.3 IT 支持人员动作

| 动作路径末段 | 允许状态 | 请求业务字段 | 成功结果 |
| --- | --- | --- | --- |
| `claim` | `PENDING` | `version` | 当前用户成为负责人，进入 `PROCESSING` |
| `add-processing-record` | `PROCESSING` | `version`、`content` | 状态不变，追加处理记录 |
| `change-category` | `PROCESSING`、`WAITING_FOR_REQUESTER` | `version`、`categoryId`、`reason` | 更新分类并追加调整记录 |
| `change-priority` | `PROCESSING`、`WAITING_FOR_REQUESTER` | `version`、`priority`、`reason` | 更新优先级并追加调整记录 |
| `transfer` | `PROCESSING`、`WAITING_FOR_REQUESTER` | `version`、`newAssigneeId`、`reason` | 原子替换负责人，状态不变 |
| `request-supplement` | `PROCESSING` | `version`、`content` | 进入 `WAITING_FOR_REQUESTER`，期限由服务端计算 |
| `withdraw-supplement-request` | `WAITING_FOR_REQUESTER` | `version`、`reason` | 回到 `PROCESSING`，原期限失效 |
| `submit-resolution` | `PROCESSING` | `version`、`content` | 进入 `WAITING_FOR_CONFIRMATION`，期限由服务端计算 |
| `close` | `PROCESSING` | `version`、`reasonCode`、`description`、条件必填的 `duplicateTicketNo` | 进入 `CLOSED` |
| `approve-cancel` | `PROCESSING`、`WAITING_FOR_CONFIRMATION` | `version` | 工单上确有待决撤销请求时进入 `CANCELED`，请求三列清空 |
| `reject-cancel` | 同上 | `version`、`reason` | 状态与期限不变，待决撤销请求失效 |

以上动作都要求当前用户是当前负责人；`claim` 例外，它要求当前用户是有效 IT 支持人员，工单仍无人负责且不能由其本人提交。`approve-cancel` / `reject-cancel` 的状态白名单自 2026-10-10 起**不含待补充**（规则变更，**已实现**）：请求仍留在工单上，但要等员工补充完、工单回到 `PROCESSING` 才能裁决——待补充上两格都不返回，直接调用也返回 `409`。

**实现状态（截至 2026-10-10）**：本表 **11** 个 IT 动作**全部实现**（动作清单本身；条件口径的两处 2026-10-10 规则变更里，**「待补充不裁决」已实现，只剩「期限进判定」按上文标注为待实现**）——片 A～片 D 交付九条：`claim`、`add-processing-record`、`submit-resolution`、`withdraw-supplement-request`（片 A）、`request-supplement`（片 B）、`change-category`、`change-priority`、`transfer`（片 C）、`close`（片 D），加两阶段撤销新增的 `approve-cancel`、`reject-cancel`；6.4 的 **6** 个员工动作同样全部实现——`confirm-resolution`、`report-unresolved`（片 A）、`supplement`（片 B）、`cancel`（片 D），加 `request-cancel`、`withdraw-cancel-request`。**完整工单状态机的四条切片（A～D）至此交付完毕。** 同日（2026-10-08）的**两阶段撤销**规则变更（`docs/kickoff.md` 4.7）把 `cancel` 收窄为「只有待受理可以这样撤销」，并新增四个动作（已计入上一段）。**后端与前端均已实现**：动作登记表四格见 `frontend/src/constants/tickets.ts`，详情页的「撤销申请待处理」块见 `frontend/src/views/work/TicketDetailView.vue`（按角色给四种文案：决策人 / 发起人 / **待补充期间的负责人** / 旁观者）；真实栈验收 178/178（片 E）并已随 [PR #18](https://github.com/Crazy-HF/Flow-Desk/pull/18) 合并 `main`（合并提交 `2cf1bc1`）；交付记录见 `PROJECT_STATUS.md`。
- `claim`：`POST /fd/v1/tickets/{ticketNo}/actions/claim`、`ClaimTicketCommand`、`TicketServiceImpl.claim`、`TicketMapper.claimPending`、`TicketParticipantMapper.recordAssignment`。
- `add-processing-record`：`POST /fd/v1/tickets/{ticketNo}/actions/add-processing-record`、`AddProcessingRecordCommand`、`TicketServiceImpl.addProcessingRecord`、`TicketMapper.advanceAssigneeAction`（状态与负责人不变，只递增 `version` 与 `record_seq` 并追加不可变 `PROCESS` 记录）。
- `submit-resolution`：`POST /fd/v1/tickets/{ticketNo}/actions/submit-resolution`、`SubmitResolutionCommand`、`TicketServiceImpl.submitResolution`、`TicketMapper.submitResolution`（进入 `WAITING_FOR_CONFIRMATION`，期限按 `flowdesk.ticket.confirmation-window`（默认 `7d`）由服务端计算，追加 `RESOLUTION` 记录）。
- `confirm-resolution`（6.4）：`POST /fd/v1/tickets/{ticketNo}/actions/confirm-resolution`、`ConfirmResolutionCommand`、`TicketServiceImpl.confirmResolution`、`TicketMapper.confirmResolution`（进入终态 `COMPLETED`，期限清空、`completion_method='REQUESTER_CONFIRMED'`、写入 `ended_at`，保留负责人，追加 `COMPLETION` 记录）。
- `withdraw-supplement-request`（**2026-10-06 完成，完整状态机片 A**）：`POST /fd/v1/tickets/{ticketNo}/actions/withdraw-supplement-request`、`WithdrawSupplementRequestCommand`（`version` + `reason` 1～1000）、`TicketServiceImpl.withdrawSupplementRequest`、`TicketMapper.withdrawSupplementRequest`（从 `WAITING_FOR_REQUESTER` 回到 `PROCESSING`、清空期限、负责人不变，追加 `SUPPLEMENT_REQUEST_WITHDRAWN` 记录）。
- `report-unresolved`（**2026-10-06 完成，完整状态机片 A**）：`POST /fd/v1/tickets/{ticketNo}/actions/report-unresolved`、`ReportUnresolvedCommand`（`version` + `reason` 1～1000）、`TicketServiceImpl.reportUnresolved`、`TicketMapper.reportUnresolved`（从 `WAITING_FOR_CONFIRMATION` 回到 `PROCESSING`、清空期限、**负责人保留**，追加 `UNSATISFIED_FEEDBACK` 记录；之前的解决结果作为历史保留）。
- `request-supplement`（**2026-10-07 完成，完整状态机片 B**）：`POST /fd/v1/tickets/{ticketNo}/actions/request-supplement`、`RequestSupplementCommand`（`version` + `content` 1～10000）、`TicketServiceImpl.requestSupplement`、`TicketMapper.requestSupplement`（进入 `WAITING_FOR_REQUESTER`，期限按 `flowdesk.ticket.supplement-window`（默认 `7d`）由服务端计算；**状态与期限写在同一条 UPDATE 内**，因此不会出现 `ck_ticket_status_deadline` 不允许的中间态，追加 `SUPPLEMENT_REQUEST` 记录）。
- `change-category` / `change-priority`（**2026-10-07 完成，完整状态机片 C**）：`POST .../actions/change-category`、`.../change-priority`、`ChangeCategoryCommand` / `ChangePriorityCommand`（`version` + `categoryId` / `priority` + `reason` 1～1000）、`TicketServiceImpl.changeCategory` / `changePriority`、`TicketMapper.changeCategory` / `changePriority`（状态、负责人与期限都不变，只替换列并追加 `CATEGORY_CHANGE` / `PRIORITY_CHANGE` 记录；目标分类必须存在且启用）。
- `transfer`（**2026-10-07 完成，完整状态机片 C**）：`POST .../actions/transfer`、`TransferCommand`（`version` + `newAssigneeId` + `reason` 1～1000）、`TicketServiceImpl.transfer`、`TicketMapper.transfer`（原子替换负责人，状态与期限不变，追加 `TRANSFER` 记录并写入 `ticket_participant` 新负责人一行）。**只要求 `TICKET_TRANSFER`，不要求 `TICKET_PROCESS`**——两条授权彼此独立；提交前按 `user_id` 升序锁住双方 `iam_user` 行并复核「启用 + 仍持有 IT_SUPPORT」。
- `GET /fd/v1/tickets/{ticketNo}/transfer-candidates`（**2026-10-07 完成，片 C**，契约见 7.4）：`TicketQueryServiceImpl.transferCandidates` 复用详情同一条可见性 SQL，只返回**启用且持有 `IT_SUPPORT`** 的候选人的 `id` 与 `displayName`，排除提交人与当前负责人；调用者不是当前负责人或状态不在 `PROCESSING`/`WAITING_FOR_REQUESTER` 时 `409`，工单不可见时 `404`，没有候选人时返回空数组。
- `close`（**2026-10-08 完成，完整状态机片 D**）：`POST .../actions/close`、`CloseTicketCommand`（`version` + `reasonCode` ∈ {`DUPLICATE`、`OUT_OF_SCOPE`、`INVALID`} + `description` 1～1000 + 条件必填的 `duplicateTicketNo`，去空白后最长 32）、`TicketServiceImpl.close`、`TicketMapper.closeManually`（状态、`close_method='MANUAL'`、`close_reason` 与 `ended_at` 写在**同一条 UPDATE** 内，避开 `ck_ticket_status_ended` 的中间态；负责人按快照保留，追加 `CLOSURE` 记录并写入 `close_method` / `close_reason` 两个记录列）。
  - **同时要求 `TICKET_PROCESS` 与 `TICKET_CLOSE`**（2026-10-08 用户裁决：关闭是结束整张工单的处置动作，必须建立在处理权限之上；与片 C 的 `transfer` 只要求 `TICKET_TRANSFER` 是两条不同的口径），且只有「处理中」的当前负责人可关闭。
  - `DUPLICATE` 时目标必须存在、属于**同一提交人**、不是自身、状态不是 `CANCELED`/`CLOSED`（`COMPLETED` 与仍在流转的工单都可以）；命中后写一条 `ticket_relation`（`source` = 被关闭的本单，`target` = 解析到的目标，受唯一键 `uk_ticket_relation_source_type` 约束）。目标属于他人与目标不存在都不回显，统一 `400/VALIDATION_FAILED`。其他原因**禁止**传 `duplicateTicketNo`（跨字段规则在服务层判定）。
  - `reasonCode` 刻意**不含** `REQUESTER_NO_RESPONSE`：那是员工逾期未补充时的系统自动关闭（`close_method = AUTO_SUPPLEMENT_TIMEOUT`，操作人记为系统），属 backlog 第 3 项，不走这个人工接口。三种人工原因由 `V1` 的 `ck_ticket_close_semantics` 直接约束，**不需要新迁移**。
- `approve-cancel` / `reject-cancel`（**2026-10-08 完成，两阶段撤销规则变更**）：`POST .../actions/approve-cancel`、`.../actions/reject-cancel`、`ApproveCancelCommand`（只有 `version`）、`RejectCancelCommand`（`version` + `reason` 1～1000）、`TicketServiceImpl.approveCancel` / `rejectCancel`、`TicketMapper.approveCancel` / `rejectCancel`。两条 SQL 形状相同，只有状态迁移那一行不同，都要求 `assignee_id = actor`、状态落在三个「有人负责且未终结」的状态、且 `cancel_requested_at IS NOT NULL`（版本与"确有待决请求"一起进 `WHERE`，因此批准、拒绝、提交人撤回三者并发时只有一条能命中）。**2026-10-10 规则变更（已实现）**：状态白名单去掉 `WAITING_FOR_REQUESTER`——待补充期间这两格不再返回，两条 SQL 的 `WHERE status` 也不接受该状态；请求仍保留在工单上，员工补充完、工单回到 `PROCESSING` 即可裁决。转交与撤回补充请求不受影响。
  - **批准**进入终态 `CANCELED`，并在**同一条 UPDATE** 内清空 `action_deadline_at`、清空请求三列、写入 `ended_at`：待补充与待确认本身带着期限（`ck_ticket_status_deadline` 要求其它状态期限为空），终态又不允许残留待决请求（`ck_ticket_cancel_request_status`），三者只能一起写；负责人按快照保留，追加 `CANCELLATION_APPROVED` 记录。
  - **拒绝**不改状态、不改期限，只让请求失效，拒绝原因写入 `CANCELLATION_REJECTED` 记录——没有原因，提交人只看到"被拒绝"而无从调整。
  - 权限只要 **`TICKET_PROCESS`**，**不要 `TICKET_CLOSE`**：批准撤销让工单进入"已取消"而不是"已关闭"，与上面 `close` 的双权限口径是两条不同的线。
  - **期限进判定（2026-10-10 规则变更，待实现）**：`cancelRequest.deadlineAt` 将参与条件更新——**请求过期后批准 / 拒绝 / 撤回一律 `409`**，员工可以重新发起并覆盖已过期的那一份（覆盖时补写「已过期」记录），**不设终身次数上限**。**现行代码尚未实现**：期限目前不在任何 `WHERE` 里，过期仍可批准。**列表「待我批准」筛选（第 7 项）与本条同批实施**，并与这两格的返回条件**共用同一判定**（本人是当前负责人 + 有未过期的待决请求；再叠加 `TICKET_PROCESS` 与「待补充不裁决」），见 5.3/5.4。到期前提醒与过期请求的自动清理属完整版 backlog 第 3 项，与通知通道一并落地。

**本表契约自 2026-10-06 起未修改**（片 A～片 D 都是按本表实现，未回头改口径）。动作结果统一为 `TicketActionResult`（`ticketNo`、最新 `status`、负责人摘要、当前期限、最新 `version`、动作时间）。详情 `allowedActions` 按条件返回，装配顺序固定为 `claim` → `add-processing-record` → `submit-resolution` → `request-supplement` → `confirm-resolution` → `withdraw-supplement-request` → `report-unresolved` → `supplement` → `change-category` → `change-priority` → `transfer` → `close` → `request-cancel` → `withdraw-cancel-request` → `approve-cancel` → `reject-cancel` → `cancel`（共 **17** 格，与 `TicketQueryServiceImpl` 的 `allowedActions.add` 调用顺序逐字一致）：`claim`（待受理、非提交人、具备领取资格）；**处理中与待补充的当前负责人**：`add-processing-record`、`submit-resolution`、`request-supplement`、`change-category`、`change-priority`（以上需 `TICKET_PROCESS`）与 `transfer`（需 `TICKET_TRANSFER`）；待补充的当前负责人：`withdraw-supplement-request`；待确认的提交人：`confirm-resolution`、`report-unresolved`；待补充的提交人：`supplement`；**处理中的当前负责人**：`close`（需 `TICKET_PROCESS` **与** `TICKET_CLOSE` 同时成立）；**三个「有人负责且未终结」状态上的提交人**：`request-cancel`（需 `TICKET_REQUESTER_ACTION`，且当前**没有**待决请求）与 `withdraw-cancel-request`（需 `TICKET_REQUESTER_ACTION`，且当前**有**待决请求）；**处理中与待确认**上的当前负责人：`approve-cancel`、`reject-cancel`（需 `TICKET_PROCESS`，且当前有待决请求；两条共用同一判定，与 `close` 的双权限口径不同；**2026-10-10 规则变更后待补充不再返回这两格，已实现**）；**只有待受理的提交人**：`cancel`（需 `TICKET_REQUESTER_ACTION`）。同一张工单上 `close` 与 `cancel` 永远不会同时出现——前者要求"本人是负责人"，后者要求"本人是提交人"，而 `ck_ticket_assignee_not_requester` 禁止两者是同一人。**`close` 与 `approve-cancel` 则可以同时出现**：撤销请求待批准期间工单不冻结，负责人本来就同时持有处理权限与关闭权限，两者与其它处理动作一样照常可用，谁先提交由 `version` 条件更新裁决（两阶段撤销的既定设计：请求期间不改变工单状态，也不改变谁能动它）。逐条 `traceId` 的验收证据：阶段 3 见 `docs/acceptance/2026-10-06-stage3-claim-process-resolution-confirm.json`（**77/77 通过**）与汇总 `docs/acceptance/2026-10-06-stage3-step3-summary.json`；片 A 见 `docs/acceptance/2026-10-06-slice-a-return-actions.json`（78/78）；片 B 见 `docs/acceptance/2026-10-07-slice-b-supplement-roundtrip.json`（67/67）；片 C 见 `docs/acceptance/2026-10-07-slice-c-adjust-transfer.json`（127/127）；片 D 见 `docs/acceptance/2026-10-08-slice-d-close-cancel.json`；阶段进度见 `docs/implementation-plan.md` 7.1。

**`submit-resolution` 从 `allowedActions` 缺失的修正（2026-10-06，用户当场授权）**：`TicketQueryServiceImpl.toDetail` 原先只构造 `claim`、`add-processing-record`、`confirm-resolution` 三个动作，接口虽已实现并验收，详情却不返回该动作名。界面按 `allowedActions` 渲染按钮，因此负责人能写处理记录却交不出解决结果——阶段 3 端到端主链（`frontend/e2e/ticket-it-flow.spec.ts`）在"提交解决结果"这一步实测暴露。现在 `canSubmitResolution` 复用 `canProcess` 的同一条判定（处理中 + 本人是负责人 + `TICKET_PROCESS`），两者前置条件完全相同，不复制表达式。

处理正文、补充请求、员工补充正文和解决结论去除首尾空白后最长 10000 个字符；要求必填时长度至少为 1。转交、调整、撤回、未解决、取消和关闭说明最长 1000 个字符，并在对应动作中要求非空。

关闭原因为 `DUPLICATE` 时，`duplicateTicketNo` 必填，目标必须是同一提交人的另一张有效工单；其他关闭原因禁止传该字段。

**口径补全（2026-10-06，用户确认）**：这里的"有效工单"指——目标存在、属于**同一提交人**、不是自身，且当前状态**不是 `CANCELED` 或 `CLOSED`**。`COMPLETED` 与仍在流转的工单都可以作为重复目标：重复关系只说明"同一问题已有另一张单"，不要求目标仍在处理中。校验不通过按 `400/VALIDATION_FAILED` 处理（目标不可见时不得回显目标是否存在，沿用同一提交人这一约束）。

### 6.4 员工动作

| 动作路径末段 | 允许状态 | 请求业务字段 | 成功结果 |
| --- | --- | --- | --- |
| `supplement` | `WAITING_FOR_REQUESTER` | `version`、正文和/或附件 | 回到 `PROCESSING`，原期限失效 |
| `confirm-resolution` | `WAITING_FOR_CONFIRMATION` | `version` | 进入 `COMPLETED`，完成方式为员工确认 |
| `report-unresolved` | `WAITING_FOR_CONFIRMATION` | `version`、`reason` | 回到 `PROCESSING`，原负责人保留 |
| `request-cancel` | `PROCESSING`、`WAITING_FOR_REQUESTER`、`WAITING_FOR_CONFIRMATION` | `version`、`reason` | 状态与期限不变，工单上写入待批准撤销请求（含响应期限） |
| `withdraw-cancel-request` | 同上 | `version` | 状态与期限不变，待决撤销请求失效 |
| `cancel` | 仅 `PENDING` | `version`、`reason` | 进入 `CANCELED` |

这些动作都要求当前用户是工单提交人。`supplement` 使用 `multipart/form-data`，正文和附件至少存在一项；其他动作使用 JSON。

**实现状态（截至 2026-10-08）**：`confirm-resolution`（阶段 3）、`report-unresolved`（片 A）、`supplement`（片 B）、`request-supplement`（片 B，IT 侧）与 `cancel`（**片 D**）均已实现；同日的两阶段撤销规则变更再加 `request-cancel` 与 `withdraw-cancel-request`，并把 `cancel` 收窄为「只有 `PENDING`」。`supplement` 的实现见 6.3 的状态清单。

- `request-cancel` / `withdraw-cancel-request`（**2026-10-08 完成，两阶段撤销规则变更**）：`POST .../actions/request-cancel`、`.../actions/withdraw-cancel-request`、`RequestCancelCommand`（`version` + `reason` 1～1000）、`WithdrawCancelRequestCommand`（只有 `version`）、`TicketServiceImpl.requestCancel` / `withdrawCancelRequest`、`TicketMapper.requestCancel` / `withdrawCancelRequest`。
  - **发起**：工单状态、负责人与工单自身的期限都不变，只把 `cancel_requested_at` / `cancel_request_reason` / `cancel_request_deadline_at` 三列**写在同一条 UPDATE 内**（`ck_ticket_cancel_request_pair` 要求三列同生同灭），响应期限按 `flowdesk.ticket.cancel-request-window`（默认 `3d`，下限 `1m`）由服务端计算；追加 `CANCELLATION_REQUEST` 记录（`from_status` 与 `to_status` 相同，与 `PROCESS` / `TRANSFER` 等不迁移状态的动作同一写法）。`cancel_requested_at IS NULL` 也在 `WHERE` 里：**一张工单同时只能有一个待决请求**，重复发起是 `409/TICKET_CONFLICT`，不静默覆盖前一次的说明。**2026-10-10 规则变更（待实现）**：该条件放宽为「没有**未过期**的待决请求」（`cancel_requested_at IS NULL OR cancel_request_deadline_at <= now`）——过期的请求可被新请求直接覆盖，覆盖时先补写一条「已过期」记录，且**不设终身次数上限**；同批落地列表「待我批准」筛选（5.3），两者共用「本人是当前负责人 + 有未过期待决请求」这一判定。
  - **撤回**：只有提交人本人能撤回自己的请求（当前负责人即使同时持有 `TICKET_REQUESTER_ACTION` 也不行），状态与期限不变，请求三列清空，追加 `CANCELLATION_REQUEST_WITHDRAWN` 记录。撤回**不写原因**——请求里原本的说明仍然留在 `CANCELLATION_REQUEST` 记录上。
  - 两个动作的权限都是 `TICKET_REQUESTER_ACTION`，身份都要求**本人是提交人**，状态都要求属于三个「有人负责且未终结」的状态。
- `cancel`（**2026-10-08 完成，完整状态机片 D；同日随两阶段撤销收窄到「待受理」**）：`POST .../actions/cancel`、`CancelTicketCommand`（`version` + `reason` 1～1000）、`TicketServiceImpl.cancel`、`TicketMapper.cancel`（进入终态 `CANCELED`；**状态与 `action_deadline_at` 写在同一条 UPDATE 内**，因此不会出现 `ck_ticket_status_deadline` 不允许的中间态；`ended_at` 一并写入，`version` 与 `record_seq` 各 +1，追加 `CANCELLATION` 记录）。
  - 权限是 `TICKET_REQUESTER_ACTION`，身份要求**本人是提交人**，状态**只允许 `PENDING`**——待受理没有负责人，不需要谁批准。处理中、待补充、待确认改走 `request-cancel` → `approve-cancel`（**2026-10-10 规则变更，已实现**：待补充期间负责人不裁决，要等员工补充完、工单回到处理中）；三个终态同样 `409/TICKET_CONFLICT`；**当前负责人撤不掉别人的工单**——他即使同时持有 `TICKET_REQUESTER_ACTION`，只要不是提交人就是 `409`。
  - 待受理本来没有负责人，`ck_ticket_status_assignee` 对「已取消」带不带负责人都允许。撤销**不写** `completion_method` / `close_method` / `close_reason`，因此「已取消」与「已完成」「已关闭」在库层面可区分（`docs/kickoff.md` 4.7：已取消不等于 IT 解决了问题）。
  - 直接撤销的原因只存在于不可变时间线的 `CANCELLATION` 记录里；两阶段路径的说明存在 `ticket.cancel_request_reason`，批准进入终态时随请求三列一并清空，说明本身仍留在 `CANCELLATION_REQUEST` 记录上。**v1 不支持恢复**，员工仍需处理时应新建工单。

**范围裁决（2026-10-06，用户确认）**：附件上传/下载、文件与数据库提交顺序、失败补偿与孤儿对账属完整版 backlog 第 2 项，尚未设计落地。因此 `supplement` **先按"正文版"实现**：请求形状不变（`multipart/form-data`，`ticket` part 携带 JSON），但只接受正文；请求里出现文件 part 时返回 `400/VALIDATION_FAILED` 并说明本版本不支持附件，**不静默忽略**。附件落地后本条随之失效。

### 6.5 非公开系统动作

待确认超时完成和待补充超时关闭由应用内部定时用例触发，不暴露给前端，也不设计可由管理员手动调用的 HTTP 接口。它们与人工动作使用相同的状态、期限和版本条件更新规则。

### 6.6 并发、幂等与错误

- 领取、转交、补充、解决、确认、撤销和关闭等状态动作依靠 `version`、预期状态及预期负责人共同防止重复执行。撤销请求的**批准、拒绝、撤回**三者共用同一组条件（版本 + 应当事人 + 三个允许状态 + `cancel_requested_at IS NOT NULL`），并发时只有一条 SQL 能命中，另外两路得到 `409` 而不是互相覆盖；撤销请求期间 IT 的其它动作照常可用，由同一套条件更新裁决唯一胜者。**2026-10-10 规则变更（待实现）**：这组条件再加一条期限条件（请求**未过期**），于是「请求刚过期」与「请求已被别人处理」都收敛到同一种 `409/TICKET_CONFLICT`（响应仍带最新 `version` 与 `status`，调用方刷新详情即可看到「已过期」）；同一个判定（本人是当前负责人 + 有未过期待决请求）同时决定列表「待我批准」筛选与详情里的批准 / 拒绝入口，见 5.3 / 5.4 / 6.3。
- 动作响应丢失后，客户端重新读取详情；若版本已变化，不用旧版本盲目重放动作。
- 创建工单使用 `submissionKey` 单独防重，因为创建前不存在可校验的工单版本。
- 无权查看目标工单返回 `404/TICKET_NOT_FOUND`；能够查看但无权执行动作返回 `403/TICKET_ACTION_FORBIDDEN`。
- 状态、负责人或版本冲突返回 `409/TICKET_CONFLICT`，响应 `data` 返回当前 `version` 和 `status` 的最小冲突信息，便于前端刷新。
- 字段错误返回 `400/VALIDATION_FAILED`；`data` 包含字段级错误列表，但不返回异常堆栈或内部实现信息。

### 6.7 创建与动作接口验收结论

**已确认**：

1. 使用明确的 `/actions/{action}` 接口，不提供通用状态更新接口。
2. 创建和员工补充直接使用 `multipart/form-data` 携带 JSON 与文件。
3. 所有已有工单写操作强制携带 `version`，统一返回最新快照摘要。
4. 使用 `submissionKey` 保证创建幂等，并相应补充数据库字段和唯一约束。
5. 系统超时动作不开放 HTTP 接口。

工单创建与动作接口组就绪状态：**Ready**。

## 7. 附件与辅助查询接口草案

### 7.1 附件上传限制

附件只随“创建工单”或“员工补充”动作上传，不提供独立上传、替换或删除接口。

**已确认**：v1 使用以下限制：

- 单个文件最大 10 MiB。
- 单次请求最多 5 个文件，文件总大小最大 25 MiB。
- 原始文件名最长 255 个字符；空文件拒绝上传。
- 允许 JPEG、PNG、PDF、TXT、DOCX 和 XLSX。
- 同时校验扩展名、声明的媒体类型和服务端识别结果，不能只相信客户端 `Content-Type`。
- 拒绝可执行文件、脚本、HTML、SVG、压缩包和其他未列入白名单的格式。

任一附件校验或保存失败时，整个创建或补充动作失败，不能留下已提交的工单快照、记录、附件元数据或孤立文件。具体文件与数据库提交顺序及失败补偿在工程准备阶段落实并测试。

文件使用服务端随机存储标识，保存在 Web 根目录之外；数据库只保存元数据，客户端文件名不得参与真实磁盘路径拼接。

### 7.2 附件下载

| 项目 | 契约 |
| --- | --- |
| 方法与路径 | `GET /fd/v1/tickets/{ticketNo}/attachments/{attachmentId}/content` |
| 响应 | 原始文件流，不使用 `R<T>` |
| 权限 | 调用者必须能够查看该工单，且附件必须真实属于该工单 |

下载响应设置服务端确认的 `Content-Type`、文件长度、经过安全编码的 `Content-Disposition: attachment` 和 `X-Content-Type-Options: nosniff`。

附件不存在、不属于路径中的工单或调用者无工单查看权时，统一返回 `404`。不能只根据 `attachmentId` 读取文件，也不能暴露真实磁盘路径。

### 7.3 分类选项

| 项目 | 契约 |
| --- | --- |
| 方法与路径 | `GET /fd/v1/categories/options` |
| 权限 | 已登录且具有创建工单或处理工单能力 |
| 响应 | `R<List<CategoryOption>>` |

只返回启用分类的 `id` 和 `name`，按 `sortOrder`、`id` 稳定排序。历史工单详情中的停用分类直接随工单快照返回，不依赖该选项接口。

### 7.4 转交候选人

| 项目 | 契约 |
| --- | --- |
| 方法与路径 | `GET /fd/v1/tickets/{ticketNo}/transfer-candidates` |
| 权限 | 当前负责人且具有 `TICKET_TRANSFER` |
| 响应 | `R<List<AssigneeOption>>` |

只返回可接收该工单的启用 IT 用户标识和显示名称，排除提交人、当前负责人以及不再具有 IT 处理能力的用户。接口必须重新校验工单状态为 `PROCESSING` 或 `WAITING_FOR_REQUESTER`。

普通员工不能调用该接口；系统管理员的管理性交接候选人由后续管理接口单独提供，避免扩大管理员对工单内容的访问。

**实现状态（2026-10-07，片 C）**：「不再具有 IT 处理能力」在本版本取**角色口径**——`iam_user.status = 'ENABLED'` 且仍持有 `IT_SUPPORT` 角色，与 `claim` 的领取资格共用同一条判定（`IamTicketClaimantAdapter.lockEligibleClaimant`），因此候选人列表与转交动作的资格复核不会出现两套标准。返回元素为 `TicketAssigneeOptionResult`（只有 `id` 与 `displayName`，不含用户名或角色）。**没有候选人时返回空数组而不是错误**，界面据此显示空态并禁止提交。调用者不是当前负责人（但有权查看）或状态不在可处理态时 `409/TICKET_CONFLICT` 并带当前快照，工单不可见时 `404/TICKET_NOT_FOUND`。注意一个已知口径：若管理员把 `TICKET_TRANSFER` 权限从 `IT_SUPPORT` 角色上撤掉，候选人仍会出现（他依然能处理工单），只是接手后不能再转交——因为"能否接手"与"能否再转交"是两条独立授权。验证证据见 `docs/acceptance/2026-10-07-slice-c-adjust-transfer.json`（127/127 通过）。

### 7.5 关联工单选择

- 创建后续工单时，前端复用 `GET /fd/v1/tickets?scope=REQUESTED_BY_ME` 搜索当前员工自己的原工单。
- IT 关闭重复工单时不新增跨权限搜索接口；负责人输入或从自己已有查看范围选择目标编号，服务端再次校验目标存在、同一提交人、不是自身且调用者有权查看。
- 返回关联摘要不代表获得目标工单详情权限；打开目标工单时仍执行独立权限校验。

### 7.6 附件与辅助查询验收结论

**已确认**：

1. 单文件 10 MiB、单次最多 5 个且总计不超过 25 MiB。
2. 白名单只包含 JPEG、PNG、PDF、TXT、DOCX 和 XLSX。
3. 附件下载使用嵌套工单路径、强制下载，并重新校验工单与附件归属。
4. 分类选项和转交候选人使用最小字段专用接口，不开放通用用户搜索。
5. v1 不提供独立附件上传、删除、预览或分片上传。

附件与辅助查询接口组就绪状态：**Ready**。

## 8. 系统管理与数据概览接口草案

### 8.1 管理接口通用规则

- 分类管理、管理性交接和 RBAC 管理接口使用 `/fd/v1/admin` 前缀；用户管理接口统一使用 `/fd/v1/users`。两类接口均要求对应的管理员业务权限。
- **版本边界**：本节系统管理接口整体属于完整版，均不阻塞求职 MVP。MVP 只使用 `EMPLOYEE`、`IT_SUPPORT`、`SYSTEM_ADMIN` 三种内置角色。**例外：8.2.1 的自定义 RBAC 已于 2026-09-21 经用户确认提前实施（先于阶段 2），阶段设计、任务拆分与验收标准见 `docs/implementation-plan.md` 9.1；2026-09-22 确认计入求职 MVP 演示范围，除后端接口、迁移与测试证据外还包含 RBAC 管理端页面（`TASK-060`）。8.2 用户管理已于 2026-09-24 经用户确认正式登记为阶段任务（`TASK-061` 后端、`TASK-062` 管理端页面）并计入求职 MVP 演示范围。**
- 管理性交接（8.3）与数据概览（8.5）仍按原计划留到完整版，本次变更不放宽它们的边界；用户管理（8.2）与分类管理（8.4）已提前实施并从完整版 backlog 移出。
- **实现状态（2026-09-28）**：8.2 用户管理八个端点与 8.2.1 四组 RBAC 端点（角色、权限、用户角色授权、角色权限授权，含批量授予、批量撤销、清空全部与一个角色授予多个用户，共 21 个端点）**均已完成并合并 `main`**（PR #8，合并提交 `f15468c`），管理端五页可走通真实闭环；测试与真实栈证据见 `docs/modules/rbac.md` 第 10、11 节与 `PROJECT_STATUS.md` 2026-09-28 记录。**8.3 管理性交接仍是 `TODO`，属完整版。**
- **实现状态（2026-09-28，分类管理）**：8.4 的六个 `/fd/v1/admin/categories` 端点与对应管理端页面（`/admin/categories`）经用户当轮指示提前实施，登记为 `TASK-063`（后端）与 `TASK-064`（页面）；契约本身不变。**分类管理后端与页面已完成运行时验收；HTTP 权限/参数/版本/重名/引用保护证据见 docs/acceptance/2026-09-29-category-ticket-scopes.json，页面 CRUD 与 1440/375 截图证据见 .ui-craft/reviews/2026-09-29-tickets/；本次纳入完整交接**，详见 `docs/implementation-plan.md` 9.2。
- 若启用动态 RBAC，`SYSTEM_ADMIN` 必须是受保护的内置角色；角色或权限仍被授权关系引用时禁止删除。
- 用户不提供物理删除接口，分类只有从未被工单引用时才允许删除。
- 用户、角色或账号状态变化成功后，撤销受影响用户的现有登录会话。
- 涉及活动工单交接时必须全部成功或全部失败，不能先停用账号再留下无效负责人。

### 8.2 用户与凭据管理

**实现状态（2026-09-24 登记）**：本节的 8 个 `/fd/v1/users` 端点与"当前用户修改密码"均已实现并有完整测试（单元/Web + 真实 MySQL 集成），登记为 `TASK-061`；管理端页面登记为 `TASK-062`。实现与本节契约一致，**替换角色使用复数路径 `PUT /fd/v1/users/{userId}/roles`**（实现曾用单数 `/role`，2026-09-24 经用户裁决改为跟随契约）。

| 用例 | 方法与路径 | 说明 |
| --- | --- | --- |
| 用户列表 | `GET /fd/v1/users` | 按关键词、状态和角色分页筛选 |
| 用户详情 | `GET /fd/v1/users/{userId}` | 返回账号、角色、状态和版本，不返回密码摘要 |
| 创建用户 | `POST /fd/v1/users` | 提交登录名、显示名称、初始密码和角色（允许零角色，见下方说明） |
| 修改基本资料 | `PUT /fd/v1/users/{userId}` | 修改显示名称并携带 `version` |
| 启用账号 | `POST /fd/v1/users/{userId}/actions/enable` | 携带 `version` |
| 停用账号 | `POST /fd/v1/users/{userId}/actions/disable` | 携带版本和必要交接方案 |
| 替换角色 | `PUT /fd/v1/users/{userId}/roles` | 携带版本、完整角色集合和必要交接方案 |
| 管理员重置密码 | `POST /fd/v1/users/{userId}/actions/reset-password` | 携带版本，设置新密码并撤销目标用户全部会话 |
| 当前用户修改密码 | `POST /fd/v1/auth/change-password` | 校验原密码，修改成功后撤销当前用户全部会话 |
| 角色、权限与授权关系管理 | 见 8.2.1 | 已按 2026-09-21 确认提前实施，计入求职 MVP 演示范围 |

**已确认**：密码长度为 8～64 个字符，不在接口文档或日志中返回密码；演示版不通过邮件发送初始密码或重置链接，由管理员通过项目演示场景之外的安全渠道告知用户。

禁止停用最后一个启用的管理员，也禁止移除其管理员角色。**用户允许零角色（2026-09-22 确认，取代原「每个用户至少保留一个角色」）**：撤销全部角色后账号仍可登录，但没有任何业务权限，只能得到空结果或 `403/ACCESS_DENIED`；`USER_ROLE_REQUIRED` 随之废弃（见 10.2）。登录名创建后不可修改，避免身份引用和审计含义变化。MVP 只能从固定三角色中分配；若完整版启用自定义权限，权限编码也只有在后端已有对应授权检查时才产生实际访问能力，新增数据本身不会自动生成业务接口或安全规则。

### 8.2.1 自定义 RBAC 管理

**2026-09-21 已确认实施，阶段设计同日确认**（原文为“完整版可选，MVP 明确不实现”）。所有本节接口均要求 `RBAC_MANAGE`：通过新的 Flyway 迁移（`V5`）预置此权限并授予受保护的 `SYSTEM_ADMIN` 角色；不得修改已发布的历史迁移。任务拆分、切片顺序与验收标准见 `docs/implementation-plan.md` 9.1。本节补充确认的语义：

- **审计**：`iam_role_permission` 原无审计列，`V5` 为其补 `granted_by` / `granted_at`（允许为空，存量行不伪造时间）；新授权必须同时写入两列，`granted_by` 取当前操作人。`iam_user_role` 沿用建表时已有的同名两列。
- **会话撤销**：授予或撤销用户角色成功后，撤销该用户全部会话；授予或撤销角色权限成功后，撤销当前拥有该角色的**全部用户**会话。两者都按“**先撤销 Redis 会话，后提交 MySQL 授权变更**”的顺序执行，与改密一致；角色权限变化时，在持有角色锁后通过 `FOR UPDATE` 按 `user_id` 升序取得受影响用户快照并逐个撤销。任一撤销失败则本次 MySQL 事务回滚。撤销会话经 IAM 定义的 `SessionRevocationPort` 完成，IAM 不依赖 auth 模块。
- **批量增量授予与幂等**：用户角色授予采用“一个 `userId` + 多个 `roleIds`”，角色权限授予采用“一个 `roleId` + 多个 `permissionIds`”。服务端先对目标 ID 去重并升序归一化，只新增尚不存在的授权关系，已存在关系保持原 `grantedBy` / `grantedAt`；请求中的任一用户、角色或权限不存在时整批失败，不产生部分写入。两类授予统一返回 `200`；整批均已存在时不写库、不撤销会话。
- **保护判定**：受保护角色与权限按 `code` 常量判定（`SYSTEM_ADMIN`、`RBAC_MANAGE`），不新增“内置”标记列。
- **允许零角色（2026-09-22 确认）**：本轮新增的三个用户角色撤销入口（单条撤销、`POST /user-roles/actions/revoke` 批量撤销、`DELETE /user-roles/users/{userId}` 清空全部）都允许目标用户此后不再拥有任何角色；`409/USER_ROLE_REQUIRED` 已废弃，任何 RBAC 接口都不再返回它。用户侧唯一保留的保护是“不得移除最后一个启用管理员的 `SYSTEM_ADMIN` 角色” → `409/LAST_ADMIN_PROTECTED`。角色侧同样不设“至少一个权限”下限：允许把角色清空到零权限（`SYSTEM_ADMIN` 仍受 `RBAC_MANAGE` 授权保护约束）。
- **分页与筛选**：排序白名单为角色/权限 `code,name,created_at`、用户角色 `granted_at`、角色权限 `role_id,permission_id`；两组授权列表必须至少给出一个筛选条件（`userId`/`roleId`、`roleId`/`permissionId`），都不给返回 `400/VALIDATION_FAILED`。
- **并发锁定**：所有 RBAC 写用例使用同一 MySQL 事务和 `SELECT ... FOR UPDATE` 锁定参与校验的已有记录，固定顺序为 **角色 → 权限 → 用户 → 授权关系**；同一层需要多行时按主键升序。允许跳过不涉及的层级，但禁止反向加锁。取得锁后必须重新读取授权关系并校验“最后启用管理员、受保护授权、引用关系”等不变量，不得用加锁前的查询结果作决定。数据库死锁或锁等待超时回滚并返回 `409/RBAC_CONFLICT`，不得自动重放包含 Redis 会话撤销的写用例。

两类授权使用各自唯一的响应模型。列表项使用单个授权模型，批量授予成功后的 `data` 使用相同模型的列表，并按目标 ID 升序返回；已存在关系返回原审计字段，新建关系记录同一次请求的操作人和授权时间：

| 响应模型 | 字段 |
| --- | --- |
| `UserRoleGrant` | `userId`（用户 ID）、`username`（用户名）、`roleId`（角色 ID）、`roleCode`（角色编码）、`roleName`（角色名称）、`grantedBy`（授权人用户 ID，可空）、`grantedAt`（授权时间） |
| `RolePermissionGrant` | `roleId`（角色 ID）、`roleCode`（角色编码）、`permissionId`（权限 ID）、`permissionCode`（权限编码）、`permissionName`（权限名称）、`grantedBy`（授权人用户 ID，可空）、`grantedAt`（授权时间；历史预置关系可空） |

`grantedAt` 遵循全局约定，返回带 UTC 偏移的 ISO 8601 字符串。`grantedBy = null` 表示 Flyway 预置或其他没有具体操作人的系统授权；本阶段不额外返回授权人的用户名或显示名称。

| 用例 | 方法与路径 | 请求与结果 |
| --- | --- | --- |
| 角色列表 | `GET /fd/v1/admin/roles` | 支持 `keyword` 和标准 `PageQuery`；返回 `R<PageResult<RoleDetail>>` |
| 角色详情 | `GET /fd/v1/admin/roles/{roleId}` | 返回角色字段及已授权 `permissionIds` |
| 创建角色 | `POST /fd/v1/admin/roles` | Body：`code`、`name`、可选 `description`、可选 `permissionIds`（最多 100 个正整数）；角色与授权关系在同一事务内写入，任一权限不存在时整体回滚并返回 `404/PERMISSION_NOT_FOUND`；返回 `201/R<RoleDetail>` |
| 修改角色 | `PUT /fd/v1/admin/roles/{roleId}` | Body：`name`、可选 `description`；`code` 创建后不可修改 |
| 删除角色 | `DELETE /fd/v1/admin/roles/{roleId}` | 角色存在用户或权限授权关系时返回 `409`；`SYSTEM_ADMIN` 始终禁止删除 |
| 权限列表 | `GET /fd/v1/admin/permissions` | 支持 `keyword` 和标准 `PageQuery`；返回 `R<PageResult<PermissionDetail>>` |
| 权限详情 | `GET /fd/v1/admin/permissions/{permissionId}` | 返回权限字段及已授权 `roleIds` |
| 创建权限 | `POST /fd/v1/admin/permissions` | Body：`code`、`name`、可选 `description`；返回 `201/R<PermissionDetail>` |
| 修改权限 | `PUT /fd/v1/admin/permissions/{permissionId}` | Body：`name`、可选 `description`；`code` 创建后不可修改 |
| 删除权限 | `DELETE /fd/v1/admin/permissions/{permissionId}` | 权限仍被角色引用时返回 `409`；不得删除 `RBAC_MANAGE` |
| 用户角色授权列表 | `GET /fd/v1/admin/user-roles` | 至少提供 `userId` 或 `roleId` 之一，支持标准 `PageQuery`；返回 `R<PageResult<UserRoleGrant>>` |
| 批量授予用户角色 | `POST /fd/v1/admin/user-roles` | Body：`userId`、非空 `roleIds`（最多 100 个正整数）；返回 `200/R<List<UserRoleGrant>>`。去重排序后只新增缺失关系；只要实际新增至少一个角色，就在本次事务中仅撤销该用户全部会话一次 |
| 单条撤销用户角色 | `DELETE /fd/v1/admin/user-roles/{userId}/{roleId}` | 返回 `200/R<Void>`；允许撤销后用户零角色，但不得使最后一个启用管理员失去管理员角色（`409/LAST_ADMIN_PROTECTED`）；成功后撤销该用户全部会话 |
| 批量撤销用户角色 | `POST /fd/v1/admin/user-roles/actions/revoke` | Body：`userId`、非空 `roleIds`（最多 100 个正整数）；返回 `200/R<Void>`。任一关系不存在时整批失败（`404/GRANT_NOT_FOUND`）且不写库；允许撤销后用户零角色；发生实际删除时只撤销该用户全部会话一次 |
| 清空用户全部角色 | `DELETE /fd/v1/admin/user-roles/users/{userId}` | 返回 `200/R<Void>`；目标用户当前无角色时幂等成功且不撤销会话；允许清空后用户零角色；发生实际删除时只撤销该用户全部会话一次 |
| 一个角色授予多个用户 | `POST /fd/v1/admin/user-roles/actions/grant-users` | Body：`roleId`、非空 `userIds`（最多 100 个正整数）；返回 `200/R<List<UserRoleGrant>>`（按 `userId` 升序）。去重排序后只给缺少该角色的用户新增关系；每个实际新增的用户在写入其关系前各撤销一次会话 |
| 角色权限授权列表 | `GET /fd/v1/admin/role-permissions` | 至少提供 `roleId` 或 `permissionId` 之一，支持标准 `PageQuery`；返回 `R<PageResult<RolePermissionGrant>>` |
| 批量授予角色权限 | `POST /fd/v1/admin/role-permissions` | Body：`roleId`、非空 `permissionIds`（最多 100 个正整数）；返回 `200/R<List<RolePermissionGrant>>`。去重排序后只新增缺失关系；只要实际新增至少一个权限，就在本次事务中撤销该角色全部用户会话一次 |
| 单条撤销角色权限 | `DELETE /fd/v1/admin/role-permissions/{roleId}/{permissionId}` | 返回 `200/R<Void>`；不得撤销 `SYSTEM_ADMIN` 的 `RBAC_MANAGE` 授权；成功后撤销该角色全部用户会话 |
| 批量撤销角色权限 | `POST /fd/v1/admin/role-permissions/actions/revoke` | Body：`roleId`、非空 `permissionIds`（最多 100 个正整数）；返回 `200/R<Void>`。任一关系不存在时整批失败（`404/GRANT_NOT_FOUND`）且不写库；`SYSTEM_ADMIN` 的 `RBAC_MANAGE` 落在批次内时返回 `409/RBAC_CONFLICT`；发生实际删除时对受影响用户各撤销一次会话 |
| 清空角色全部权限 | `DELETE /fd/v1/admin/role-permissions/roles/{roleId}` | 返回 `200/R<Void>`；目标角色当前无权限时幂等成功且不撤销会话；`SYSTEM_ADMIN` 仍包含 `RBAC_MANAGE` 时返回 `409/RBAC_CONFLICT`；发生实际删除时对受影响用户各撤销一次会话 |

`iam_user_role` 与 `iam_role_permission` 都是只有复合主键和审计字段的授权关系，没有独立可编辑的业务字段；因此这两组接口采用“查询、授予、撤销”，而非没有实际语义的 `PUT`。撤销按粒度分三档：路径参数指定的单条关系、请求体给出的多条关系（`actions/revoke`）、以及清空某主体全部关系；后两档是**整批原子**语义，任一目标关系缺失就整批失败，已存在的关系不会被改写。资源不存在分别返回 `404/ROLE_NOT_FOUND`、`404/PERMISSION_NOT_FOUND`、`404/USER_NOT_FOUND` 或 `404/GRANT_NOT_FOUND`；编码重复返回 `409/ROLE_CODE_CONFLICT` 或 `409/PERMISSION_CODE_CONFLICT`；违反保护或引用约束返回 `409/RBAC_CONFLICT`。

### 8.3 活动工单与管理性交接

| 用例 | 方法与路径 | 返回范围 |
| --- | --- | --- |
| 查询待交接工单 | `GET /fd/v1/admin/users/{userId}/active-ticket-assignments` | 仅工单编号、状态、当前负责人、版本 |
| 查询某工单接替候选人 | `GET /fd/v1/admin/tickets/{ticketNo}/handoff-candidates` | 启用且有 IT 角色、不是提交人和原负责人的用户 |

停用账号或移除 IT 角色时，如果目标用户仍负责活动工单，请求必须包含：

- `userVersion`：目标用户版本。
- `handoffReason`：发生工单交接时必填，作为每张管理性交接记录的原因；没有活动工单时不要求无依据的原因。
- `handoffs`：每张活动工单对应的 `ticketNo`、`ticketVersion` 和 `newAssigneeId`。

服务端重新查询目标用户负责的全部活动工单，并要求请求中的交接集合完整、无重复且候选人仍有效。账号或角色变更、全部负责人替换、参与关系和管理性交接记录必须在同一 MySQL 事务中成功；成功响应前还必须完成 Redis 会话撤销。任一用户版本、工单版本或候选资格变化时返回 `409/ADMIN_HANDOFF_CONFLICT`。

MySQL 与 Redis 之间不存在天然原子事务。工程准备阶段必须明确调用顺序、Redis 不可用时的失败策略和安全测试，保证接口不会在账号已变更后仍返回“会话撤销成功”的错误结果。

管理员通过这些接口只能看到交接所需元数据，不能获得标题、问题描述、记录或附件。管理员若同时具有 IT 角色，也只能通过其 IT 身份对应的普通工单接口访问有权查看的内容。

### 8.4 分类管理

**实现状态（2026-09-28，`TASK-063` 后端 / `TASK-064` 管理端页面）**：六个端点与 `/admin/categories` 页面经用户当轮指示提前实施（原属完整版 backlog）。本节契约**未做任何修改**，实现按本节逐项落地。落地口径：`GET` 默认按 `sort_order, id` 升序（与员工端下拉顺序一致，因此管理页与服务端看到的是同一个顺序）；`POST` 落库即为 `ENABLED`（命令里没有 `status`，避免出现"建完即停用"的入口）；`PUT` 与两个启停端点的请求体都带 `version`；`DELETE` 按本节要求用**必填查询参数** `version`。引用约束由 `ticket.category_id` 的限制删除外键承担——分类模块不去跨模块读 `ticket` 表，删除被外键拒绝时转成 `409/CATEGORY_IN_USE`，与 `docs/database-design.md` 18.1 的处置一致。

| 用例 | 方法与路径 | 说明 |
| --- | --- | --- |
| 分类列表 | `GET /fd/v1/admin/categories` | 返回启用和停用分类，支持分页与名称搜索 |
| 创建分类 | `POST /fd/v1/admin/categories` | 名称必填且唯一，同时设置排序值 |
| 修改分类 | `PUT /fd/v1/admin/categories/{categoryId}` | 修改名称或排序值并携带版本 |
| 启用分类 | `POST /fd/v1/admin/categories/{categoryId}/actions/enable` | 携带版本 |
| 停用分类 | `POST /fd/v1/admin/categories/{categoryId}/actions/disable` | 携带版本；不影响历史工单 |
| 删除分类 | `DELETE /fd/v1/admin/categories/{categoryId}` | 携带版本；仅从未被工单引用时成功 |

**已确认**：分类使用数据库设计中已有的乐观锁 `version` 字段，不增加新表。分类名称去除首尾空白后长度为 1～100，排序值使用非负整数。删除接口通过必填查询参数 `version` 传递期望版本。

### 8.5 数据概览

| 项目 | 契约 |
| --- | --- |
| 方法与路径 | `GET /fd/v1/dashboard/tickets` |
| 权限 | `DASHBOARD_VIEW` |
| 查询范围 | 复用工单列表的 `PENDING_QUEUE`、`ASSIGNED_TO_ME`、`PARTICIPATED_BY_ME` |
| 可选筛选 | `createdFrom`、`createdTo`、`categoryId` |

响应使用 `R<TicketDashboard>`，一次返回当前权限范围内的工单总数，以及按状态、优先级、分类和负责人分组的数量。所有统计先应用与列表一致的可见范围，不能先统计全量数据再只隐藏页面入口。

系统管理员角色本身不具有数据概览权限；多角色管理员只有在同时具有 IT 角色，并符合具体工单查看范围时才能看到对应统计。v1 不提供导出、趋势图、跨权限全局报表或独立统计库。

### 8.6 系统管理与概览验收结论

**已确认**：

1. 管理员可以创建用户、维护预置角色、启停账号和重置密码；用户可以修改自己的密码。
2. 停用账号或移除 IT 角色时，全部活动工单交接与账号变更作为一个完整用例提交。
3. 分类使用 `version` 执行乐观并发控制。
4. 数据概览复用工单列表的可见范围，只提供简单分组数量。
5. 管理员不会因为管理权限获得工单正文、记录、附件或全局业务统计权限。

系统管理与数据概览接口组就绪状态：**Ready**。

## 9. 权限与接口映射

后端先校验 RBAC 能力，再校验资源关系、当前状态、负责人和版本。前端拥有某个权限编码不代表任意工单操作一定成功。

| 权限编码 | 主要接口或动作 | 额外业务校验 |
| --- | --- | --- |
| `TICKET_CREATE` | 创建工单、读取分类选项 | 启用分类、原工单可见性、提交幂等键 |
| `TICKET_VIEW_OWN` | `REQUESTED_BY_ME`、本人工单详情和时间线 | 当前用户必须是提交人 |
| `TICKET_REQUESTER_ACTION` | 补充、确认、未解决、发起或撤回撤销请求、直接撤销待受理工单 | 提交人、状态和版本 |
| `TICKET_VIEW_QUEUE` | `PENDING_QUEUE`、待受理详情 | 工单仍为待受理 |
| `TICKET_CLAIM` | `claim` | 有效 IT、非提交人、待受理、版本 |
| `TICKET_VIEW_PARTICIPATED` | 负责或参与列表、详情和时间线 | 当前或历史参与关系 |
| `TICKET_PROCESS` | 处理记录、分类与优先级调整、补充请求、解决结果、批准或拒绝撤销请求 | 当前负责人、允许状态和版本 |
| `TICKET_TRANSFER` | 转交及候选人 | 当前负责人、允许状态、接收人资格和版本 |
| `TICKET_CLOSE` | 手动关闭 | 当前负责人、处理中、关闭原因及版本 |
| `TICKET_ADMIN_HANDOFF` | 最小交接元数据、候选人和管理性交接 | 不授予工单正文访问 |
| `USER_MANAGE` | 用户、角色、账号及密码管理 | 用户版本、最后管理员保护（用户允许零角色，见 8.2） |
| `RBAC_MANAGE` | 角色、权限、用户角色及角色权限管理 | 内置管理员保护、编码唯一、引用约束和会话撤销 |
| `CATEGORY_MANAGE` | 分类管理 | 分类版本、名称唯一和引用约束 |
| `DASHBOARD_VIEW` | 工单数据概览 | 复用 IT 工单可见范围 |

附件下载和关联摘要不单设权限编码，继承所属工单的查看权限。认证接口只要求相应的匿名、Refresh Cookie 或已登录状态。

## 10. 统一错误契约

### 10.1 错误响应

失败响应仍使用 `R<T>`。`code` 供前端稳定判断，`message` 用于展示；`data` 只在需要时包含以下安全细节：

- `traceId`：服务端请求追踪标识。
- `fieldErrors`：字段名与校验错误编码，不包含请求中的密码或文件内容。
- `version`、`status`：调用者有权查看工单时的最小冲突信息（`409/TICKET_CONFLICT` 才有）。**字段名以 `ErrorDetails` 为准**——早些时候这里写作 `currentVersion` / `currentStatus`，与实现不符；2026-10-08 的两阶段撤销验收脚本按实际字段核对后改正，§6.6 的措辞本来就是对的。

生产响应不得包含异常类名、堆栈、SQL、磁盘路径、Token、密码或内部数据库主键。

### 10.2 核心错误编码

| HTTP | 错误编码 | 场景 |
| --- | --- | --- |
| `400` | `VALIDATION_FAILED` | 字段、分页、筛选或条件必填校验失败 |
| `401` | `AUTH_INVALID_CREDENTIALS` | 登录凭据无效，不区分具体原因 |
| `401` | `AUTH_REQUIRED` | 未提供或无法验证 Access Token |
| `401` | `AUTH_SESSION_INVALID` | 会话过期、撤销或 Refresh Token 无效 |
| `403` | `ACCESS_DENIED` | 缺少接口级业务权限 |
| `403` | `TICKET_ACTION_FORBIDDEN` | 可查看工单但不满足动作权限 |
| `403` | `ORIGIN_NOT_ALLOWED` | 依赖 Cookie 的认证接口收到不在白名单内的请求来源 |
| `404` | `TICKET_NOT_FOUND` | 工单不存在或不可见 |
| `404` | `USER_NOT_FOUND` | 管理范围内用户不存在 |
| `404` | `CATEGORY_NOT_FOUND` | 管理范围内分类不存在 |
| `404` | `ROLE_NOT_FOUND` | 角色不存在，或 `roleId` 非正整数 |
| `404` | `PERMISSION_NOT_FOUND` | 权限不存在，或 `permissionId` 非正整数 |
| `404` | `GRANT_NOT_FOUND` | 要撤销的授权关系不存在 |
| `404` | `ATTACHMENT_NOT_FOUND` | 附件不存在、归属不符或不可见 |
| `404` | `RESOURCE_NOT_FOUND` | 请求路径未匹配任何接口（Spring MVC 兜底，非业务语义；业务 404 使用各自编码） |
| `409` | `TICKET_CONFLICT` | 工单版本、状态或负责人已变化 |
| `409` | `TICKET_CREATE_CONFLICT` | 创建工单命中非提交键的唯一约束（如工单编号冲突），可重试；原始数据库异常保留为 cause |
| `409` | `USERNAME_CONFLICT` | 登录名已存在 |
| `409` | `USER_CONFLICT` | 用户版本或状态已变化 |
| `409` | ~~`USER_ROLE_REQUIRED`~~（已废弃，2026-09-22） | 原用于“操作会移除用户最后一个角色”；现允许用户零角色，所有接口都不再返回该编码，保留此行仅为追溯 |
| `409` | `LAST_ADMIN_PROTECTED` | 操作会失去最后一个启用管理员 |
| `409` | `ADMIN_HANDOFF_REQUIRED` | 缺少完整的活动工单交接方案 |
| `409` | `ADMIN_HANDOFF_CONFLICT` | 交接期间用户、工单或候选资格变化 |
| `409` | `CATEGORY_NAME_CONFLICT` | 分类名称已存在 |
| `409` | `CATEGORY_IN_USE` | 删除仍被工单引用的分类 |
| `409` | `CATEGORY_CONFLICT` | 分类版本或状态已变化 |
| `409` | `ROLE_CODE_CONFLICT` | 角色编码已存在 |
| `409` | `PERMISSION_CODE_CONFLICT` | 权限编码已存在 |
| `409` | `RBAC_CONFLICT` | 违反内置管理员保护或授权引用约束（删除 `SYSTEM_ADMIN`、删除 `RBAC_MANAGE`、删除仍被引用的角色或权限、撤销或清空 `SYSTEM_ADMIN` 的 `RBAC_MANAGE`），或 RBAC 写事务发生锁等待超时/死锁及批量删除行数与请求不一致 |
| `413` | `ATTACHMENT_TOO_LARGE` | 单文件、数量或总大小超过限制 |
| `415` | `ATTACHMENT_TYPE_UNSUPPORTED` | 文件类型不在白名单或检测不一致 |
| `500` | `INTERNAL_ERROR` | 未预期错误；对外隐藏内部细节 |

## 11. 重试与幂等边界

| 操作类型 | 契约 |
| --- | --- |
| GET 查询 | 无副作用，可安全重试 |
| 退出登录 | 按当前会话幂等，可安全重复调用 |
| Refresh Token | 每次成功后轮换，不自动使用旧 Token 重试 |
| 创建工单 | 复用同一 `submissionKey` 可安全重试并获得首次结果 |
| 已有工单动作 | 使用 `version` 防止重复写；响应不确定时先重新查询再决定后续操作 |
| 创建用户、创建分类 | 不承诺请求级幂等；唯一业务键防止产生同名重复资源 |
| 用户、角色、密码和分类修改 | 携带目标资源版本；冲突后重新读取，不自动覆盖 |

## 12. API 阶段完整性检查

已完成以下检查：

- 认证、工单查询、创建、全部人工状态动作、附件、管理性交接、用户、分类和数据概览均有接口归属。
- 每个工单动作都能追溯到已确认状态迁移，没有开放通用状态修改入口。
- RBAC、工单可见性、当前负责人、状态和版本的校验层次明确。
- 请求校验、分页筛选、错误结构、HTTP 状态、幂等和并发行为明确。
- 工单快照与不可变记录、附件归属、创建防重和分类版本与数据库设计一致。
- 超时任务保留为内部应用用例，没有误开放人工 HTTP 入口。

以下内容按阶段边界留到工程准备：Access Token 与 Refresh Token 的具体有效期、Cookie/CSRF 配置、密码哈希参数、附件文件与数据库提交顺序、依赖版本、OpenAPI 生成方式及测试工具。

完整版 API 契约设计状态：**Ready**。当前求职 MVP 只实现 `docs/implementation-plan.md` 对应接口；未进入 MVP 的接口继续作为完整版契约保留，不得据此提前扩展当前阶段。
