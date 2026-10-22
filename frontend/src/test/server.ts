import { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { apiClient } from '@/api/client'

/** What a scripted route answers: a status (200 by default) and the `data` of the envelope. */
export interface Reply {
  status?: number
  data?: unknown
  /** The error body for a failure, e.g. { code: 'TOKEN_EXPIRED' }. */
  body?: unknown
  /** Answer only when this resolves, to hold a request open. */
  wait?: Promise<unknown>
}

export interface Seen {
  method: string
  url: string
  token: string | null
  body: unknown
}

type Handler = (req: Seen) => Reply | undefined

/**
 * Stands in for the backend behind the real `apiClient`, so the app's own interceptors (token,
 * session restore, renewal, retries) run as they do in a browser. Routes are "METHOD /path",
 * matched on the path without its query string; anything unscripted is a 404 and recorded.
 */
export function fakeServer(routes: Record<string, Handler | Reply>) {
  const seen: Seen[] = []
  const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
    const url = (config.url ?? '').split('?')[0]
    const method = (config.method ?? 'get').toUpperCase()
    const auth = String(config.headers?.Authorization ?? '')
    const req: Seen = {
      method, url,
      token: auth.startsWith('Bearer ') ? auth.slice(7) : null,
      body: typeof config.data === 'string' && config.data ? JSON.parse(config.data) : config.data,
    }
    seen.push(req)
    const route = routes[`${method} ${url}`]
    const reply = (typeof route === 'function' ? route(req) : route) ?? { status: 404, body: { message: `no route ${method} ${url}` } }
    if (reply.wait) await reply.wait
    const status = reply.status ?? 200
    const response = {
      status, statusText: String(status), headers: {}, config, request: {},
      data: status < 400 ? { success: true, data: reply.data ?? null, timestamp: '2026-10-22T00:00:00Z' } : (reply.body ?? {}),
    }
    if (status >= 400) {
      throw new AxiosError(`Request failed with status code ${status}`, 'ERR_BAD_REQUEST', config, {}, response)
    }
    return response
  }
  apiClient.defaults.adapter = adapter
  return { seen, calls: (route: string) => seen.filter(s => `${s.method} ${s.url}` === route) }
}

/** A promise and the function that settles it, to hold a reply until the test says so. */
export function gate() {
  let open!: () => void
  const wait = new Promise<void>(resolve => { open = resolve })
  return { wait, open }
}
