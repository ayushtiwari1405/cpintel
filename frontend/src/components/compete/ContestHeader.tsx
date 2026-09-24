import { clsx } from 'clsx'
import { ExternalLink, FolderOpen, Snowflake, Timer, Trophy, X } from 'lucide-react'
import { LockdownBadge } from './LockdownBadge'
import type { ContestInfo, RankInfo } from '@/types'
import type { LockdownState } from '@/utils/desktopBridge'

/** Seconds -> H:MM:SS, or M:SS under an hour. */
function clock(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`
}

const PHASE_LABELS: Record<string, string> = {
  BEFORE: 'Not started',
  CODING: 'Running',
  VIRTUAL: 'Virtual',
  PENDING_SYSTEM_TEST: 'Pending system tests',
  SYSTEM_TEST: 'System testing',
  FINISHED: 'Finished',
}

interface Props {
  contest: ContestInfo
  /** Seconds since this contest payload was received, so the clock ticks between refetches. */
  elapsed: number
  rank?: RankInfo
  /** Absent in examination mode, where there is no other contest to go to. */
  onClose?: () => void
  /** Undefined when this contest does not allow personal files — the button then stays away. */
  onOpenFiles?: () => void
  /** Null in the browser build, which has nothing to lock. */
  lockdown?: LockdownState | null
}

export function ContestHeader({ contest, elapsed, rank, onClose, onOpenFiles, lockdown }: Props) {
  const before = contest.phase === 'BEFORE'
  const untilStart = contest.secondsUntilStart - elapsed
  const remaining = contest.secondsRemaining - elapsed

  // The countdown can run past zero before the poll notices the phase flip; hold at zero and
  // say "starting" rather than showing a negative clock.
  const starting = before && untilStart <= 0

  return (
    <div className="card flex items-center gap-4 flex-wrap py-3">
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <a
            href={contest.url}
            target="_blank"
            rel="noreferrer"
            className="text-sm font-medium text-gray-100 hover:text-indigo-300
                       transition-colors truncate flex items-center gap-1.5"
          >
            {contest.name}
            <ExternalLink size={12} className="flex-shrink-0 text-gray-600" />
          </a>
          <span className={clsx(
            'badge flex-shrink-0',
            contest.running ? 'badge-green'
              : before ? 'badge-blue' : 'badge-gray'
          )}>
            {PHASE_LABELS[contest.phase] ?? contest.phase}
          </span>
          {contest.frozen && (
            <span className="badge-blue flex-shrink-0 gap-1">
              <Snowflake size={10} /> frozen
            </span>
          )}
          <LockdownBadge state={lockdown ?? null} />
        </div>
        <div className="text-[11px] text-gray-600 mt-0.5">
          #{contest.id} · {Math.round(contest.durationSeconds / 60)} min
          {contest.problems.length > 0 && ` · ${contest.problems.length} problems`}
        </div>
      </div>

      {/* Countdown before the start, remaining time once running. */}
      {(before || contest.running) && (
        <div className="flex items-center gap-2 flex-shrink-0">
          <Timer size={15} className={contest.running ? 'text-green-400' : 'text-indigo-400'} />
          <div className="leading-tight">
            <div className={clsx(
              'font-mono text-lg tabular-nums',
              contest.running ? 'text-green-300' : 'text-indigo-300'
            )}>
              {starting ? 'starting…' : clock(before ? untilStart : remaining)}
            </div>
            <div className="text-[10px] text-gray-600 uppercase tracking-wide">
              {before ? 'until start' : 'remaining'}
            </div>
          </div>
        </div>
      )}

      {rank?.participating && (
        <div className="flex items-center gap-2 flex-shrink-0 pl-4 border-l border-gray-800">
          <Trophy size={15} className="text-yellow-400" />
          <div className="leading-tight">
            <div className="font-mono text-lg tabular-nums text-gray-100">
              {rank.rank != null ? `#${rank.rank}` : '—'}
            </div>
            <div className="text-[10px] text-gray-600 uppercase tracking-wide">
              {rank.solvedCount ?? 0} solved
              {rank.points != null && ` · ${Math.round(rank.points)} pts`}
            </div>
          </div>
        </div>
      )}

      {onOpenFiles && (
        <button
          onClick={onOpenFiles}
          title="Your own templates and notes"
          className="text-gray-500 hover:text-indigo-300 transition-colors flex-shrink-0
                     flex items-center gap-1.5 text-xs"
        >
          <FolderOpen size={15} /> Files
        </button>
      )}

      {onClose && (
        <button
          onClick={onClose}
          title="Load a different contest"
          className="text-gray-600 hover:text-gray-300 transition-colors flex-shrink-0"
        >
          <X size={16} />
        </button>
      )}
    </div>
  )
}
