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
- 该接口必须校验允许的请求来源并落实 CSRF 防护，具体 Cookie 属性和防护方案在工程准备阶段确定。

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

分页响应为 `R<PageResult<TicketListItem>>`。`PageResult<T>` 至少包含 `items`、`page`、`size`、`totalElements` 和 `totalPages`。

列表项只返回识别和筛选所需字段：工单编号、标题、分类、优先级、状态、提交人摘要、负责人摘要、当前有效截止时间、创建时间、更新时间和版本号，不返回问题正文或完整时间线。

### 5.4 工单详情

详情以外部业务编号 `ticketNo` 定位，不向前端暴露内部工单主键。响应包含：

- 原始标题、问题描述、提交人、分类和优先级。
- 当前状态、当前或最后负责人、当前有效截止时间和版本号。
- 适用时的完成方式、关闭方式、关闭原因和结束时间。
- 创建时间和最近更新时间。
- **已确认**：返回后端根据当前用户、角色、工单关系和状态计算的 `allowedActions`，用于前端正确展示可用按钮；它不能替代操作接口再次鉴权。

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

以上动作都要求当前用户是当前负责人；`claim` 例外，它要求当前用户是有效 IT 支持人员，工单仍无人负责且不能由其本人提交。

处理正文、补充请求、员工补充正文和解决结论去除首尾空白后最长 10000 个字符；要求必填时长度至少为 1。转交、调整、撤回、未解决、取消和关闭说明最长 1000 个字符，并在对应动作中要求非空。

关闭原因为 `DUPLICATE` 时，`duplicateTicketNo` 必填，目标必须是同一提交人的另一张有效工单；其他关闭原因禁止传该字段。

### 6.4 员工动作

| 动作路径末段 | 允许状态 | 请求业务字段 | 成功结果 |
| --- | --- | --- | --- |
| `supplement` | `WAITING_FOR_REQUESTER` | `version`、正文和/或附件 | 回到 `PROCESSING`，原期限失效 |
| `confirm-resolution` | `WAITING_FOR_CONFIRMATION` | `version` | 进入 `COMPLETED`，完成方式为员工确认 |
| `report-unresolved` | `WAITING_FOR_CONFIRMATION` | `version`、`reason` | 回到 `PROCESSING`，原负责人保留 |
| `cancel` | 四种非终态 | `version`、`reason` | 进入 `CANCELED` |

这些动作都要求当前用户是工单提交人。`supplement` 使用 `multipart/form-data`，正文和附件至少存在一项；其他动作使用 JSON。

### 6.5 非公开系统动作

待确认超时完成和待补充超时关闭由应用内部定时用例触发，不暴露给前端，也不设计可由管理员手动调用的 HTTP 接口。它们与人工动作使用相同的状态、期限和版本条件更新规则。

### 6.6 并发、幂等与错误

- 领取、转交、补充、解决、确认、撤销和关闭等状态动作依靠 `version`、预期状态及预期负责人共同防止重复执行。
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
- 管理员可以在线维护自定义角色、权限及其授权关系。系统初始化仍提供基础角色和权限；`SYSTEM_ADMIN` 是受保护的内置角色，不能删除或失去 RBAC 管理所需权限。
- 角色或权限只要仍被授权关系引用便禁止删除；删除前必须先显式解除全部关联。
- 用户不提供物理删除接口，分类只有从未被工单引用时才允许删除。
- 用户、角色或账号状态变化成功后，撤销受影响用户的现有登录会话。
- 涉及活动工单交接时必须全部成功或全部失败，不能先停用账号再留下无效负责人。

### 8.2 用户与凭据管理

| 用例 | 方法与路径 | 说明 |
| --- | --- | --- |
| 用户列表 | `GET /fd/v1/users` | 按关键词、状态和角色分页筛选 |
| 用户详情 | `GET /fd/v1/users/{userId}` | 返回账号、角色、状态和版本，不返回密码摘要 |
| 创建用户 | `POST /fd/v1/users` | 提交登录名、显示名称、初始密码和至少一个角色 |
| 修改基本资料 | `PUT /fd/v1/users/{userId}` | 修改显示名称并携带 `version` |
| 启用账号 | `POST /fd/v1/users/{userId}/actions/enable` | 携带 `version` |
| 停用账号 | `POST /fd/v1/users/{userId}/actions/disable` | 携带版本和必要交接方案 |
| 替换角色 | `PUT /fd/v1/users/{userId}/roles` | 携带版本、完整角色集合和必要交接方案 |
| 管理员重置密码 | `POST /fd/v1/users/{userId}/actions/reset-password` | 携带版本，设置新密码并撤销目标用户全部会话 |
| 当前用户修改密码 | `POST /fd/v1/auth/change-password` | 校验原密码，修改成功后撤销当前用户全部会话 |
| 角色、权限与授权关系管理 | 见 8.2.1 | 要求 `RBAC_MANAGE` |

**已确认**：密码长度为 8～64 个字符，不在接口文档或日志中返回密码；演示版不通过邮件发送初始密码或重置链接，由管理员通过项目演示场景之外的安全渠道告知用户。

禁止停用最后一个启用的管理员，也禁止移除其管理员角色。每个用户至少保留一个角色；登录名创建后不可修改，避免身份引用和审计含义变化。自定义权限编码只能在后端已有对应授权检查时产生实际访问能力，新增数据本身不会自动生成业务接口或安全规则。

### 8.2.1 自定义 RBAC 管理

所有本节接口均要求 `RBAC_MANAGE`。实现该能力时，必须通过新的 Flyway 迁移预置此权限并授予受保护的 `SYSTEM_ADMIN` 角色；不得修改已发布的历史迁移。

| 用例 | 方法与路径 | 请求与结果 |
| --- | --- | --- |
| 角色列表 | `GET /fd/v1/admin/roles` | 支持 `keyword` 和标准 `PageQuery`；返回 `R<PageResult<RoleDetail>>` |
| 角色详情 | `GET /fd/v1/admin/roles/{roleId}` | 返回角色字段及已授权 `permissionIds` |
| 创建角色 | `POST /fd/v1/admin/roles` | Body：`code`、`name`、可选 `description`；返回 `201/R<RoleDetail>` |
| 修改角色 | `PUT /fd/v1/admin/roles/{roleId}` | Body：`name`、可选 `description`；`code` 创建后不可修改 |
| 删除角色 | `DELETE /fd/v1/admin/roles/{roleId}` | 角色存在用户或权限授权关系时返回 `409`；`SYSTEM_ADMIN` 始终禁止删除 |
| 权限列表 | `GET /fd/v1/admin/permissions` | 支持 `keyword` 和标准 `PageQuery`；返回 `R<PageResult<PermissionDetail>>` |
| 权限详情 | `GET /fd/v1/admin/permissions/{permissionId}` | 返回权限字段及已授权 `roleIds` |
| 创建权限 | `POST /fd/v1/admin/permissions` | Body：`code`、`name`、可选 `description`；返回 `201/R<PermissionDetail>` |
| 修改权限 | `PUT /fd/v1/admin/permissions/{permissionId}` | Body：`name`、可选 `description`；`code` 创建后不可修改 |
| 删除权限 | `DELETE /fd/v1/admin/permissions/{permissionId}` | 权限仍被角色引用时返回 `409`；不得删除 `RBAC_MANAGE` |
| 用户角色授权列表 | `GET /fd/v1/admin/user-roles` | 至少提供 `userId` 或 `roleId` 之一，支持标准 `PageQuery` |
| 授予用户角色 | `POST /fd/v1/admin/user-roles` | Body：`userId`、`roleId`；重复授权幂等成功，并撤销该用户全部会话 |
| 撤销用户角色 | `DELETE /fd/v1/admin/user-roles/{userId}/{roleId}` | 不得使用户失去最后一个角色，或使最后一个启用管理员失去管理员角色；成功后撤销全部会话 |
| 角色权限授权列表 | `GET /fd/v1/admin/role-permissions` | 至少提供 `roleId` 或 `permissionId` 之一，支持标准 `PageQuery` |
| 授予角色权限 | `POST /fd/v1/admin/role-permissions` | Body：`roleId`、`permissionId`；重复授权幂等成功 |
| 撤销角色权限 | `DELETE /fd/v1/admin/role-permissions/{roleId}/{permissionId}` | 不得撤销 `SYSTEM_ADMIN` 的 `RBAC_MANAGE` 授权 |

`iam_user_role` 与 `iam_role_permission` 都是只有复合主键和审计字段的授权关系，没有独立可编辑的业务字段；因此这两组接口采用“查询、授予、撤销”，而非没有实际语义的 `PUT`。资源不存在分别返回 `404/ROLE_NOT_FOUND`、`404/PERMISSION_NOT_FOUND` 或 `404/GRANT_NOT_FOUND`；编码重复返回 `409/ROLE_CODE_CONFLICT` 或 `409/PERMISSION_CODE_CONFLICT`；违反保护或引用约束返回 `409/RBAC_CONFLICT`。

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
| `TICKET_REQUESTER_ACTION` | 补充、确认、未解决、撤销 | 提交人、状态和版本 |
| `TICKET_VIEW_QUEUE` | `PENDING_QUEUE`、待受理详情 | 工单仍为待受理 |
| `TICKET_CLAIM` | `claim` | 有效 IT、非提交人、待受理、版本 |
| `TICKET_VIEW_PARTICIPATED` | 负责或参与列表、详情和时间线 | 当前或历史参与关系 |
| `TICKET_PROCESS` | 处理记录、分类与优先级调整、补充请求、解决结果 | 当前负责人、允许状态和版本 |
| `TICKET_TRANSFER` | 转交及候选人 | 当前负责人、允许状态、接收人资格和版本 |
| `TICKET_CLOSE` | 手动关闭 | 当前负责人、处理中、关闭原因及版本 |
| `TICKET_ADMIN_HANDOFF` | 最小交接元数据、候选人和管理性交接 | 不授予工单正文访问 |
| `USER_MANAGE` | 用户、角色、账号及密码管理 | 用户版本、至少一个角色、最后管理员保护 |
| `RBAC_MANAGE` | 角色、权限、用户角色及角色权限管理 | 内置管理员保护、编码唯一、引用约束和会话撤销 |
| `CATEGORY_MANAGE` | 分类管理 | 分类版本、名称唯一和引用约束 |
| `DASHBOARD_VIEW` | 工单数据概览 | 复用 IT 工单可见范围 |

附件下载和关联摘要不单设权限编码，继承所属工单的查看权限。认证接口只要求相应的匿名、Refresh Cookie 或已登录状态。

## 10. 统一错误契约

### 10.1 错误响应

失败响应仍使用 `R<T>`。`code` 供前端稳定判断，`message` 用于展示；`data` 只在需要时包含以下安全细节：

- `traceId`：服务端请求追踪标识。
- `fieldErrors`：字段名与校验错误编码，不包含请求中的密码或文件内容。
- `currentVersion`、`currentStatus`：调用者有权查看工单时的最小冲突信息。

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
| `404` | `TICKET_NOT_FOUND` | 工单不存在或不可见 |
| `404` | `USER_NOT_FOUND` | 管理范围内用户不存在 |
| `404` | `CATEGORY_NOT_FOUND` | 管理范围内分类不存在 |
| `404` | `ATTACHMENT_NOT_FOUND` | 附件不存在、归属不符或不可见 |
| `409` | `TICKET_CONFLICT` | 工单版本、状态或负责人已变化 |
| `409` | `USERNAME_CONFLICT` | 登录名已存在 |
| `409` | `USER_CONFLICT` | 用户版本或状态已变化 |
| `409` | `USER_ROLE_REQUIRED` | 操作会移除用户最后一个角色 |
| `409` | `LAST_ADMIN_PROTECTED` | 操作会失去最后一个启用管理员 |
| `409` | `ADMIN_HANDOFF_REQUIRED` | 缺少完整的活动工单交接方案 |
| `409` | `ADMIN_HANDOFF_CONFLICT` | 交接期间用户、工单或候选资格变化 |
| `409` | `CATEGORY_NAME_CONFLICT` | 分类名称已存在 |
| `409` | `CATEGORY_IN_USE` | 删除仍被工单引用的分类 |
| `409` | `CATEGORY_CONFLICT` | 分类版本或状态已变化 |
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

API 契约就绪状态：**Ready**。下一阶段进入工程准备检查，不创建业务代码。
