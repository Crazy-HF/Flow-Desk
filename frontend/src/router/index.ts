import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import ForbiddenView from '@/views/ForbiddenView.vue'
import HomeView from '@/views/HomeView.vue'
import LoginView from '@/views/LoginView.vue'
import NotFoundView from '@/views/NotFoundView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    /** 匿名可访问的路由。 */
    public?: boolean
    /** 进入该路由所需的权限编码；界面判断只影响展示，后端仍是最终授权边界。 */
    permission?: string
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/', name: 'home', component: HomeView },
    { path: '/403', name: 'forbidden', component: ForbiddenView },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView },
  ],
})

/**
 * 路由守卫。
 *
 * <p>受保护路由先尝试恢复一次身份（页面刷新后没有内存令牌，靠 Refresh Cookie 换新的）；
 * 未登录时回到登录页并记住原地址，登录后可以直接跳回去。</p>
 */
router.beforeEach(async (to) => {
  if (to.meta.public === true) {
    return true
  }

  const auth = useAuthStore()
  if (!auth.isAuthenticated && !(await auth.restoreOnce())) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta.permission !== undefined && !auth.hasPermission(to.meta.permission)) {
    return { name: 'forbidden' }
  }

  return true
})

export default router
