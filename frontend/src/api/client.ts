import axios, { type AxiosError } from 'axios'
import { useAuthStore } from '@/store/authStore'
import { installSession } from './session'

/**
 * Where the API lives, versioned.
 *
 * Exported because the Codeforces cookie helper needs the same value: it runs as a separate
 * process and posts the session to the backend itself, so it has to be handed an absolute URL
 * built from this. It previously carried its own copy that had drifted to the unversioned
 * '/api', which 404s.
 */
export const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1'

const BASE_URL = API_BASE_URL

export const apiClient = axios.create({
  baseURL: BASE_URL,
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,
})

/** Set once signing out has begun, so a burst of failing requests leaves the page only once. */
let leaving = false

// The token on each request, the session restored once after a reload, renewal on a 401, and
// reads retried while the server restarts: see ./session.
installSession(apiClient, {
  token: () => useAuthStore.getState().accessToken,
  signedIn: () => useAuthStore.getState().isAuthenticated,
  // The refresh token is an HttpOnly cookie the browser sends by itself; this page never sees
  // it. The answer carries a new access token and rotates the cookie. An empty JSON object
  // rather than no body: with none, axios labels the request form-encoded, which the endpoint
  // does not read. Plain axios, so the request does not pass back through the interceptors.
  renew: () => axios.post(`${BASE_URL}/auth/refresh`, {}, { withCredentials: true })
    .then(response => response.data.data.accessToken as string),
  keep: token => useAuthStore.getState().setAccessToken(token),
  ended: error => {
    if (leaving) return
    leaving = true
    // An examination session stops renewing when its paper ends, and an ordinary one stops
    // while its holder's examination is running; say which on the way out.
    const examOver = useAuthStore.getState().mode === 'EXAM'
    const code = (error as AxiosError<{ code?: string }>)?.response?.data?.code
    const examRunning = code === 'EXAM_IN_PROGRESS'
    // The paper was switched to desktop-only, or this session was carried out of the app.
    const desktopOnly = code === 'DESKTOP_APP_REQUIRED'
    useAuthStore.getState().logout()
    window.location.href = examRunning ? '/login?exam-in-progress=1'
      : desktopOnly ? '/login?desktop-app-required=1'
      : examOver ? '/login?exam-ended=1' : '/login'
  },
})
