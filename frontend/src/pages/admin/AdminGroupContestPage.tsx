import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  AlertTriangle, Clipboard, ExternalLink, Eye, Loader2, RefreshCw, ShieldOff, Trophy,
} from 'lucide-react'
import { clsx } from 'clsx'
import { useGroupStandings, useGroupViolations, useRefreshStandings } from '@/hooks/useGroups'
import { Ago, EmptyRow, Panel, Pill, actionLabel } from '@/components/admin/AdminUi'
import type { StandingRow, ViolationSummary } from '@/types'

function duration(ms: number): string {
  const total = Math.round(ms / 1000)
  if (total < 60) return `${total}s`
  const minutes = Math.floor(total / 60)
  return total % 60 === 0 ? `${minutes}m` : `${minutes}m ${total % 60}s`
}

/** Penalty minutes, which is what both judges count in. */
function penalty(minutes: number): string {
  if (minutes <= 0) return '—'
  return `${minutes}`
}

const VIOLATION_TONE: Record<string, 'gray' | 'red' | 'amber' | 'indigo'> = {
  FOCUS_LOST: 'amber',
  CLIPBOARD_FOREIGN: 'red',
  BLOCKED_ACTION: 'gray',
  LOCKDOWN_UNAVAILABLE: 'red',
  LOCKDOWN_PARTIAL: 'amber',
  LOCKDOWN_RELEASED: 'red',
}

/**
 * One group contest: where its members placed, and what their monitors reported.
 *
 * The two halves are kept side by side but never combined into a single number. There is no
 * honest way to weigh ninety seconds away from the window against a discarded clipboard, and
 * a "suspicion score" would be read as a verdict by whoever saw it next.
 */
export default function AdminGroupContestPage() {
  const { contestId } = useParams()
  const id = Number(contestId)
  const valid = Number.isFinite(id)

  const { data: standings, isLoading } = useGroupStandings(valid ? id : null)
  const { data: violations } = useGroupViolations(valid ? id : null)
  const refresh = useRefreshStandings()

  const [tab, setTab] = useState<'standings' | 'conduct'>('standings')

  if (isLoading || !standings) {
    return (
      <div className="flex items-center justify-center gap-2 py-20 text-sm text-gray-500">
        <Loader2 size={16} className="animate-spin" /> Loading the board…
      </div>
    )
  }

  const { contest } = standings
  const computed = contest.platform === 'CODEFORCES'

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-baseline gap-3">
        <Link
          to={`/admin/groups/${contest.groupId}`}
          className="text-xs text-gray-500 hover:text-gray-300"
        >
          ← {contest.groupName}
        </Link>
        <h1 className="text-lg font-semibold text-gray-100">{contest.name}</h1>
        <Pill tone={contest.status === 'LIVE' ? 'green' : 'gray'}>
          {contest.status.toLowerCase()}
        </Pill>
        <span className="text-xs text-gray-600">
          {contest.platform} · <span className="font-mono">{contest.externalId}</span>
        </span>
        {contest.url && (
          <a
            href={contest.url}
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-1 text-xs text-indigo-400 hover:text-indigo-300"
          >
            Open on the judge <ExternalLink size={11} />
          </a>
        )}
      </div>

      {contest.standingsError && (
        <div className="flex items-start gap-2 rounded-xl border border-amber-900
          bg-amber-950/30 p-3 text-xs text-amber-300">
          <AlertTriangle size={14} className="mt-0.5 flex-shrink-0" />
          <div>
            <p className="font-medium">The last refresh failed, so these numbers are stale.</p>
            <p className="mt-0.5 opacity-80">{contest.standingsError}</p>
          </div>
        </div>
      )}

      {standings.unmatched.length > 0 && (
        <div className="flex items-start gap-2 rounded-xl border border-amber-900
          bg-amber-950/30 p-3 text-xs text-amber-300">
          <AlertTriangle size={14} className="mt-0.5 flex-shrink-0" />
          <div>
            <p className="font-medium">
              {standings.unmatched.length} member
              {standings.unmatched.length === 1 ? '' : 's'} could not be found on the judge:
              {' '}{standings.unmatched.join(', ')}.
            </p>
            <p className="mt-0.5 opacity-80">
              Before their first submission this is normal. Afterwards it nearly always means
              the handle on the group's member list does not match the one on the board — they
              are left unranked rather than scored zero.
            </p>
          </div>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <div className="flex gap-1">
          {(['standings', 'conduct'] as const).map(t => (
            <button
              key={t}
              onClick={() => setTab(t)}
              className={clsx(
                'flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs transition-colors',
                tab === t
                  ? 'bg-gray-800 text-gray-100 font-medium'
                  : 'text-gray-500 hover:text-gray-300'
              )}
            >
              {t === 'standings' ? <Trophy size={13} /> : <Eye size={13} />}
              {t === 'standings' ? 'Standings' : 'What the monitors reported'}
              {t === 'conduct' && violations && violations.total > 0 && (
                <span className="text-[10px] text-gray-600">{violations.total}</span>
              )}
            </button>
          ))}
        </div>

        <div className="ml-auto flex items-center gap-2 text-xs text-gray-600">
          <span>
            {standings.refreshedAt
              ? <>Built <Ago at={standings.refreshedAt} /></>
              : 'Never built'}
          </span>
          <button
            onClick={() => refresh.mutate({ contestId: id })}
            disabled={refresh.isPending}
            title="Rebuild from the judge. On Codeforces this is one rate-limited call per member."
            className="flex items-center gap-1.5 rounded-lg border border-gray-800 bg-gray-900
                       px-2.5 py-1.5 text-xs text-gray-300 transition-colors hover:bg-gray-800
                       disabled:opacity-50"
          >
            {refresh.isPending
              ? <Loader2 size={13} className="animate-spin" />
              : <RefreshCw size={13} />}
            Refresh
          </button>
        </div>
      </div>

      {tab === 'standings' ? (
        <Panel
          title="Group standings"
          description={computed
            ? 'Computed from each member’s submissions — see the note below'
            : 'Read from the judge’s own scoreboard'}
        >
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                  <th className="w-12 px-4 py-2 font-medium">#</th>
                  <th className="px-4 py-2 font-medium">Member</th>
                  <th className="px-4 py-2 font-medium">Handle</th>
                  <th className="px-4 py-2 font-medium text-right">Solved</th>
                  <th className="px-4 py-2 font-medium text-right">Penalty</th>
                  <th className="px-4 py-2 font-medium">Monitor reported</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-800">
                {standings.rows.length === 0 && (
                  <EmptyRow colSpan={6}>
                    Nothing yet. Refresh once the contest has started and people have submitted.
                  </EmptyRow>
                )}
                {standings.rows.map(row => (
                  <StandingsRow key={row.userId} row={row} />
                ))}
              </tbody>
            </table>
          </div>

          {computed && (
            <p className="border-t border-gray-800 px-4 py-3 text-xs text-gray-500">
              Codeforces will not answer a filtered standings query for a public contest, so
              these numbers are computed from each member's own submission list: problems solved,
              plus ten penalty minutes per rejected attempt on a problem they went on to solve.
              For an ICPC-mode round that matches the official board. For a regular rated round,
              which scores by decaying problem points, the group's internal order can differ from
              Codeforces' own — this is a ranking of the group, not a copy of theirs.
            </p>
          )}
        </Panel>
      ) : (
        <Panel
          title="What the monitors reported"
          description="Observations, not conclusions"
        >
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                  <th className="px-4 py-2 font-medium">Member</th>
                  <th className="px-4 py-2 font-medium">What</th>
                  <th className="px-4 py-2 font-medium">Detail</th>
                  <th className="px-4 py-2 font-medium text-right">For</th>
                  <th className="px-4 py-2 font-medium">When</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-800">
                {(!violations || violations.entries.length === 0) && (
                  <EmptyRow colSpan={5}>
                    Nothing reported. Either nobody was away for more than a few seconds, or
                    the round was sat without the desktop app — those look different here,
                    because a browser reports itself as the weaker kind of monitoring.
                  </EmptyRow>
                )}
                {violations?.entries.map(entry => (
                  <tr key={entry.violationId} className="transition-colors hover:bg-gray-800/40">
                    <td className="px-4 py-2.5 text-gray-300">{entry.username}</td>
                    <td className="px-4 py-2.5">
                      <Pill tone={VIOLATION_TONE[entry.type] ?? 'gray'}>
                        {actionLabel(entry.type)}
                      </Pill>
                    </td>
                    <td className="max-w-md px-4 py-2.5 text-xs text-gray-500">
                      {entry.detail ?? '—'}
                    </td>
                    <td className="px-4 py-2.5 text-right text-xs tabular-nums text-gray-500">
                      {entry.durationMs ? duration(entry.durationMs) : '—'}
                    </td>
                    <td className="px-4 py-2.5 text-xs text-gray-500">
                      <Ago at={entry.occurredAt} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <p className="border-t border-gray-800 px-4 py-3 text-xs text-gray-500">
            The monitor can see that someone left the contest window for longer than ten
            seconds. It cannot see whether that was a second screen with the solution on it, a
            notification, or someone answering the door. It does not stop anyone leaving, and it
            cannot see a phone or a second machine at all, so a clean record is not proof of
            anything either. Treat this as a place to start a conversation, not as a verdict.
          </p>
        </Panel>
      )}
    </div>
  )
}

function StandingsRow({ row }: { row: StandingRow }) {
  return (
    <tr className={clsx(
      'transition-colors hover:bg-gray-800/40',
      !row.found && 'opacity-60'
    )}>
      <td className="px-4 py-2.5 font-mono text-sm text-gray-400">
        {row.groupRank ?? '—'}
      </td>
      <td className="px-4 py-2.5 font-medium text-gray-200">{row.username}</td>
      <td className="px-4 py-2.5 font-mono text-xs text-gray-500">
        {row.handle ?? <span className="not-italic text-amber-500">no handle set</span>}
      </td>
      <td className="px-4 py-2.5 text-right tabular-nums text-gray-200">
        {row.found ? row.solved : '—'}
      </td>
      <td className="px-4 py-2.5 text-right tabular-nums text-gray-500">
        {row.found ? penalty(row.penalty) : '—'}
      </td>
      <td className="px-4 py-2.5">
        <ConductCell violations={row.violations} />
      </td>
    </tr>
  )
}

/**
 * The conduct column, as separate counts rather than a total.
 *
 * Each figure means something different and they are not commensurable, so they are shown
 * beside each other and left for a person to weigh.
 */
function ConductCell({ violations }: { violations: ViolationSummary | null }) {
  if (!violations || violations.total === 0) {
    return <span className="text-xs text-gray-700">nothing</span>
  }

  return (
    <div className="flex flex-wrap items-center gap-1.5 text-[11px]">
      {violations.focusLosses > 0 && (
        <span
          title={`Left the window ${violations.focusLosses} time(s), ${duration(violations.awayMs)} in total`}
          className="flex items-center gap-1 rounded bg-amber-950/60 px-1.5 py-0.5 text-amber-300"
        >
          <Eye size={10} /> {violations.focusLosses}× away
          {violations.awayMs > 0 && <span className="opacity-70">{duration(violations.awayMs)}</span>}
        </span>
      )}
      {violations.clipboardWipes > 0 && (
        <span
          title="Clipboard content appeared while the window was away, and was discarded"
          className="flex items-center gap-1 rounded bg-red-950/60 px-1.5 py-0.5 text-red-300"
        >
          <Clipboard size={10} /> {violations.clipboardWipes}
        </span>
      )}
      {violations.lockdownUnavailable && (
        <span
          title="Sat in a browser, where monitoring is weaker and can simply be closed"
          className="flex items-center gap-1 rounded bg-red-950/60 px-1.5 py-0.5 text-red-300"
        >
          <ShieldOff size={10} /> browser only
        </span>
      )}
      {violations.lockdownPartial && (
        <span
          title="From an older build that tried to grab keyboard shortcuts and could not"
          className="rounded bg-amber-950/60 px-1.5 py-0.5 text-amber-300"
        >
          partial lock
        </span>
      )}
      {violations.lockdownReleased && (
        <span
          title="Monitoring stopped while the contest was still running"
          className="rounded bg-red-950/60 px-1.5 py-0.5 text-red-300"
        >
          released early
        </span>
      )}
      {violations.blockedActions > 0 && (
        <span
          title={`${violations.blockedActions} navigations or window-opens the monitor refused`}
          className="rounded bg-gray-800 px-1.5 py-0.5 text-gray-400"
        >
          {violations.blockedActions} blocked
        </span>
      )}
    </div>
  )
}
