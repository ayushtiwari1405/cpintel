import { clsx } from 'clsx'
import { Loader2, Snowflake, Users } from 'lucide-react'
import type { Leaderboard, LeaderboardCell, LeaderboardRow } from '@/types'

interface Props {
  board: Leaderboard | undefined
  isLoading?: boolean
}

/**
 * One problem's cell: solved, attempted, or untouched.
 *
 * The minute a problem went green is the thing a contestant actually compares against — it is
 * what the penalty is built from — so it is shown in preference to the attempt count once a
 * problem is solved.
 */
function Cell({ cell }: { cell: LeaderboardCell | undefined }) {
  if (!cell || (!cell.solved && cell.attempts === 0)) {
    return <td className="px-2 py-2 text-center text-gray-800">·</td>
  }
  return (
    <td className="px-2 py-2 text-center">
      <span className={clsx(
        'inline-block min-w-[2.25rem] rounded px-1.5 py-0.5 text-[11px] tabular-nums',
        cell.solved
          ? 'bg-green-500/15 text-green-400'
          : 'bg-red-500/10 text-red-400/80'
      )}>
        {cell.solved
          ? (cell.minute != null ? cell.minute : '✓')
          : `−${cell.attempts}`}
      </span>
    </td>
  )
}

function Row({ row, indexes }: { row: LeaderboardRow; indexes: string[] }) {
  const byIndex = new Map(row.problems.map(p => [p.index, p]))
  return (
    <tr className={clsx(
      'transition-colors',
      row.mine ? 'bg-indigo-950/40' : 'hover:bg-gray-900/60'
    )}>
      <td className="px-3 py-2 tabular-nums text-gray-500">{row.rank ?? '—'}</td>
      <td className={clsx(
        'px-2 py-2 truncate max-w-[12rem]',
        row.mine ? 'font-medium text-indigo-200' : 'text-gray-300'
      )}>
        {row.teamName}
      </td>
      <td className="px-2 py-2 text-right tabular-nums font-medium text-gray-200">
        {row.solved}
      </td>
      <td className="px-2 py-2 text-right tabular-nums text-gray-600">
        {row.penalty ?? ''}
      </td>
      {indexes.map(index => <Cell key={index} cell={byIndex.get(index)} />)}
    </tr>
  )
}

/**
 * The contest's board, as the judge ranks it, with the viewer's own team broken out above it.
 *
 * <p>Every number here is DOMjudge's own — rank, solved count, penalty, the minute a problem
 * went green — so this and the board on the judge's own projector agree. Nothing is recomputed
 * from the submissions list, which would have produced a second opinion that diverges the
 * first time a rejudge lands.
 *
 * <p>The two banners are not decoration. A frozen board has deliberately stopped showing the
 * last hour, and a board read from the public view has stopped moving for a different reason
 * entirely — but both look identical from the outside, and both look exactly like a room where
 * nobody is solving anything. Saying which is the difference between a contestant trusting the
 * panel and a contestant quietly concluding it is broken.
 */
export function LeaderboardPanel({ board, isLoading }: Props) {
  if (!board) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2 px-6 text-center">
        {isLoading
          ? <Loader2 size={16} className="animate-spin text-gray-600" />
          : <p className="max-w-xs text-xs leading-relaxed text-gray-600">
              The board is not available for this contest.
            </p>}
      </div>
    )
  }

  const indexes = board.problemIndexes

  return (
    <div className="flex h-full flex-col">
      {(board.frozen || !board.live) && (
        <div className="flex flex-shrink-0 items-start gap-2 border-b border-gray-800
          bg-gray-900/50 px-3 py-2 text-[11px] leading-relaxed text-gray-400">
          <Snowflake size={12} className="mt-0.5 flex-shrink-0 text-sky-400" />
          <span>
            {board.frozen
              ? 'The board is frozen for the end of the contest — it is not showing the most '
                + 'recent submissions, including your own.'
              : 'This is the public board, which stops updating once the contest freezes. '
                + 'Your own rank in the header stays accurate.'}
          </span>
        </div>
      )}

      {board.myTeam && (
        <div className="flex-shrink-0 border-b border-gray-800 px-3 py-3">
          <div className="mb-2 flex items-center gap-1.5 text-[11px] font-medium text-gray-500">
            <Users size={12} />
            {board.myTeamName ?? 'Your team'}
          </div>
          <table className="w-full text-xs">
            <thead>
              <tr className="text-[11px] text-gray-600">
                <th className="px-3 py-1 text-left font-medium">#</th>
                <th className="px-2 py-1 text-left font-medium">Team</th>
                <th className="px-2 py-1 text-right font-medium">Solved</th>
                <th className="px-2 py-1 text-right font-medium">Pen</th>
                {indexes.map(i => (
                  <th key={i} className="px-2 py-1 text-center font-mono font-medium">{i}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              <Row row={board.myTeam} indexes={indexes} />
            </tbody>
          </table>
        </div>
      )}

      <div className="min-h-0 flex-1 overflow-y-auto">
        {board.rows.length === 0 ? (
          <p className="px-6 py-8 text-center text-xs leading-relaxed text-gray-600">
            Nobody is on the board yet.
          </p>
        ) : (
          <table className="w-full text-xs">
            <thead className="sticky top-0 bg-gray-950">
              <tr className="text-[11px] text-gray-600">
                <th className="px-3 py-2 text-left font-medium">#</th>
                <th className="px-2 py-2 text-left font-medium">Team</th>
                <th className="px-2 py-2 text-right font-medium">Solved</th>
                <th className="px-2 py-2 text-right font-medium">Pen</th>
                {indexes.map(i => (
                  <th key={i} className="px-2 py-2 text-center font-mono font-medium">{i}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800/70">
              {board.rows.map(row => (
                <Row key={row.teamId} row={row} indexes={indexes} />
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
