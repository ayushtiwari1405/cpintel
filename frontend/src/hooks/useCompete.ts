import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { competeApi } from '@/api/competeApi'
import { useToast } from '@/components/common/Toaster'
import type { CompetePlatform, ContestInfo, ContestRef, ContestSubmission } from '@/types'

/** How often the live rank is re-read when nothing else prompts it. */
const RANK_INTERVAL_MS = 15 * 60 * 1000

/**
 * How often a DOMjudge rank is re-read.
 *
 * Far more often than the Codeforces one, and affordably so: DOMjudge answers the whole
 * scoreboard in one request that the backend caches for every contestant at once, whereas the
 * Codeforces path has to scrape a standings page per person. The number below is what the
 * contestant sees; what the judge sees is one fetch every few seconds however many people are
 * watching.
 */
const DJ_RANK_INTERVAL_MS = 30 * 1000

/** Re-renders once a second so countdowns tick without refetching anything. */
export function useTicker(active: boolean) {
  const [, setTick] = useState(0)
  useEffect(() => {
    if (!active) return
    const id = setInterval(() => setTick(t => t + 1), 1000)
    return () => clearInterval(id)
  }, [active])
}

export function useLoadContest() {
  const toast = useToast()
  return useMutation({
    mutationFn: ({ platform, url }: { platform: CompetePlatform; url: string }) =>
      competeApi.load(platform, url),
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Could not load that contest')
    },
  })
}

/** The query key for a contest, so every hook here invalidates the same entries. */
const keyOf = (ref?: ContestRef) => [ref?.platform ?? null, ref?.id ?? null]

/**
 * Contest phase and remaining time.
 *
 * Polled on a timer because the page has to notice the moment a contest flips from BEFORE to
 * CODING on its own — the countdown is computed locally, but the phase change is authoritative
 * only from the judge. Faster near the start, slower once running.
 */
export function useContest(ref?: ContestRef) {
  return useQuery({
    queryKey: ['compete', 'contest', ...keyOf(ref)],
    queryFn: () => competeApi.contest(ref!).then(r => r.data),
    enabled: !!ref,
    // While counting down, poll hard enough that the page flips over promptly when the
    // window opens; once running the clock is local so there is little to ask about.
    refetchInterval: (query) => {
      const c = query.state.data as ContestInfo | undefined
      return c && c.phase === 'BEFORE' ? 10_000 : 60_000
    },
  })
}

export function useContestStatement(ref?: ContestRef, index?: string, enabled = true) {
  return useQuery({
    queryKey: ['compete', 'statement', ...keyOf(ref), index],
    queryFn: () => competeApi.statement(ref!, index!).then(r => r.data),
    enabled: !!ref && !!index && enabled,
    staleTime: 1000 * 60 * 60,
  })
}

/**
 * The statement PDF as an object URL, for judges that publish one.
 *
 * Fetched rather than linked because the arena's routes need the bearer token an iframe would
 * not send. The URL is revoked when the problem changes, so switching between problems for an
 * hour does not accumulate blobs.
 */
export function useStatementPdf(ref?: ContestRef, index?: string, enabled = true) {
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!ref || !index || !enabled) {
      setUrl(null)
      return
    }

    let revoked = false
    let objectUrl: string | null = null
    setError(null)

    competeApi.statementPdf(ref, index)
      .then(blob => {
        if (revoked) return
        objectUrl = URL.createObjectURL(blob)
        setUrl(objectUrl)
      })
      .catch(() => {
        if (!revoked) setError('No statement is attached to this problem on the judge.')
      })

    return () => {
      revoked = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
      setUrl(null)
    }
  }, [ref?.platform, ref?.id, index, enabled])

  return { url, error }
}

export function useContestLanguages(ref?: ContestRef, enabled = true) {
  return useQuery({
    queryKey: ['compete', 'languages', ...keyOf(ref)],
    queryFn: () => competeApi.languages(ref!).then(r => r.data),
    enabled: !!ref && enabled,
    staleTime: 1000 * 60 * 60,
  })
}

/**
 * The user's submissions in this contest.
 *
 * Polls every 5s while anything is still judging, then drops back to 60s — the judge is the
 * only thing that changes this list, so there is no reason to keep asking once it is quiet.
 *
 * On DOMjudge this poll is answered entirely from the backend's contest cache, so the cost of
 * 200 contestants polling at 5s is one fetch of the judge every few seconds rather than 200.
 */
export function useContestSubmissions(ref?: ContestRef, enabled = true) {
  return useQuery({
    queryKey: ['compete', 'submissions', ...keyOf(ref)],
    queryFn: () => competeApi.submissions(ref!).then(r => r.data),
    enabled: !!ref && enabled,
    refetchInterval: (query) => {
      const rows = query.state.data as ContestSubmission[] | undefined
      return rows?.some(s => !s.finished) ? 5_000 : 60_000
    },
  })
}

/** Live rank. The page also invalidates it whenever a verdict resolves. */
export function useContestRank(ref?: ContestRef, enabled = true) {
  return useQuery({
    queryKey: ['compete', 'rank', ...keyOf(ref)],
    queryFn: () => competeApi.rank(ref!).then(r => r.data),
    enabled: !!ref && enabled,
    refetchInterval: ref?.platform === 'DOMJUDGE'
      ? DJ_RANK_INTERVAL_MS
      : RANK_INTERVAL_MS,
  })
}

export function useContestSubmit(ref?: ContestRef) {
  const qc = useQueryClient()
  const toast = useToast()

  return useMutation({
    mutationFn: (body: { index: string; languageId: string; source: string }) =>
      competeApi.submit(ref!, body),
    onSuccess: (res) => {
      toast.push('info', `Submitted ${res.data.index ?? ''} to the contest`)
      qc.invalidateQueries({ queryKey: ['compete', 'submissions', ...keyOf(ref)] })
      qc.invalidateQueries({ queryKey: ['archive'] })
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Submission failed')
      qc.invalidateQueries({ queryKey: ['practice', 'cf-session'] })
      qc.invalidateQueries({ queryKey: ['compete', 'contest', ...keyOf(ref)] })
      // Archived before the attempt was made, so it is readable back even now.
      qc.invalidateQueries({ queryKey: ['archive'] })
    },
  })
}
