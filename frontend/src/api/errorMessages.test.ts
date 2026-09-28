import { describe, expect, it } from 'vitest'

import { describeError } from './errorMessages'

describe('describeError', () => {
  it('把已知编码翻译成界面文案', () => {
    expect(describeError('AUTH_SESSION_INVALID')).toBe('登录会话已失效，请重新登录')
    expect(describeError('TICKET_NOT_FOUND')).toBe('工单不存在或你没有查看权限')
  })

  it('覆盖 RBAC 管理页会撞上的全部错误码', () => {
    expect(describeError('ROLE_NOT_FOUND')).toBe('角色不存在或已被删除')
    expect(describeError('PERMISSION_NOT_FOUND')).toBe('权限不存在或已被删除')
    expect(describeError('GRANT_NOT_FOUND')).toBe('授权关系不存在，请刷新后重试')
    expect(describeError('ROLE_CODE_CONFLICT')).toBe('角色编码已存在')
    expect(describeError('PERMISSION_CODE_CONFLICT')).toBe('权限编码已存在')
    expect(describeError('RBAC_CONFLICT')).toContain('受保护')
    expect(describeError('LAST_ADMIN_PROTECTED')).toBe('不能移除最后一个启用的管理员')
  })

  it('已废弃的 USER_ROLE_REQUIRED 不再有文案，零角色不需要提示', () => {
    expect(describeError('USER_ROLE_REQUIRED', '兜底')).toBe('兜底')
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
