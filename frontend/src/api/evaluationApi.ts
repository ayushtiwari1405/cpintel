import { apiClient } from './client'
import type {
  ApiResponse, AssignmentBoard, ClassroomTa, EvaluationCell, EvaluationSheet,
  EvaluationSubmission, MarkRequest, ReopenRequest, TaAdded, TaAssignmentRequest, TaExam, TaRequest,
} from '@/types'

/**
 * Marking examinations by hand.
 *
 * Two doors onto the same sheet: an admin's, which covers the whole examination, and a TA's,
 * which covers only what they were given to mark. `EvaluationSource` lets the sheet use either.
 */
export interface EvaluationSource {
  key: unknown[]
  sheet: () => Promise<EvaluationSheet>
  submission: (userId: number, label: string) => Promise<EvaluationSubmission>
  mark: (body: MarkRequest) => Promise<EvaluationCell>
  /** TA only: freeze their marking. */
  freeze?: () => Promise<EvaluationSheet>
  /** Admin only: send an answer back to the TA who froze it, or take it back. */
  reopen?: (body: ReopenRequest) => Promise<EvaluationCell>
}

export const adminEvaluationApi = {
  tas: (classroomId: number) =>
    apiClient.get<ApiResponse<ClassroomTa[]>>(`/admin/classrooms/${classroomId}/tas`)
      .then(r => r.data),

  addTa: (classroomId: number, body: TaRequest) =>
    apiClient.post<ApiResponse<TaAdded>>(`/admin/classrooms/${classroomId}/tas`, body)
      .then(r => r.data),

  removeTa: (classroomId: number, userId: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/classrooms/${classroomId}/tas/${userId}`)
      .then(r => r.data),

  board: (eventId: number) =>
    apiClient.get<ApiResponse<AssignmentBoard>>(`/admin/events/${eventId}/evaluation`)
      .then(r => r.data),

  assign: (eventId: number, body: TaAssignmentRequest) =>
    apiClient.post<ApiResponse<AssignmentBoard>>(
      `/admin/events/${eventId}/evaluation/assignments`, body).then(r => r.data),

  unfreeze: (eventId: number, taUserId: number) =>
    apiClient.delete<ApiResponse<AssignmentBoard>>(
      `/admin/events/${eventId}/evaluation/freezes/${taUserId}`).then(r => r.data),

  unassign: (eventId: number, assignmentId: number) =>
    apiClient.delete<ApiResponse<AssignmentBoard>>(
      `/admin/events/${eventId}/evaluation/assignments/${assignmentId}`).then(r => r.data),

  source: (eventId: number): EvaluationSource => ({
    key: ['admin', 'evaluation', eventId],
    sheet: () => apiClient.get<ApiResponse<EvaluationSheet>>(
      `/admin/events/${eventId}/evaluation/sheet`).then(r => r.data.data),
    submission: (userId, label) => apiClient.get<ApiResponse<EvaluationSubmission>>(
      `/admin/events/${eventId}/evaluation/submissions/${userId}/${encodeURIComponent(label)}`)
      .then(r => r.data.data),
    mark: body => apiClient.put<ApiResponse<EvaluationCell>>(
      `/admin/events/${eventId}/evaluation/marks`, body).then(r => r.data.data),
    reopen: body => apiClient.put<ApiResponse<EvaluationCell>>(
      `/admin/events/${eventId}/evaluation/reopen`, body).then(r => r.data.data),
  }),
}

export const evaluationApi = {
  exams: () =>
    apiClient.get<ApiResponse<TaExam[]>>('/evaluation/exams').then(r => r.data),

  source: (eventId: number): EvaluationSource => ({
    key: ['evaluation', eventId],
    sheet: () => apiClient.get<ApiResponse<EvaluationSheet>>(`/evaluation/exams/${eventId}`)
      .then(r => r.data.data),
    submission: (userId, label) => apiClient.get<ApiResponse<EvaluationSubmission>>(
      `/evaluation/exams/${eventId}/submissions/${userId}/${encodeURIComponent(label)}`)
      .then(r => r.data.data),
    mark: body => apiClient.put<ApiResponse<EvaluationCell>>(
      `/evaluation/exams/${eventId}/marks`, body).then(r => r.data.data),
    freeze: () => apiClient.post<ApiResponse<EvaluationSheet>>(
      `/evaluation/exams/${eventId}/freeze`).then(r => r.data.data),
  }),
}
