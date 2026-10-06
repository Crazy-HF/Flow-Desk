import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import CategoryListView from '@/views/admin/CategoryListView.vue'
import PermissionListView from '@/views/admin/PermissionListView.vue'
import RoleListView from '@/views/admin/RoleListView.vue'
import RolePermissionGrantView from '@/views/admin/RolePermissionGrantView.vue'
import UserListView from '@/views/admin/UserListView.vue'
import UserRoleGrantView from '@/views/admin/UserRoleGrantView.vue'
import LoginView from '@/views/auth/LoginView.vue'
import ForbiddenView from '@/views/error/ForbiddenView.vue'
import NotFoundView from '@/views/error/NotFoundView.vue'
import HomeView from '@/views/work/HomeView.vue'
import PlannedWorkView from '@/views/work/PlannedWorkView.vue'
import TicketCreateView from '@/views/work/TicketCreateView.vue'
import TicketDetailView from '@/views/work/TicketDetailView.vue'
import TicketListView from '@/views/work/TicketListView.vue'
import TicketQueueView from '@/views/work/TicketQueueView.vue'

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

/**
 * 能查看工单的三种角色入口：提交人、队列、参与人，任一命中即可进入工单页面。
 *
 * <p>列表页内部再按这四个范围分别显隐（见 `TICKET_SCOPES`）：`TICKET_VIEW_PARTICIPATED`
 * 同时覆盖"我负责的"与"我参与的"两个范围。这里只决定入口是否展示，后端仍是最终授权边界。</p>
 */
const TICKET_VIEW_PERMISSIONS = ['TICKET_VIEW_OWN', 'TICKET_VIEW_QUEUE', 'TICKET_VIEW_PARTICIPATED']

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true, title: '登录' } },
    { path: '/', name: 'home', component: HomeView, meta: { title: '首页' } },
    {
      path: '/tickets',
      name: 'tickets',
      component: TicketListView,
      meta: { title: '工单', permission: TICKET_VIEW_PERMISSIONS },
    },
    {
      /**
       * 刻意不写成 `/tickets/queue`：那条路径同时匹配 `/tickets/:ticketNo`，
       * 谁生效取决于路由数组顺序——顺序一被上面插进一条新路由就悄悄改变行为。
       * IT 工作台不是某张工单的详情，给它自己的前缀更诚实。
       */
      path: '/it/queue',
      name: 'ticket-queue',
      component: TicketQueueView,
      // 默认停在「待受理」，但这一页也承载「我负责的」，所以入口权限就是队列权限本身
      meta: { title: 'IT 工作台', permission: 'TICKET_VIEW_QUEUE' },
    },
    {
      path: '/tickets/new',
      name: 'ticket-new',
      component: TicketCreateView,
      meta: { title: '新建工单', permission: 'TICKET_CREATE' },
    },
    {
      path: '/tickets/:ticketNo',
      name: 'ticket-detail',
      component: TicketDetailView,
      meta: { title: '工单详情', permission: TICKET_VIEW_PERMISSIONS },
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
      component: UserListView,
      meta: { title: '用户管理', permission: 'USER_MANAGE' },
    },
    {
      path: '/admin/users/:userId',
      name: 'admin-user-detail',
      component: PlannedWorkView,
      meta: { title: '用户详情', permission: 'USER_MANAGE', plannedTask: '完整版 backlog：用户详情' },
    },
    {
      path: '/admin/roles',
      name: 'admin-roles',
      component: RoleListView,
      meta: { title: '角色管理', permission: 'RBAC_MANAGE' },
    },
    {
      path: '/admin/permissions',
      name: 'admin-permissions',
      component: PermissionListView,
      meta: { title: '权限管理', permission: 'RBAC_MANAGE' },
    },
    {
      path: '/admin/user-roles',
      name: 'admin-user-roles',
      component: UserRoleGrantView,
      meta: { title: '用户角色授权', permission: 'RBAC_MANAGE' },
    },
    {
      path: '/admin/role-permissions',
      name: 'admin-role-permissions',
      component: RolePermissionGrantView,
      meta: { title: '角色权限授权', permission: 'RBAC_MANAGE' },
    },
    {
      path: '/admin/categories',
      name: 'admin-categories',
      component: CategoryListView,
      meta: { title: '分类管理', permission: 'CATEGORY_MANAGE' },
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
