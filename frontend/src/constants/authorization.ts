/**
 * 角色与权限的展示映射，以及侧栏导航条目。
 *
 * <p>编码必须与后端迁移 V2 预置的 `iam_role.code`、`iam_permission.code` 一致；
 * 界面只用它做展示与入口显隐，后端始终是最终授权边界。</p>
 */
export const roleLabels: Record<string, string> = {
  EMPLOYEE: '普通员工',
  IT_SUPPORT: 'IT 支持人员',
  SYSTEM_ADMIN: '系统管理员',
}

export const permissionLabels: Record<string, string> = {
  TICKET_CREATE: '创建工单',
  TICKET_VIEW_OWN: '查看自己的工单',
  TICKET_REQUESTER_ACTION: '提交人操作',
  TICKET_VIEW_QUEUE: '查看待受理队列',
  TICKET_CLAIM: '领取工单',
  TICKET_VIEW_PARTICIPATED: '查看参与工单',
  TICKET_PROCESS: '处理工单',
  TICKET_TRANSFER: '转交工单',
  TICKET_CLOSE: '关闭工单',
  TICKET_ADMIN_HANDOFF: '管理性交接',
  USER_MANAGE: '用户管理',
  CATEGORY_MANAGE: '分类管理',
  DASHBOARD_VIEW: '数据概览',
}

/** 侧栏一级栏目。面包屑与侧栏共用这一份声明，两处不会各写一遍栏目名。 */
export type NavigationGroup = 'work' | 'admin'

export const navigationGroupLabels: Record<NavigationGroup, string> = {
  work: '业务工作',
  admin: '系统管理',
}

export interface NavigationEntry {
  /** 目标路由名。 */
  name: string
  label: string
  /**
   * 图标编码：**预留字段，当前没有任何来源赋值**。
   *
   * <p>后端既没有菜单接口、也没有 `icon` 字段，所以条目仍然写在本文件里。
   * 先保留这个字段位：为空时侧栏不渲染图标位，等后端给出编码约定后按同一份声明接图标，
   * 不需要再改导航条目的结构。</p>
   */
  icon?: string
  group: NavigationGroup
  /** 这些子路由也沿用当前入口的选中态。 */
  activeRouteNames?: string[]
  /** 具备其中任一权限即可看到该入口。 */
  permission: string | string[]
}

/** 侧栏导航条目；尚未实现的页面指向占位页，但仍然按权限展示。 */
export const navigationEntries: NavigationEntry[] = [
  {
    name: 'tickets',
    label: '工单',
    group: 'work',
    activeRouteNames: ['ticket-detail'],
    permission: ['TICKET_VIEW_OWN', 'TICKET_VIEW_QUEUE', 'TICKET_VIEW_PARTICIPATED'],
  },
  { name: 'ticket-new', label: '新建工单', group: 'work', permission: 'TICKET_CREATE' },
  { name: 'dashboard', label: '数据概览', group: 'work', permission: 'DASHBOARD_VIEW' },
  {
    name: 'admin-users',
    label: '用户管理',
    group: 'admin',
    activeRouteNames: ['admin-user-detail'],
    permission: 'USER_MANAGE',
  },
  { name: 'admin-categories', label: '分类管理', group: 'admin', permission: 'CATEGORY_MANAGE' },
]

export function labelForRole(code: string): string {
  return roleLabels[code] ?? code
}

export function labelForPermission(code: string): string {
  return permissionLabels[code] ?? code
}
