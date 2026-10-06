import ElementPlus from 'element-plus'
import { createApp, h } from 'vue'

/**
 * 测试用的 `ElMessageBox` 替身。
 *
 * <p>`ElMessageBox.confirm` 是**命令式**组件：它自带 `document.body` 容器并渲染到 Vue Test Utils
 * 的 `wrapper` 之外，所以用例既不能在 wrapper 里找到确认框里的输入框，也没法"点确认"。
 * 直接把它替成 `vi.fn()` 更糟——那样输入框根本不会出现，只能靠猜断言。
 * 这里换一种做法：替身真的把宿主组件挂起来，并把"打开 → 填内容 → 点确认"这段顺序
 * 按真人操作的次序暴露给用例。</p>
 *
 * <p>关键在**顺序**：真人先看到弹窗、写好正文，最后才点确认。用例必须能有同一段顺序，
 * 否则测出来的是"确认按钮先被按下"这个现实中不存在的时序。</p>
 */
export interface MessageBoxHandle {
  title: string
  /** 确认框里的正文输入框；不需要正文的动作没有这个元素。 */
  textarea: () => HTMLTextAreaElement | null
  /** 输入正文（走 `input` 事件，等同用户键入）。 */
  type: (text: string) => Promise<void>
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
  accept: () => void
  cancel: () => void
  destroy: () => void
}

interface PendingBox {
  handle: MessageBoxHandle
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
