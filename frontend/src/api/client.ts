import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/store/authStore'

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

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = useAuthStore.getState().accessToken
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * Whether a failure says the server could not be reached, rather than that it refused.
 *
 * A deploy restarts the backend for about half a minute, and nginx answers 503
 * SERVER_RESTARTING meanwhile (502 or 504 without it, or no response at all). None of that is
 * the session's fault, so none of it may sign anybody out — and a read can simply be tried again.
 */
function unreachable(error: AxiosError): boolean {
  const status = error.response?.status
  // No answer at all: the network or a server that is down. A request the page itself
  // cancelled (navigating away) is not that, and must not be retried.
  if (status === undefined) return error.code !== 'ERR_CANCELED'
  if (status === 502 || status === 504) return true
  return status === 503
    && (error.response?.data as { code?: string } | undefined)?.code === 'SERVER_RESTARTING'
}

/** Waits between tries of a read while the server is unreachable: about a minute in all. */
const READ_RETRY_DELAYS_MS = [1_000, 2_000, 4_000, 8_000, 15_000, 30_000]

let isRefreshing = false
let failedQueue: Array<{ resolve: (v: string) => void; reject: (e: unknown) => void }> = []

const processQueue = (error: unknown, token: string | null) => {
  failedQueue.forEach(({ resolve, reject }) => {
    if (error) reject(error)
    else resolve(token!)
  })
  failedQueue = []
}

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const originalRequest = error.config as InternalAxiosRequestConfig & {
      _retry?: boolean
      _unreachableTries?: number
    }

    // A read while the server restarts is tried again after a pause, so a page opened during a
    // deploy loads when the server is back instead of showing an error. Writes are not repeated:
    // a submission that may have arrived must not be sent twice behind the person's back.
    // A read that timed out has already waited its 30 seconds, and is not tried again.
    if (originalRequest && unreachable(error) && error.code !== 'ECONNABORTED'
        && (originalRequest.method ?? 'get').toLowerCase() === 'get') {
      const tries = originalRequest._unreachableTries ?? 0
      if (tries < READ_RETRY_DELAYS_MS.length) {
        originalRequest._unreachableTries = tries + 1
        await new Promise(resolve => setTimeout(resolve, READ_RETRY_DELAYS_MS[tries]))
        return apiClient(originalRequest)
      }
    }

    // A 401 from signing in is a wrong password, not an expired session; renewing would turn
    // the message into a bounce back to the login page.
    const signingIn = /\/auth\/(login|register|refresh|forgot-password|reset-password)/
      .test(originalRequest?.url ?? '')

    if (error.response?.status === 401 && !originalRequest._retry && !signingIn) {
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject })
        }).then((token) => {
          originalRequest.headers.Authorization = `Bearer ${token}`
          return apiClient(originalRequest)
        })
      }

      originalRequest._retry = true
      isRefreshing = true

      try {
        // The refresh token is an HttpOnly cookie the browser sends by itself; this page never
        // sees it. The answer carries a new access token and rotates the cookie. An empty JSON
        // object rather than no body: with none, axios labels the request form-encoded, which
        // the endpoint does not read.
        const response = await axios.post(`${BASE_URL}/auth/refresh`, {},
          { withCredentials: true })
        const { accessToken } = response.data.data
        useAuthStore.getState().setAccessToken(accessToken)
        processQueue(null, accessToken)
        originalRequest.headers.Authorization = `Bearer ${accessToken}`
        return apiClient(originalRequest)
      } catch (refreshError) {
        processQueue(refreshError, null)
        // The server could not be reached to renew — a deploy, a blip. The session is still good;
        // keep it, and the next request after the server is back renews it.
        if (unreachable(refreshError as AxiosError)) {
          return Promise.reject(refreshError)
        }
        // An examination session stops renewing when its paper ends, and an ordinary one stops
        // while its holder's examination is running; say which on the way out.
        const examOver = useAuthStore.getState().mode === 'EXAM'
        const code = (refreshError as AxiosError<{ code?: string }>)?.response?.data?.code
        const examRunning = code === 'EXAM_IN_PROGRESS'
        // The paper was switched to desktop-only, or this session was carried out of the app.
        const desktopOnly = code === 'DESKTOP_APP_REQUIRED'
        useAuthStore.getState().logout()
        window.location.href = examRunning ? '/login?exam-in-progress=1'
          : desktopOnly ? '/login?desktop-app-required=1'
          : examOver ? '/login?exam-ended=1' : '/login'
        return Promise.reject(refreshError)
      } finally {
        isRefreshing = false
      }
    }

    return Promise.reject(error)
  }
)
