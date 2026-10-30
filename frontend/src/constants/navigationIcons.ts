import {
  Avatar,
  Briefcase,
  Connection,
  DataAnalysis,
  DocumentAdd,
  Files,
  Key,
  Monitor,
  Setting,
  Share,
  Tickets,
  User,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'

/**
 * 导航图标映射。
 *
 * <p>图标是**前端展示决定**，不是后端契约：后端既没有菜单接口也没有 icon 字段，
 * 导航条目仍声明在 `constants/authorization.ts`。侧栏与首页入口共用这一份映射，
 * 两处不会对同一个页面给出两个图标。</p>
 */
export const routeIcons: Record<string, Component> = {
  tickets: Tickets,
  'ticket-new': DocumentAdd,
  'ticket-queue': Monitor,
  dashboard: DataAnalysis,
  'admin-users': User,
  'admin-roles': Avatar,
  'admin-permissions': Key,
  'admin-user-roles': Share,
  'admin-role-permissions': Connection,
  'admin-categories': Files,
}

export const groupIcons: Record<'work' | 'admin', Component> = {
  work: Briefcase,
  admin: Setting,
}

export function iconForRoute(name: string, group: 'work' | 'admin'): Component {
  return routeIcons[name] ?? groupIcons[group]
}
