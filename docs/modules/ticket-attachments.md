# 附件模块实现说明（通用附件模块 + 工单绑定）

> 本文件是「附件（完整版 backlog 第 2 项的「重」一侧）」的实施入口文档，记录已确认规则、
> 分步计划与**当前步骤**，写给实现与 review 用。
> 契约上限是 `docs/api-design.md` 7.1/7.2/7.6；失败顺序与对账口径是 `docs/engineering-readiness.md` 7.2；
> 存储方案（`AttachmentStorage` 抽象：`local` / `s3` 两个驱动，2026-10-14 结论变更）是 `docs/technical-architecture.md` 6.3；表结构是 `docs/database-design.md` 6.3/19.3。
> 三者与本文件冲突时，以已确认的契约文档为准，并先修正本文件。
>
> **形态（2026-10-12 用户确认，方案 B）**：附件做成**独立于工单、可跨项目复用的通用附件模块**
> （类 RuoYi 的独立上传/下载形态），工单侧只保留绑定与归属校验；跨项目复用 = 拷走
> `com.flowdesk.attachment` 包 + `attachment` 表 + `flowdesk.attachment.*` 配置，
> 新项目只需写自己的绑定表与授权端口实现。
>
> **阶段状态（2026-10-14）**：第 1～7 步已落地（存储与校验组件、通用上传/下载端点、实体/Mapper、
> 授权端口与异常映射、multipart 限额、工单侧接入、提交顺序/失败补偿与孤儿对账任务）。
> **本轮「附件完善」批 1～批 3 也已交付**（见 `docs/modules/attachment-storage-and-preview.md` 6.1）：
> 存储抽象 + S3 驱动、内联预览端点（规范路径 `GET /attachments/{id}/content?disposition=`，
> 原 `download` 保留为 deprecated 别名）、批量上传（重复 `files` part 1～5）、限额可配、
> 前端附件区（缩略图 / 灯箱 / PDF 预览 / 文本内联 / 下载）与上传控件（逐文件进度、取消、粘贴、失败带文件名），
> 创建与补充两个入口均已接线。
> **仍未完成**：`S3AttachmentStorageIT` 未在真实 MinIO 上执行（本机镜像仓库无法提供镜像，用例主动跳过而非通过）；
> 前端全量 E2E 未补；真实栈验收脚本未跑。
>
> **修复批（2026-10-15，分支 `flow-desk/attachment-fix`）**：按用户三项裁决落地「删除未绑定附件」端点、
> 前端上传前大小预校验（`accept` 软过滤、不复制类型白名单）与「移除即释放」；预签名直传**裁决不做**。
> 交付记录见 `docs/modules/attachment-storage-and-preview.md` 6.2。

## 1. 目标与边界

**目标**：实现受控的附件上传与下载，且附件能力**独立于工单**——通用附件模块（`com.flowdesk.attachment`）
负责文件存储、校验、上传/下载/预览端点与对账；工单模块只声明「附件绑定到哪个记录」并复用自身权限。

**做**：

- 通用上传端点 `POST /attachments/upload`（`files` 可重复 part，1～5 个）与通用内容端点
  `GET /attachments/{attachmentId}/content?disposition=attachment|inline`（公共路径不带 `/fd/v1` 前缀；
  `GET /attachments/download/{attachmentId}` 保留为 deprecated 别名）。
- **删除未绑定附件** `DELETE /attachments/{attachmentId}`（2026-10-15 落地）：只允许上传者本人删除
  **尚未被任何业务对象引用**的附件，元数据与文件一并删除；已绑定按 `409/ATTACHMENT_ALREADY_BOUND`
  拒绝（记录不可变，已绑定附件是审计链的一部分）。
- 两个业务入口接入：创建工单、员工补充（`files` part + `attachmentIds` 引用）。
- 服务端校验与内容嗅探（扩展名 + 声明媒体类型 + 服务端识别结果三重一致）。
- 存储抽象 `AttachmentStorage` 与两个驱动：`local`（暂存与最终目录同卷、原子移动）、`s3`（S3 兼容对象存储）；失败补偿与孤儿对账驱动无关。
- 内联预览白名单（JPEG/PNG/PDF/TXT）与安全响应头；HTML / SVG 一律不内联。
- 工单侧授权端口：依附件的 `ticket_attachment` 绑定校验工单可见性，供公共内容端点复用。
- 测试、真实栈验收、前端上传与下载界面。

**不做**（越界即停）：

- 不做附件替换、重命名接口；不做图片处理、病毒扫描、断点续传和分片上传（`docs/kickoff.md` 3.3）。**删除已按 2026-10-15 裁决落地，但只允许删「未绑定」附件**（见上文「做」清单）。
- **不做预签名直传（2026-10-15 用户裁决，已由"候选"收口为"不做"）**：服务端不做校验才有带宽收益，与本项目「服务端识别结果」纪律冲突，见 `docs/modules/attachment-storage-and-preview.md` §5。
- 不自建对象存储集群；使用 S3 兼容的服务化存储（本地 MinIO、生产任意 S3 / OSS），见 `docs/technical-architecture.md` 6.3/13。
- 不改已发布的历史迁移；两表由 `V9` 建立（原编号 V8，2026-10-13 因 main 的撤销到期迁移已占用 V8 而改号；`ticket_attachment` 为 `V9` 重建的绑定表）。
- 不新增权限码：下载与预览按「上传者本人或业务授权」判定（`docs/api-design.md` 第 9 节）。

## 2. 已确认的规则（来自既有契约，不再重议）

- **两类上传路径（`docs/api-design.md` 7.1）**：独立上传（`POST /attachments/upload`，单文件 `file` part，**已实现**）与随动作上传（创建/补充携带 `files` 或 `attachmentIds`，待步骤 5）。
- **限制（7.1/7.6）**：单文件 ≤ 10 MiB；单次请求 ≤ 5 个文件且总计 ≤ 25 MiB；原始文件名去空白后 1～255；空文件拒绝；白名单 JPEG、PNG、PDF、TXT、DOCX、XLSX。
- **三重校验（7.1）**：扩展名、声明媒体类型与**服务端识别结果**三者一致；拒绝可执行文件、脚本、HTML、SVG、压缩包等未列入白名单的格式。
- **引用规则（7.1，待实现）**：`attachmentIds` 只能引用当前用户上传、未绑定、未超保留期（默认 24h）的附件；他人/已绑定/不存在的引用统一 `404/ATTACHMENT_NOT_FOUND` 或 `400/VALIDATION_FAILED`，不回显他人附件是否存在。
- **整体失败（7.1）**：任一附件校验或保存失败，整个上传动作失败，不留已提交快照、记录、元数据或孤立文件。
- **两表归属（`docs/database-design.md` 6.3/19.3）**：通用 `attachment`（无业务归属）+ 工单绑定表 `ticket_attachment`（`attachment_id` 唯一 + `record_id`，只允许绑定创建/补充记录，跨表类型约束由工单事务用例校验）。
- **存储定位（7.1、19.3）**：服务端随机 `storage_key`，Web 根目录之外；DB 只存元数据；客户端文件名不参与路径拼接；`sha256` 必填。
- **文件与数据库提交顺序（`docs/engineering-readiness.md` 7.2）**：同卷 + 启动校验；流式暂存 → 校验 → 开事务写元数据/绑定 → 逐个原子移动 → 全成才提交；任一移动失败回滚并清理（已移动清单逆向清理）；提交失败删除已移动文件；孤儿交对账。
- **对账（7.2）**：清理超期暂存；以 `storage_key` 为权威扫描最终目录；元数据在而文件缺失 = 完整性事故（告警，不删元数据、不伪造）；防穿越；单实例不并发处理同一暂存标识；**超期未绑定附件进入清理流程**。
- **下载（7.2）**：公共路径 `GET /attachments/download/{attachmentId}`（2026-10-13 已实现；工单嵌套路径按同日约定推翻）；原始流（不用 `R<T>`）；服务端确认 `Content-Type`、长度、安全编码 `Content-Disposition: attachment`、`X-Content-Type-Options: nosniff`；统一 `404/ATTACHMENT_NOT_FOUND`；不暴露磁盘路径。
- **权限（第 9 节）**：上传者本人，或附件经业务授权端口判定可见（工单绑定可见性在步骤 5 由 ticket 侧实现 `AttachmentAccessPort`）。

## 3. 现状盘点

| 项 | 状态 | 落点 |
| --- | --- | --- |
| 表 `attachment` + `ticket_attachment`（绑定） | **已就位** | `V9__generalize_attachment.sql`（原 V8，2026-10-13 改号）：通用表（唯一 `storage_key`、上传人外键、`size_bytes > 0`）+ 绑定表（`attachment_id` 唯一、`record_id` 外键、`(record_id, id)` 索引）。`V1` 旧表从未投入使用，`V9` 直接重建 |
| 存储配置 | **已就位** | `flowdesk.attachment.root: ${FLOWDESK_ATTACHMENT_ROOT}`（分组键，环境变量名不变）；测试 profile 用 tmp 目录 |
| 通用存储组件 | **已落地（已迁包）** | `com.flowdesk.attachment.config.AttachmentProperties`；`com.flowdesk.attachment.infrastructure`：`AttachmentStorage`、`LocalAttachmentStorage`（随机 key、流式暂存、原子移动、删除、读取、`stagedPath` 供暂存后校验、穿越防护、启动可写与原子移动探针）、`StagedAttachment`、`AttachmentStorageException` |
| 内容嗅探与校验 | **已落地（2026-10-13）** | `com.flowdesk.attachment.domain`：`AttachmentLimits`（限额常量）、`DetectedFileType`（白名单类型 + 规范媒体类型 + 扩展名）、`AttachmentValidationException`（Violation 带错误码映射语义）；`application.AttachmentValidator`（数量/名称/大小/总量 + 签名嗅探 + OOXML 容器中央目录枚举 + 三重一致） |
| 后端实体/Mapper/服务/端点 | **已落地（2026-10-13）** | `attachment` 实体 + `AttachmentMapper`；`AttachmentService`/`AttachmentServiceImpl`（上传：预检→暂存→复核→事务写元数据→原子移动→提交，含提交失败 `afterCompletion` 补偿；下载：上传者或端口可见，统一 404）；`AttachmentController`（`/upload`、`/download/{id}`）；`AttachmentExceptionHandler`（400/413/415/404 映射，`HIGHEST_PRECEDENCE`）；`CurrentUploaderPort` + auth 适配器；`AttachmentAccessPort` 已定义并由 `TicketAttachmentAccessAdapter` 实现（2026-10-13） |
| 工单侧接入 | **已落地（2026-10-13）** | `TicketController.create/supplement` 接受 `files`（重复 part）；`CreateTicketCommand`/`SupplementCommand` 增 `attachmentIds`；`TicketServiceImpl` 编排（暂存→校验→事务内写元数据与绑定→原子移动→补偿）；绑定写 `ticket_attachment`（`TicketAttachmentMapper`）；`TicketAttachmentAccessAdapter` 实现下载可见性（逐字复用详情可见性 SQL）；旧「正文版」`400` 拦截已移除 |
| 对账任务 | **已落地（2026-10-13）** | `AttachmentReconciliationTask`（`com.flowdesk.attachment.infrastructure`）：三段对账 + 保留期 `flowdesk.attachment.retention`（默认 24h，与工单侧引用校验共用同一属性）；存储层新增 `listStaged`/`listFinal`/`finalExists`（只返回受控随机存储名） |
| 前端 | **零实现** | 无上传控件、无附件块 |
| 测试与验收 | **已开始** | 校验与存储单测 **19 项**；其余测试按用户 2026-10-13 指示（「不用管测试」）未发起；无验收脚本、无 E2E |

## 4. 实现步骤（顺序即执行顺序）

| # | 步骤 | 要点与落点 |
| --- | --- | --- |
| 1 | 结构独立 + 契约与 schema 切换 | **已完成（2026-10-12；2026-10-13 迁移改号 V8→V9）**：5 个存储类迁入 `com.flowdesk.attachment`；`V9` 迁移（原 V8，因 main 的撤销到期迁移占用 V8 而改号）；四份契约文档与 `implementation-plan` 同步；`DatabaseMigrationIT` 断言更新 |
| 2 | 内容嗅探与校验 | **已完成（2026-10-13）**：依**魔数**（文件签名）判定服务端识别类型，不信任客户端 `Content-Type`；DOCX/XLSX 是 OOXML 容器，必须同时看到 ZIP 签名、`[Content_Types].xml` 与 `word/document.xml`（或 `xl/workbook.xml`），按中央目录枚举、不展开数据（防压缩炸弹）；TXT 按「前 8 KiB 无 NUL 字节」判定；白名单与限额同 §2；三重一致；任一失败整个动作失败。落点：`com.flowdesk.attachment.domain` + `application.AttachmentValidator`，由通用服务调用 |
| 3 | 通用上传端点 | **已完成（2026-10-13）**：`POST /attachments/upload`——预检 → 流式暂存 → 暂存后复核 → 事务写 `attachment` 元数据 → 原子移动 → 提交；失败补偿（暂存/已移动文件）与提交失败 `afterCompletion` 兜底齐备；成功返回附件标识与元数据 |
| 4 | 通用下载端点 + 授权端口 | **已完成（2026-10-13）**：`GET /attachments/download/{attachmentId}`——上传者本人或经 `AttachmentAccessPort`（定义在通用模块 `application.port`，可缺省）；流式输出 + 安全响应头（`Content-Disposition`/`nosniff`）；404 统一隐藏 |
| 5 | 工单侧接入 | **已完成（2026-10-13，用户明确授权直接写入）**：`TicketController.create/supplement` 增加 `files`（重复 part）与 `attachmentIds`；绑定写 `ticket_attachment`（同事务）；`supplement` 去掉旧 `400` 拦截；`TicketAttachmentAccessAdapter` 实现 `AttachmentAccessPort`（可见性谓词逐字复用 `selectVisibleDetail`） |
| 6 | 提交顺序与失败补偿 | **已完成（2026-10-13）**：三处写入口（独立上传、创建、补充）均按 7.2 实现：流式暂存 → 校验 → 事务内写元数据/绑定 → 逐个**原子移动** → 提交；失败补偿删除暂存与已移动文件（补偿失败只告警，交第 7 步对账兜底）；`sha256` 在暂存阶段计算 |
| 7 | 孤儿对账任务 | **已完成（2026-10-13）**：`AttachmentReconciliationTask`（每小时整点，`@EnableScheduling` 已开）——超期暂存清理、最终目录以数据库为权威对账（无记录文件删除；记录在而文件缺失只告警）、超期未绑定附件清理（**先删元数据再删文件**，靠下一轮自愈；无授权端口实现时整段跳过，宁可堆积不误删）；列举时只认受控随机存储名 |
| 8 | 测试与真实栈验收 | 测试类由 Agent 负责（校验失败、暂存写失败、首/中/末一个移动失败、原子移动不支持、MySQL 回滚、提交失败、清理失败、进程中断后孤儿对账、记录缺文件、带权限下载与 404 隐藏）；真实栈验收脚本 + 证据 JSON + 前端 E2E（创建带附件 → 详情下载） |
| 9 | 前端 | 新建页与补充入口的上传控件（含「先传后绑」引用）与失败文案（与后端错误码对应）、详情页附件块与鉴权下载（文件流下载需带令牌，不能直链） |
| 10 | 收口 | 契约与状态文档同步（`PROJECT_STATUS.md`、`AGENTS.md` 当前阶段），按仓库惯例分支交接 |

## 5. 开工前置与当前步骤

- **开工前置**：关联已合并 `main`（PR #21、合并提交 `7c7807d`，`a3b978c` 已在 `origin/main`）；附件主题分支 `flow-desk/ticket-attachments` 已从该提交创建（2026-10-12）；同日经用户确认**方案 B**（独立模块 + 两表 + 独立端点）与**两表选择**（通用表 + 绑定表）。
- **当前步骤（2026-10-13）**：第 5～7 步已按用户明确授权全部落地（含 `TicketServiceImpl` 与对账任务）；迁移改号 **`V8`→`V9`**；保留期统一为 `flowdesk.attachment.retention`（默认 24h）。**下一步 = 第 8 步「测试与真实栈验收」**（待用户统一发起；存量用例的红为预期），随后是前端（第 9 步）与收口交接（第 10 步，仍是重基 + V9/基线合并）。

## 6. 已裁决（原「开工前待裁决」，2026-10-12 全部关闭）

1. **`files` part 形状**：重复 part 名 `files`（多文件集合）+ `ticket` part JSON 内 `attachmentIds` 引用（先传后绑）；不设带每文件元数据的 part。
2. **失败补偿是否落隔离目录**：v1 **直接删除 + 告警日志**，不设隔离目录（`docs/engineering-readiness.md` 7.2 允许「隔离/删除」两种口径）。

## 7. 与其它文档的关系

- `docs/implementation-plan.md` 第 9 节 backlog 第 2 项（本项来源与批次顺序；2026-10-12 已登记方案 B）；本文件是其「重」侧的细化，不替代契约文档。
- 规则正文引用：`docs/api-design.md` 6.2/6.4（创建与补充的 `files`/`attachmentIds`）、7.1/7.2/7.6、第 9 节、10.2（`ATTACHMENT_NOT_FOUND`）；`docs/engineering-readiness.md` 7.2、11.2；`docs/technical-architecture.md` 第 4 节、6.3；`docs/database-design.md` 6.3、19.3、23、24。
- 交付状态同步：本文件为唯一入口；`PROJECT_STATUS.md` 仅在附件阶段完成或交接时同步（按仓库惯例）。