import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { fetchCurrentUser, login, logout } from '@/api/auth'
import type { AuthUser } from '@/api/auth'
import { refreshAccessToken } from '@/api/http'

/**
 * 身份状态。
 *
 * <p>Access Token 只保存在内存里：不写 localStorage、不写 sessionStorage、不写 Cookie。
 * Refresh Token 由后端写入 HttpOnly Cookie，JavaScript 读不到，只能通过 `/auth/refresh` 使用。</p>
 */
export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const user = ref<AuthUser | null>(null)
  const restoreAttempted = ref(false)

  const isAuthenticated = computed(() => user.value !== null)
  const permissions = computed(() => user.value?.permissions ?? [])
  const roles = computed(() => user.value?.roles ?? [])

  /** 页面与菜单的展示判断；后端仍是最终授权边界。 */
  function hasPermission(code: string): boolean {
    return permissions.value.includes(code)
  }

  function setAccessToken(token: string): void {
    accessToken.value = token
  }

  function clearSession(): void {
    accessToken.value = null
    user.value = null
  }

  async function signIn(username: string, password: string): Promise<void> {
    const payload = await login(username, password)
    accessToken.value = payload.accessToken
    user.value = payload.user
    restoreAttempted.value = true
  }

  /** 刷新页面后恢复身份：先用 Refresh Cookie 换新的 Access Token，再读当前身份。 */
  async function restoreSession(): Promise<boolean> {
    if (accessToken.value && user.value) {
      return true
    }
    try {
      // 显式写回自己的状态，不依赖网络层的副作用；两者最终写入的是同一个令牌
      accessToken.value = await refreshAccessToken()
      user.value = await fetchCurrentUser()
      return true
    } catch {
      clearSession()
      return false
    }
  }

  /** 应用启动只尝试恢复一次：未登录用户连续跳转页面时不该反复打后端。 */
  async function restoreOnce(): Promise<boolean> {
    if (restoreAttempted.value) {
      return isAuthenticated.value
    }
    restoreAttempted.value = true
    return restoreSession()
  }

  async function signOut(): Promise<void> {
    try {
      await logout()
    } finally {
      // 无论后端是否成功，本地身份都必须清空：界面状态不能停在"看起来还登录着"
      clearSession()
    }
  }

  return {
    accessToken,
    user,
    isAuthenticated,
    permissions,
    roles,
    hasPermission,
    setAccessToken,
    clearSession,
    signIn,
    restoreSession,
    restoreOnce,
    signOut,
  }
})
