import { describe, expect, it } from 'vitest'

import { describeError } from './errorMessages'

describe('describeError', () => {
  it('把已知编码翻译成界面文案', () => {
    expect(describeError('AUTH_SESSION_INVALID')).toBe('登录会话已失效，请重新登录')
    expect(describeError('TICKET_NOT_FOUND')).toBe('工单不存在或你没有查看权限')
  })

  it('未知编码与非后端错误回落到兜底文案', () => {
    expect(describeError('SOMETHING_NEW', '自定义兜底')).toBe('自定义兜底')
    expect(describeError(new Error('network down'))).toBe('操作失败，请稍后重试')
  })

  it('从后端错误信封里取业务码', () => {
    const axiosLikeError = {
      isAxiosError: true,
      response: { data: { code: 'ACCESS_DENIED', message: 'x', data: null } },
    }

    expect(describeError(axiosLikeError)).toBe('当前账号没有执行该操作的权限')
  })
})
