import axios from 'axios'
import type { AxiosError, InternalAxiosRequestConfig } from 'axios'

/** 后端统一响应信封（`R<T>`）：`code` 是稳定业务码，`message` 只用于展示。 */
export interface ApiEnvelope<T> {
  code: string
  message: string
  data: T
}

/**
 * 身份读写出口：由 store 在应用启动时注入。
 *
 * <p>网络层不直接依赖 store，既避免循环引用，也让"单次刷新协调"可以脱离界面测试。</p>
 */
export interface AuthSessionAccessor {
  getAccessToken: () => string | null
  setAccessToken: (accessToken: string) => void
  /** 刷新失败或会话被撤销：调用方应清空内存身份并回到登录页。 */
  onSessionLost: () => void
}

let accessor: AuthSessionAccessor | null = null

export function configureAuthSession(next: AuthSessionAccessor): void {
  accessor = next
}

export const http = axios.create({
  baseURL: '/fd/v1',
  timeout: 10_000,
  // Refresh Cookie 与前端同源，显式声明以免将来跨域部署时被默认值坑到
  withCredentials: true,
})

/**
 * 公共路径实例：附件上传/下载/预览使用 `attachments/xx`，**不带 `/fd/v1` 前缀**。
 *
 * <p>复用同一套拦截器（注入令牌、401 后单次刷新并重放），只是 baseURL 为空；
 * 超时放宽到 60s，因为上传大文件与后端代理输出文件流都比普通接口慢。</p>
 */
export const publicHttp = axios.create({
  baseURL: '',
  timeout: 60_000,
  withCredentials: true,
})

http.interceptors.request.use((config) => {
  const accessToken = accessor?.getAccessToken()
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

publicHttp.interceptors.request.use((config) => {
  const accessToken = accessor?.getAccessToken()
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

/** 认证入口自身不参与"过期后重试"，否则会绕成环。 */
const AUTH_ENTRY_PATHS = ['/auth/login', '/auth/refresh', '/auth/logout']

const RETRIED_FLAG = 'flowdeskRetried'

type RetriableConfig = InternalAxiosRequestConfig & { [RETRIED_FLAG]?: boolean }

let refreshing: Promise<string> | null = null

/**
 * 刷新访问令牌。
 *
 * <p>并发调用共享同一个 Promise：多个请求同时发现令牌过期时，只会向后端发出一次
 * `/auth/refresh`，其余请求复用同一次结果。</p>
 */
export function refreshAccessToken(): Promise<string> {
  refreshing ??= http
    .post<ApiEnvelope<{ accessToken: string }>>('/auth/refresh')
    .then((response) => {
      const accessToken = response.data.data.accessToken
      accessor?.setAccessToken(accessToken)
      return accessToken
    })
    .finally(() => {
      refreshing = null
    })

  return refreshing
}

http.interceptors.response.use(undefined, async (error: AxiosError) => {
  const config = error.config as RetriableConfig | undefined
  if (!config || error.response?.status !== 401 || config[RETRIED_FLAG]) {
    throw error
  }

  // 从未持有令牌（例如登录失败）或本身就是认证入口：不刷新，把 401 原样交给调用方
  const hadToken = accessor?.getAccessToken() != null
  const isAuthEntry = AUTH_ENTRY_PATHS.some((path) => config.url?.startsWith(path))
  if (!hadToken || isAuthEntry) {
    throw error
  }

  config[RETRIED_FLAG] = true
  try {
    const accessToken = await refreshAccessToken()
    config.headers.Authorization = `Bearer ${accessToken}`
    return await http.request(config)
  } catch (refreshError) {
    accessor?.onSessionLost()
    throw refreshError
  }
})

/** 公共路径实例的同一套 401 重试：登录令牌过期时的行为必须与 `/fd/v1` 一致。 */
publicHttp.interceptors.response.use(undefined, async (error: AxiosError) => {
  const config = error.config as RetriableConfig | undefined
  if (!config || error.response?.status !== 401 || config[RETRIED_FLAG]) {
    throw error
  }
  const hadToken = accessor?.getAccessToken() != null
  if (!hadToken) {
    throw error
  }

  config[RETRIED_FLAG] = true
  try {
    const accessToken = await refreshAccessToken()
    config.headers.Authorization = `Bearer ${accessToken}`
    return await publicHttp.request(config)
  } catch (refreshError) {
    accessor?.onSessionLost()
    throw refreshError
  }
})

/** 取后端错误信封里的稳定业务码；不是后端响应时返回空串。 */
export function errorCode(error: unknown): string {
  const envelope = (error as AxiosError<ApiEnvelope<unknown>> | undefined)?.response?.data
  return envelope?.code ?? ''
}
