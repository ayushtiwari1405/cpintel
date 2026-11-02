import { apiClient } from './client'
import type {
  ApiResponse, BankClassroom, QuestionCheck, QuestionDetail, QuestionImport, QuestionRequest, QuestionSummary,
  StatementPreviewRequest,
} from '@/types'

/** A rendered statement, and the formulas that could not be drawn in it. */
export interface RenderedStatement {
  pdf: Blob
  problems: string[]
}

/**
 * For a FormData body. The client's default Content-Type is JSON, and axios turns a FormData
 * body into a JSON string whenever the request says JSON, so an upload has to say multipart;
 * the browser then fills in the boundary itself.
 */
export const MULTIPART = { 'Content-Type': 'multipart/form-data' }

function problemsOf(header: unknown): string[] {
  if (typeof header !== 'string' || !header) return []
  try {
    const parsed = JSON.parse(decodeURIComponent(header.replace(/\+/g, ' ')))
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

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

  /** The zip itself as the body: it may be far bigger than a form upload is allowed to be. */
  importPackage: (classroomId: number, file: File) =>
    apiClient.post<ApiResponse<QuestionImport>>(
      `/question-bank/classrooms/${classroomId}/questions/import`, file,
      { params: { fileName: file.name }, headers: { 'Content-Type': 'application/zip' }, timeout: 300_000 })
      .then(r => r.data.data),

  startCheck: (questionId: number, generateMissing: boolean) =>
    apiClient.post<ApiResponse<QuestionCheck>>(`/question-bank/questions/${questionId}/check`, { generateMissing })
      .then(r => r.data.data),

  latestCheck: (questionId: number) =>
    apiClient.get<ApiResponse<QuestionCheck | null>>(`/question-bank/questions/${questionId}/check`)
      .then(r => r.data.data),

  packageTemplate: () =>
    apiClient.get<Blob>('/question-bank/package-template.zip', { responseType: 'blob' }).then(r => r.data),

  exportPackage: (questionId: number) =>
    apiClient.get<Blob>(`/question-bank/questions/${questionId}/package.zip`,
      { responseType: 'blob', timeout: 120_000 }).then(r => r.data),

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
      `/question-bank/questions/${questionId}/tests`, form, { timeout: 120_000, headers: MULTIPART })
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

  preview: (questionId: number, body: StatementPreviewRequest, signal?: AbortSignal) =>
    apiClient.post<Blob>(`/question-bank/questions/${questionId}/statement/preview`, body,
      { responseType: 'blob', timeout: 60_000, signal })
      .then((r): RenderedStatement => ({ pdf: r.data, problems: problemsOf(r.headers['x-statement-problems']) })),

  statement: (questionId: number) =>
    apiClient.get<Blob>(`/question-bank/questions/${questionId}/statement.pdf`,
      { responseType: 'blob', timeout: 60_000 })
      .then((r): RenderedStatement => ({ pdf: r.data, problems: problemsOf(r.headers['x-statement-problems']) })),

  uploadStatementPdf: (questionId: number, file: File, version: number) => {
    const form = new FormData()
    form.append('file', file, file.name)
    form.append('version', String(version))
    return apiClient.put<ApiResponse<QuestionDetail>>(
      `/question-bank/questions/${questionId}/statement-pdf`, form, { timeout: 120_000, headers: MULTIPART })
      .then(r => r.data.data)
  },

  removeStatementPdf: (questionId: number, version: number) =>
    apiClient.delete<ApiResponse<QuestionDetail>>(
      `/question-bank/questions/${questionId}/statement-pdf`, { params: { version } })
      .then(r => r.data.data),

  download: (questionId: number, testId: number, part: 'input' | 'answer') =>
    apiClient.get<Blob>(`/question-bank/questions/${questionId}/tests/${testId}/${part}`,
      { responseType: 'blob', timeout: 120_000 }).then(r => r.data),
}
