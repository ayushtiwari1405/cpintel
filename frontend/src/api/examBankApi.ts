import { apiClient } from './client'
import type { ApiResponse, ExamAnnouncement, ExamBankState } from '@/types'

/** An examination's questions from the question bank, and putting them on the judge. Admins only. */
export const examBankApi = {
  state: (eventId: number) =>
    apiClient.get<ApiResponse<ExamBankState>>(`/admin/events/${eventId}/bank`).then(r => r.data.data),

  choose: (eventId: number, questionIds: number[]) =>
    apiClient.put<ApiResponse<ExamBankState>>(`/admin/events/${eventId}/bank/questions`, { questionIds })
      .then(r => r.data.data),

  /** Uploads every question: give it time. */
  publish: (eventId: number) =>
    apiClient.post<ApiResponse<ExamBankState>>(`/admin/events/${eventId}/bank/publish`, null, { timeout: 600_000 })
      .then(r => r.data.data),

  republish: (eventId: number, questionId: number, notice: string | null) =>
    apiClient.post<ApiResponse<ExamBankState>>(
      `/admin/events/${eventId}/bank/questions/${questionId}/republish`,
      { confirm: true, notice }, { timeout: 300_000 }).then(r => r.data.data),

  announce: (eventId: number, message: string) =>
    apiClient.post<ApiResponse<ExamAnnouncement[]>>(`/admin/events/${eventId}/announcements`, { message })
      .then(r => r.data.data),
}
