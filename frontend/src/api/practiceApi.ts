import { apiClient } from './client'
import type {
  ApiResponse, CfSessionStatus, LanguageOption,
  ProblemDetail, ProblemSummary, SubmitResponse, VerdictResponse,
} from '@/types'

export interface ProblemQuery {
  q?: string
  minRating?: number
  maxRating?: number
  tag?: string
  limit?: number
}

export const practiceApi = {
  search: (params: ProblemQuery) =>
    apiClient.get<ApiResponse<ProblemSummary[]>>('/practice/problems', { params })
      .then(r => r.data),

  tags: () =>
    apiClient.get<ApiResponse<string[]>>('/practice/tags').then(r => r.data),

  getProblem: (contestId: number, index: string) =>
    apiClient.get<ApiResponse<ProblemDetail>>(`/practice/problems/${contestId}/${index}`)
      .then(r => r.data),

  languages: () =>
    apiClient.get<ApiResponse<LanguageOption[]>>('/practice/languages').then(r => r.data),

  sessionStatus: () =>
    apiClient.get<ApiResponse<CfSessionStatus>>('/practice/cf-session').then(r => r.data),

  /**
   * Manual paste path. `userAgent` matters: Codeforces sits behind Cloudflare, which ties the
   * cf_clearance cookie to the User-Agent that solved its challenge — so a header pasted from
   * this browser has to be replayed as this browser or the session reads as signed out.
   */
  connectSession: (cookieHeader: string) =>
    apiClient.post<ApiResponse<CfSessionStatus>>('/practice/cf-session', {
      cookieHeader,
      userAgent: navigator.userAgent,
    }).then(r => r.data),

  disconnectSession: () =>
    apiClient.delete<ApiResponse<void>>('/practice/cf-session').then(r => r.data),

  submit: (body: { contestId: number; index: string; languageId: string; source: string }) =>
    apiClient.post<ApiResponse<SubmitResponse>>('/practice/submit', body, { timeout: 90_000 })
      .then(r => r.data),

  verdict: (submissionId: number) =>
    apiClient.get<ApiResponse<VerdictResponse>>(`/practice/submissions/${submissionId}/verdict`)
      .then(r => r.data),
}
