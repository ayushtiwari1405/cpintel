import { API_BASE_URL, apiClient } from './client'
import { useAuthStore } from '@/store/authStore'

/**
 * Client for the local cookie helper (scripts/grab-cf-cookie.py --serve).
 *
 * Codeforces publishes no login API, and a browser cannot get at the session itself: the
 * JSESSIONID cookie is HttpOnly so JavaScript cannot read it, and CORS forbids posting to
 * codeforces.com with credentials. A small process on the user's own machine is the only way
 * to bridge that without asking for a password.
 *
 * The helper never hands the cookie back to this page — it posts the session to the CPIntel
 * backend itself, and answers here with nothing more than the handle it connected.
 */
const HELPER_BASE = 'http://127.0.0.1:7717'

export const HELPER_COMMAND = './scripts/grab-cf-cookie.py --serve'

export interface HelperHealth {
  ok: boolean
  version: string
  profiles: string[]
}

export interface HelperConnectResult {
  connected: boolean
  handle: string
  browser?: string
  profile?: string
  cookies?: string[]
}

/** Where the backend's development server listens, when nothing else says otherwise. */
const DEV_BACKEND_ORIGIN = 'http://localhost:8080'

/**
 * Absolute backend URL — the helper runs outside the browser, so a relative path is no use.
 *
 * <p>This used to return an unversioned `http://localhost:8080/api`, and every connection
 * attempt failed with "No such endpoint": the helper posts to `<base>/practice/cf-session`,
 * which resolved to `/api/practice/cf-session` while the endpoint is `/api/v1/practice/...`.
 * The comment claimed it matched the vite dev proxy, and it did — but the proxy prefix is
 * deliberately unversioned so it keeps working across API versions, which makes it the wrong
 * thing to build a request path from. The version now comes from {@link API_BASE_URL}, the
 * same constant every other call uses, so the two cannot drift apart again.
 */
export function apiBase(): string {
  const configured = import.meta.env.VITE_API_URL
  if (configured && /^https?:\/\//i.test(configured)) return configured
  return DEV_BACKEND_ORIGIN + API_BASE_URL
}

async function parse(res: Response) {
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(body.error || `Helper returned ${res.status}`)
  return body
}

export const cfHelper = {
  /** Short timeout: this is a liveness check, not a request worth waiting on. */
  health: async (): Promise<HelperHealth> => {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 1500)
    try {
      return await parse(await fetch(`${HELPER_BASE}/health`, { signal: ctrl.signal }))
    } finally {
      clearTimeout(timer)
    }
  },

  connect: async (handle: string): Promise<HelperConnectResult> => {
    if (!useAuthStore.getState().accessToken) {
      throw new Error('You are signed out of CPIntel — sign in and try again.')
    }

    // The access token lives 15 minutes, and the one sitting in the store may well be older
    // than that. apiClient's interceptor is what renews it, so make one ordinary call through
    // it first: if the token has expired that request refreshes it, and the store then holds a
    // live one. Handing the helper a stale token is otherwise indistinguishable, at the helper,
    // from being signed out — it just gets "Authentication required" from the backend.
    await apiClient.get('/practice/cf-session')

    const token = useAuthStore.getState().accessToken
    if (!token) throw new Error('Your CPIntel session expired — sign in and try again.')

    return parse(await fetch(`${HELPER_BASE}/connect`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // Sent as a fallback only. The helper prefers the User-Agent of the browser it actually
      // read the cookies from, which may not be this one; Cloudflare ties cf_clearance to that
      // browser, so its string is the one that works.
      body: JSON.stringify({
        handle, token, apiBase: apiBase(), userAgent: navigator.userAgent,
      }),
    }))
  },
}
