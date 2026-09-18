import { apiClient } from './client'
import type {
  ApiResponse, ArchiveAttempt, ArchiveAttemptPage, ArchiveSource, CompetePlatform, TestReport,
} from '@/types'

/** Previously submitted source code, so reading it back never means leaving the app. */
export const archiveApi = {
  forProblem: (platform: CompetePlatform, contestId: string, index: string) =>
    apiClient.get<ApiResponse<ArchiveAttemptPage>>(
      `/submissions/problem/${platform}/${encodeURIComponent(contestId)}/${index}`)
      .then(r => r.data),

  recent: (limit = 50) =>
    apiClient.get<ApiResponse<ArchiveAttempt[]>>('/submissions/recent', { params: { limit } })
      .then(r => r.data),

  /** Local read of an archived source. No network on the server side either. */
  source: (archiveId: string) =>
    apiClient.get<ApiResponse<ArchiveSource>>(`/submissions/${archiveId}/source`)
      .then(r => r.data),

  /**
   * What the judge ran. Separate from the source because it may need a round trip to
   * Codeforces, and reading your own code back must not wait on that.
   */
  tests: (archiveId: string) =>
    apiClient.get<ApiResponse<TestReport>>(`/submissions/${archiveId}/tests`,
      { timeout: 45_000 }).then(r => r.data),

  /** Pulls a submission made outside CPIntel back off Codeforces. Slower — it is a scrape. */
  codeforcesSource: (contestId: string, submissionId: number) =>
    apiClient.get<ApiResponse<ArchiveSource>>(
      `/submissions/codeforces/${contestId}/${submissionId}/source`,
      { timeout: 45_000 }).then(r => r.data),
}
