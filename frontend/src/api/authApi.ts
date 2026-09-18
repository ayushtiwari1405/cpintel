import { apiClient } from './client'
import type { ApiResponse } from '@/types'

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  user: import('@/types').User
}

export const authApi = {
  // Self-registration is closed by default (cpintel.auth.registration-enabled) and there is no
  // sign-up screen. Kept because the endpoint is still real: a deployment that turns the flag
  // back on needs only a form to call this again.
  register: (data: { username: string; email: string; password: string; fullName?: string }) =>
    apiClient.post<ApiResponse<AuthResponse>>('/auth/register', data).then(r => r.data),

  login: (data: { email: string; password: string }) =>
    apiClient.post<ApiResponse<AuthResponse>>('/auth/login', data).then(r => r.data),

  refresh: (refreshToken: string) =>
    apiClient.post<ApiResponse<AuthResponse>>('/auth/refresh', { refreshToken }).then(r => r.data),

  logout: () =>
    apiClient.post<ApiResponse<void>>('/auth/logout').then(r => r.data),

  forgotPassword: (email: string) =>
    apiClient.post<ApiResponse<void>>('/auth/forgot-password', { email }).then(r => r.data),
}
