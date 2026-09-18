import { apiClient } from './client'
import type { ApiResponse, RunRequest, RunResponse, RunnerRuntime, RunnerStatus } from '@/types'

/** Compile and run a solution on the machine hosting the backend. */
export const runApi = {
  status: () =>
    apiClient.get<ApiResponse<RunnerStatus>>('/run/status').then(r => r.data),

  languages: () =>
    apiClient.get<ApiResponse<RunnerRuntime[]>>('/run/languages').then(r => r.data),

  run: (body: RunRequest) =>
    apiClient.post<ApiResponse<RunResponse>>('/run', body).then(r => r.data),
}
