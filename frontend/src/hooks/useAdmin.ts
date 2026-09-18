import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi, type AuditQuery, type CreateUserBody, type UserQuery } from '@/api/adminApi'
import { useToast } from '@/components/common/Toaster'
import { useAuth } from '@/contexts/AuthContext'
import { isAdmin as hasConsole, isSuperAdmin } from '@/utils/roles'
import type { AssignableRole } from '@/types'

/**
 * Data for the admin console.
 *
 * Every query here is gated on the caller actually being an admin, so a regular user who
 * lands on one of these routes never fires a request that is only going to come back 403.
 */
export function useIsAdmin() {
  const { user } = useAuth()
  // Both console tiers, not just ADMIN. Comparing against the one string would have left a
  // super admin — the most privileged account there is — with every query on these screens
  // disabled, and a console that renders empty for the only person who can fix it.
  return hasConsole(user)
}

/** Gates the two controls only a super admin may use: assigning roles, and creating accounts. */
export function useIsSuperAdmin() {
  const { user } = useAuth()
  return isSuperAdmin(user)
}

export function useAdminOverview() {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'overview'],
    queryFn: () => adminApi.overview().then(r => r.data),
    enabled: isAdmin,
    // Counts on a status screen go stale quietly; a short window keeps a returning tab
    // honest without turning the page into a poller.
    staleTime: 30_000,
  })
}

export function useAdminUsers(query: UserQuery) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'users', query],
    queryFn: () => adminApi.users(query).then(r => r.data),
    enabled: isAdmin,
    placeholderData: previous => previous,
  })
}

export function useAdminUser(userId: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'user', userId],
    queryFn: () => adminApi.user(userId!).then(r => r.data),
    enabled: isAdmin && userId != null,
  })
}

export function useAdminAudit(query: AuditQuery) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'audit', query],
    queryFn: () => adminApi.audit(query).then(r => r.data),
    enabled: isAdmin,
    placeholderData: previous => previous,
  })
}

/**
 * Anything that changes an account.
 *
 * The whole admin cache is invalidated rather than one key, because a single change shows up
 * in several places at once — the row, the detail panel, the overview counts, and the audit
 * trail that just gained an entry for it.
 */
function useAdminMutation<TArgs, TResult>(
  fn: (args: TArgs) => Promise<TResult>,
  success: (args: TArgs, result: TResult) => string,
) {
  const queryClient = useQueryClient()
  const toast = useToast()

  return useMutation({
    mutationFn: fn,
    onSuccess: (result, args) => {
      queryClient.invalidateQueries({ queryKey: ['admin'] })
      toast.push('success', success(args, result))
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'That change did not go through')
    },
  })
}

export function useCreateUser() {
  return useAdminMutation(
    (body: CreateUserBody) => adminApi.create(body),
    body => `Created ${body.username}. Give them the password you set — they can change it `
      + 'from their profile.',
  )
}

export function useSetUserRole() {
  return useAdminMutation(
    ({ userId, role }: { userId: number; role: AssignableRole }) =>
      adminApi.setRole(userId, role),
    ({ role }) => role === 'ADMIN'
      ? 'Promoted to admin — they have console access now'
      : 'Demoted to a regular user',
  )
}

export function useSetUserActive() {
  return useAdminMutation(
    ({ userId, active, reason }: { userId: number; active: boolean; reason?: string }) =>
      adminApi.setActive(userId, active, reason),
    ({ active }) => active
      ? 'Account reactivated — they can sign in again'
      : 'Account deactivated and signed out everywhere',
  )
}

export function useRevokeSessions() {
  return useAdminMutation(
    ({ userId }: { userId: number }) => adminApi.revokeSessions(userId),
    () => 'Signed out of every device',
  )
}

// ------------------------------------------------------- contest file policy

export function useContestFilePolicy() {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'file-policy'],
    queryFn: () => adminApi.policy().then(r => r.data),
    enabled: isAdmin,
  })
}

export function useSetPolicyDefault() {
  return useAdminMutation(
    ({ enabled, note }: { enabled: boolean; note?: string }) =>
      adminApi.setDefault(enabled, note),
    ({ enabled }) => enabled
      ? 'Contests allow personal files by default again'
      : 'Personal files are now off by default for every contest',
  )
}

export function useClearPolicyDefault() {
  return useAdminMutation<void, unknown>(
    () => adminApi.clearDefault(),
    () => 'Back to the default from configuration',
  )
}

export function useSetContestRule() {
  return useAdminMutation(
    ({ platform, contestId, enabled, note }:
      { platform: string; contestId: string; enabled: boolean; note?: string }) =>
      adminApi.setRule(platform, contestId, enabled, note),
    ({ contestId, enabled }) => enabled
      ? `Contest ${contestId} allows personal files`
      : `Personal files are off for contest ${contestId}`,
  )
}

export function useClearContestRule() {
  return useAdminMutation(
    ({ platform, contestId }: { platform: string; contestId: string }) =>
      adminApi.clearRule(platform, contestId),
    ({ contestId }) => `Contest ${contestId} follows the default again`,
  )
}
