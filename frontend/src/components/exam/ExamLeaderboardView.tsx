import type { ReactNode } from 'react'
import { formatDistanceToNow } from 'date-fns'
import { clsx } from 'clsx'
import { EyeOff, Loader2, Lock, Trophy } from 'lucide-react'

import type { ExamLeaderboard, ExamLeaderboardCell } from '@/types'

interface Props {
  board: ExamLeaderboard | undefined
  isLoading?: boolean
  /** Highlights this person's row. */
  meId?: number
  /** An admin sees the board whatever candidates are shown, with a note saying which. */
  admin?: boolean
}

/** h:mm:ss, or m:ss under an hour. */
export function clock(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}

function Cell({ cell }: { cell: ExamLeaderboardCell | undefined }) {
  if (!cell || (!cell.solved && cell.wrongAttempts === 0 && !cell.pending)) {
    return <td className="px-2 py-2 text-center text-gray-800">·</td>
  }
  if (cell.solved) {
    return (
      <td className="px-2 py-2 text-center">
        <span
          className={clsx(
            'inline-flex flex-col items-center rounded px-1.5 py-0.5 text-[11px] tabular-nums',
            cell.firstSolve ? 'bg-green-500/30 text-green-300' : 'bg-green-500/15 text-green-400'
          )}
          title={cell.firstSolve ? 'First to solve this problem' : undefined}
        >
          {clock(cell.solvedAtSeconds ?? 0)}
          {cell.wrongAttempts > 0 && (
            <span className="text-[10px] text-green-600">+{cell.wrongAttempts}</span>
          )}
        </span>
      </td>
    )
  }
  return (
    <td className="px-2 py-2 text-center">
      <span className={clsx(
        'inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] tabular-nums',
        cell.pending ? 'bg-amber-500/10 text-amber-400' : 'bg-red-500/10 text-red-400/80'
      )}>
        {cell.wrongAttempts > 0 && `−${cell.wrongAttempts}`}
        {cell.pending && <Loader2 size={10} className="animate-spin" />}
      </span>
    </td>
  )
}

function Notice({ icon: Icon, title, children }: {
  icon: typeof Trophy
  title: string
  children?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center gap-2 px-6 py-12 text-center">
      <Icon size={22} className="text-gray-700" />
      <p className="text-sm font-medium text-gray-300">{title}</p>
      {children && <p className="max-w-sm text-xs leading-relaxed text-gray-500">{children}</p>}
    </div>
  )
}

/**
 * An examination's leaderboard.
 *
 * <p>Ranked by the server: problems solved, then total time — each solve timed from when the
 * accepted code was sent, not from when its verdict came back, plus any penalty per earlier
 * wrong attempt. The board is a snapshot recomputed on the admin's schedule, which is said on
 * it so nobody reads a fifteen-minute-old board as live.
 */
export function ExamLeaderboardView({ board, isLoading, meId, admin }: Props) {
  if (isLoading && !board) {
    return (
      <div className="flex items-center justify-center gap-2 py-12 text-sm text-gray-500">
        <Loader2 size={14} className="animate-spin" /> Loading the leaderboard…
      </div>
    )
  }
  if (!board) return null

  if (!admin) {
    if (board.state === 'DISABLED') {
      return <Notice icon={EyeOff} title="Leaderboard disabled">
        There is no leaderboard for this examination.
      </Notice>
    }
    if (board.state === 'UNPUBLISHED') {
      return <Notice icon={Lock} title="Final standings not published">
        The examination is over, but its final leaderboard has not been released.
      </Notice>
    }
  }
  if (board.state === 'NOT_STARTED' || !board.standings) {
    return <Notice icon={Trophy} title="The leaderboard opens when the examination starts" />
  }

  const { standings } = board
  // The server only schedules a next update while the paper runs.
  const final = !board.nextRefreshAt

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-gray-800
        px-4 py-2.5 text-xs text-gray-500">
        <span>
          {final ? 'Final standings' : 'Updated'}{' '}
          {formatDistanceToNow(new Date(standings.generatedAt), { addSuffix: true })}
          {board.nextRefreshAt && (
            <> · next update {formatDistanceToNow(new Date(board.nextRefreshAt),
              { addSuffix: true })}</>
          )}
        </span>
        <span>
          Ranked by solved, then time
          {standings.penaltyMinutes > 0
            ? ` · +${standings.penaltyMinutes} min per wrong attempt`
            : ' · no penalty for wrong attempts'}
          {standings.pendingSubmissions > 0 && ` · ${standings.pendingSubmissions} still judging`}
        </span>
      </div>

      <div className="min-h-0 flex-1 overflow-auto">
        <table className="w-full text-sm">
          <thead className="sticky top-0 bg-gray-950">
            <tr className="border-b border-gray-800 text-xs text-gray-500">
              <th className="px-3 py-2 text-left font-medium">#</th>
              <th className="px-3 py-2 text-left font-medium">Candidate</th>
              <th className="px-3 py-2 text-right font-medium">Solved</th>
              <th className="px-3 py-2 text-right font-medium">Time</th>
              {standings.problems.map(label => (
                <th key={label} className="px-2 py-2 text-center font-medium">{label}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800/60">
            {standings.rows.length === 0 && (
              <tr>
                <td colSpan={4 + standings.problems.length}
                  className="px-4 py-10 text-center text-sm text-gray-600">
                  Nobody is assigned to this examination.
                </td>
              </tr>
            )}
            {standings.rows.map(row => {
              const byLabel = new Map(row.cells.map(cell => [cell.label, cell]))
              return (
                <tr key={row.userId} className={clsx(
                  'transition-colors hover:bg-gray-800/30',
                  row.userId === meId && 'bg-indigo-600/10'
                )}>
                  <td className="px-3 py-2 tabular-nums text-gray-400">{row.rank}</td>
                  <td className="px-3 py-2">
                    <span className={row.userId === meId ? 'text-indigo-300' : 'text-gray-200'}>
                      {row.username}
                    </span>
                    {row.fullName && (
                      <span className="ml-2 text-xs text-gray-600">{row.fullName}</span>
                    )}
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums text-gray-200">{row.solved}</td>
                  <td className="px-3 py-2 text-right tabular-nums text-gray-400">
                    {row.solved > 0 ? clock(row.totalSeconds) : '—'}
                  </td>
                  {standings.problems.map(label => (
                    <Cell key={label} cell={byLabel.get(label)} />
                  ))}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
