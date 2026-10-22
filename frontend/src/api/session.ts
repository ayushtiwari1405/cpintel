import { AxiosError, CanceledError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'

/**
 * How requests carry the session, and how it is renewed.
 *
 * <p>The access token lives in memory only; the refresh token is an HttpOnly cookie. After a
 * reload the store still says somebody is signed in, but there is no token, so the session has
 * to be restored from the cookie before anything can be asked of the server.
 *
 * <h2>Why requests now wait for it</h2>
 *
 * <p>Restoring used to be lazy: every request went out with no token, each got a 401, and the
 * first 401 renewed the session. So every reload produced a burst of 401s, and a request whose
 * 401 arrived after that renewal had finished started another one, rotating the refresh cookie
 * twice for one reload. Now a request that needs the session waits for a single renewal that
 * every waiting request shares, and a 401 for a request sent before a renewal finished is
 * retried with the new token rather than renewing again.
 *
 * <p>Kept free of the store and the router (they are passed in) so the behaviour can be run
 * against real axios in a test.
 */
export interface SessionHooks {
  /** The access token in memory, if any. */
  token(): string | null
  /** Whether the stored state says somebody is signed in, so a session is worth restoring. */
  signedIn(): boolean
  /** Asks the server for a new access token using the refresh cookie. */
  renew(): Promise<string>
  /** Keeps a token the server issued. */
  keep(token: string): void
  /** The session has ended for good: sign out and say why. Called once per failed renewal. */
  ended(error: unknown): void
  /** Waits before trying a read again while the server is unreachable (for tests). */
  sleep?(ms: number): Promise<void>
}

type Retryable = InternalAxiosRequestConfig & { _retry?: boolean; _unreachableTries?: number }

/** Requests that are themselves about signing in, and so never wait for or renew a session. */
const SIGNING_IN = /\/auth\/(login|register|refresh|forgot-password|reset-password)/

/**
 * Whether a failure says the server could not be reached, rather than that it refused.
 *
 * A deploy restarts the backend for about half a minute, and nginx answers 503
 * SERVER_RESTARTING meanwhile (502 or 504 without it, or no response at all). None of that is
 * the session's fault, so none of it may sign anybody out — and a read can simply be tried again.
 */
export function unreachable(error: unknown): boolean {
  const e = error as AxiosError
  if (e instanceof CanceledError) return false
  const status = e?.response?.status
  // No answer at all: the network or a server that is down. A request the page itself
  // cancelled (navigating away) is not that, and must not be retried.
  if (status === undefined) return e?.code !== 'ERR_CANCELED'
  if (status === 502 || status === 504) return true
  return status === 503
    && (e.response?.data as { code?: string } | undefined)?.code === 'SERVER_RESTARTING'
}

/** Waits between tries of a read while the server is unreachable: about a minute in all. */
export const READ_RETRY_DELAYS_MS = [1_000, 2_000, 4_000, 8_000, 15_000, 30_000]

export function installSession(client: AxiosInstance, hooks: SessionHooks) {
  const sleep = hooks.sleep ?? (ms => new Promise<void>(resolve => setTimeout(resolve, ms)))
  let pending: Promise<string> | null = null

  /** One renewal at a time; everybody who needs it while it runs shares its answer. */
  function renew(): Promise<string> {
    if (!pending) {
      pending = hooks.renew()
        .then(token => {
          hooks.keep(token)
          return token
        }, error => {
          // An unreachable server is not an ended session: keep it, and the next request after
          // the server is back renews it.
          if (!unreachable(error)) hooks.ended(error)
          throw error
        })
        .finally(() => { pending = null })
    }
    return pending
  }

  client.interceptors.request.use(async (config: InternalAxiosRequestConfig) => {
    let token = hooks.token()
    if (!token && hooks.signedIn() && !SIGNING_IN.test(config.url ?? '')) {
      try {
        token = await renew()
      } catch (error) {
        // Unreachable: send it anyway; it will fail the same way and be retried below, which
        // comes back through here and tries the renewal again.
        if (!unreachable(error)) {
          // Signing out is under way; this request has nothing to be sent with.
          throw new CanceledError('The session has ended', config)
        }
      }
    }
    if (token) config.headers.Authorization = `Bearer ${token}`
    return config
  })

  client.interceptors.response.use(
    response => response,
    async (error: AxiosError) => {
      const request = error.config as Retryable | undefined
      if (!request) return Promise.reject(error)

      // A read while the server restarts is tried again after a pause, so a page opened during a
      // deploy loads when the server is back instead of showing an error. Writes are not
      // repeated: a submission that may have arrived must not be sent twice behind the person's
      // back. A read that timed out has already waited its 30 seconds, and is not tried again.
      if (unreachable(error) && error.code !== 'ECONNABORTED'
          && (request.method ?? 'get').toLowerCase() === 'get') {
        const tries = request._unreachableTries ?? 0
        if (tries < READ_RETRY_DELAYS_MS.length) {
          request._unreachableTries = tries + 1
          await sleep(READ_RETRY_DELAYS_MS[tries])
          return client(request)
        }
      }

      // A 401 from signing in is a wrong password, not an expired session; renewing would turn
      // the message into a bounce back to the login page.
      if (error.response?.status !== 401 || request._retry || SIGNING_IN.test(request.url ?? '')) {
        return Promise.reject(error)
      }
      request._retry = true

      // Sent with a token that has since been replaced: the newer one answers it, and renewing
      // again would only rotate the cookie a second time.
      const sentWith = String(request.headers?.Authorization ?? '').replace(/^Bearer /, '') || null
      const current = hooks.token()
      if (current && current !== sentWith) return client(request)

      // The request interceptor puts the renewed token on the retry.
      await renew()
      return client(request)
    },
  )

  return { renew }
}
