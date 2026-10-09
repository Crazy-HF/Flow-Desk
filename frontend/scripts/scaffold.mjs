#!/usr/bin/env node
// 页面脚手架：从 scripts/templates/page/ 生成一个与既有页面同构的列表页。
//
// 它存在的理由只有一个：让"以后每个页面都和之前的页面一致"不再依赖记忆。
// 模板必须来自上过生产、过了闸门的真实页面（RoleListView.vue 一系），
// 因此改模板前先改那一页，再把改动搬回来。
//
// 用法（在 frontend/ 下执行）：
//   node scripts/scaffold.mjs --layer admin --page NoticeListView --title 通知管理 \
//     --api notices --permission RBAC_MANAGE --api-section 8.7
//
// 刻意不做的事：不自动改 router/index.ts 与 constants/authorization.ts。
// 那两处需要人判断"权限是任一命中还是全部满足""子路由要不要沿用选中态"，
// 自动改容易悄悄弄坏导航与面包屑——脚本只把该贴的片段打印出来。

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const FRONTEND = resolve(HERE, '..')
const TEMPLATE_DIR = join(HERE, 'templates', 'page')

// 只有这些层允许生成：与 frontend/AGENTS.md 的目录分层表一致。
const ALLOWED_LAYERS = ['admin', 'work', 'auth', 'error']

const USAGE = `页面脚手架

  node scripts/scaffold.mjs --layer <层> --page <XxxView> --title <中文标题> --api <领域模块> --permission <权限码> [选项]

必填
  --layer        存放层，取值：${ALLOWED_LAYERS.join(' | ')}
  --page         页面组件名，PascalCase 且以 View 结尾，例如 NoticeListView
  --title        页面中文标题，例如 通知管理
  --api          领域 API 模块名（src/api/<这个名字>.ts），例如 notices
  --permission   进入该页所需的权限码，例如 RBAC_MANAGE

可选
  --api-section      docs/api-design.md 的章节号，写进注释，例如 8.7
  --description     页面副标题（写"这一页是干什么的"，不写技术说明）
  --empty           空态描述，缺省：新建第一条后它会出现在这里。
  --id-field        行主键字段名，缺省 id
  --force           目标文件已存在时覆盖
  --dry-run         只打印将生成的内容，不写文件
`

function parseArgs(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i]
    if (!token.startsWith('--')) continue
    const key = token.slice(2)
    if (key === 'force' || key === 'dry-run' || key === 'help') {
      out[key] = true
      continue
    }
    const value = argv[i + 1]
    if (value === undefined || value.startsWith('--')) {
      fail(`选项 --${key} 缺少值`)
    }
    out[key] = value
    i++
  }
  return out
}

function fail(message) {
  console.error(`\n脚手架无法继续：${message}\n\n${USAGE}`)
  process.exit(1)
}

const toPascal = (value) =>
  value
    .replace(/[_\-\s]+/g, ' ')
    .split(' ')
    .filter(Boolean)
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join('')

const toKebab = (value) =>
  value
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replace(/[_\s]+/g, '-')
    .toLowerCase()

function main() {
  const args = parseArgs(process.argv.slice(2))
  if (args.help) {
    console.log(USAGE)
    return
  }

  for (const required of ['layer', 'page', 'title', 'api', 'permission']) {
    if (!args[required]) fail(`缺少必填选项 --${required}`)
  }
  if (!ALLOWED_LAYERS.includes(args.layer)) {
    fail(`--layer 只能是 ${ALLOWED_LAYERS.join(' / ')}，收到 "${args.layer}"`)
  }
  if (!/^[A-Z][A-Za-z0-9]*View$/.test(args.page)) {
    fail(`--page 必须是 PascalCase 且以 View 结尾，例如 NoticeListView，收到 "${args.page}"`)
  }
  if (!/^[A-Z][A-Z0-9_]*$/.test(args.permission)) {
    fail(`--permission 必须是大写下划线权限码，例如 RBAC_MANAGE，收到 "${args.permission}"`)
  }
  if (!/^[a-z][a-z0-9]*$/.test(args.api)) {
    fail(`--api 必须是小写驼峰领域模块名，例如 notices，收到 "${args.api}"`)
  }

  const className = args.page.replace(/View$/, '')
  const idField = args['id-field'] ?? 'id'
  const domain = args.api
  const singular = toPascal(domain.replace(/s$/, ''))

  const tokens = {
    CLASS: className,
    TITLE: args.title,
    KEBAB: toKebab(className),
    PERMISSION: args.permission,
    PERMISSION_FLAG: `canManage${className}`,
    DESCRIPTION:
      args.description ?? `维护${args.title}；这一页对应 docs/api-design.md 里的接口契约。`,
    EMPTY_DESCRIPTION: args.empty ?? '新建第一条后它会出现在这里。',
    API_SECTION: args['api-section'] ?? '（待补章节号）',
    API_MODULE: `@/api/${domain}`,
    API_PREFIX: `/${domain}`,
    ID_FIELD: idField,
    TYPE_DETAIL: `${singular}Detail`,
    TYPE_LIST_PARAMS: `${singular}ListParams`,
    API_LIST: `list${toPascal(domain)}`,
    API_CREATE: `create${singular}`,
    API_DELETE: `delete${singular}`,
    FIXTURE: `{ ${idField}: 1, name: '示例数据' }`,
    FIXTURE_ASSERT: '示例数据',
  }

  tokens.API_IMPORTS = [tokens.API_CREATE, tokens.API_DELETE, tokens.API_LIST].join(', ')
  tokens.MOCK_FACTORY = [
    `  ${tokens.API_LIST}: vi.fn(),`,
    `  ${tokens.API_CREATE}: vi.fn(),`,
    `  ${tokens.API_DELETE}: vi.fn(),`,
  ].join('\n')

  const outputs = [
    {
      label: `src/views/${args.layer}/${className}View.vue`,
      path: join(FRONTEND, 'src', 'views', args.layer, `${className}View.vue`),
      template: 'ListView.vue.tpl',
    },
    {
      label: `src/views/${args.layer}/${className}View.test.ts`,
      path: join(FRONTEND, 'src', 'views', args.layer, `${className}View.test.ts`),
      template: 'ListView.test.ts.tpl',
    },
    {
      label: `src/api/${domain}.ts`,
      path: join(FRONTEND, 'src', 'api', `${domain}.ts`),
      template: 'api.ts.tpl',
    },
  ]

  const rendered = outputs.map((output) => {
    const raw = readFileSync(join(TEMPLATE_DIR, output.template), 'utf8')
    const content = raw.replace(/\{\{([A-Z_]+)\}\}/g, (match, key) => {
      if (!(key in tokens)) fail(`模板 ${output.template} 用了未知占位符 ${match}`)
      return tokens[key]
    })
    return { ...output, content }
  })

  if (args['dry-run']) {
    for (const output of rendered) {
      console.log(`\n===== ${output.label} =====\n`)
      console.log(output.content)
    }
    return
  }

  for (const output of rendered) {
    if (existsSync(output.path) && !args.force) {
      fail(`${output.label} 已存在；确认要覆盖请加 --force`)
    }
  }

  mkdirSync(join(FRONTEND, 'src', 'views', args.layer), { recursive: true })
  for (const output of rendered) {
    writeFileSync(output.path, output.content, 'utf8')
    console.log(`写入 ${output.label}`)
  }

  const routeName = `${args.layer}-${toKebab(className)}`
  console.log(`
接下来两处需要你手工贴（脚本刻意不代改）：

1) src/router/index.ts —— 路由表里加一条，import 与其它页面放在一起：

   import ${className}View from '@/views/${args.layer}/${className}View.vue'
   ...
   {
     path: '/${args.layer}/${toKebab(className)}',
     name: '${routeName}',
     component: ${className}View,
     meta: { title: '${args.title}', permission: '${args.permission}' },
   },

   权限语义：meta.permission 是**任一命中即通过**（数组表示多选一）。

2) src/constants/authorization.ts —— 需要出现在侧栏时，往 navigationEntries 加一条：

   {
     name: '${routeName}',
     label: '${args.title}',
     group: '${args.layer === 'admin' ? 'admin' : 'work'}',
     permission: '${args.permission}',
   },

   子路由若要沿用本入口的选中态，再补 activeRouteNames: ['<子路由 name>']。

最后按 frontend/PAGE-TEMPLATE.md §6 的步骤跑闸门并截图自查：
   pnpm typecheck && pnpm lint && pnpm build
`)
}

main()
