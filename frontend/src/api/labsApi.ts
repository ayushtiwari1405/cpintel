import { apiClient } from './client'
import type {
  ApiResponse, LabLeetCodeProblem, LabProblemView, LabProgress, LabRequest, LabSummary, MyLabs,
} from '@/types'

/**
 * Lab assignments: Codeforces and LeetCode problems a classroom's students are set to solve.
 *
 * Saving looks every new problem up on its platform, so it can take a few seconds.
 */
export const adminLabsApi = {
  list: (classroomId: number) =>
    apiClient.get<ApiResponse<LabSummary[]>>(`/admin/classrooms/${classroomId}/labs`).then(r => r.data),

  detail: (classroomId: number, labId: number) =>
    apiClient.get<ApiResponse<LabSummary>>(`/admin/classrooms/${classroomId}/labs/${labId}`)
      .then(r => r.data),

  resolve: (classroomId: number, link: string) =>
    apiClient.post<ApiResponse<LabProblemView>>(`/admin/classrooms/${classroomId}/labs/resolve`,
      { link }, { timeout: 60_000 }).then(r => r.data),

  create: (classroomId: number, body: LabRequest) =>
    apiClient.post<ApiResponse<LabSummary>>(`/admin/classrooms/${classroomId}/labs`, body,
      { timeout: 180_000 }).then(r => r.data),

  update: (classroomId: number, labId: number, body: LabRequest) =>
    apiClient.put<ApiResponse<LabSummary>>(`/admin/classrooms/${classroomId}/labs/${labId}`, body,
      { timeout: 180_000 }).then(r => r.data),

  remove: (classroomId: number, labId: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/classrooms/${classroomId}/labs/${labId}`)
      .then(r => r.data),

  progress: (classroomId: number, labId: number) =>
    apiClient.get<ApiResponse<LabProgress>>(`/admin/classrooms/${classroomId}/labs/${labId}/progress`)
      .then(r => r.data),

  check: (classroomId: number, labId: number) =>
    apiClient.post<ApiResponse<{ started: boolean; message: string }>>(
      `/admin/classrooms/${classroomId}/labs/${labId}/check`).then(r => r.data),

  marksXlsx: (classroomId: number, labId: number) =>
    apiClient.get<Blob>(`/admin/classrooms/${classroomId}/labs/${labId}/marks.xlsx`,
      { responseType: 'blob', timeout: 120_000 }).then(r => r.data),
}

export const labsApi = {
  mine: () => apiClient.get<ApiResponse<MyLabs>>('/labs/mine').then(r => r.data),

  leetCodeProblem: (problemId: number) =>
    apiClient.get<ApiResponse<LabLeetCodeProblem>>(`/labs/problems/${problemId}/leetcode`).then(r => r.data),

  /** Looks for your new solves now. Once a minute at most. */
  check: () =>
    apiClient.post<ApiResponse<{ recorded: number; checkedAt: string }>>('/labs/check', undefined,
      { timeout: 120_000 }).then(r => r.data),
}
