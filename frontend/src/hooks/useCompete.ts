import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { competeApi, domjudgeApi } from '@/api/competeApi'
import { useToast } from '@/components/common/Toaster'
import type { CompetePlatform, ContestInfo, ContestRef, ContestSubmission } from '@/types'
import { browserLanguages, browserStatement, browserSubmit, cfRelay } from '@/api/cfBrowser'

/**
 * Whether a Codeforces round's pages go through this browser.
 *
 * A hosted server cannot fetch Codeforces' website (its browser check refuses a server), so
 * statements, compilers and submissions for a Codeforces round are fetched by the extension or
 * the desktop app when either is present. DOMjudge is the deployment's own judge and always
 * goes through the server.
 */
const viaBrowser = (ref?: ContestRef) => ref?.platform === 'CODEFORCES' && !!cfRelay()

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
    queryFn: async () => {
      const detail = (await competeApi.statement(ref!, index!)).data
      if (detail.statementAvailable || !viaBrowser(ref)) return detail
      try {
        return await browserStatement(Number(ref!.id), index!, true)
      } catch (e: any) {
        return { ...detail,
          statementIssue: e?.browserCheck ? 'BROWSER_CHECK' as const : 'UNAVAILABLE' as const }
      }
    },
    enabled: !!ref && !!index && enabled,
    staleTime: 1000 * 60 * 60,
  })
}

/**
 * The statement document, as an object URL plus what it actually is.
 *
 * Fetched rather than linked because the arena's routes need the bearer token an iframe would
 * not send. The URL is revoked when the problem changes, so switching between problems for an
 * hour does not accumulate blobs.
 *
 * **The type is read from the response, not assumed.** DOMjudge serves whatever the problem
 * package holds, and a real contest mixes them — of one seven-problem set, four statements
 * were PDFs and three were plain text. The text ones used to be handed to a PDF embed, which
 * renders as an empty white rectangle and is indistinguishable from a statement that failed
 * to load.
 */
export function useStatementPdf(ref?: ContestRef, index?: string, enabled = true) {
  const [url, setUrl] = useState<string | null>(null)
  const [text, setText] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!ref || !index || !enabled) {
      setUrl(null)
      setText(null)
      return
    }

    let revoked = false
    let objectUrl: string | null = null
    setError(null)
    setText(null)

    competeApi.statementPdf(ref, index)
      .then(async blob => {
        if (revoked) return

        // Text is read into a string and rendered by the app; anything else — a PDF, or a
        // type nothing recognises — gets an object URL for the embed to open.
        if ((blob.type || '').startsWith('text/plain')) {
          const body = await blob.text()
          if (!revoked) setText(body)
          return
        }
        objectUrl = URL.createObjectURL(blob)
        setUrl(objectUrl)

        // The embed is for reading; the examples in it are only reachable as text. Best
        // effort — a scanned PDF has none, and the statement is on screen either way.
        if ((blob.type || '').startsWith('application/pdf')) {
          competeApi.statementText(ref, index)
            .then(body => { if (!revoked) setText(body) })
            .catch(() => { /* no examples to seed from; the cases start empty */ })
        }
      })
      .catch((err: any) => {
        if (revoked) return
        // The server's own explanation where there is one: it distinguishes "this problem has
        // no statement" from "this DOMjudge publishes statements somewhere CPIntel did not
        // look", and those need different people to do something about them.
        setError(err?.response?.data?.message
          ?? 'The statement for this problem could not be read from the judge.')
      })

    return () => {
      revoked = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
      setUrl(null)
    }
    // Keyed on the contest's identity, not the ref object, which is rebuilt on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ref?.platform, ref?.id, index, enabled])

  return { url, text, error }
}

export function useContestLanguages(ref?: ContestRef, enabled = true) {
  return useQuery({
    queryKey: ['compete', 'languages', ...keyOf(ref)],
    queryFn: async () => {
      if (viaBrowser(ref)) {
        const scraped = await browserLanguages(Number(ref!.id), true).catch(() => [])
        if (scraped.length > 0) return scraped
      }
      return competeApi.languages(ref!).then(r => r.data)
    },
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
    mutationFn: async (body: { index: string; languageId: string; source: string }) => {
      if (!viaBrowser(ref)) return competeApi.submit(ref!, body)
      const submissionId = await browserSubmit({
        contestId: Number(ref!.id), contest: true, ...body })
      // The submissions list reads the verdict from the public API; this is enough to say so.
      return { data: { id: String(submissionId), index: body.index.toUpperCase() } } as
        Awaited<ReturnType<typeof competeApi.submit>>
    },
    onSuccess: (res) => {
      toast.push('info', `Submitted ${res.data.index ?? ''} to the contest`)
      qc.invalidateQueries({ queryKey: ['compete', 'submissions', ...keyOf(ref)] })
      qc.invalidateQueries({ queryKey: ['archive'] })
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? err.message ?? 'Submission failed')
      qc.invalidateQueries({ queryKey: ['practice', 'cf-session'] })
      qc.invalidateQueries({ queryKey: ['compete', 'contest', ...keyOf(ref)] })
      // Archived before the attempt was made, so it is readable back even now.
      qc.invalidateQueries({ queryKey: ['archive'] })
    },
  })
}

/**
 * How often the open leaderboard re-reads the board.
 *
 * Only while the panel is actually on screen — the hook is disabled otherwise, so a contestant
 * who never opens it costs nothing. On DOMjudge the backend answers from its contest cache, so
 * the whole room watching the board still costs the judge one fetch every few seconds.
 */
const LEADERBOARD_INTERVAL_MS = 30 * 1000

/**
 * The contest's full board.
 *
 * `enabled` is the panel's own visibility rather than the contest's state: a finished contest
 * still has a board worth looking at, and an open panel on a running one should keep moving.
 */
export function useLeaderboard(ref?: ContestRef, enabled = false) {
  return useQuery({
    queryKey: ['compete', 'leaderboard', ...keyOf(ref)],
    queryFn: () => competeApi.leaderboard(ref!).then(r => r.data),
    enabled: !!ref && enabled,
    refetchInterval: enabled ? LEADERBOARD_INTERVAL_MS : false,
  })
}

/** The DOMjudge account an admin attached to this user, if any. */
export function useDomjudgeAccount(enabled = true) {
  return useQuery({
    queryKey: ['domjudge', 'account'],
    queryFn: () => domjudgeApi.account().then(r => r.data),
    enabled,
    staleTime: 1000 * 60 * 5,
  })
}

/**
 * The contests this account may enter.
 *
 * Read from the judge under the contestant's own credentials, so it is DOMjudge's view of what
 * they are registered for. Kept briefly fresh rather than cached hard: an admin adding a team
 * to a contest minutes before it starts is the normal case, not the exception.
 */
export function useDomjudgeContests(enabled = true) {
  return useQuery({
    queryKey: ['domjudge', 'contests'],
    queryFn: () => domjudgeApi.contests().then(r => r.data),
    enabled,
    staleTime: 1000 * 30,
  })
}
