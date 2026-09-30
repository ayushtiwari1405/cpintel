import { clsx } from 'clsx'
import { AlertCircle, CalendarClock, CheckCircle2, Loader2, Radio } from 'lucide-react'
import { useDomjudgeContests } from '@/hooks/useCompete'
import { useMyClassrooms } from '@/hooks/useClassrooms'
import type { DomjudgeContestSummary } from '@/types'

interface Props {
  onPick: (contestId: string) => void
  loading: boolean
  /** Only this classroom's contests; null shows every classroom the student is in. */
  classroomId?: number | null
}

function startsIn(seconds: number): string {
  if (seconds <= 0) return ''
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (h >= 24) return `in ${Math.round(h / 24)}d`
  if (h > 0) return `in ${h}h ${m}m`
  if (m > 0) return `in ${m}m`
  return 'any moment'
}

function whenOf(contest: DomjudgeContestSummary): string {
  if (contest.running) return 'Running now'
  if (contest.phase === 'FINISHED') {
    return contest.endsAt
      ? `Finished ${new Date(contest.endsAt).toLocaleDateString()}`
      : 'Finished'
  }
  if (!contest.startsAt) return 'Not scheduled'
  return `${new Date(contest.startsAt).toLocaleString([], {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  })} · ${startsIn(contest.secondsUntilStart)}`
}

/**
 * The contests this contestant's DOMjudge account may enter.
 *
 * <p>A list rather than a box to paste a link into, because on DOMjudge the judge already
 * knows the answer. It shows a team the contests it is registered for, so reading that list
 * under the contestant's own credentials gives exactly the rounds they can actually sit — no
 * roster to maintain inside CPIntel, and nothing that can drift out of step with the judge.
 *
 * <p>Finished contests are kept. Somebody opening the page after a round wants to see where
 * they came, and the arena renders a finished contest read-only anyway.
 *
 * <p>A student in several classrooms sees every classroom's judge at once, each read under that
 * classroom's own login and labelled with it, since two judges can both have a "demo".
 */
export function DomjudgeContestPicker({ onPick, loading, classroomId = null }: Props) {
  const { data: classrooms, isLoading: accountLoading } = useMyClassrooms()
  const withLogin = classrooms?.filter(c => c.judgeLoginAttached) ?? []
  const linked = withLogin.length > 0
  const several = (classrooms?.length ?? 0) > 1

  const { data: allContests, isLoading, error } = useDomjudgeContests(linked)
  const contests = allContests?.filter(c => classroomId == null || c.classroomId === classroomId)
  const loginIn = (id: number) => classrooms?.find(c => c.classroomId === id)

  if (accountLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 size={16} className="animate-spin text-gray-600" />
      </div>
    )
  }

  // Nothing to compete as. The contestant cannot fix this themselves — an admin attaches the
  // account — so the message names who to ask rather than offering a form that does not exist.
  if (!linked) {
    return (
      <div className="flex items-start gap-2 rounded-lg border border-yellow-900/60
        bg-yellow-950/20 px-3 py-3 text-xs leading-relaxed text-yellow-200/90">
        <AlertCircle size={14} className="mt-0.5 flex-shrink-0" />
        <span>
          No DOMjudge account is attached to you yet, so there are no contests to show. Whoever
          is running the round attaches one — ask them, and this list fills in by itself.
        </span>
      </div>
    )
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 size={16} className="animate-spin text-gray-600" />
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-start gap-2 rounded-lg border border-red-900/60
        bg-red-950/20 px-3 py-3 text-xs leading-relaxed text-red-200/90">
        <AlertCircle size={14} className="mt-0.5 flex-shrink-0" />
        <span>
          Could not reach DOMjudge to list your contests. The judge may be down, or the
          attached account may no longer be valid.
        </span>
      </div>
    )
  }

  if (!contests || contests.length === 0) {
    return (
      <p className="rounded-lg border border-gray-800 px-3 py-3 text-xs leading-relaxed
        text-gray-500">
        {classroomId != null && !loginIn(classroomId)?.judgeLoginAttached
          ? 'No DOMjudge login is attached for you in this classroom yet.'
          : 'Your DOMjudge account is not registered for any contests yet.'}
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      <p className="text-[11px] text-gray-600">
        Each contest is entered as your login in its classroom
        {withLogin.length === 1 && withLogin[0].domjudgeUsername && (
          <> (<span className="text-gray-400">{withLogin[0].domjudgeUsername}</span>)</>
        )}
        {' '}— submissions land on that team's board.
      </p>

      <div className="flex flex-col gap-1.5">
        {contests.map(contest => {
          const finished = contest.phase === 'FINISHED'
          return (
            <button
              key={contest.id}
              onClick={() => onPick(contest.id)}
              disabled={loading}
              className={clsx(
                'flex items-center gap-3 rounded-lg border px-3 py-2.5 text-left',
                'transition-colors disabled:opacity-50',
                contest.running
                  ? 'border-green-900/70 bg-green-950/20 hover:bg-green-950/40'
                  : 'border-gray-800 hover:border-gray-700 hover:bg-gray-900/60'
              )}
            >
              <span className="flex-shrink-0">
                {contest.running
                  ? <Radio size={14} className="text-green-400" />
                  : finished
                    ? <CheckCircle2 size={14} className="text-gray-700" />
                    : <CalendarClock size={14} className="text-gray-600" />}
              </span>

              <span className="min-w-0 flex-1">
                <span className={clsx(
                  'block truncate text-sm',
                  finished ? 'text-gray-500' : 'text-gray-200'
                )}>
                  {contest.name}
                </span>
                <span className="block text-[11px] text-gray-600">
                  {several && classroomId == null && (
                    <span className="text-gray-500">{contest.classroomName} · </span>
                  )}
                  {whenOf(contest)}
                </span>
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
