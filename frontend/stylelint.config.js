/**
 * 样式闸门（rung 3 · Enforce）：把 frontend/AGENTS.md「设计 token（唯一真源）」
 * 从书面约定变成可执行规则。由 `pnpm lint` 调用，CI 的 frontend-verify job
 * 因此自动获得该覆盖，不需要单独加一个 job。
 *
 * 规则语义：下列属性的值里必须出现 `var(--fd-*)` 或 `var(--el-*)`。
 * 覆盖范围 = AGENTS.md 点名的六个轴：颜色、间距、字号、圆角、阴影、动效时长。
 *
 * 刻意不纳入的（写在这里，免得后来者以为是漏了）：
 * - 尺寸类（width / height / min-* / max-* / inset / top 等）：大多是布局约束而不是设计刻度
 *   —— `min-width: 0` 的 flex 复位、`min-width: 320px` 的视口下限、`100vh`。
 *   为它们造 token 会违反 .ui-craft/tokens.md「只在定义处出现的刻度应当删除」。
 * - 断点数值（@media 里的 52rem）：stylelint 不检查媒体查询的取值，仍靠 review。
 * - 缓动是否过冲：token 里只有 --fd-ease-standard，且 transition 必须引用 token，
 *   所以字面量 cubic-bezier 会被下面第一条规则挡住。
 *
 * 例外只有两个文件，它们是颜色与刻度字面量的合法归宿：
 * src/styles/tokens.css 与 src/styles/element/var-override.scss。
 * 其余位置确实无法用变量解决时，用行内豁免并写明理由，让每个例外都能被 grep 到：
 *   stylelint-disable-next-line declaration-property-value-allowed-list -- 理由
 */

/** 值必须引用项目 token：--fd-* 是结构刻度，--el-* 是 Element Plus 映射。 */
const TOKEN = /var\(--(fd|el)-/
/** 复位值：0 / auto 及其组合（`margin: 0`、`margin: auto 0`）不是魔法数字。 */
const RESET = /^((0|auto)(\s+(0|auto))*)$/
/** 显式无值。 */
const NONE = /^none$/
/** 零时长。 */
const ZERO_TIME = /^0m?s$/
/** `transition: all` —— AGENTS.md 要求列出具体属性。 */
const ALL = /\ball\b/

export default {
  defaultSeverity: 'error',
  ignoreFiles: ['dist/**', 'coverage/**', 'playwright-report/**', 'test-results/**'],
  overrides: [
    // Vue SFC 的 <style> 块与独立 SCSS 文件都需要各自的解析器
    { files: ['**/*.vue'], customSyntax: 'postcss-html' },
    { files: ['**/*.scss'], customSyntax: 'postcss-scss' },
    {
      // 字面量的两个合法归宿：只关掉 token 规则，其余结构规则照常生效
      files: ['src/styles/tokens.css', 'src/styles/element/**'],
      rules: {
        'color-no-hex': null,
        'color-named': null,
        'declaration-property-value-allowed-list': null,
      },
    },
  ],
  rules: {
    // ---- 颜色：任何属性里都不许出现十六进制或具名颜色（含 border / background 简写） ----
    'color-no-hex': true,
    'color-named': 'never',

    // ---- 六个轴：值必须引用 token ----
    'declaration-property-value-allowed-list': {
      // 颜色
      color: [TOKEN],
      '/^(background|background-color|background-image)$/': [TOKEN, NONE],
      '/^border(-(top|right|bottom|left))?-color$/': [TOKEN],
      'outline-color': [TOKEN],
      'text-decoration-color': [TOKEN],
      'caret-color': [TOKEN],
      'accent-color': [TOKEN],
      '/^(fill|stroke)$/': [TOKEN],
      // 间距
      '/^(margin|padding)(-(top|right|bottom|left))?$/': [TOKEN, RESET],
      '/^(gap|row-gap|column-gap)$/': [TOKEN, RESET],
      // 字号
      'font-size': [TOKEN],
      // 圆角
      '/^border-radius$|^border-(top|bottom)-(left|right)-radius$/': [TOKEN],
      // 阴影
      '/^(box-shadow|text-shadow)$/': [TOKEN, NONE],
      // 动效
      transition: [TOKEN, NONE],
      '/^(transition|animation)-(duration|delay|timing-function)$/': [TOKEN, ZERO_TIME],
    },

    // ---- Element Plus 只走变量映射，不改组件内部结构 ----
    'selector-disallowed-list': ['/:deep\\(/'],

    // ---- 动效：禁止 transition: all ----
    'declaration-property-value-disallowed-list': {
      transition: [ALL],
      'transition-property': [ALL],
    },

    // ---- 基础正确性：核心规则，不需要额外依赖 ----
    'block-no-empty': true,
    'color-no-invalid-hex': true,
    'declaration-block-no-duplicate-properties': true,
    'font-family-no-duplicate-names': true,
    'function-calc-no-unspaced-operator': true,
    'keyframe-declaration-no-important': true,
    'no-duplicate-selectors': true,
  },
}
