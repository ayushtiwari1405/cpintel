import { clsx } from 'clsx'
import { Eye, EyeOff } from 'lucide-react'
import type { LockdownState } from '@/utils/desktopBridge'

/** Milliseconds as a short human duration: 45s, 3m 20s. */
function duration(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000))
  if (total < 60) return `${total}s`
  return `${Math.floor(total / 60)}m ${String(total % 60).padStart(2, '0')}s`
}

/**
 * Whether the contest is being watched, and what it has seen.
 *
 * Turns red the moment focus leaves and counts up while it is gone, so the state of the thing
 * is visible on the way back rather than only in a notification that may have been missed. The
 * absences that crossed the warning threshold are shown separately from the total count,
 * because a dozen two-second glances and one four-minute absence are different events and
 * collapsing them into one number would hide which happened.
 */
export function LockdownBadge({ state }: { state: LockdownState | null }) {
  if (!state?.engaged) return null

  const threshold = Math.round(state.warnAfterMs / 1000)

  const detail = [
    'This contest is being monitored.',
    state.focusLosses === 0
      ? 'The window has not lost focus.'
      : `Left the window ${state.focusLosses} time${state.focusLosses === 1 ? '' : 's'}, `
        + `${duration(state.awayMs)} in total.`,
    state.longAbsences > 0
      ? `${state.longAbsences} of those ran past ${threshold} seconds and were warned about.`
      : null,
    state.clipboardWipes > 0
      ? `Clipboard cleared ${state.clipboardWipes} time${state.clipboardWipes === 1 ? '' : 's'} `
        + 'after changing while you were away.'
      : null,
    `Absences over ${threshold} seconds are reported to whoever is running the contest.`,
  ].filter(Boolean).join('\n')

  return (
    <span
      title={detail}
      className={clsx(
        'flex flex-shrink-0 items-center gap-1.5 rounded-md border px-2 py-1 text-[11px]',
        state.away
          ? 'border-red-800 bg-red-950/60 text-red-300'
          : state.longAbsences > 0
            ? 'border-amber-800 bg-amber-950/50 text-amber-300'
            : 'border-indigo-800 bg-indigo-950/50 text-indigo-300'
      )}
    >
      {state.away ? <EyeOff size={12} /> : <Eye size={12} />}
      <span className="font-medium">
        {state.away ? `Away ${duration(state.currentAwayMs)}` : 'Monitored'}
      </span>
      {!state.away && state.awayMs > 0 && (
        <span className="opacity-70">· {duration(state.awayMs)} away</span>
      )}
    </span>
  )
}
