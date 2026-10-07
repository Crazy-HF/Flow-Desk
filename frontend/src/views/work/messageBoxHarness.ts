import ElementPlus from 'element-plus'
import { createApp, h, nextTick } from 'vue'

/**
 * 测试用的 `ElMessageBox` 替身。
 *
 * <p>`ElMessageBox.confirm` 是**命令式**组件：它自带 `document.body` 容器并渲染到 Vue Test Utils
 * 的 `wrapper` 之外，所以用例既不能在 wrapper 里找到确认框里的输入框，也没法"点确认"。
 * 直接把它替成 `vi.fn()` 更糟——那样输入框根本不会出现，只能靠猜断言。
 * 这里换一种做法：替身真的把宿主组件挂起来，并把"打开 → 填内容 → 点确认"这段顺序
 * 按真人操作的次序暴露给用例。</p>
 *
 * <p>关键在**顺序**：真人先看到弹窗、选好目标值、写好正文，最后才点确认。用例必须能有同一段顺序，
 * 否则测出来的是"确认按钮先被按下"这个现实中不存在的时序。</p>
 */
export interface MessageBoxHandle {
  title: string
  /** 确认框里的正文输入框；不需要正文或原因的动作没有这个元素。 */
  textarea: () => HTMLTextAreaElement | null
  /** 输入正文（走 `input` 事件，等同用户键入）。 */
  type: (text: string) => Promise<void>
  /** 确认框里的目标值选择器；不需要先选目标值的动作没有这个元素。 */
  select: () => HTMLElement | null
  /** 展开目标值下拉，返回可选的文案（真人要先点开下拉才看得到选项）。 */
  openSelect: () => Promise<string[]>
  /** 展开下拉并选中一项（按文案点，等同用户点那一行）。 */
  choose: (label: string) => Promise<void>
  /** 确认框正文的可见文字：空态与错误说明都必须说给用户，不能只看 DOM 里有没有元素。 */
  text: () => string
  /** 点「确认」，动作随后才会真正执行。 */
  accept: () => Promise<void>
  /** 点「取消」。 */
  cancel: () => Promise<void>
}

export interface MessageBoxHarness {
  /** 安装到 `vi.mock('element-plus')` 的 `ElMessageBox.confirm` 上。签名与真实方法一致。 */
  confirm: (message: unknown, title?: unknown, options?: unknown) => Promise<unknown>
  /** 最近一次打开的确认框；没打开过就是 `null`。 */
  current: () => MessageBoxHandle | null
  /** 打开过的次数，用来断言"哪些动作真的弹了确认框"。 */
  openCount: () => number
}

interface Host {
  textarea: () => HTMLTextAreaElement | null
  select: () => HTMLElement | null
  openSelect: () => Promise<string[]>
  choose: (label: string) => Promise<void>
  text: () => string
  accept: () => void
  cancel: () => void
  destroy: () => void
}

interface PendingBox {
  handle: MessageBoxHandle
}

/**
 * 当前下拉里可见的选项。
 *
 * <p>`el-select` 的下拉被 teleport 到 `body`，只能从 `document.body` 找；收起的下拉与不可用的选项
 * 带 `display: none` 的内联样式，那不算"用户看得到"。</p>
 */
function visibleOptions(): HTMLElement[] {
  return Array.from(document.body.querySelectorAll<HTMLElement>('.el-select-dropdown__item')).filter(
    (item) => item.style.display !== 'none',
  )
}

/** 创建一个替身；每次 `confirm` 调用都会真实挂载一次确认框。 */
export function createMessageBoxHarness(): MessageBoxHarness {
  let pending: PendingBox | null = null
  let opened = 0

  function confirm(message: unknown, title?: unknown, options?: unknown): Promise<unknown> {
    const config = (typeof title === 'object' && title !== null ? title : options) as
      | {
          confirmButtonText?: string
          cancelButtonText?: string
          message?: unknown
          beforeClose?: (
            action: 'confirm' | 'cancel' | 'close',
            state: unknown,
            done: () => void,
          ) => void
        }
      | undefined
    const boxTitle = typeof title === 'string' ? title : ''
    const rendered = config?.message ?? message
    opened += 1

    return new Promise((resolve, reject) => {
      let settled = false
      const container = document.createElement('div')
      document.body.appendChild(container)
      const app = createApp({
        name: 'MessageBoxHarnessHost',
        setup() {
          return () =>
            h('div', { class: 'el-message-box__wrapper' }, [
              h('h3', { class: 'el-message-box__title' }, boxTitle),
              h('div', { class: 'el-message-box__message' }, [
                typeof rendered === 'function'
                  ? (rendered as () => ReturnType<typeof h>)()
                  : (rendered as ReturnType<typeof h>),
              ]),
              h('div', { class: 'el-message-box__btns' }, [
                h(
                  'button',
                  {
                    class: 'el-button el-message-box__btns-cancel',
                    type: 'button',
                    onClick: () => host.cancel(),
                  },
                  config?.cancelButtonText ?? '取消',
                ),
                h(
                  'button',
                  {
                    class: 'el-button el-button--primary el-message-box__btns-confirm',
                    type: 'button',
                    onClick: () => host.accept(),
                  },
                  config?.confirmButtonText ?? '确定',
                ),
              ]),
            ])
        },
      })
      app.use(ElementPlus)
      app.mount(container)

      const settleCancel = (reason: 'cancel' | 'close') => {
        if (settled) {
          return
        }
        settled = true
        host.destroy()
        reject(reason)
      }

      const host: Host = {
        textarea: () => container.querySelector('textarea'),
        select: () => container.querySelector<HTMLElement>('.el-select__wrapper'),
        /**
         * 目标值下拉由 `el-select` teleport 到 `body`（默认行为），所以只能从 `document.body` 找，
         * 不在 `container` 里——这也是这个替身要提供"展开下拉"这件事的原因：
         * 从 `wrapper` 出发的用例看不到它，只能靠猜选项。
         */
        openSelect: async () => {
          const trigger = host.select()
          if (!trigger) {
            throw new Error('这个确认框没有目标值选择器')
          }
          trigger.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
          await nextTick()
          return visibleOptions().map((item) => item.textContent?.trim() ?? '')
        },
        choose: async (label: string) => {
          const labels = await host.openSelect()
          const target = visibleOptions().find((item) => (item.textContent?.trim() ?? '') === label)
          if (!target) {
            throw new Error(`下拉里没有「${label}」，可选的是：${labels.join('、') || '（无）'}`)
          }
          target.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
          // 选中后下拉收起、选中值回流到被测组件，两件事都在微任务里完成
          await nextTick()
        },
        text: () => container.textContent ?? '',
        /**
         * 与真实组件一致：先交给 `beforeClose`，**只有它调用 `done()` 才真正关闭**。
         * `ElMessageBox` 的 `handleAction` 就是这个顺序，所以"正文为空时不关弹窗"
         * 这条行为只有在这个替身里才测得出来。
         */
        accept: () => {
          const close = () => {
            if (settled) {
              return
            }
            settled = true
            host.destroy()
            resolve('confirm')
          }
          if (config?.beforeClose) {
            config.beforeClose('confirm', {}, close)
          } else {
            close()
          }
        },
        cancel: () => {
          if (config?.beforeClose) {
            config.beforeClose('cancel', {}, () => settleCancel('cancel'))
          } else {
            settleCancel('cancel')
          }
        },
        destroy: () => {
          if (pending?.handle === handle) {
            pending = null
          }
          app.unmount()
          container.remove()
        },
      }

      const handle: MessageBoxHandle = {
        title: boxTitle,
        textarea: () => host.textarea(),
        type: async (text: string) => {
          const textarea = host.textarea()
          if (!textarea) {
            throw new Error('这个确认框没有正文输入框')
          }
          textarea.value = text
          textarea.dispatchEvent(new Event('input', { bubbles: true }))
          // 等 Vue 把这次输入同步回被测组件的状态
          await Promise.resolve()
        },
        select: () => host.select(),
        openSelect: () => host.openSelect(),
        choose: (label: string) => host.choose(label),
        text: () => host.text(),
        accept: async () => host.accept(),
        cancel: async () => host.cancel(),
      }

      pending = { handle }
    })
  }

  return {
    confirm,
    current: () => pending?.handle ?? null,
    openCount: () => opened,
  }
}
