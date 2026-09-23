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

  /**
   * Signing in with an email address or a username.
   *
   * One field for both. People here are given a username and a first password when their
   * account is made, and the address on the account may be one they never use — so a box that
   * insists on the address asks half of them for something they do not have to hand. Which of
   * the two was typed is worked out on the server.
   */
  login: (data: { identifier: string; password: string }) =>
    apiClient.post<ApiResponse<AuthResponse>>('/auth/login', data).then(r => r.data),

  refresh: (refreshToken: string) =>
    apiClient.post<ApiResponse<AuthResponse>>('/auth/refresh', { refreshToken }).then(r => r.data),

  logout: () =>
    apiClient.post<ApiResponse<void>>('/auth/logout').then(r => r.data),

  /**
   * Asks for a reset link.
   *
   * Answers the same way whether or not the address belongs to anybody, so nothing here can
   * tell you an account exists. The screen says the same thing for the same reason.
   */
  forgotPassword: (email: string) =>
    apiClient.post<ApiResponse<void>>('/auth/forgot-password', { email }).then(r => r.data),

  /** Spends a link from an email and sets the password it was issued for. */
  resetPassword: (data: { token: string; newPassword: string }) =>
    apiClient.post<ApiResponse<void>>('/auth/reset-password', data).then(r => r.data),

  /**
   * Changes your own password while signed in.
   *
   * The current one is asked for even though this request already carries a valid token,
   * because a token can be an unlocked laptop. Succeeding signs every device out, including
   * the one that asked — the caller has to send the user back to the sign-in screen.
   */
  changePassword: (data: { currentPassword: string; newPassword: string }) =>
    apiClient.post<ApiResponse<void>>('/auth/change-password', data).then(r => r.data),
}
