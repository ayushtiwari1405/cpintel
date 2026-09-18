import { useQuery } from '@tanstack/react-query'

import { archiveApi } from '@/api/archiveApi'
import type { ArchiveAttempt, CompetePlatform } from '@/types'

/**
 * Every attempt at one problem.
 *
 * Refetched on mount rather than served stale: the list gains a row every time the user
 * submits, and a history panel that has not noticed the last submission is worse than no
 * panel at all.
 */
export function useProblemAttempts(platform: CompetePlatform, contestId?: string,
                                   index?: string, enabled = true) {
  return useQuery({
    queryKey: ['archive', 'problem', platform, contestId, index],
    queryFn: () => archiveApi.forProblem(platform, contestId!, index!).then(r => r.data),
    enabled: !!contestId && !!index && enabled,
    staleTime: 15_000,
  })
}

/** Everything archived for this user — the "reuse an old solution" view. */
export function useRecentAttempts(enabled = true, limit = 50) {
  return useQuery({
    queryKey: ['archive', 'recent', limit],
    queryFn: () => archiveApi.recent(limit).then(r => r.data),
    enabled,
    staleTime: 60_000,
  })
}

/**
 * The source of one attempt.
 *
 * Which endpoint answers depends on where the code lives, which is exactly what the caller
 * should not have to think about: an archived row is a local read, anything else is fetched
 * from Codeforces and archived on the way through, so the second open of the same submission
 * is local too.
 *
 * Cached indefinitely because a submission's source cannot change — the query key identifies
 * an immutable thing.
 */
export function useAttemptSource(attempt: ArchiveAttempt | null) {
  const key = attempt
    ? attempt.id ?? `cf:${attempt.contestId}:${attempt.externalId}`
    : null

  return useQuery({
    queryKey: ['archive', 'source', key],
    queryFn: () => {
      if (attempt!.id) return archiveApi.source(attempt!.id).then(r => r.data)
      return archiveApi
        .codeforcesSource(attempt!.contestId!, attempt!.externalId!)
        .then(r => r.data)
    },
    enabled: !!attempt && (!!attempt.id || (!!attempt.contestId && !!attempt.externalId)),
    staleTime: Infinity,
    gcTime: 30 * 60 * 1000,
    retry: false,
  })
}

/**
 * The judge's per-test results for one archived submission.
 *
 * Enabled only when the caller asks, because the first read can cost a fetch from Codeforces.
 * Cached forever afterwards: a finished submission's tests never change.
 */
export function useTestReport(archiveId: string | null, enabled: boolean) {
  return useQuery({
    queryKey: ['archive', 'tests', archiveId],
    queryFn: () => archiveApi.tests(archiveId!).then(r => r.data),
    enabled: !!archiveId && enabled,
    staleTime: Infinity,
    gcTime: 30 * 60 * 1000,
    retry: false,
  })
}
