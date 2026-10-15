# 附件完善（存储抽象 + 对象存储 + 内联预览 + 批量）实施说明

> 本文件是「附件完善（路线 B）」的实施入口文档，记录范围、分批计划、关键设计与**待确认决策点**。
> 契约上限是 `docs/api-design.md` 7.1/7.2/7.6；失败顺序与对账口径是 `docs/engineering-readiness.md` 7.2；
> 存储方案是 `docs/technical-architecture.md` 6.3（**本批修改**）；表结构是 `docs/database-design.md` 6.3/19.3。
> 上一批（通用附件模块 + 工单接入，步骤 1～7）的交付记录见 `docs/modules/ticket-attachments.md`。
>
> **分支**：`flow-desk/attachment-storage-preview`，基点 `023c2fb`（= `main`，PR #23 合并后的 tip）。
> 上一个主题分支 `flow-desk/ticket-attachments` 已随 PR #23 合并 `main`，按仓库惯例**不再在其上继续开发**。
>
> **阶段状态（2026-10-14）**：方案已登记，**代码未动**。第 6 节的决策点确认后进入批 1。

## 1. 目标与边界

**目标**：把附件从「单实例本地受控目录」升级为一个**真附件系统**——存储可换驱动（本地 / 任意 S3 兼容对象存储）、
支持内联预览与批量上传、限额可配，且**不削弱既有的三重校验、失败补偿与对账纪律**。

**做**：

- 存储层抽成 `AttachmentStorage` 接口 + 两个驱动：`local`（开发/测试，保留现有原子移动）与 `s3`（生产，S3 兼容协议）。
- 开发/演示用 MinIO（S3 协议，本地零云成本）；生产换阿里云 OSS 或任意 S3 兼容存储，**应用代码不改**。
- 内联预览端点：`GET /attachments/{attachmentId}/content?disposition=inline`。
- 批量上传 1～5 个、上传进度、取消、粘贴上传、失败精确到文件名。
- 限额可配（`flowdesk.attachment.max-file-size` 等），不再改代码。
- 前端：缩略图、图片灯箱、PDF 预览、文本内联、整块附件区（上传人 / 大小 / 时间）。
- 对象存储下的失败补偿与孤儿对账重做（按 key 删对象；对账从「扫本地目录」改为「列对象 + 比对 DB」）。
- 修改 `docs/technical-architecture.md` 6.3 与 `docs/kickoff.md` 3.3 中「v1 不做对象存储」两条已确认结论。

**不做**（越界即停）：

- 不做附件替换、删除接口；不做病毒扫描、断点续传、分片上传。
- 不做附件的签名 URL 重定向预览——**预览必须留在「后端授权 → 后端代理内容」这条路上**，URL 一泄露就是公开文件。
- 不引入消息队列、分布式任务平台；对账仍是单实例 `@Scheduled`。
- 不改已发布的历史迁移（本批**不新增迁移**：表结构无需变化）。

## 2. 已确认的既有规则（本批不改）

- **两类上传路径**：独立上传（`POST /attachments/upload`）与随动作上传（创建/补充携带 `files` / `attachmentIds`）。
- **三重一致**（7.1）：扩展名 + 声明媒体类型 + **服务端识别结果**；白名单 JPEG / PNG / PDF / TXT / DOCX / XLSX。
- **整体失败**：任一附件校验或保存失败，整个动作失败，不留元数据或孤立文件。
- **引用规则**：`attachmentIds` 只能引用本人上传、未绑定、未超保留期的附件。
- **提交顺序**（`engineering-readiness.md` 7.2）：流式暂存 → 校验 → 事务内写元数据/绑定 → 逐个移动到最终存储 → 提交；失败补偿 + 对账兜底。
- **授权**：上传者本人，或经 `AttachmentAccessPort` 判定业务可见；不可见与不存在统一 404。
- **存储定位**：服务端随机 `storageKey`，Web 根目录之外，DB 只存元数据，客户端文件名不参与路径拼接。

## 3. 关键设计

### 3.1 存储抽象：把「暂存」留在本地，只把「最终存储」抽象出去

这是本批最重要的一个设计决定，理由是**校验组件需要可随机访问的本地路径**
（`AttachmentValidator` 的魔数嗅探与 OOXML 中央目录枚举都走 `AttachmentStorage.stagedPath()`）：

- **两个驱动共用同一套本地暂存语义**：`stage()` 一律写入本地暂存目录（流式 + 同时算 SHA-256）。
- **差异只在「落最终」这一步**：
  - `local`：`Files.move(..., ATOMIC_MOVE)`（现状不变）。
  - `s3`：`PutObject` 上传对象（带服务端识别出的 `Content-Type`）→ 删除本地暂存文件。
- **收益**：`AttachmentValidator`、`TicketServiceImpl` 的编排、`AttachmentServiceImpl` 的上传顺序**一行不用改**；
  对象存储的引入对上层是透明的。

接口调整（`com.flowdesk.attachment.infrastructure.AttachmentStorage`）：

| 方法 | 变化 | 说明 |
| --- | --- | --- |
| `stage(InputStream)` | 不变 | 两个驱动都写本地暂存 |
| `moveToFinal(key)` | **改名 `promote(key, contentType)`** | 语义从「移动」放宽为「把暂存内容提交到最终存储」；`contentType` 供 S3 设置对象类型 |
| `stagedPath(key)` | 不变 | 仍是本地路径，校验组件继续可用 |
| `openFinal(key)` | 不变 | local = 文件流；s3 = `GetObject` 流 |
| `deleteStaged` / `deleteFinal` | 不变 | s3 的 `deleteFinal` = `DeleteObject`（不存在视为成功，幂等） |
| `listStaged()` | 不变 | 仍是本地暂存目录（含 `modifiedAt`） |
| `listFinal()` | 不变 | local = 目录列举；s3 = `ListObjectsV2`（自动翻页，`modifiedAt` 取 `LastModified`） |
| `finalExists(key)` | 不变 | local = `isRegularFile`；s3 = `HeadObject`（`NoSuchKey` → false） |
| `kind()` | **新增** | 返回 `local` / `s3`，供启动日志与「同卷约束仅 local 要求」的判定 |
| `verify()` | **新增（启动自检）** | local：现有一致性探针；s3：bucket 可达 + 探针对象可写/可读/可删 |

**同卷约束的收窄**：现有 `LocalAttachmentStorage` 构造时的「暂存与最终同卷 + 支持原子移动」探针
**只保留在 local 驱动**；s3 驱动改为 bucket 连通性探针，不再要求本地同卷。

**驱动选择**：`flowdesk.attachment.storage: local | s3`（默认 `local`），用 `@ConditionalOnProperty` 分两个配置类装配；
`LocalAttachmentStorage` 去掉 `@Component`，改为在配置类里显式 `@Bean`。

### 3.2 S3 驱动

- SDK：`software.amazon.awssdk:s3`（AWS SDK v2），不引入 spring-cloud-aws——只需要一个 client，不值得多一层 BOM。
- MinIO 用 `pathStyleAccess(true)` + 自定义 `endpointOverride`；生产 OSS/S3 走同一套代码，只换 endpoint 与凭据。
- 配置（全部走环境变量）：

  | 配置项 | 说明 |
  | --- | --- |
  | `flowdesk.attachment.storage` | `local` / `s3`，默认 `local` |
  | `flowdesk.attachment.s3.endpoint` | MinIO：`http://127.0.0.1:9000`；生产：OSS/S3 的 endpoint |
  | `flowdesk.attachment.s3.bucket` | 桶名 |
  | `flowdesk.attachment.s3.region` | 区域（MinIO 可填任意值） |
  | `flowdesk.attachment.s3.access-key` / `secret-key` | 凭据，**不写入仓库** |
  | `flowdesk.attachment.s3.path-style` | 默认 `true`（MinIO 必须） |

- `compose.yaml` 增加 `minio` 服务 + 一次性 `createbuckets` 初始化（`minio/mc`），本地开发与演示开箱可用。
- **CI 不依赖云**：单元测试用内存假驱动；对象存储集成测试用 Testcontainers MinIO（本机容器，不是云）。
  默认 `local` 驱动，CI 的既有用例路径完全不变。

### 3.3 内联预览端点（本批唯一真正的安全雷区）

- 端点：`GET /attachments/{attachmentId}/content?disposition=inline|attachment`（缺省 `attachment`）。
- **内联白名单**（`inline` 只对这四个生效）：`image/jpeg`、`image/png`、`application/pdf`、`text/plain`。
- **一律不内联**：HTML、SVG（当前上传白名单里根本没有，属纵深防御）、DOCX / XLSX（Office 文档降级为下载）。
- 判定依据是 **DB 里服务端识别出的类型**，绝不回显客户端声明；类型不在内联白名单时，
  即使请求 `disposition=inline` 也**强制降级**为 `attachment`。
- 响应头（inline 与 attachment 都要）：

  | 头 | 值 | 目的 |
  | --- | --- | --- |
  | `Content-Type` | 服务端识别类型 | 不信任客户端 |
  | `Content-Disposition` | `inline; filename*=UTF-8''…` / `attachment; filename*=UTF-8''…` | 安全编码文件名 |
  | `X-Content-Type-Options` | `nosniff` | 阻止浏览器嗅探改判 |
  | `Content-Security-Policy` | `default-src 'none'; sandbox` | 即使内容被当作 HTML 解析也不执行脚本 |
  | `Cross-Origin-Resource-Policy` | `same-origin` | 阻止跨站嵌入 |
  | `Cache-Control` | `private, no-store` | 授权内容不进共享缓存 |

- **授权不变**：`isVisibleTo`（上传者本人 + `AttachmentAccessPort`）；不可见与不存在统一 404。
- **绝不**用签名 URL 重定向——那等于绕过应用授权。

### 3.4 批量上传与前端体验

- 后端 `/attachments/upload` 接受重复 `files` part（1～5，与工单侧 `files` 同形状），**整体成功或整体失败**，
  失败响应里带**具体文件名与原因**（满足「失败精确到文件名」，同时不破坏 7.1 的整体失败语义）。
- 前端按**逐文件**调用单文件上传，从而天然获得逐文件进度、独立取消与精确失败；后端批量能力保留给其它调用方。
- 前端能力：多选、上传进度、取消（`AbortController`）、粘贴上传（`paste` 事件取 `clipboardData.files`）、
  失败逐条列出（文件名 + 错误码对应文案）。
- 附件区：上传人显示名、人类可读大小、上传时间、下载/预览入口。

### 3.5 限额可配

`AttachmentLimits` 的常量迁入 `AttachmentProperties`（**契约文档 7.1/7.6 需同步改写**）：

| 配置项 | 默认 | 现常量 |
| --- | --- | --- |
| `flowdesk.attachment.max-file-size` | `10MB` | `MAX_FILE_SIZE_BYTES` |
| `flowdesk.attachment.max-files-per-request` | `5` | `MAX_FILES_PER_REQUEST` |
| `flowdesk.attachment.max-request-total-size` | `25MB` | `MAX_REQUEST_TOTAL_BYTES` |
| `flowdesk.attachment.max-original-name-length` | `255` | `MAX_ORIGINAL_NAME_LENGTH` |

**已知耦合（必须一起改，否则调高限额不生效）**：Spring 的
`spring.servlet.multipart.max-file-size` / `max-request-size` 是外层闸，且是静态配置。
本批把外层闸设为「≥ 应用层配置」的安全值并在 `application.yml` 注明两者关系；白名单类型仍留在代码里（不属于「限额」）。

### 3.6 对象存储下的失败补偿与对账

- **失败补偿**：`promote` 上传成功但事务提交失败 → `afterCompletion` 按 `storageKey` 删对象（现有钩子复用，
  只把 `deleteFinal` 的实现换成 `DeleteObject`）。补偿本身失败只告警。
- **对账重做**：`AttachmentReconciliationTask` 三段在接口统一后**几乎不用改**（`listFinal()` 由驱动吸收差异）：
  1. 超期暂存清理——暂存仍在本地，不变；
  2. 最终存储对账——「列对象 + 比对 DB」，DB 为权威；无记录对象删除，记录在而对象缺失只告警；
  3. 超期未绑定清理——先删元数据再删对象，靠下一轮自愈。
- **`@Scheduled` 自带开关**：新增 `flowdesk.attachment.reconciliation.enabled`（默认 `true`），
  由 `@ConditionalOnProperty` 或方法内早退控制；测试与本地调试可关。
- **一处规模注记**：`selectAllStorageKeys()` 一次全量取键，在对象存储下不再有「目录列举」的天然分页，
  演示规模可接受；本批**不**改造为分批比对，登记为已知限制。

## 4. 分批计划

| 批 | 主题 | 主要落点 |
| --- | --- | --- |
| **批 1** | 存储抽象 + 对象存储驱动 | `AttachmentStorage`（接口调整）、`LocalAttachmentStorage`（改名 `promote`、保留同卷探针）、`S3AttachmentStorage`（新增）、`AttachmentStorageConfiguration`（按配置装配）、`AttachmentProperties`（`storage` / `s3` 段）、`compose.yaml`（MinIO + 建桶）、`pom.xml`（AWS SDK v2）、`AttachmentReconciliationTask`（开关）、`application*.yml` |
| **批 2** | 内联预览 | `AttachmentController`（`/content` 端点）、`AttachmentService` / `AttachmentServiceImpl`（`open(id, disposition)`）、`domain` 新增内联白名单判定、安全响应头、前端附件区 + 图片灯箱 + PDF 预览 + 文本内联 |
| **批 3** | 批量 + 前端体验 + 限额可配 | `UploadAttachmentCommand`（多文件）、`AttachmentValidator`（改读 properties）、`AttachmentProperties`（限额段）、`application.yml`（multipart 外层闸）、前端多选/进度/取消/粘贴/逐条失败 |
| **可选** | 预签名直传 | 见 §5 |

## 5. 可选批（预签名直传）的取舍

**倾向不做，登记为候选**，理由是它和本项目的核心纪律冲突：

- 直传把内容写进对象存储，**服务端没有经过 `stagedPath`**，三重一致校验要么放弃、要么回读对象重做。
- 回读校验需要「下载对象 → 本地临时文件 → 魔数 + OOXML 中央目录枚举」，**带宽与当前方案基本等价**，
  只在「不做服务端校验」时才有收益——而那正是本项目刻意保留的安全边界。
- 结论：真要省带宽，代价是放弃「服务端识别结果」这一重校验，属于安全口径变更，应单独立项并显式取舍。

## 6. 待确认决策点（2026-10-14 已由用户裁决）

| # | 决策点 | 选项 | 用户裁决 |
| --- | --- | --- | --- |
| 1 | **ServiceImpl 业务实现的归属**（批 2/批 3 会改 `AttachmentServiceImpl`，批 1 不改） | A 本轮授权 Agent 直接写入（含 ServiceImpl），用户 review；B 沿用现行规则（先文字 + 流程图，再由用户写，测试与前端等用户写完统一发起）；C 混合 | **A** |
| 2 | **预览端点路径** | A 新增 `/attachments/{id}/content`，保留 `/attachments/download/{id}` 作别名并标记 deprecated；B 只用新路径、删除旧路径；C 保留 `/download/{id}`，用 `?disposition=inline` 扩展 | **A** |
| 3 | **S3 驱动的测试与 CI 口径** | A 单元测试用内存假驱动 + Testcontainers MinIO 集成测试；B 只用内存假驱动；C 只做手工真实栈验收 | **A** |
| 4 | **本轮范围** | A 批 1 + 批 2 + 批 3（预签名直传留下一批）；B 加预签名直传；C 只做批 1 | **A** |

## 6.1 本轮交付记录（2026-10-14）

### 后端（批 1 + 批 2 + 批 3）

- 存储抽象：`AttachmentStorage`（`moveToFinal` → `promote(key, contentType)`、新增 `kind()`）、`AbstractAttachmentStorage`（本地暂存共用实现）、`LocalAttachmentStorage`（保留同卷探针，改为构造器注入 properties）、`S3AttachmentStorage`（AWS SDK v2，`putObject` + `deleteObject` + `listObjectsV2Paginator` + `headObject`）、`AttachmentStorageConfiguration`（按 `flowdesk.attachment.storage` 装配，`@ConditionalOnProperty`）。
- 配置：`AttachmentProperties` 扩为 8 项（含 `storage`、四个限额、`s3` 段）；`AttachmentLimits` 删除；`application.yml` / `.env.example` / `compose.yaml`（`minio` + `minio-init`）同步。
- 预览：`GET /attachments/{id}/content?disposition=attachment|inline`，`/attachments/download/{id}` 保留为 deprecated 别名；`DetectedFileType.inlinePreviewable()` 作为唯一白名单；响应头 `nosniff` + `CORP: same-origin` + `Cache-Control: private, no-store`；**PDF 内联不下发 CSP**（沙箱会屏蔽 Chrome PDFium 查看器，已在控制器注释中说明并有断言）。
- 批量：`POST /attachments/upload` 接受重复 `files` part（1～5，整体成败，错误文案含文件名）。
- 工单侧：详情 `attachments[]` 投影（`TicketAttachmentMapper.selectAttachmentsByTicketId` + `TicketAttachmentResult`）；创建与补充均支持 `attachmentIds` 先传后绑。
- 补偿修正（真实缺陷）：暂存后复核失败时，该文件自己的暂存副本原先会漏删——`AttachmentServiceImpl.stageOne` 与 `TicketServiceImpl.stageOne` 均补 try/catch 先删暂存再抛出。

### 前端（批 2 + 批 3）

- 新增 `api/attachments.ts`（逐文件上传、`content` 取回、`saveBlob`、`formatFileSize`、`isUploadCanceled`）与 `api/http.ts` 的 `publicHttp`（公共前缀 `attachments/xx`，同一套 Bearer 注入与 401 单次刷新重放，超时 60s）。
- 新增 `components/AttachmentUpload.vue`：**一个文件一次请求**（进度 / 取消 / 失败天然按文件独立，失败文案带文件名）、1～5 上限、粘贴上传（监听挂在控件自身，不抢全局）、拖放、`update:uploading` 供父组件拦提交。
- 新增 `components/AttachmentList.vue`：缩略图（图片）、`el-image` 灯箱、PDF `iframe` 预览、纯文本内联（超 2 万字符截断并说明）、下载；**所有内容经后端授权代理取回为 Blob**，没有一条直链；可预览性由服务端 `inlinePreviewable` 决定。
- 接线：`TicketCreateView` 附件区 + 上传中禁止提交 + `attachmentIds` 入请求体；`TicketDetailView` 附件区（紧跟问题描述）+ 动作弹窗的附件槽（登记表新增 `TicketActionMeta.attachments`，`supplement` 使用），并按服务端跨字段规则放行"只补附件、正文留空"。
- 样式：`styles/main.css` 追加附件区与上传控件段落（全部走 `--fd-*` token，`stylelint` 通过）。

### 验证（本轮实际执行）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 前端类型检查 | `npx vue-tsc -b` | 通过（0 error） |
| 前端 Lint | `npx eslint . --max-warnings=0` + `npx stylelint "src/**/*.{css,scss,vue}" --max-warnings=0` | 通过 |
| 前端单测 | `npx vitest run` | 见下方「验证口径」执行记录 |
| 后端附件单测 | `sh /tmp/mvnx.sh -B -q test ...` | 附件四个测试类 40 项全绿 |
| 后端 S3 集成测试 | `S3AttachmentStorageIT` | **未真正执行**：本机镜像仓库无法提供 `minio/minio`（`免费节点当前繁忙`），用例按 `Assumptions.abort` 主动跳过而非通过。需在可用镜像的环境补跑。 |

## 7. 文档影响面（实施时同步）

- `docs/technical-architecture.md`：6.3 结论（本地目录 → S3 兼容对象存储 + 驱动可换）、
  第 13 节「明确不采用的方案」中的「对象存储集群」、第 14 节结论与代价段、第 11 节运行与部署边界（附件目录 → 对象存储）。
- `docs/kickoff.md`：3.3（去掉「在线预览与对象存储不属于 v1」，改为本批已确认口径）。
- `docs/api-design.md`：7.1 / 7.2 / 7.6（`/content` 端点、内联白名单、批量、限额可配）、10.2（新增错误码，如有）。
- `docs/engineering-readiness.md`：7.2（同卷约束仅 local；对账从扫目录改为列对象 + 比对 DB）、第 5 节部署清单、11.2。
- `docs/database-design.md`：6.3 / 19.3 的存储定位表述。
- `docs/modules/ticket-attachments.md`：第 1 节「不做」清单与第 3 节现状盘点。
- `docs/implementation-plan.md` 第 9 节、`README.md`、`PROJECT_STATUS.md`、`AGENTS.md` 当前阶段。

## 8. 验证口径（实施时按此执行）

- 后端：`.\mvnw.cmd -B clean verify "-DargLine=-Djdk.attach.allowAttachSelf=true"`；新增 S3 驱动集成测试与预览安全用例。
- 预览安全用例（必须真的断言响应头）：`disposition=inline` 对 JPEG/PNG/PDF/TXT 返回 `inline`；
  对 DOCX/XLSX 返回 `attachment`；`nosniff` / CSP / CORP 头逐字存在；不可见附件统一 404。
- 前端：`typecheck` / `lint` / `build` / 单测 / 全量 E2E（含带附件创建 → 详情预览与下载）。
- 真实栈验收：MinIO 起的本地栈 + 脚本 + 证据 JSON（沿用仓库既有惯例，不伪造证据）。
- **本批不新增 Flyway 迁移**（表结构不变）。
