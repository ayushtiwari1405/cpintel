import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/adminApi'
import { groupsApi } from '@/api/groupsApi'
import { useToast } from '@/components/common/Toaster'
import { useIsAdmin } from '@/hooks/useAdmin'

// ------------------------------------------------------------- participant

export function useMyGroups() {
  return useQuery({
    queryKey: ['groups', 'mine'],
    queryFn: () => groupsApi.myGroups().then(r => r.data),
  })
}

export function useMyGroupContests() {
  return useQuery({
    queryKey: ['groups', 'my-contests'],
    queryFn: () => groupsApi.myContests().then(r => r.data),
  })
}

/**
 * Whether the contest open on the compete page is being run for a group.
 *
 * Answers null for ordinary practice, which is the common case, so this is cheap to leave
 * mounted. The result decides whether the desktop lock has anywhere to report.
 */
export function useActiveGroupContest(platform: string, externalId: string | null) {
  return useQuery({
    queryKey: ['groups', 'active', platform, externalId],
    queryFn: () => groupsApi.active(platform, externalId!).then(r => r.data),
    enabled: !!externalId,
    staleTime: 60_000,
  })
}

// ------------------------------------------------------------------- admin

export function useAdminGroups() {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'groups'],
    queryFn: () => adminApi.groups().then(r => r.data),
    enabled: isAdmin,
  })
}

export function useAdminGroup(groupId: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'group', groupId],
    queryFn: () => adminApi.group(groupId!).then(r => r.data),
    enabled: isAdmin && groupId != null,
  })
}

export function useGroupStandings(contestId: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'standings', contestId],
    queryFn: () => adminApi.standings(contestId!).then(r => r.data),
    enabled: isAdmin && contestId != null,
  })
}

export function useGroupViolations(contestId: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'violations', contestId],
    queryFn: () => adminApi.violations(contestId!).then(r => r.data),
    enabled: isAdmin && contestId != null,
  })
}

/**
 * Anything that changes a group.
 *
 * Invalidates the whole admin cache rather than one key: adding a member changes the group's
 * member count, every contest's standings and the overview's totals at once.
 */
function useGroupMutation<TArgs, TResult>(
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

export function useCreateGroup() {
  return useGroupMutation(
    (body: { name: string; description?: string }) => adminApi.createGroup(body),
    ({ name }) => `Created ${name}`,
  )
}

export function useDeactivateGroup() {
  return useGroupMutation(
    ({ groupId }: { groupId: number }) => adminApi.deactivateGroup(groupId),
    () => 'Group retired — its contests and results are kept',
  )
}

export function useAddMember() {
  return useGroupMutation(
    ({ groupId, userId, externalHandle }:
      { groupId: number; userId: number; externalHandle?: string }) =>
      adminApi.addMember(groupId, { userId, externalHandle }),
    (_args, result: any) => `${result?.data?.username ?? 'They'} joined the group`,
  )
}

/**
 * Preview and commit a pasted roster.
 *
 * Not a `useQuery` despite the preview being a read: it is a read of a *draft*, keyed by text
 * the admin is still editing, and caching those by paste content would fill the cache with
 * every intermediate state of a 200-row textarea.
 */
export function useImportRoster() {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: ({ groupId, text, dryRun }: {
      groupId: number; text: string; dryRun: boolean
    }) => adminApi.importRoster(groupId, { text, dryRun }).then(r => r.data),
    onSuccess: (result, vars) => {
      if (vars.dryRun) return
      qc.invalidateQueries({ queryKey: ['admin', 'group', vars.groupId] })
      qc.invalidateQueries({ queryKey: ['admin', 'groups'] })
      const created = result.rows.filter(r => r.generatedPassword).length
      toast.push('success', created > 0
        ? `Imported ${result.total} rows — ${created} new accounts created`
        : `Imported ${result.total} rows`)
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Could not read that roster')
    },
  })
}

export function useUpdateMember() {
  return useGroupMutation(
    ({ groupId, userId, externalHandle }:
      { groupId: number; userId: number; externalHandle?: string }) =>
      adminApi.updateMember(groupId, userId, { userId, externalHandle }),
    () => 'Handle updated — refresh the standings to use it',
  )
}

export function useRemoveMember() {
  return useGroupMutation(
    ({ groupId, userId }: { groupId: number; userId: number }) =>
      adminApi.removeMember(groupId, userId),
    () => 'Removed from the group',
  )
}

export function useAddGroupContest() {
  return useGroupMutation(
    ({ groupId, ...body }: {
      groupId: number
      platform: string
      externalId: string
      name: string
      url?: string
      startsAt?: string
      endsAt?: string
      lockdownRequired?: boolean
    }) => adminApi.addContest(groupId, body),
    ({ name }) => `${name} is now being tracked for this group`,
  )
}

export function useRemoveGroupContest() {
  return useGroupMutation(
    ({ contestId }: { contestId: number }) => adminApi.removeContest(contestId),
    () => 'Contest removed, along with everything recorded about it',
  )
}

export function useRefreshStandings() {
  return useGroupMutation(
    ({ contestId }: { contestId: number }) => adminApi.refreshStandings(contestId),
    () => 'Standings rebuilt from the judge',
  )
}
