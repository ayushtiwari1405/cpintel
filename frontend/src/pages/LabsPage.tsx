import { Link } from 'react-router-dom'
import { format, formatDistanceToNow } from 'date-fns'
import { clsx } from 'clsx'
import {
  AlertTriangle, CheckCircle2, CircleDashed, Clock, ExternalLink, FlaskConical, Loader2, RefreshCw,
} from 'lucide-react'
import { PageHeader } from '@/components/common/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/common/States'
import { useCheckMyLabs, useMyLabs } from '@/hooks/useLabs'
import type { LabHandle, LabPlatform, MyLab, MyLabProblem } from '@/types'

const PLATFORM_NAME: Record<LabPlatform, string> = { CODEFORCES: 'Codeforces', LEETCODE: 'LeetCode' }

/**
 * The lab assignments set in your classrooms: problems on Codeforces and LeetCode, solved
 * there. CPIntel reads your accepted submissions from your verified handles every few minutes.
 */
export default function LabsPage() {
  const { data, isLoading, isError, error, refetch } = useMyLabs()
  const check = useCheckMyLabs()

  if (isError) return <ErrorState title="Could not load your labs" error={error} onRetry={() => refetch()} />
  if (isLoading || !data) return <LoadingState label="Loading your labs…" />

  const open = data.labs.filter(l => l.state === 'OPEN')
    .sort((a, b) => a.dueAt.localeCompare(b.dueAt))
  const closed = data.labs.filter(l => l.state !== 'OPEN')

  const needs = new Set(data.labs.flatMap(l => l.problems.map(p => p.problem.platform)))

  return (
    <div className="space-y-6">
      <PageHeader title="Labs"
        description="Problems your instructors set on Codeforces and LeetCode. Solve them there; CPIntel picks up your accepted submissions every few minutes."
        actions={data.labs.length > 0 && (
          <button type="button" className="btn-secondary btn-sm" disabled={check.isPending}
            onClick={() => check.mutate()}>
            {check.isPending
              ? <Loader2 size={13} className="animate-spin" aria-hidden="true" />
              : <RefreshCw size={13} aria-hidden="true" />}
            {check.isPending ? 'Checking…' : 'Check for new solves'}
          </button>
        )} />

      <HandleWarnings needs={needs} codeforces={data.codeforces} leetcode={data.leetcode} />

      {data.labs.length === 0 && (
        <EmptyState icon={<FlaskConical size={18} />} title="No labs yet">
          When an instructor sets a lab in one of your classrooms, it appears here once it opens.
        </EmptyState>
      )}

      {open.length > 0 && (
        <section aria-labelledby="labs-open" className="space-y-3">
          <h2 id="labs-open" className="text-xs font-semibold uppercase tracking-wide text-gray-500">Open</h2>
          {open.map(lab => <LabCard key={lab.labId} lab={lab} />)}
        </section>
      )}
      {closed.length > 0 && (
        <section aria-labelledby="labs-closed" className="space-y-3">
          <h2 id="labs-closed" className="text-xs font-semibold uppercase tracking-wide text-gray-500">Past</h2>
          {closed.map(lab => <LabCard key={lab.labId} lab={lab} />)}
        </section>
      )}
    </div>
  )
}

function HandleWarnings({ needs, codeforces, leetcode }: {
  needs: Set<LabPlatform>
  codeforces: LabHandle | null
  leetcode: LabHandle | null
}) {
  const problems: string[] = []
  for (const [platform, handle] of [['CODEFORCES', codeforces], ['LEETCODE', leetcode]] as const) {
    if (!needs.has(platform)) continue
    if (!handle) problems.push(`link your ${PLATFORM_NAME[platform]} handle`)
    else if (!handle.verified) problems.push(`verify your ${PLATFORM_NAME[platform]} handle (${handle.handle})`)
  }
  if (problems.length === 0) return null
  return (
    <div role="status" className="flex items-start gap-2 rounded-xl border border-amber-900/60
      bg-amber-950/30 p-4 text-sm text-amber-200">
      <AlertTriangle size={16} className="mt-0.5 shrink-0" aria-hidden="true" />
      <p>
        Your solves are not being counted yet. To get credit,{' '}
        {problems.join(' and ')} on the <Link to="/platforms" className="font-medium underline">Platforms</Link> page.
      </p>
    </div>
  )
}

function LabCard({ lab }: { lab: MyLab }) {
  const solved = lab.problems.filter(p => p.status === 'SOLVED').length
  const due = new Date(lab.dueAt)
  const isOpen = lab.state === 'OPEN'
  const pct = lab.problems.length ? Math.round((solved / lab.problems.length) * 100) : 0

  return (
    <article className="rounded-xl border border-gray-800 bg-gray-900">
      <header className="flex flex-col gap-3 border-b border-gray-800 px-4 py-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <p className="text-[11px] font-medium uppercase tracking-wide text-gray-500">{lab.classroomName}</p>
          <h3 className="text-base font-semibold text-gray-50">{lab.title}</h3>
          <p className={clsx('mt-0.5 inline-flex items-center gap-1 text-xs',
            isOpen ? 'text-gray-300' : 'text-gray-500')}>
            <Clock size={12} aria-hidden="true" />
            {isOpen ? 'Due ' : 'Closed '}
            {format(due, 'EEE d MMM, HH:mm')} ({formatDistanceToNow(due, { addSuffix: true })})
          </p>
        </div>
        <div className="shrink-0 text-left sm:text-right">
          <p className="font-mono text-lg font-semibold tabular-nums text-gray-50">
            {fmt(lab.marks)}<span className="text-sm text-gray-500"> / {fmt(lab.totalMarks)}</span>
          </p>
          <p className="text-xs text-gray-500">{solved} of {lab.problems.length} solved in time</p>
          <div className="mt-1.5 h-1.5 w-full rounded-full bg-gray-800 sm:w-40" aria-hidden="true">
            <div className="h-full rounded-full bg-accent-500" style={{ width: `${pct}%` }} />
          </div>
        </div>
      </header>

      {lab.description && (
        <p className="whitespace-pre-line border-b border-gray-800 px-4 py-3 text-sm text-gray-400">
          {lab.description}
        </p>
      )}

      <ul className="divide-y divide-gray-800">
        {lab.problems.map(p => <ProblemRow key={p.problem.problemId} item={p} />)}
      </ul>

      <footer className="flex flex-wrap gap-x-4 gap-y-1 px-4 py-2.5 text-[11px] text-gray-500">
        <span>
          {lab.countEarlierSolves
            ? 'Problems you solved before the lab opened count.'
            : `Only solves after ${format(new Date(lab.opensAt), 'd MMM, HH:mm')} count.`}
        </span>
        <span>Late solves are shown but earn no marks.</span>
        {lab.problems.some(p => p.problem.platform === 'LEETCODE') && lab.countEarlierSolves && (
          <span>On LeetCode, only your recent solves are visible: resubmit an old solution if it is not picked up.</span>
        )}
        {lab.lastCheckedAt && (
          <span>Last checked {formatDistanceToNow(new Date(lab.lastCheckedAt), { addSuffix: true })}.</span>
        )}
      </footer>
    </article>
  )
}

function ProblemRow({ item }: { item: MyLabProblem }) {
  const { problem, status, solvedAt } = item
  return (
    <li className="flex items-center gap-3 px-4 py-2.5">
      <StatusIcon status={status} />
      <div className="min-w-0 flex-1">
        <a href={problem.url} target="_blank" rel="noreferrer"
          className="inline-flex max-w-full items-center gap-1 text-sm text-gray-100 hover:text-accent-300">
          <span className="truncate">{problem.title}</span>
          <ExternalLink size={12} className="shrink-0 text-gray-500" aria-hidden="true" />
        </a>
        <p className="text-xs text-gray-500">
          {PLATFORM_NAME[problem.platform]}
          {problem.difficulty && <> · {problem.difficulty}</>}
          {solvedAt && <> · accepted {format(new Date(solvedAt), 'd MMM, HH:mm')}</>}
        </p>
      </div>
      <span className="shrink-0 font-mono text-xs tabular-nums text-gray-400">
        {fmt(problem.marks)} {Number(problem.marks) === 1 ? 'mark' : 'marks'}
      </span>
    </li>
  )
}

function StatusIcon({ status }: { status: MyLabProblem['status'] }) {
  if (status === 'SOLVED') {
    return <CheckCircle2 size={18} className="shrink-0 text-green-400" aria-label="Solved in time" />
  }
  if (status === 'LATE') {
    return <Clock size={18} className="shrink-0 text-amber-400" aria-label="Solved late" />
  }
  return <CircleDashed size={18} className="shrink-0 text-gray-600" aria-label="Not solved" />
}

function fmt(n: number) {
  return Number(n).toLocaleString(undefined, { maximumFractionDigits: 2 })
}
