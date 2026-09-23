import { apiClient } from './client'
import type { ApiResponse, RunRequest, RunResponse, RunnerRuntime, RunnerStatus } from '@/types'

/** Which event a run belongs to, when it belongs to one. Omitted on Practice. */
export interface RunScope {
  platform?: string
  contestId?: string
}

/** Compile and run a solution on the machine hosting the backend. */
export const runApi = {
  status: () =>
    apiClient.get<ApiResponse<RunnerStatus>>('/run/status').then(r => r.data),

  /**
   * Languages the backend can build here.
   *
   * Narrowed to what the event allows when one is named — the same restriction the Submit
   * button is held to, resolved server-side from the same list, so the two pickers cannot
   * offer different things.
   */
  languages: (scope: RunScope = {}) =>
    apiClient.get<ApiResponse<RunnerRuntime[]>>('/run/languages', {
      params: { platform: scope.platform, contestId: scope.contestId },
    }).then(r => r.data),

  run: (body: RunRequest) =>
    apiClient.post<ApiResponse<RunResponse>>('/run', body).then(r => r.data),
}
