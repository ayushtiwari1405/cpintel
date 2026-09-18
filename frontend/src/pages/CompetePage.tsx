import { useEffect, useMemo, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Clock, Eye, FileText, FolderOpen, ListOrdered, Loader2, Send } from 'lucide-react'

import { CfSessionCard } from '@/components/practice/CfSessionCard'
import { StatementView } from '@/components/practice/StatementView'
import { ContestHeader } from '@/components/compete/ContestHeader'
import { ContestLoader } from '@/components/compete/ContestLoader'
import { ProblemNav } from '@/components/compete/ProblemNav'
import { FilesPanel } from '@/components/compete/FilesPanel'
import { SubmissionsList } from '@/components/compete/SubmissionsList'
import { EditorPane } from '@/components/workspace/EditorPane'
import { SplitPane } from '@/components/workspace/SplitPane'
import { TabStrip } from '@/components/workspace/TabStrip'

import {
  useContest, useContestLanguages, useContestRank, useContestStatement,
  useContestSubmissions, useContestSubmit, useLoadContest, useStatementPdf, useTicker,
} from '@/hooks/useCompete'
import { useCfSession } from '@/hooks/usePractice'
import { useLockdown } from '@/hooks/useLockdown'
import { useAwayMonitor } from '@/hooks/useAwayMonitor'
import { useActiveGroupContest } from '@/hooks/useGroups'
import { useViolationReporter } from '@/hooks/useViolationReporter'
import type { CompetePlatform, ContestRef, VerdictResponse } from '@/types'

/** Survives a refresh mid-contest, which matters when the clock is running. */
const STORAGE_KEY = 'cpintel.compete.contest'

/** What the old single-judge build stored: a bare Codeforces contest number. */
const LEGACY_STORAGE_KEY = 'cpintel.compete.contestId'

/**
 * The contest this browser was last in, across a refresh.
 *
 * Reads the old key as well, so someone who reloads mid-round after an upgrade lands back in
 * their contest rather than on an empty picker. A bare number can only ever have been a
 * Codeforces id, which is what makes that migration unambiguous.
 */
function restoreContest(): ContestRef | null {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) {
      const parsed = JSON.parse(saved)
      if (parsed?.platform && parsed?.id) return parsed as ContestRef
    }
    const legacy = localStorage.getItem(LEGACY_STORAGE_KEY)
    if (legacy && Number(legacy)) return { platform: 'CODEFORCES', id: legacy }
  } catch {
    // A corrupt entry is not worth failing the page over; start from the picker.
  }
  return null
}

export default function CompetePage() {
  const qc = useQueryClient()

  const [contestRef, setContestRef] = useState<ContestRef | null>(restoreContest)
  const [selected, setSelected] = useState<string | null>(null)
  const [languageId, setLanguageId] = useState('')
  const [filesOpen, setFilesOpen] = useState(false)
  const [tab, setTab] = useState<'description' | 'problems' | 'submissions'>('description')
  // Each problem keeps its own buffer — switching tabs must not lose work.
  const [sources, setSources] = useState<Record<string, string>>({})

  const { data: session } = useCfSession()
  const load = useLoadContest()

  /**
   * Whether this judge needs a per-user credential before anything works.
   *
   * Only Codeforces does. A DOMjudge round is submitted on the contestant's behalf by the
   * deployment's own admin account, so there is nothing for them to connect and the session
   * card must not be shown — offering it would ask 200 people to solve a problem they do not
   * have.
   */
  const needsCfSession = (contestRef?.platform ?? 'CODEFORCES') === 'CODEFORCES'
  const credentialsReady = !needsCfSession || !!session?.connected

  const { data: contest, dataUpdatedAt } = useContest(contestRef ?? undefined)
  const started = !!contest && contest.phase !== 'BEFORE'

  const { data: statement, isLoading: statementLoading } =
    useContestStatement(contestRef ?? undefined, selected ?? undefined, started)
  const { url: statementPdfUrl, error: statementPdfError } = useStatementPdf(
    contestRef ?? undefined, selected ?? undefined,
    started && !!statement?.statementPdfUrl)
  const { data: languages } = useContestLanguages(contestRef ?? undefined, started)
  const { data: submissions, isFetching: submissionsFetching } =
    useContestSubmissions(contestRef ?? undefined, credentialsReady)
  const { data: rank } = useContestRank(contestRef ?? undefined,
    credentialsReady && started)
  const submit = useContestSubmit(contestRef ?? undefined)

  // Tick once a second so the countdown moves between contest refetches.
  useTicker(!!contest && (contest.phase === 'BEFORE' || contest.running))
  const elapsed = dataUpdatedAt ? (Date.now() - dataUpdatedAt) / 1000 : 0

  /**
   * The desktop lock, held for exactly as long as the round is live.
   *
   * Tied to `contest.running` rather than to a button: there is no moment where someone has to
   * remember to switch it on, and no moment where they can quietly switch it off. It releases
   * itself when the contest ends, when the contest is closed, and when this page unmounts.
   * In the browser build this does nothing and returns null.
   */
  const desktopMonitor = useLockdown({
    active: !!contest?.running,
    reason: contest?.name ?? 'Contest',
  })

  /**
   * The browser build watches the tab instead.
   *
   * Weaker — a page can be throttled in a background tab, and closed outright — which is why a
   * contest sat this way also reports that no desktop monitor was present. But leaving the
   * contest is leaving it either way, and saying nothing at all in the browser would make the
   * web build look like the clean way to sit a monitored round.
   */
  const browserMonitor = useAwayMonitor(!!contest?.running)
  const lockdown = desktopMonitor ?? browserMonitor

  /**
   * Whether this round is being run for a group an admin is watching.
   *
   * The compete page only knows which Codeforces contest is open — it has no idea a group was
   * laid over it — so the server is asked. Answers null for ordinary practice, which is the
   * common case, and then nothing is reported anywhere.
   */
  const { data: groupContest } = useActiveGroupContest(
    contestRef?.platform ?? 'CODEFORCES', contestRef?.id ?? null)

  useViolationReporter({
    contestId: groupContest?.lockdownRequired ? groupContest.contestId : null,
    active: !!contest?.running,
    lockdown,
  })

  const rows = useMemo(() => submissions ?? [], [submissions])

  useEffect(() => {
    if (contestRef) localStorage.setItem(STORAGE_KEY, JSON.stringify(contestRef))
    else localStorage.removeItem(STORAGE_KEY)
    // The old key is cleared either way, so the migration above runs exactly once.
    localStorage.removeItem(LEGACY_STORAGE_KEY)
  }, [contestRef])

  // Land on the first problem as soon as Codeforces publishes the list.
  useEffect(() => {
    if (!selected && contest?.problems?.length) setSelected(contest.problems[0].index)
  }, [contest, selected])

  useEffect(() => {
    if (!languageId && languages?.length) setLanguageId(languages[0].id)
  }, [languages, languageId])

  // A resolved verdict is the one event that can move the rank, so re-read it then rather
  // than polling standings on a fast timer.
  const finishedCount = rows.filter(s => s.finished).length
  useEffect(() => {
    if (contestRef && finishedCount > 0) {
      qc.invalidateQueries({
        queryKey: ['compete', 'rank', contestRef.platform, contestRef.id],
      })
    }
  }, [finishedCount, contestRef, qc])

  const handleLoad = (platform: CompetePlatform, url: string) => {
    load.mutate({ platform, url }, {
      onSuccess: (res) => {
        setContestRef({ platform, id: res.data.id })
        setSelected(null)
        setSources({})
        setTab('description')
      },
    })
  }

  const closeContest = () => {
    setContestRef(null)
    setSelected(null)
    setSources({})
    setLanguageId('')
  }

  if (!contestRef || !contest) {
    return (
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="mx-auto flex max-w-3xl flex-col gap-4 p-6">
          {needsCfSession && !session?.connected && <CfSessionCard />}
          <ContestLoader onLoad={handleLoad} loading={load.isPending} />
          {contestRef && !contest && (
            <div className="flex items-center justify-center gap-2 text-sm text-gray-500">
              <Loader2 size={14} className="animate-spin" /> Loading contest {contestRef.id}…
            </div>
          )}

          {/* Uploading is a before-the-round job, so the library is reachable from here too. */}
          <button
            onClick={() => setFilesOpen(true)}
            className="mx-auto flex items-center gap-1.5 text-xs text-gray-600
                       hover:text-indigo-300 transition-colors"
          >
            <FolderOpen size={13} /> Your files
          </button>

          <FilesPanel open={filesOpen} onClose={() => setFilesOpen(false)} />
        </div>
      </div>
    )
  }

  // The newest submission for the open problem drives the verdict chip in the console.
  const latest = rows.find(s => s.index === selected)
  const verdict: VerdictResponse | null = latest ? {
    submissionId: latest.id,
    verdict: latest.verdict,
    passedTestCount: latest.passedTestCount ?? undefined,
    timeConsumedMillis: latest.timeConsumedMillis ?? undefined,
    memoryConsumedBytes: latest.memoryConsumedBytes ?? undefined,
    finished: latest.finished,
  } : null

  const source = selected ? (sources[selected] ?? '') : ''
  const pendingCount = rows.filter(s => !s.finished).length

  const leftPane = (
    <div className="flex flex-col flex-1 min-w-0 min-h-0 bg-gray-950">
      <TabStrip
        tabs={[
          { id: 'description', label: 'Description', icon: FileText },
          {
            id: 'problems',
            label: 'Problems',
            icon: ListOrdered,
            badge: contest.problems.length > 0
              ? <span className="text-[10px] text-gray-600">{contest.problems.length}</span>
              : null,
          },
          {
            id: 'submissions',
            label: 'Submissions',
            icon: Send,
            // A spinner here is the only sign that something is still being judged once the
            // console has moved on to the next problem.
            badge: pendingCount > 0
              ? <Loader2 size={11} className="animate-spin" />
              : rows.length > 0
                ? <span className="text-[10px] text-gray-600">{rows.length}</span>
                : null,
          },
        ]}
        active={tab}
        onChange={id => setTab(id as typeof tab)}
      />

      {/* Each tab owns its own scrolling. A scrollbar on this wrapper as well would give the
          statement two, nested one inside the other. */}
      <div className="flex-1 min-h-0">
        {tab === 'description' && (
          <div className="h-full px-4 py-3">
            <StatementView
              problem={statement}
              isLoading={statementLoading}
              pdfUrl={statementPdfUrl}
              pdfError={statementPdfError}
            />
          </div>
        )}

        {tab === 'problems' && (
          <div className="h-full overflow-y-auto p-3">
            <ProblemNav
              problems={contest.problems}
              selected={selected}
              onSelect={index => { setSelected(index); setTab('description') }}
              submissions={rows}
            />
          </div>
        )}

        {tab === 'submissions' && (
          <div className="h-full min-h-0">
            <SubmissionsList submissions={rows} isLoading={submissionsFetching} />
          </div>
        )}
      </div>
    </div>
  )

  return (
    <div className="flex flex-col flex-1 min-h-0">
      {needsCfSession && !session?.connected && (
        <div className="px-4 pt-3 flex-shrink-0">
          <CfSessionCard />
        </div>
      )}

      {/* Someone being watched is told so, on the screen where it is happening. A lock that
          reports on a contestant without saying so is a different and much worse product. */}
      {groupContest?.lockdownRequired && (
        <div className="mx-4 mt-3 flex items-start gap-2 rounded-lg border border-indigo-900
          bg-indigo-950/40 px-3 py-2 text-xs text-indigo-200 flex-shrink-0">
          <Eye size={13} className="mt-0.5 flex-shrink-0" />
          <span>
            This round counts for <span className="font-medium">{groupContest.groupName}</span>.
            Leaving this window is noticed: you are warned after
            {' '}{Math.round((lockdown?.warnAfterMs ?? 10_000) / 1000)} seconds away, and
            absences past that are reported to whoever is running the group.
          </span>
        </div>
      )}

      <div className="px-4 pt-3 pb-3 flex-shrink-0">
        <ContestHeader
          contest={contest}
          elapsed={elapsed}
          rank={rank}
          onClose={closeContest}
          onOpenFiles={contest.personalFilesEnabled ? () => setFilesOpen(true) : undefined}
          lockdown={lockdown}
        />
      </div>

      {/* Reads go through the contest, so the rule for this contest is what answers. */}
      <FilesPanel
        open={filesOpen && contest.personalFilesEnabled}
        onClose={() => setFilesOpen(false)}
        contest={contestRef}
        // Inserted above whatever is in the buffer rather than over it: a template is
        // usually the first thing in a file, and nothing typed during a contest may be lost
        // to a mis-click.
        onUseInEditor={selected
          ? text => setSources(prev => {
              const current = prev[selected] ?? ''
              return { ...prev, [selected]: current ? `${text}\n${current}` : text }
            })
          : undefined}
      />

      {!started ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 border-t
          border-gray-800 text-center">
          <Clock size={28} className="text-indigo-400" />
          <div className="text-sm text-gray-300">This contest has not started yet.</div>
          <p className="max-w-md text-xs leading-relaxed text-gray-600">
            The statements, editor and submissions all appear here the moment the judge moves
            it to running — this page is watching for that, so leave it open.
            {needsCfSession
              ? ' Make sure you are registered on Codeforces before then.'
              : ' Your team is already registered on the judge; nothing else to do.'}
          </p>
        </div>
      ) : (
        <>
          {!contest.submissionsOpen && contest.submissionsClosedReason && (
            <p className="px-4 pb-2 text-xs text-yellow-500/90 flex-shrink-0">
              {contest.submissionsClosedReason}
            </p>
          )}

          <SplitPane
            className="flex-1 border-t border-gray-800"
            storageKey="cpintel.compete.split"
            initial={45}
            min={25}
            max={70}
            first={leftPane}
            second={
              <EditorPane
                languages={languages ?? []}
                languageId={languageId}
                onLanguageChange={setLanguageId}
                source={source}
                onSourceChange={value => {
                  if (selected) setSources(prev => ({ ...prev, [selected]: value }))
                }}
                onSubmit={() => {
                  if (selected && source.trim()) {
                    submit.mutate({ index: selected, languageId, source })
                  }
                }}
                submitting={submit.isPending}
                polling={!!latest && !latest.finished}
                verdict={verdict}
                canSubmit={contest.submissionsOpen}
                problemSelected={!!selected}
                samples={statement?.samples ?? []}
                problemKey={selected ? `${contestRef.id}${selected}` : undefined}
                contest={contestRef}
                problemIndex={selected ?? undefined}
                storageKey="cpintel.compete"
                submitLabel="Submit"
              />
            }
          />
        </>
      )}
    </div>
  )
}
