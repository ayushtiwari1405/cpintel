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
    const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean }

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
        // An examination session stops renewing when its paper ends; say so on the way out.
        const examOver = useAuthStore.getState().mode === 'EXAM'
        useAuthStore.getState().logout()
        window.location.href = examOver ? '/login?exam-ended=1' : '/login'
        return Promise.reject(refreshError)
      } finally {
        isRefreshing = false
      }
    }

    return Promise.reject(error)
  }
)
