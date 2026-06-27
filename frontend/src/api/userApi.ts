import { apiClient } from './client'
import type { ApiResponse, User } from '@/types'

export const userApi = {
  getMe: () =>
    apiClient.get<ApiResponse<User>>('/users/me').then(r => r.data),

  updateMe: (data: {
    fullName?: string
    country?: string
    institution?: string
    avatarUrl?: string
  }) =>
    apiClient.put<ApiResponse<User>>('/users/me', data).then(r => r.data),

  getDashboard: () =>
    apiClient.get<ApiResponse<any>>('/users/dashboard').then(r => r.data),
}
