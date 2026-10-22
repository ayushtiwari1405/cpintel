import { apiClient } from './client'
import type { ApiResponse, PlanType, PracticePlan, RevisionItem, SkillBasis } from '@/types'

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
  getSkillBasis: () =>
    apiClient.get<ApiResponse<SkillBasis>>('/analytics/skill-basis').then(r => r.data),
  setSkillBasis: (basis: SkillBasis['requested']) =>
    apiClient.put<ApiResponse<SkillBasis>>('/analytics/skill-basis', { basis }).then(r => r.data),
}

export const recommendationApi = {
  getPlan: (type: PlanType) =>
    apiClient.get<ApiResponse<PracticePlan>>(`/recommendations/${type}`).then(r => r.data),
  regenerate: (type: PlanType) =>
    apiClient.post<ApiResponse<PracticePlan>>(`/recommendations/${type}/regenerate`).then(r => r.data),
  getRevision: (horizonDays = 14) =>
    apiClient.get<ApiResponse<RevisionItem[]>>('/recommendations/revision', { params: { horizonDays } })
      .then(r => r.data),
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
