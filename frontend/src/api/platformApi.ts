import { apiClient } from './client'
import type { ApiResponse, HandleChallenge, PlatformAccount, SyncJob } from '@/types'

export const platformApi = {
  getLinked: () =>
    apiClient.get<ApiResponse<PlatformAccount[]>>('/integrations').then(r => r.data),

  link: (platform: string, handle: string) =>
    apiClient.post<ApiResponse<PlatformAccount>>(
      `/integrations/${platform.toLowerCase()}/link`, { handle }
    ).then(r => r.data),

  unlink: (platform: string) =>
    apiClient.delete<ApiResponse<void>>(
      `/integrations/${platform.toLowerCase()}`
    ).then(r => r.data),

  sync: (platform: string) =>
    apiClient.post<ApiResponse<{ jobId: number; status: string }>>(
      `/integrations/${platform.toLowerCase()}/sync`
    ).then(r => r.data),

  /** Opens a proof that the handle is yours, or returns the one already open. */
  startVerify: (platform: string) =>
    apiClient.post<ApiResponse<HandleChallenge>>(
      `/integrations/${platform.toLowerCase()}/verify`
    ).then(r => r.data),

  /** Looks for the proof on the platform; fails with a message when it is not there yet. */
  checkVerify: (platform: string) =>
    apiClient.post<ApiResponse<PlatformAccount>>(
      `/integrations/${platform.toLowerCase()}/verify/check`, undefined, { timeout: 60_000 }
    ).then(r => r.data),

  getSyncStatus: (jobId: number) =>
    apiClient.get<ApiResponse<SyncJob>>(`/integrations/sync-status/${jobId}`).then(r => r.data),
}
