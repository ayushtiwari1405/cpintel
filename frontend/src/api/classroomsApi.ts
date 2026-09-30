import { apiClient } from './client'
import type {
  ApiResponse, ClassroomMember, ClassroomRequest, ClassroomStaffMember, ClassroomSummary,
  JudgeCheck, MyClassroom,
} from '@/types'

/**
 * Classrooms: one DOMjudge instance each, and the students, teams and events run on it.
 *
 * Admins see the classrooms they own or were added to as staff; a superadmin sees all of them.
 * The service-account password is write-only — nothing here ever reads it back.
 */
export const adminClassroomsApi = {
  list: () =>
    apiClient.get<ApiResponse<ClassroomSummary[]>>('/admin/classrooms').then(r => r.data),

  detail: (id: number) =>
    apiClient.get<ApiResponse<ClassroomSummary>>(`/admin/classrooms/${id}`).then(r => r.data),

  /** Checks the judge answers before saving, so this can take a few seconds. */
  create: (body: ClassroomRequest) =>
    apiClient.post<ApiResponse<ClassroomSummary>>('/admin/classrooms', body, { timeout: 60_000 })
      .then(r => r.data),

  update: (id: number, body: ClassroomRequest) =>
    apiClient.put<ApiResponse<ClassroomSummary>>(`/admin/classrooms/${id}`, body,
      { timeout: 60_000 }).then(r => r.data),

  archive: (id: number) =>
    apiClient.post<ApiResponse<void>>(`/admin/classrooms/${id}/archive`).then(r => r.data),

  restore: (id: number) =>
    apiClient.post<ApiResponse<void>>(`/admin/classrooms/${id}/restore`).then(r => r.data),

  check: (id: number) =>
    apiClient.post<ApiResponse<JudgeCheck>>(`/admin/classrooms/${id}/check`, undefined,
      { timeout: 60_000 }).then(r => r.data),

  members: (id: number) =>
    apiClient.get<ApiResponse<ClassroomMember[]>>(`/admin/classrooms/${id}/members`)
      .then(r => r.data),

  addMember: (id: number, userId: number) =>
    apiClient.post<ApiResponse<ClassroomMember>>(`/admin/classrooms/${id}/members`, { userId })
      .then(r => r.data),

  removeMember: (id: number, userId: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/classrooms/${id}/members/${userId}`)
      .then(r => r.data),

  staff: (id: number) =>
    apiClient.get<ApiResponse<ClassroomStaffMember[]>>(`/admin/classrooms/${id}/staff`)
      .then(r => r.data),

  addStaff: (id: number, userId: number) =>
    apiClient.post<ApiResponse<void>>(`/admin/classrooms/${id}/staff`, { userId })
      .then(r => r.data),

  removeStaff: (id: number, userId: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/classrooms/${id}/staff/${userId}`)
      .then(r => r.data),
}

export const classroomsApi = {
  mine: () =>
    apiClient.get<ApiResponse<MyClassroom[]>>('/classrooms/mine').then(r => r.data),
}
