import { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { configureAuthSession, errorCode, http } from './http'

/** 假的传输层：不经过网络，按 URL 与请求头决定响应，便于断言"刷新只发生一次"。 */
type Handler = (config: InternalAxiosRequestConfig) => AxiosResponse | Promise<AxiosResponse>

function respond(config: InternalAxiosRequestConfig, status: number, data: unknown): AxiosResponse {
  return { data, status, statusText: String(status), headers: {}, config }
}

/** 真实适配器用 settle 把非 2xx 变成异常，这里保持一致，否则拦截器行为会失真。 */
function reject(config: InternalAxiosRequestConfig, status: number, code: string): never {
  const response = respond(config, status, { code, message: code, data: null })
  throw new AxiosError('request failed', String(status), config, null, response)
}

function useAdapter(handler: Handler): void {
  http.defaults.adapter = (config) => Promise.resolve(handler(config as InternalAxiosRequestConfig))
}

describe('http 认证拦截器', () => {
  let accessToken: string | null = null
  let sessionLost = false

  beforeEach(() => {
    accessToken = null
    sessionLost = false
    configureAuthSession({
      getAccessToken: () => accessToken,
      setAccessToken: (next) => {
        accessToken = next
      },
      onSessionLost: () => {
        sessionLost = true
      },
    })
  })

  afterEach(() => {
    delete http.defaults.adapter
  })

  it('并发 401 只触发一次刷新，并用新令牌重试原请求', async () => {
    accessToken = 'stale-token'
    let refreshCalls = 0
    const authorizationHeaders: string[] = []

    useAdapter(async (config) => {
      if (config.url === '/auth/refresh') {
        refreshCalls += 1
        await new Promise((resolve) => setTimeout(resolve, 5))
        return respond(config, 200, { code: 'OK', message: 'ok', data: { accessToken: 'fresh-token' } })
      }
      const authorization = String(config.headers.Authorization)
      authorizationHeaders.push(authorization)
      if (authorization === 'Bearer fresh-token') {
        return respond(config, 200, { code: 'OK', message: 'ok', data: { id: 1 } })
      }
      reject(config, 401, 'AUTH_SESSION_INVALID')
    })

    const responses = await Promise.all([http.get('/auth/me'), http.get('/auth/me'), http.get('/auth/me')])

    expect(refreshCalls).toBe(1)
    expect(responses.map((response) => response.status)).toEqual([200, 200, 200])
    expect(accessToken).toBe('fresh-token')
    expect(authorizationHeaders.filter((value) => value === 'Bearer stale-token')).toHaveLength(3)
    expect(authorizationHeaders.filter((value) => value === 'Bearer fresh-token')).toHaveLength(3)
  })

  it('刷新失败时通知会话失效，并把错误抛出', async () => {
    accessToken = 'stale-token'
    useAdapter((config) => reject(config, 401, 'AUTH_SESSION_INVALID'))

    await expect(http.get('/auth/me')).rejects.toThrow()
    expect(sessionLost).toBe(true)
  })

  it('重试之后仍然 401 时不再刷新，避免绕成死循环', async () => {
    accessToken = 'stale-token'
    let refreshCalls = 0

    useAdapter((config) => {
      if (config.url === '/auth/refresh') {
        refreshCalls += 1
        return respond(config, 200, { code: 'OK', message: 'ok', data: { accessToken: 'fresh-token' } })
      }
      reject(config, 401, 'AUTH_SESSION_INVALID')
    })

    await expect(http.get('/auth/me')).rejects.toThrow()
    expect(refreshCalls).toBe(1)
  })

  it('没有令牌时的 401（例如登录失败）不触发刷新，业务码原样返回', async () => {
    let refreshCalls = 0
    useAdapter((config) => {
      if (config.url === '/auth/refresh') {
        refreshCalls += 1
      }
      reject(config, 401, 'AUTH_INVALID_CREDENTIALS')
    })

    const error = await http.post('/auth/login', { username: 'nobody', password: 'wrong' }).catch((reason: unknown) => reason)

    expect(refreshCalls).toBe(0)
    expect(sessionLost).toBe(false)
    expect(errorCode(error)).toBe('AUTH_INVALID_CREDENTIALS')
  })

  it('持有令牌时把令牌注入到请求头', async () => {
    accessToken = 'memory-token'
    let authorization = ''
    useAdapter((config) => {
      authorization = String(config.headers.Authorization)
      return respond(config, 200, { code: 'OK', message: 'ok', data: null })
    })

    await http.get('/auth/me')

    expect(authorization).toBe('Bearer memory-token')
  })
})
