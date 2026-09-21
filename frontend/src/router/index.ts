import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import LoginView from '@/views/auth/LoginView.vue'
import ForbiddenView from '@/views/error/ForbiddenView.vue'
import NotFoundView from '@/views/error/NotFoundView.vue'
import HomeView from '@/views/work/HomeView.vue'
import PlannedWorkView from '@/views/work/PlannedWorkView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    /** 匿名可访问的路由。 */
    public?: boolean
    /** 进入该路由所需的权限编码；数组表示任一命中即可。界面判断只影响展示，后端仍是最终授权边界。 */
    permission?: string | string[]
    /** 页面标题，导航与占位页使用。 */
    title?: string
    /** 占位页标注的实现任务，例如 `TASK-021-MVP`。 */
    plannedTask?: string
  }
}

/** 能查看工单的三种角色入口：提交人、队列、参与人，任一命中即可进入工单页面。 */
const TICKET_VIEW_PERMISSIONS = ['TICKET_VIEW_OWN', 'TICKET_VIEW_QUEUE', 'TICKET_VIEW_PARTICIPATED']

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true, title: '登录' } },
    { path: '/', name: 'home', component: HomeView, meta: { title: '首页' } },
    {
      path: '/tickets',
      name: 'tickets',
      component: PlannedWorkView,
      meta: { title: '工单', permission: TICKET_VIEW_PERMISSIONS, plannedTask: 'TASK-020 / TASK-023-MVP' },
    },
    {
      path: '/tickets/new',
      name: 'ticket-new',
      component: PlannedWorkView,
      meta: { title: '新建工单', permission: 'TICKET_CREATE', plannedTask: 'TASK-021-MVP / TASK-023-MVP' },
    },
    {
      path: '/tickets/:ticketNo',
      name: 'ticket-detail',
      component: PlannedWorkView,
      meta: { title: '工单详情', permission: TICKET_VIEW_PERMISSIONS, plannedTask: 'TASK-022-MVP / TASK-023-MVP' },
    },
    {
      path: '/dashboard',
      name: 'dashboard',
      component: PlannedWorkView,
      meta: { title: '数据概览', permission: 'DASHBOARD_VIEW', plannedTask: '完整版 backlog：数据概览' },
    },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: PlannedWorkView,
      meta: { title: '用户管理', permission: 'USER_MANAGE', plannedTask: '完整版 backlog：系统管理' },
    },
    {
      path: '/admin/users/:userId',
      name: 'admin-user-detail',
      component: PlannedWorkView,
      meta: { title: '用户详情', permission: 'USER_MANAGE', plannedTask: '完整版 backlog：系统管理' },
    },
    {
      path: '/admin/categories',
      name: 'admin-categories',
      component: PlannedWorkView,
      meta: { title: '分类管理', permission: 'CATEGORY_MANAGE', plannedTask: '完整版 backlog：系统管理' },
    },
    { path: '/403', name: 'forbidden', component: ForbiddenView, meta: { title: '无访问权限' } },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView, meta: { title: '页面不存在' } },
  ],
})

/**
 * 路由守卫。
 *
 * <p>受保护路由先尝试恢复一次身份（页面刷新后没有内存令牌，靠 Refresh Cookie 换新的）；
 * 未登录时回到登录页并记住原地址，登录后可以直接跳回去。缺少 `meta.permission` 声明的权限时进入 `/403`。</p>
 */
router.beforeEach(async (to) => {
  if (to.meta.public === true) {
    return true
  }

  const auth = useAuthStore()
  if (!auth.isAuthenticated && !(await auth.restoreOnce())) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  const required = to.meta.permission
  if (required !== undefined) {
    const codes = Array.isArray(required) ? required : [required]
    if (!auth.hasAnyPermission(codes)) {
      return { name: 'forbidden' }
    }
  }

  return true
})

export default router
