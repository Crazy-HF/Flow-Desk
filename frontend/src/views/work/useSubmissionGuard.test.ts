import { describe, expect, it } from 'vitest'

import { useSubmissionGuard } from './useSubmissionGuard'

interface Payload {
  submissionKey: string
  title: string
}

describe('useSubmissionGuard', () => {
  it('首次 claim 生成键并冻结内容，重复 claim 直接返回同一份请求', () => {
    const guard = useSubmissionGuard<Payload>(() => 'key-1')

    const first = guard.claim((key) => ({ submissionKey: key, title: '打印机无法连接' }))
    // 页面在每次点击时都会重新读一遍表单，所以这里故意传一个会给出不同内容的构造函数：
    // 冻结生效的话，第二次拿到的仍然是第一次那份内容
    const second = guard.claim((key) => ({ submissionKey: key, title: '改过的标题' }))

    expect(first).toEqual({ submissionKey: 'key-1', title: '打印机无法连接' })
    expect(second).toBe(first)
    expect(guard.key.value).toBe('key-1')
    expect(guard.isPending.value).toBe(true)
  })

  it('请求失败后不清理：重试仍是同一个键，因此不会建出第二张工单', () => {
    let sequence = 0
    const guard = useSubmissionGuard<Payload>(() => `key-${++sequence}`)

    const first = guard.claim((key) => ({ submissionKey: key, title: '打印机无法连接' }))
    // 模拟提交失败：页面刻意不动 guard，因为"响应不确定"正是最需要保留原请求的时候
    const retry = guard.claim((key) => ({ submissionKey: key, title: '打印机无法连接' }))

    expect(retry).toBe(first)
    expect(retry.submissionKey).toBe('key-1')
    expect(sequence).toBe(1)
  })

  it('clear 之后 claim 开始一次全新的提交，键也换了', () => {
    let sequence = 0
    const guard = useSubmissionGuard<Payload>(() => `key-${++sequence}`)

    guard.claim((key) => ({ submissionKey: key, title: '第一次' }))
    guard.clear()

    expect(guard.key.value).toBeNull()
    expect(guard.isPending.value).toBe(false)

    const next = guard.claim((key) => ({ submissionKey: key, title: '第二次' }))
    expect(next).toEqual({ submissionKey: 'key-2', title: '第二次' })
  })

  it('键由生成器给出，不在 guard 内部另造一份', () => {
    const guard = useSubmissionGuard<Payload>(() => 'FD-FIXED-KEY')

    expect(guard.claim((key) => ({ submissionKey: key, title: 't' })).submissionKey).toBe(
      'FD-FIXED-KEY',
    )
  })
})
