import { apiClient } from './client'
import type { ApiResponse } from '@/types'

export const analyticsApi = {
  getOverview: () =>
    apiClient.get<ApiResponse<any>>('/analytics/overview').then(r => r.data),
  getTopics: () =>
    apiClient.get<ApiResponse<any[]>>('/analytics/topics').then(r => r.data),
  getContests: () =>
    apiClient.get<ApiResponse<any>>('/analytics/contests').then(r => r.data),
  getTrends: () =>
    apiClient.get<ApiResponse<any>>('/analytics/trends').then(r => r.data),
  refresh: () =>
    apiClient.post<ApiResponse<void>>('/analytics/refresh').then(r => r.data),
}

export const recommendationApi = {
  getDaily: () =>
    apiClient.get<ApiResponse<any>>('/recommendations/daily').then(r => r.data),
  getWeekly: () =>
    apiClient.get<ApiResponse<any>>('/recommendations/weekly').then(r => r.data),
  getRevision: () =>
    apiClient.get<ApiResponse<any[]>>('/recommendations/revision').then(r => r.data),
  markRevisionDone: (id: number) =>
    apiClient.post<ApiResponse<void>>(`/recommendations/revision/${id}/done`).then(r => r.data),
}

export const roadmapApi = {
  getCurrent: () =>
    apiClient.get<ApiResponse<any[]>>('/roadmaps/current').then(r => r.data),
  regenerate: () =>
    apiClient.post<ApiResponse<any[]>>('/roadmaps/regenerate').then(r => r.data),
  updateNode: (nodeId: number, status: string) =>
    apiClient.patch<ApiResponse<any>>(`/roadmaps/nodes/${nodeId}?status=${status}`).then(r => r.data),
}
