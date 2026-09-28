# 管理端四页视觉改版（2026-09-28）

用户当轮明确授权：**以用户管理页为基准，直接推广到其余四个 RBAC 管理页，不需要重新打样或逐页等待确认。**
本报告是这四页（角色管理、权限管理、用户角色授权、角色权限授权）的改版说明与证据。
用户管理页本轮只在共享层对齐（分页断点抽成 composable），页面视觉未改。

## 1. Before / After / Why

| Before | After | Why |
| --- | --- | --- |
| 四页用 `AppPage` 默认 `card` 模式，被 70rem 居中大卡片限制 | 四页改用 `layout="list"`，跟随工作区宽度（`--fd-admin-list-max` = 100%） | 表格的宽度决定者是列宽与操作位，不是阅读宽度；实测 1440px 下容器从被压窄变为 1168px |
| 工具栏、表格、分页是三块独立元素，只有表格在面板里 | 三者收进同一个 `.admin-data` 面板（工具栏带头部分隔线、分页带上缘分隔线） | 它们是同一件事的三个部分（对这批数据做什么 / 数据是什么 / 多少条），分成三块会读成三个无关区域 |
| 控件普遍 `size="small"` | 去掉 `size="small"`，回到默认尺寸（`--fd-control-size-md`，36px） | 旧尺寸下按钮与输入框比正文还小；参考后台的控件是正常尺度 |
| 按钮语义不统一：修改/新增都用 primary，行内删除是 `text` 无底色图标 | 按操作语义着色：primary 查询/新增/授权、success 编辑/保存/启用、danger 删除/撤销、warning 重置密码、info 重置/刷新/取消/次级导航；次操作 `plain` | 一眼能分出"哪个是主操作、哪个会破坏数据"；`text` 图标按钮在密集表格里缺少可点击的视觉证据 |
| 行内操作是中性 `text` 图标；删除按钮靠原生 `title` 解释为什么不能点 | 行内操作统一 `plain` + `circle` 的有色图标按钮，保留 `el-tooltip` 与 `aria-label`；保护原因交给 `ProtectedMark` | 原生 `title` 在禁用按钮上不可靠（禁用元素常常不触发 hover），而标记一直在行内可见 |
| 两句用户可见文案直接讲实现：权限页"编码是后端授权检查的锚点""新增权限本身不会自动产生业务接口或安全规则"，角色页"审计都按编码引用角色" | 改为业务语言："权限是访问能力的开关""新建权限只是登记一项能力：要真正生效，还需要把它授予角色""授权关系与操作记录都按编码引用" | 用户不需要知道后端怎么实现；说清后果即可 |
| 保护标记的 `reason` 写的是编码常量（"不能移除它的 RBAC_MANAGE 授权""不能从 SYSTEM_ADMIN 撤销"） | 写业务语言 + 编码只作为对象的显示名（"不能取消它的角色管理授权""也不能从系统管理员角色撤销"） | 标记的作用是让人理解"为什么这行不能动"，不是复述权限表 |
| 授权页的选中主体后，操作栏与关系行散落在卡片里；关系行撤销是 `text` 图标 | 授权页同样分成"授予面板 + 数据面板"；关系行保留（主体 — 关系轴 — 客体 — 审计），撤销按钮改为带描边的 `plain` danger 圆形图标 | 统一宽度、分区、密度、按钮与分页语言，但**不把授权关系改成 CRUD 表格**——授权是责任记录，不是同构数据行 |

## 2. 改了哪些文件

| 文件 | 改动 |
| --- | --- |
| `frontend/src/views/admin/RoleListView.vue` | `layout="list"`；筛选区 + `.admin-data`；列宽 ident 200→224、count 96→112、actions 132→144；行内删除改为 tooltip + `aria-label` 无原生 `title`；弹窗加 `admin-dialog` 与图标按钮；业务化文案 |
| `frontend/src/views/admin/PermissionListView.vue` | 同上（ident 224→240、count 96→112、actions 100→120）；去掉"后端授权检查"类文案 |
| `frontend/src/views/admin/UserRoleGrantView.vue` | `layout="list"` + `.grant-form`；授予表单与数据区分离；主体未选时数据区只有空态（操作栏整块不渲染）；清空/跳转按钮补语义色与图标；撤销按钮改带描边图标 |
| `frontend/src/views/admin/RolePermissionGrantView.vue` | 同上；清空按钮保留禁用态与原因提示；单条撤销的禁用原因改为业务语言 |
| `frontend/src/views/admin/UserListView.vue` | 仅把内联的 `resize` 监听换成共享 composable（行为与断点不变） |
| `frontend/src/views/admin/useCompactPagination.ts` | **新增**：分页窄屏收敛（`matchMedia`，缺失时退回宽屏分页） |
| `frontend/src/styles/main.css` | `.admin-data` 面板（与 `.user-list-data` 共用规则）；`.admin-page .page__card` 宽度改用 `--fd-admin-list-max`；`.admin-dialog` 宽度不超视口；`.grant-form` 字段对齐；`.grant-row__action .el-button` 描边；窄屏媒体查询覆盖五页 |
| `frontend/src/styles/tokens.css` | 新增 `--fd-admin-list-max` |
| `frontend/AGENTS.md` | 新增「管理页统一视觉语言（2026-09-28 已推广到五页）」共享约定段 |
| `.ui-craft/brief.md`、`tokens.md`、`surfaces/admin-rbac.md` | 同步最终决定；旧 70rem 与纯图标方案标为历史 |

**没有改动**：后端代码、接口契约、数据库、权限规则、E2E 用例、任何测试文件、本次范围之外的业务页面；未新增依赖；未使用 `:deep()`；未放宽 lint。

## 3. 实跑命令与结果（本轮全部实跑）

| 命令 | 结果 |
| --- | --- |
| `vue-tsc -b`（= `pnpm typecheck`） | 退出码 **0** |
| `eslint . --max-warnings=0` | 退出码 **0** |
| `stylelint "src/**/*.{css,scss,vue}" --max-warnings=0` | 退出码 **0**（六轴 token 闸门，无行内豁免新增） |
| `vitest run --maxWorkers=1`（= `pnpm test:unit --run`） | **17 套件 74 项全绿**，退出码 0 |
| `vite build`（= `pnpm build`） | 退出码 **0**；`dist/assets/index-*.js` 1148.70 kB / gzip 366.97 kB，仅有既有的大 chunk 提示 |
| `playwright test`（= `pnpm test:e2e`，真实栈） | **13 项全绿**，23.9s，退出码 0 |
| `node .ui-craft/reviews/2026-09-28-admin-pages/capture.mjs` | 采集 19 张截图 + 布局探针，退出码 0；采集脚本留在本机工作区，本次同步报告、探针结果及截图 |

> 说明：本会话 `pnpm` 本身无法启动（`pnpm.mjs` 的 Windows shim 解析失败），因此上表的 pnpm 脚本按其
> `package.json` 中的等价命令，用 `node node_modules/...` 直接执行；`pnpm typecheck` = `vue-tsc -b`、
> `pnpm lint` = `eslint . --max-warnings=0 && pnpm lint:style`、`pnpm build` = `vue-tsc -b && vite build`
> （typecheck 已单独跑过并退出 0）、`pnpm test:unit` = `vitest`、`pnpm test:e2e` = `playwright test`。
> 这不是"跳过了 lint 或 build"，而是换了等价的调用入口。
>
> **一次副作用及其处置**：首次调用 `pnpm` 时，pnpm 12 的 packageManager 自管理逻辑往
> `frontend/pnpm-lock.yaml` 写入了 `@pnpm/exe@12.3.4`（外加 snapshot 段）——这与本次改版无关，
> 属于包管理器自举产物。已用 `git checkout -- frontend/pnpm-lock.yaml` 还原，还原后该文件无改动；
> 仓库既有 `pnpm@12.3.4` 固定不受影响。**结论：复核时请勿把这个 lockfile 差异当成改版产物。**

### 3.1 过程中修掉的真实缺陷（不是风格问题）

1. **`window.matchMedia is not a function` 让 5 个页面测试文件一起失败（24 项）**——`useCompactPagination`
   在 setup 阶段直接调用 `window.matchMedia`，而 jsdom 没有实现它。修法是在 composable 内做能力探测并退回宽屏分页。
   **没有改任何测试文件**，也没有放宽断言。
2. **两处文案改动撞上既有断言**——把授权页的"迁移预置"改成"系统预置"、把角色权限授权页空态里的
   `SYSTEM_ADMIN 的 RBAC_MANAGE` 换成业务说法后，`UserRoleGrantView.test.ts` 与
   `RolePermissionGrantView.test.ts` 断言失败。按"不改测试"的边界**回退了这两处文案**，
   只保留不触发断言的业务化改写。

## 4. 真实交互与视觉证据（1440×900 与 375×812）

真实栈：`local` profile + 真实 MySQL 3308 / Redis 6380 + 后端 8081 + `vite preview` 4173（代理 `/fd`）。
登录用演示管理员 `admin`；`/fd/v1/auth/login` 经 4173 代理返回 `200/OK`。

- **e2e 的真实闭环（自动化）**：建临时角色 → 授权限 → 清空 → 删除；给员工授临时角色 → 撤销 → 删除角色；
  五页入口按权限显隐 + 受保护对象删除按钮 `disabled`；窄屏 768px 文档不横向溢出、关系行竖排且关系轴隐藏。
  四条 RBAC 用例全部覆盖本轮改过的四页，且全部通过——说明按钮改名、结构重排、面板合并都没有破坏真实交互。
- **探针实测（`checks.json`）**：14 个场景的**整页横向溢出全部为 0**（1440 与 375 都查）；
  四页均无 Element Plus 默认蓝残留（`rgb(64,158,255)` 命中 0 个元素）；
  375px 下权限弹窗实测 `x=16, width=343, y=121.8, height=432.2`，完全在 375×812 视口内。
  JS 错误只有 2 条 `net::ERR_FAILED`，来自**故意注入**的错误态用例（`route.abort`），不是页面缺陷。
- **逐张查看截图后确认**：筛选卡片与数据面板分离、工具栏在面板头、边框表格、右下分页含"共 N 条 / 条页 / 前往 N 页"；
  行内三枚彩色图标按钮都落在操作列内、不换行不拥挤；长描述列省略号 + tooltip；
  窄屏表格内部横向滚动而文档不溢出、工具按钮不挤压、分页收敛为"共 N 条 / 页码 / 前后页"；
  授权页关系行在窄屏竖排且隐藏关系轴，撤销按钮左对齐。

### 4.1 截图清单

桌面 1440（正常态）：`roles-desktop-1440.png`、`permissions-desktop-1440.png`、
`user-roles-desktop-1440.png`、`role-permissions-desktop-1440.png`

已选主体的授权页（关系行有数据）：`user-roles-granted-1440.png`、`role-permissions-granted-1440.png`

窄屏 375（正常态）：`roles-mobile-375.png`、`permissions-mobile-375.png`、
`user-roles-mobile-375.png`、`role-permissions-mobile-375.png`、`user-roles-granted-mobile-375.png`

异常 / 弹窗 / 禁用态：`roles-error.png`、`permissions-error.png`（加载失败 + 原位重试）、
`roles-create-dialog.png`、`permissions-create-dialog.png`、`permissions-dialog-mobile-375.png`、
`roles-protected-row.png`（受保护行、删除入口禁用）

## 5. 六态落点核对

| 状态 | 证据 |
| --- | --- |
| loading | `AdminListPanel` 的骨架屏，五页共用（本次未改该容器） |
| empty | 未选主体时数据区显示"先选择要授权的用户 / 角色"；有主体但无关系时显示"该用户当前没有角色"等 |
| error | `roles-error.png` / `permissions-error.png`：行内 `role="alert"` 面板 + 原位"重新加载"（primary + 图标） |
| disabled | `roles-protected-row.png` 与 e2e 断言：受保护对象的删除/清空/撤销入口 `disabled` |
| 无权限 | 路由 `meta.permission` 显隐由既有 e2e 覆盖（员工看不到五页入口，退出后 403）；用户管理页的角色筛选仍按 `RBAC_MANAGE` 显隐 |
| 窄屏 | 375 五张截图 + 768px e2e 断言，整页溢出 0 |

## 6. 临时数据与演示库

采集脚本**只读**：不点任何提交按钮。写入来自既有 e2e 用例（自建临时角色 → 自清理）。
采集后经真实接口复核，演示库回到基线：

- 角色 **3**（`EMPLOYEE, IT_SUPPORT, SYSTEM_ADMIN`）、权限 **14**；`E2E%` 与 `MANUAL%` 残留 **0**；
- `employee`(1) 1 个角色、`it`(2) 1 个、`admin`(3) 3 个；`SYSTEM_ADMIN` 权限 4 项
  （`TICKET_ADMIN_HANDOFF, USER_MANAGE, CATEGORY_MANAGE, RBAC_MANAGE`）——**原有演示授权未被修改**。

## 7. 未验证 / 未做

- **用户逐项视觉反馈仍未取得**。本轮是用户授权推广，不等于用户已看过并认可这四页的视觉；
  后续调整以用户反馈为准。本报告不把"已推广"写成"审美验收通过"。
- 未跑后端测试（`mvnw clean verify`）。本轮未改任何后端代码，也没有新增/修改测试类。
- 未跑 1920px 宽度截图（本轮要求 1440 与 375）；1920 的宽布局在用户页打样时已核对过。
- `pnpm` 子命令本身在本机无法启动（shim 解析失败），上表用等价 `node` 入口执行；若在
  正常环境下复核，可直接跑 `pnpm typecheck` / `pnpm lint` / `pnpm build` / `pnpm test:unit --run` / `pnpm test:e2e`。
- 未做（越界）：未提交、未推送、未建 PR；未改后端业务、数据库、接口契约、身份流程、权限规则；
  未改本次范围之外的业务页面；未新增依赖；未放宽 lint；未新增测试类；未改动任何测试文件。
- **工作区保留情况**：进入本轮时已有的未提交与暂存改动（`src/` 32 条、`docs/` 3 条、
  `frontend/src/components/AdminListPanel.vue`、`frontend/src/views/admin/UserListView.vue`、
  `frontend/AGENTS.md`、`.ui-craft/brief.md`、`PROJECT_STATUS.md`、`?? .ui-craft/reviews/2026-09-28-user-buttons/`）
  全部原样保留，未做任何还原或覆盖。
