import { useState } from 'react'
import { formatDistanceToNow } from 'date-fns'
import { Code2, FileText, Loader2 } from 'lucide-react'
import { clsx } from 'clsx'

import { useMyExamSubmission, useMyExamSubmissions } from '@/hooks/useExams'
import type { MyExam } from '@/types'

/**
 * Reading your own code back from a paper that is over.
 *
 * <p>Deliberately just that. Not the marks, not the verdicts of anybody else, not the test
 * data — those belong to the judge and to whoever decides to publish results, and a review
 * screen that quietly became a results screen would take that decision away from them. The
 * verdict shown against each row is the one the judge already told this candidate at the time.
 *
 * <p>The list carries no source; a paper with sixty attempts on it would otherwise be a
 * megabyte of code nobody asked to read yet. Opening one fetches it.
 */
export function PastExamCode({ exam }: { exam: MyExam }) {
  const [openId, setOpenId] = useState<string | null>(null)
  const { data: attempts, isLoading } = useMyExamSubmissions(exam.event.eventId)
  const { data: opened, isFetching } = useMyExamSubmission(exam.event.eventId, openId)

  if (isLoading) {
    return (
      <div className="flex items-center justify-center gap-2 py-10 text-sm text-gray-500">
        <Loader2 size={14} className="animate-spin" /> Looking for what you submitted…
      </div>
    )
  }

  if (!attempts || attempts.length === 0) {
    return (
      <div className="rounded-xl border border-gray-800 bg-gray-900/60 px-5 py-8 text-center">
        <FileText size={22} className="mx-auto text-gray-700" />
        <p className="mt-3 text-sm text-gray-400">
          Nothing of yours was recorded for this examination.
        </p>
        <p className="mx-auto mt-1 max-w-md text-xs leading-relaxed text-gray-600">
          Only submissions you sent from CPIntel are kept here. Anything sent straight to the
          judge is on the judge.
        </p>
      </div>
    )
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)]">
      <div className="space-y-1.5">
        <p className="px-1 text-xs uppercase tracking-wider text-gray-600">
          {attempts.length} submission{attempts.length === 1 ? '' : 's'}
        </p>
        {attempts.map(attempt => (
          <button
            key={attempt.id}
            onClick={() => setOpenId(attempt.id)}
            className={clsx(
              'w-full rounded-lg border px-3 py-2.5 text-left transition-colors',
              openId === attempt.id
                ? 'border-indigo-700 bg-indigo-950/40'
                : 'border-gray-800 bg-gray-900 hover:bg-gray-800/60'
            )}
          >
            <div className="flex items-center justify-between gap-2">
              <span className="text-sm font-medium text-gray-200">
                {attempt.problemLabel ?? '—'}
                {attempt.problemName && (
                  <span className="ml-1.5 font-normal text-gray-500">{attempt.problemName}</span>
                )}
              </span>
              <VerdictPill verdict={attempt.verdict} />
            </div>
            <div className="mt-1 flex flex-wrap items-center gap-x-3 text-xs text-gray-600">
              <span>
                {formatDistanceToNow(new Date(attempt.submittedAt), { addSuffix: true })}
              </span>
              {attempt.languageLabel && <span>{attempt.languageLabel}</span>}
              {attempt.sourceBytes != null && <span>{attempt.sourceBytes} B</span>}
            </div>
          </button>
        ))}
      </div>

      <div className="rounded-xl border border-gray-800 bg-gray-950">
        {openId == null ? (
          <div className="flex h-full min-h-[240px] flex-col items-center justify-center
            px-6 text-center">
            <Code2 size={22} className="text-gray-700" />
            <p className="mt-3 text-sm text-gray-500">Pick a submission to read it back.</p>
          </div>
        ) : isFetching && !opened?.source ? (
          <div className="flex h-full min-h-[240px] items-center justify-center gap-2
            text-sm text-gray-500">
            <Loader2 size={14} className="animate-spin" /> Fetching your code…
          </div>
        ) : (
          <>
            <div className="flex flex-wrap items-center justify-between gap-2 border-b
              border-gray-800 px-4 py-2.5">
              <span className="text-xs text-gray-400">
                {opened?.problemLabel ?? '—'} · {opened?.languageLabel ?? 'source'}
              </span>
              <VerdictPill verdict={opened?.verdict ?? null} />
            </div>
            <pre className="max-h-[60vh] overflow-auto px-4 py-3 text-xs leading-relaxed
              text-gray-300">
              <code>{opened?.source ?? ''}</code>
            </pre>
          </>
        )}
      </div>
    </div>
  )
}

/**
 * The verdict the judge gave at the time.
 *
 * Shown because it was already shown to this candidate during the paper — it is a fact about
 * their own submission, not a mark. Anything that is not plainly accepted or plainly rejected
 * stays grey rather than being guessed at.
 */
function VerdictPill({ verdict }: { verdict: string | null }) {
  if (!verdict) return null
  const value = verdict.toUpperCase()
  const tone = value.includes('ACCEPT') || value === 'AC'
    ? 'bg-green-900/40 text-green-400'
    : value.includes('SUBMIT') || value.includes('PEND') || value.includes('QUEUE')
      ? 'bg-gray-800 text-gray-400'
      : 'bg-red-900/40 text-red-400'

  return (
    <span className={clsx(
      'inline-flex flex-shrink-0 items-center rounded-full px-2 py-0.5 text-[11px] font-medium',
      tone)}>
      {verdict.toLowerCase()}
    </span>
  )
}
