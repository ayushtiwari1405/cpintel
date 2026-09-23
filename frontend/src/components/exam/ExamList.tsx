import { formatDistanceToNow } from 'date-fns'
import { ArrowRight, CalendarClock, Eye, FileText, Loader2, ShieldCheck } from 'lucide-react'
import { clsx } from 'clsx'

import type { EventSummary } from '@/types'

interface Props {
  exams: EventSummary[] | undefined
  isLoading: boolean
  onEnter: (examId: number) => void
  entering: number | null
}

/**
 * The examinations this candidate has been given.
 *
 * <p>Written to answer the three questions somebody has while standing in front of it: is this
 * mine, when does it start, and what happens when I go in. The last of those is why monitoring
 * is stated on the card rather than discovered on entry — being watched is not a detail to find
 * out about afterwards.
 */
export function ExamList({ exams, isLoading, onEnter, entering }: Props) {
  if (isLoading) {
    return (
      <div className="flex items-center justify-center gap-2 py-10 text-sm text-gray-500">
        <Loader2 size={14} className="animate-spin" /> Looking for your examinations…
      </div>
    )
  }

  if (!exams || exams.length === 0) {
    return (
      <div className="rounded-xl border border-gray-800 bg-gray-900/60 px-5 py-8 text-center">
        <FileText size={22} className="mx-auto text-gray-700" />
        <p className="mt-3 text-sm text-gray-400">No examinations are assigned to you.</p>
        <p className="mx-auto mt-1 max-w-md text-xs leading-relaxed text-gray-600">
          One appears here as soon as it is set for you or for a team you are on. This page
          checks by itself, so it is safe to leave open while you wait.
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-2">
      {exams.map(exam => {
        // An ended paper opens too, onto the code this candidate submitted into it. Only a
        // draft is genuinely nothing to open — and a candidate is never shown one.
        const openable = exam.lifecycle !== 'DRAFT'
        const over = exam.lifecycle === 'ENDED' || exam.lifecycle === 'ARCHIVED'
        const label = exam.lifecycle === 'ACTIVE' ? 'Enter' : over ? 'Your code' : 'Open'
        return (
          <article
            key={exam.eventId}
            className="rounded-xl border border-gray-800 bg-gray-900 p-4"
          >
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <h3 className="truncate text-sm font-medium text-gray-200">{exam.name}</h3>
                  <LifecyclePill lifecycle={exam.lifecycle} />
                </div>
                {exam.description && (
                  <p className="mt-1 text-xs leading-relaxed text-gray-500">{exam.description}</p>
                )}

                <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs
                  text-gray-500">
                  <span className="flex items-center gap-1.5">
                    <CalendarClock size={12} />
                    {when(exam)}
                  </span>
                  {exam.durationSeconds != null && (
                    <span>{Math.round(exam.durationSeconds / 60)} minutes</span>
                  )}
                  {exam.problemCount > 0 && (
                    <span>{exam.problemCount} problem{exam.problemCount === 1 ? '' : 's'}</span>
                  )}
                  {exam.lockdownRequired && !over && (
                    <span className="flex items-center gap-1.5 text-indigo-300">
                      <Eye size={12} />
                      monitored · {exam.awayThresholdSeconds}s away allowed
                    </span>
                  )}
                </div>
              </div>

              <button
                onClick={() => onEnter(exam.eventId)}
                disabled={!openable || entering === exam.eventId}
                className={clsx(
                  'flex items-center gap-1.5 rounded-lg px-3 py-2 text-sm font-medium',
                  'transition-colors disabled:cursor-not-allowed disabled:opacity-40',
                  exam.lifecycle === 'ACTIVE'
                    ? 'bg-indigo-600 text-white hover:bg-indigo-500'
                    : 'border border-gray-800 bg-gray-900 text-gray-300 hover:bg-gray-800'
                )}
              >
                {entering === exam.eventId
                  ? <Loader2 size={14} className="animate-spin" />
                  : <ArrowRight size={14} />}
                {label}
              </button>
            </div>

            {over && (
              <p className="mt-3 flex items-start gap-2 rounded-lg border border-gray-800
                bg-gray-950/60 px-3 py-2 text-xs leading-relaxed text-gray-400">
                <FileText size={13} className="mt-0.5 flex-shrink-0" />
                <span>
                  This paper is over. Opening it shows the code you submitted — not your
                  marks, which are published separately if they are published at all.
                </span>
              </p>
            )}

            {exam.lockdownRequired && !over && (
              <p className="mt-3 flex items-start gap-2 rounded-lg border border-indigo-900
                bg-indigo-950/40 px-3 py-2 text-xs leading-relaxed text-indigo-200">
                <ShieldCheck size={13} className="mt-0.5 flex-shrink-0" />
                <span>
                  While this paper is open, the examination watches whether its window has
                  focus. You are warned after {exam.awayThresholdSeconds} seconds away, and
                  absences past that are recorded. Nothing here decides anything on its own —
                  an invigilator reads what happened.
                </span>
              </p>
            )}
          </article>
        )
      })}
    </div>
  )
}

function when(exam: EventSummary): string {
  if (!exam.startsAt) return 'No time set yet'
  const start = new Date(exam.startsAt)
  if (exam.lifecycle === 'ACTIVE' && exam.endsAt) {
    return `ends ${formatDistanceToNow(new Date(exam.endsAt), { addSuffix: true })}`
  }
  return `${exam.lifecycle === 'ENDED' ? 'ran' : 'starts'} `
    + formatDistanceToNow(start, { addSuffix: true })
}

function LifecyclePill({ lifecycle }: { lifecycle: EventSummary['lifecycle'] }) {
  const tone = {
    DRAFT:     'bg-gray-800 text-gray-400',
    SCHEDULED: 'bg-amber-900/40 text-amber-400',
    ACTIVE:    'bg-green-900/40 text-green-400',
    ENDED:     'bg-gray-800 text-gray-400',
    ARCHIVED:  'bg-gray-800 text-gray-500',
  }[lifecycle]

  const label = {
    DRAFT: 'draft', SCHEDULED: 'scheduled', ACTIVE: 'live', ENDED: 'ended', ARCHIVED: 'archived',
  }[lifecycle]

  return (
    <span className={clsx(
      'inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium', tone)}>
      {label}
    </span>
  )
}
