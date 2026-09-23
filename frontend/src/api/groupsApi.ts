import { apiClient } from './client'
import type {
  ApiResponse, GroupContestSummary, GroupSummary, MyGroupContest, ViolationEvent,
} from '@/types'

/**
 * A participant's own view of the groups they are in.
 *
 * Narrow on purpose: their contests, their placing, and a way to report what their own desktop
 * lock observed. Nothing here can reach anyone else's conduct data — that lives behind
 * `/api/admin/groups`, which is a different controller rather than the same one with a flag.
 */
export const groupsApi = {
  myGroups: () =>
    apiClient.get<ApiResponse<GroupSummary[]>>('/groups').then(r => r.data),

  myContests: () =>
    apiClient.get<ApiResponse<MyGroupContest[]>>('/groups/contests').then(r => r.data),

  /** Null when the open contest is not being run for any group you are in. */
  active: (platform: string, externalId: string) =>
    apiClient.get<ApiResponse<GroupContestSummary | null>>('/groups/contests/active', {
      params: { platform, externalId },
    }).then(r => r.data),

  reportViolations: (contestId: number, events: ViolationEvent[]) =>
    apiClient.post<ApiResponse<{ stored: number }>>(
      `/groups/contests/${contestId}/violations`, { events }).then(r => r.data),

  /**
   * Says this contestant's monitoring is still running.
   *
   * Distinct from the violation report, which is a batched evidence trail and cannot answer
   * "is the lock alive now" — the absence of violations is what a contestant who is behaving
   * and one who closed the monitor have in common. The reply carries the interval to use, so
   * the page's timer and the server's tolerance cannot drift apart.
   */
  heartbeat: (contestId: number) =>
    apiClient.post<ApiResponse<{ intervalSeconds: number }>>(
      `/groups/contests/${contestId}/monitor/heartbeat`, {}).then(r => r.data),
}
