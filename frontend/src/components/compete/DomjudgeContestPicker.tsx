import { clsx } from 'clsx'
import { AlertCircle, CalendarClock, CheckCircle2, Loader2, Radio } from 'lucide-react'
import { useDomjudgeAccount, useDomjudgeContests } from '@/hooks/useCompete'
import type { DomjudgeContestSummary } from '@/types'

interface Props {
  onPick: (contestId: string) => void
  loading: boolean
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
 */
export function DomjudgeContestPicker({ onPick, loading }: Props) {
  const { data: account, isLoading: accountLoading } = useDomjudgeAccount()
  const linked = !!account?.linked

  const { data: contests, isLoading, error } = useDomjudgeContests(linked)

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
        Your DOMjudge account <span className="text-gray-300">{account?.username}</span> is not
        registered for any contests yet.
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      <p className="text-[11px] text-gray-600">
        Competing as <span className="text-gray-400">{account?.teamName ?? account?.username}</span>
        {' '}— submissions land on this team's board.
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
                <span className="block text-[11px] text-gray-600">{whenOf(contest)}</span>
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
