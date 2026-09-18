import { apiClient } from './client'
import type {
  ApiResponse, CompetePlatform, ContestInfo, ContestRef, ContestSubmission,
  LanguageOption, ProblemDetail, RankInfo,
} from '@/types'

/**
 * The compete arena, over whichever judge the contest runs on.
 *
 * Every route is addressed by `{platform}/{contestId}` rather than by a bare id. Codeforces
 * and DOMjudge number their contests independently, so an id alone stopped identifying a
 * contest the moment the second judge was wired up.
 */
const base = (ref: ContestRef) =>
  `/compete/${ref.platform}/${encodeURIComponent(ref.id)}`

export const competeApi = {
  load: (platform: CompetePlatform, url: string) =>
    apiClient.post<ApiResponse<ContestInfo>>('/compete/contest', { platform, url })
      .then(r => r.data),

  contest: (ref: ContestRef) =>
    apiClient.get<ApiResponse<ContestInfo>>(base(ref)).then(r => r.data),

  statement: (ref: ContestRef, index: string) =>
    apiClient.get<ApiResponse<ProblemDetail>>(`${base(ref)}/problems/${index}`)
      .then(r => r.data),

  languages: (ref: ContestRef) =>
    apiClient.get<ApiResponse<LanguageOption[]>>(`${base(ref)}/languages`)
      .then(r => r.data),

  submit: (ref: ContestRef, body: { index: string; languageId: string; source: string }) =>
    apiClient.post<ApiResponse<ContestSubmission>>(`${base(ref)}/submit`, body,
      { timeout: 90_000 }).then(r => r.data),

  submissions: (ref: ContestRef) =>
    apiClient.get<ApiResponse<ContestSubmission[]>>(`${base(ref)}/submissions`)
      .then(r => r.data),

  rank: (ref: ContestRef) =>
    apiClient.get<ApiResponse<RankInfo>>(`${base(ref)}/rank`).then(r => r.data),

  /**
   * The statement PDF, as bytes.
   *
   * Fetched through the API client rather than pointed at with an `<iframe src>` so the
   * request carries the bearer token — the arena's routes are authenticated, and an iframe
   * would send none. The caller turns the blob into an object URL.
   */
  statementPdf: (ref: ContestRef, index: string) =>
    apiClient.get<Blob>(`${base(ref)}/problems/${index}/statement.pdf`,
      { responseType: 'blob' }).then(r => r.data),
}
