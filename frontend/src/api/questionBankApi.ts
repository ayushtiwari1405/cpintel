import { apiClient } from './client'
import type {
  ApiResponse, BankClassroom, QuestionDetail, QuestionRequest, QuestionSummary,
} from '@/types'

/**
 * A classroom's question bank. Open to its admins and its TAs alike, so it lives outside
 * `/admin`; the server decides per classroom who may read and change what.
 */
export const questionBankApi = {
  classrooms: () =>
    apiClient.get<ApiResponse<BankClassroom[]>>('/question-bank/classrooms').then(r => r.data.data),

  list: (classroomId: number, archived = false) =>
    apiClient.get<ApiResponse<QuestionSummary[]>>(
      `/question-bank/classrooms/${classroomId}/questions`, { params: { archived } })
      .then(r => r.data.data),

  create: (classroomId: number, body: QuestionRequest) =>
    apiClient.post<ApiResponse<QuestionDetail>>(
      `/question-bank/classrooms/${classroomId}/questions`, body).then(r => r.data.data),

  get: (questionId: number) =>
    apiClient.get<ApiResponse<QuestionDetail>>(`/question-bank/questions/${questionId}`)
      .then(r => r.data.data),

  update: (questionId: number, body: QuestionRequest) =>
    apiClient.put<ApiResponse<QuestionDetail>>(`/question-bank/questions/${questionId}`, body)
      .then(r => r.data.data),

  setArchived: (questionId: number, archived: boolean) =>
    apiClient.post<ApiResponse<QuestionDetail>>(
      `/question-bank/questions/${questionId}/${archived ? 'archive' : 'restore'}`)
      .then(r => r.data.data),

  addTests: (questionId: number, files: File[], sample: boolean, version: number) => {
    const form = new FormData()
    files.forEach(f => form.append('files', f, f.name))
    form.append('sample', String(sample))
    form.append('version', String(version))
    return apiClient.post<ApiResponse<QuestionDetail>>(
      `/question-bank/questions/${questionId}/tests`, form, { timeout: 120_000 })
      .then(r => r.data.data)
  },

  deleteTest: (questionId: number, testId: number, version: number) =>
    apiClient.delete<ApiResponse<QuestionDetail>>(
      `/question-bank/questions/${questionId}/tests/${testId}`, { params: { version } })
      .then(r => r.data.data),

  reorder: (questionId: number, sample: boolean, testIds: number[], version: number) =>
    apiClient.put<ApiResponse<QuestionDetail>>(
      `/question-bank/questions/${questionId}/tests/order`, { sample, testIds, version })
      .then(r => r.data.data),

  download: (questionId: number, testId: number, part: 'input' | 'answer') =>
    apiClient.get<Blob>(`/question-bank/questions/${questionId}/tests/${testId}/${part}`,
      { responseType: 'blob', timeout: 120_000 }).then(r => r.data),
}
