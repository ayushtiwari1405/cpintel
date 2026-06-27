import { apiClient } from './client'
import type { ApiResponse, PlatformAccount, SyncJob } from '@/types'

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

  getSyncStatus: (jobId: number) =>
    apiClient.get<ApiResponse<SyncJob>>(`/integrations/sync-status/${jobId}`).then(r => r.data),
}
