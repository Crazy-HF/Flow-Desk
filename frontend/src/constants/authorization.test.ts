import { describe, expect, it } from 'vitest'

import {
  isProtectedPermission,
  isProtectedRole,
  labelForPermission,
  labelForRole,
  navigationEntries,
  permissionLabels,
} from './authorization'

describe('授权常量', () => {
  it('保护判定按后端一致的 code 常量，不靠"内置"标记列', () => {
    expect(isProtectedRole('SYSTEM_ADMIN')).toBe(true)
    expect(isProtectedRole('IT_SUPPORT')).toBe(false)
    expect(isProtectedPermission('RBAC_MANAGE')).toBe(true)
    expect(isProtectedPermission('TICKET_CREATE')).toBe(false)
  })

  it('展示映射覆盖全部内置编码，未知编码回落到原编码', () => {
    expect(labelForRole('EMPLOYEE')).toBe('普通员工')
    expect(labelForRole('CUSTOM_ROLE')).toBe('CUSTOM_ROLE')
    expect(labelForPermission('RBAC_MANAGE')).toBe('角色与权限管理')
    expect(permissionLabels.RBAC_MANAGE).toBe('角色与权限管理')
  })

  it('四组 RBAC 维护页入口按 RBAC_MANAGE 声明，用户管理按 USER_MANAGE 声明', () => {
    const rbacEntries = navigationEntries.filter(
      (entry) => entry.group === 'admin' && entry.permission === 'RBAC_MANAGE',
    )

    expect(rbacEntries.map((entry) => entry.name)).toEqual([
      'admin-roles',
      'admin-permissions',
      'admin-user-roles',
      'admin-role-permissions',
    ])

    const users = navigationEntries.find((entry) => entry.name === 'admin-users')
    expect(users?.permission).toBe('USER_MANAGE')
  })
})
