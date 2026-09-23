import { apiClient } from './client'
import type {
  AdminOverview, AdminUserDetail, AdminUserPage, ApiResponse, AuditPage, ParticipationRow,
  AdminUserRow, AssignableRole, ContestFilePolicy, ContestFileRule, GroupContestSummary,
  DomjudgeAccount, DomjudgeTeamOption, GroupDetail, GroupMember, GroupStandings, GroupSummary,
  GeneratedPassword, Role,
  ViolationFeed,
} from '@/types'

export interface CreateUserBody {
  username: string
  email: string
  password: string
  fullName?: string
  role: AssignableRole
}

export interface UserQuery {
  query?: string
  role?: Role | ''
  active?: boolean | null
  page?: number
  size?: number
}

export interface AuditQuery {
  action?: string
  userId?: number | null
  since?: string
  page?: number
  size?: number
}

/**
 * The admin console.
 *
 * Everything here sits under `/api/admin`, which the server requires the ADMIN role for in the
 * filter chain as well as on each controller. The UI hides these screens from ordinary users,
 * but that is a courtesy — the enforcement is entirely server-side, so a hand-written request
 * from a regular account gets a 403 rather than data.
 */
export type RosterRowStatus =
  | 'ADD_EXISTING'      // matched an account not yet in the group
  | 'CREATE_AND_ADD'    // no such account; one will be created
  | 'ALREADY_MEMBER'    // matched someone already in the group
  | 'DUPLICATE'         // the same person appears earlier in the paste
  | 'INVALID'           // unusable row

export interface RosterRowOutcome {
  line: number
  email: string | null
  username: string | null
  fullName: string | null
  cfHandle: string | null
  teamName: string | null
  status: RosterRowStatus
  message: string
  userId: number | null
  /** Present only on a committed creation, and only in that one response. */
  generatedPassword: string | null
}

export interface RosterImportResult {
  dryRun: boolean
  rows: RosterRowOutcome[]
  total: number
  toAdd: number
  toCreate: number
  alreadyMembers: number
  duplicates: number
  invalid: number
  mayCreateAccounts: boolean
  blockedReason: string | null
}

export const adminApi = {
  /** Moves a member to another team in one action, keeping their judge handle. */
  moveMember: (groupId: number, userId: number, targetGroupId: number) =>
    apiClient.post<ApiResponse<GroupMember>>(
      `/admin/groups/${groupId}/members/${userId}/move`, { targetGroupId }).then(r => r.data),

  /**
   * Every contest and examination this person was assigned, and how they did.
   *
   * Includes the ones they never entered, which is usually why an admin opened the account:
   * somebody who did not sit an examination is invisible in any list built from results.
   */
  participation: (userId: number) =>
    apiClient.get<ApiResponse<ParticipationRow[]>>(`/admin/users/${userId}/participation`)
      .then(r => r.data),

  overview: () =>
    apiClient.get<ApiResponse<AdminOverview>>('/admin/overview').then(r => r.data),

  // ------------------------------------------------------------------ users

  users: (q: UserQuery = {}) =>
    apiClient.get<ApiResponse<AdminUserPage>>('/admin/users', {
      params: {
        query: q.query || undefined,
        role: q.role || undefined,
        active: q.active ?? undefined,
        page: q.page ?? 0,
        size: q.size ?? 25,
      },
    }).then(r => r.data),

  user: (userId: number) =>
    apiClient.get<ApiResponse<AdminUserDetail>>(`/admin/users/${userId}`).then(r => r.data),

  // Super admin only — an ordinary admin gets a 403 from these two.
  create: (body: CreateUserBody) =>
    apiClient.post<ApiResponse<AdminUserRow>>('/admin/users', body).then(r => r.data),

  setRole: (userId: number, role: AssignableRole) =>
    apiClient.put<ApiResponse<AdminUserRow>>(`/admin/users/${userId}/role`, { role })
      .then(r => r.data),

  setActive: (userId: number, active: boolean, reason?: string) =>
    apiClient.put<ApiResponse<AdminUserRow>>(`/admin/users/${userId}/active`, { active, reason })
      .then(r => r.data),

  revokeSessions: (userId: number) =>
    apiClient.post<ApiResponse<void>>(`/admin/users/${userId}/revoke-sessions`)
      .then(r => r.data),

  /**
   * Sets a new password on somebody's account, for the person who cannot reach their own mail.
   *
   * The self-service route is better and lives on the sign-in page; this exists because it
   * does not always work, and "ask an administrator" has to lead somewhere. Leave `password`
   * out to have one generated.
   *
   * The password comes back in the response and that is the only copy — it is stored hashed
   * and is never emailed, because a live credential in a mailbox is what the reset-link flow
   * exists to avoid.
   */
  setPassword: (userId: number, password?: string, reason?: string) =>
    apiClient.post<ApiResponse<GeneratedPassword>>(
      `/admin/users/${userId}/password`, { password, reason }).then(r => r.data),

  /**
   * Deletes an account and everything it owns. Super admin only.
   *
   * The blunt instrument, and refused server-side for anybody who has sat an examination:
   * their session log is evidence about a paper, and deleting an account should not also be a
   * decision to destroy that. Deactivating is what "remove this person" almost always means.
   */
  deleteUser: (userId: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/users/${userId}`).then(r => r.data),

  // ------------------------------------------------------------------ audit

  audit: (q: AuditQuery = {}) =>
    apiClient.get<ApiResponse<AuditPage>>('/admin/audit', {
      params: {
        action: q.action || undefined,
        userId: q.userId ?? undefined,
        since: q.since || undefined,
        page: q.page ?? 0,
        size: q.size ?? 50,
      },
    }).then(r => r.data),

  // ---------------------------------------------------- contest file policy

  policy: () =>
    apiClient.get<ApiResponse<ContestFilePolicy>>('/admin/contest-files').then(r => r.data),

  setDefault: (enabled: boolean, note?: string) =>
    apiClient.put<ApiResponse<ContestFilePolicy>>('/admin/contest-files/default',
      { enabled, note }).then(r => r.data),

  clearDefault: () =>
    apiClient.delete<ApiResponse<ContestFilePolicy>>('/admin/contest-files/default')
      .then(r => r.data),

  setRule: (platform: string, contestId: string, enabled: boolean, note?: string) =>
    apiClient.put<ApiResponse<ContestFileRule>>(
      `/admin/contest-files/${platform}/${contestId}`, { enabled, note }).then(r => r.data),

  clearRule: (platform: string, contestId: string) =>
    apiClient.delete<ApiResponse<void>>(
      `/admin/contest-files/${platform}/${encodeURIComponent(contestId)}`)
      .then(r => r.data),

  // ----------------------------------------------------------- group contests

  groups: () =>
    apiClient.get<ApiResponse<GroupSummary[]>>('/admin/groups').then(r => r.data),

  group: (groupId: number) =>
    apiClient.get<ApiResponse<GroupDetail>>(`/admin/groups/${groupId}`).then(r => r.data),

  createGroup: (body: { name: string; description?: string }) =>
    apiClient.post<ApiResponse<GroupSummary>>('/admin/groups', body).then(r => r.data),

  updateGroup: (groupId: number, body: { name: string; description?: string }) =>
    apiClient.put<ApiResponse<GroupSummary>>(`/admin/groups/${groupId}`, body).then(r => r.data),

  deactivateGroup: (groupId: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/groups/${groupId}`).then(r => r.data),

  addMember: (groupId: number, body: { userId: number; externalHandle?: string }) =>
    apiClient.post<ApiResponse<GroupMember>>(`/admin/groups/${groupId}/members`, body)
      .then(r => r.data),

  /**
   * Bulk roster import.
   *
   * Send `dryRun: true` first. It writes nothing and returns what each row would do, which is
   * the only chance to catch a mis-read column before it becomes hundreds of wrong accounts.
   */
  importRoster: (groupId: number,
                 body: { text: string; dryRun: boolean; teamName?: string }) =>
    apiClient.post<ApiResponse<RosterImportResult>>(
      `/admin/groups/${groupId}/members/import`, body).then(r => r.data),

  updateMember: (groupId: number, userId: number, body: { userId: number; externalHandle?: string }) =>
    apiClient.put<ApiResponse<GroupMember>>(`/admin/groups/${groupId}/members/${userId}`, body)
      .then(r => r.data),

  removeMember: (groupId: number, userId: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/groups/${groupId}/members/${userId}`)
      .then(r => r.data),

  addContest: (groupId: number, body: {
    platform: string
    externalId: string
    name: string
    url?: string
    startsAt?: string
    endsAt?: string
    lockdownRequired?: boolean
  }) =>
    apiClient.post<ApiResponse<GroupContestSummary>>(`/admin/groups/${groupId}/contests`, body)
      .then(r => r.data),

  removeContest: (contestId: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/groups/contests/${contestId}`).then(r => r.data),

  standings: (contestId: number) =>
    apiClient.get<ApiResponse<GroupStandings>>(`/admin/groups/contests/${contestId}/standings`)
      .then(r => r.data),

  /** Rebuilds from the judge. Slow by nature — one rate-limited call per member on Codeforces. */
  refreshStandings: (contestId: number) =>
    apiClient.post<ApiResponse<GroupStandings>>(
      `/admin/groups/contests/${contestId}/standings/refresh`, undefined, { timeout: 180_000 })
      .then(r => r.data),

  violations: (contestId: number, limit = 200) =>
    apiClient.get<ApiResponse<ViolationFeed>>(
      `/admin/groups/contests/${contestId}/violations`, { params: { limit } }).then(r => r.data),
}

/**
 * Attaching contestants' DOMjudge accounts.
 *
 * Admin-only, and deliberately not something a contestant can do for themselves: an account
 * they had the password to could be a teammate's, and every submission made afterwards would
 * be attributed to that team by the judge itself.
 *
 * Nothing here ever reads a password back. `status` reports the username and the resolved
 * team so an admin can confirm they wired up the right one; re-attaching is how a wrong or
 * changed password is corrected.
 */
export const adminDomjudgeApi = {
  status: (userId: number) =>
    apiClient.get<ApiResponse<DomjudgeAccount>>(`/admin/domjudge/credentials/${userId}`)
      .then(r => r.data),

  teams: () =>
    apiClient.get<ApiResponse<DomjudgeTeamOption[]>>('/admin/domjudge/teams')
      .then(r => r.data),

  attach: (body: {
    userId: number; username: string; password: string; name?: string; teamId?: string
  }) =>
    apiClient.post<ApiResponse<DomjudgeAccount>>('/admin/domjudge/credentials', body)
      .then(r => r.data),

  detach: (userId: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/domjudge/credentials/${userId}`)
      .then(r => r.data),
}
