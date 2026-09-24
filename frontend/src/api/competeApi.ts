import { apiClient } from './client'
import type {
  ApiResponse, CompetePlatform, ContestInfo, ContestRef, ContestSubmission,
  DomjudgeAccount, DomjudgeContestSummary, LanguageOption, Leaderboard, ProblemDetail,
  RankInfo,
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
   * The whole board, read only while the leaderboard panel is open.
   *
   * Separate from `rank`, which the header polls for every contestant in the room. Folding the
   * two together would have made the expensive call as frequent as the cheap one.
   */
  leaderboard: (ref: ContestRef) =>
    apiClient.get<ApiResponse<Leaderboard>>(`${base(ref)}/leaderboard`).then(r => r.data),

  /**
   * The statement PDF, as bytes.
   *
   * Fetched through the API client rather than pointed at with an `<iframe src>` so the
   * request carries the bearer token — the arena's routes are authenticated, and an iframe
   * would send none. The caller turns the blob into an object URL.
   */
  statementPdf: (ref: ContestRef, index: string) =>
    apiClient.get<Blob>(`${base(ref)}/problems/${index}/statement.pdf`,
      { responseType: 'blob' }).then(r => r.data).catch(async err => {
        // With responseType 'blob' the error body arrives as a Blob too, so the server's
        // explanation is unreadable as err.response.data.message until it is decoded.
        const data = err?.response?.data
        if (data instanceof Blob) {
          try { err.response.data = JSON.parse(await data.text()) } catch { /* not JSON */ }
        }
        throw err
      }),

  /** The statement's text, extracted server-side from a PDF — read only for its examples. */
  statementText: (ref: ContestRef, index: string) =>
    apiClient.get<string>(`${base(ref)}/problems/${index}/statement.txt`,
      { responseType: 'text' }).then(r => r.data),
}

/**
 * The DOMjudge account a contestant competes as.
 *
 * Not under `/compete/{platform}/{contestId}` because both of these are properties of an
 * account rather than of a contest — and the contest list in particular has to be answerable
 * before a contest has been picked, which is exactly when there is no contest id for the path.
 */
export const domjudgeApi = {
  account: () =>
    apiClient.get<ApiResponse<DomjudgeAccount>>('/domjudge/account').then(r => r.data),

  contests: () =>
    apiClient.get<ApiResponse<DomjudgeContestSummary[]>>('/domjudge/contests')
      .then(r => r.data),
}
