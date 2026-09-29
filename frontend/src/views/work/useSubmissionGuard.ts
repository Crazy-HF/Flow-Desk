import { computed, ref, shallowRef } from 'vue'
import type { ComputedRef, Ref } from 'vue'

/**
 * 一次提交的身份：幂等键 + 冻结的请求内容。
 *
 * <p>创建工单没有可校验的版本，防重完全依赖 `submissionKey`：后端按
 * `(requester_id, submission_key)` 去重，同一个键只会建出一张工单。因此"重试"必须是
 * **同一个键、同一份内容**，否则重试会变成第二次创建。</p>
 *
 * <p>这里把这条规则收在一个地方，页面不自己拼键：</p>
 * <p>1. `claim()` 第一次调用时生成键并冻结内容，之后的每次调用都返回同一份——请求期间重复点击、
 * 网络失败后重试，都只会用同一个键发出同一次提交。</p>
 * <p>2. 只有 `clear()` 会结束这次身份。调用点只有两个：**确定创建成功**之后，以及
 * **用户改了表单内容**之后。</p>
 * <p>3. 失败（包括连响应都没拿到的网络错误）**不清理**。响应不确定时正是最需要保留原请求的时候：
 * 清掉键再重试就会建出第二张工单。</p>
 * <p>4. 内容变了必须清理。继续用旧键的话，后端会按**旧内容**的键命中已建工单并返回它，
 * 用户在表单里的新输入被静默丢弃——这比多建一张工单更难发现。</p>
 */
export interface SubmissionGuard<TPayload> {
  /** 本次提交的幂等键；没有在途提交时为 `null`。 */
  key: Ref<string | null>
  /** 是否已经冻结了一次提交（界面据此说明"重试不会再建一张工单"）。 */
  isPending: ComputedRef<boolean>
  /** 取本次要发送的请求：首次调用生成键并冻结，之后原样返回。 */
  claim: (build: (submissionKey: string) => TPayload) => TPayload
  /** 结束本次提交身份。只在确定成功或表单内容已改变时调用。 */
  clear: () => void
}

/**
 * @param generateKey 幂等键生成器，测试里可以注入确定值
 */
export function useSubmissionGuard<TPayload>(
  generateKey: () => string,
): SubmissionGuard<TPayload> {
  const key = ref<string | null>(null)
  // shallowRef：冻结的是"整份请求内容"这一个值，不需要 Vue 逐层转成响应式代理
  const payload = shallowRef<TPayload | null>(null)
  const isPending = computed(() => key.value !== null)

  function claim(build: (submissionKey: string) => TPayload): TPayload {
    if (key.value === null || payload.value === null) {
      const nextKey = generateKey()
      key.value = nextKey
      payload.value = build(nextKey)
    }
    return payload.value
  }

  function clear(): void {
    key.value = null
    payload.value = null
  }

  return { key, isPending, claim, clear }
}
