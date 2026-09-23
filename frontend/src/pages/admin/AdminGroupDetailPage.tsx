import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  AlertTriangle, ArrowRight, Loader2, Plus, Trash2, UserPlus,
} from 'lucide-react'
import { DomjudgeAccountCell } from '@/components/admin/DomjudgeAccountCell'
import {
  useAddGroupContest, useAddMember, useAdminGroup, useAdminGroups, useMoveMember,
  useRemoveGroupContest, useRemoveMember, useUpdateMember,
} from '@/hooks/useGroups'
import { useAdminUsers } from '@/hooks/useAdmin'
import { useTeamAnalytics } from '@/hooks/useExams'
import { Ago, EmptyRow, Panel, Pill, StatCard } from '@/components/admin/AdminUi'
import { RosterImportPanel } from '@/components/admin/RosterImportPanel'
import type { GroupContestStatus } from '@/types'

/**
 * How this team has done, across everything it was given.
 *
 * Participation is the figure worth reading first, and it is deliberately people-who-turned-up
 * over people-assigned rather than anything about scores: a team whose average looks respectable
 * because a third of it never sat the paper is precisely what this panel exists to show.
 */
function TeamAnalyticsPanel({ teamId }: { teamId: number }) {
  const { data, isLoading } = useTeamAnalytics(teamId)
  if (isLoading || !data) return null

  return (
    <Panel
      title="How this team has done"
      description="Across every contest and examination it was assigned"
    >
      <div className="grid grid-cols-2 gap-3 p-4 sm:grid-cols-4">
        <StatCard label="Events" value={data.events}
          hint={`${data.contests} contest(s), ${data.exams} examination(s)`} />
        <StatCard label="Turned up" value={`${Math.round(data.participationRate * 100)}%`}
          tone={data.participationRate >= 0.9 ? 'good'
            : data.participationRate >= 0.6 ? 'warn' : 'danger'}
          hint={`${data.participants} of the people assigned`} />
        <StatCard label="Average solved" value={data.averageSolved.toFixed(1)}
          hint="Per ranked member, per event" />
        <StatCard label="Problems solved" value={data.totalSolved} hint="All events together" />
      </div>

      {data.recent.length > 0 && (
        <ul className="divide-y divide-gray-800 border-t border-gray-800">
          {data.recent.map(row => (
            <li key={row.eventId} className="flex flex-wrap items-center justify-between gap-2
              px-4 py-2 text-sm">
              <span className="text-gray-300">
                {row.name}
                <Pill tone={row.kind === 'EXAM' ? 'indigo' : 'gray'}>
                  {row.kind === 'EXAM' ? 'exam' : 'contest'}
                </Pill>
              </span>
              <span className="text-xs text-gray-500">
                {row.ranked} ranked · {row.averageSolved.toFixed(1)} solved on average
                {row.bestRank != null && row.bestMember && (
                  <> · best {row.bestMember} at {row.bestRank}</>
                )}
                {row.startsAt && <> · <Ago at={row.startsAt} /></>}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}

const STATUS_TONE: Record<GroupContestStatus, 'gray' | 'green' | 'indigo'> = {
  SCHEDULED: 'indigo',
  LIVE: 'green',
  FINISHED: 'gray',
}

/**
 * One group: who is in it, and which contests it has sat.
 *
 * The handle column is the thing that most often needs attention. Codeforces members are found
 * through their linked account, but a member who has not linked one — and every DOMjudge member,
 * since CPIntel has no notion of a DOMjudge team — has to be matched by a name typed in here,
 * and a mismatch shows up as an empty board rather than as an error.
 */
export default function AdminGroupDetailPage() {
  const { groupId } = useParams()
  const id = Number(groupId)
  const { data, isLoading } = useAdminGroup(Number.isFinite(id) ? id : null)

  const addMember = useAddMember()
  const updateMember = useUpdateMember()
  const removeMember = useRemoveMember()
  const moveMember = useMoveMember()
  const { data: allTeams } = useAdminGroups()
  const addContest = useAddGroupContest()
  const removeContest = useRemoveGroupContest()

  const [search, setSearch] = useState('')
  const { data: candidates } = useAdminUsers({ query: search, size: 8 })

  const [contest, setContest] = useState({
    platform: 'CODEFORCES',
    externalId: '',
    name: '',
    url: '',
    startsAt: '',
    endsAt: '',
    lockdownRequired: true,
  })

  if (isLoading || !data) {
    return (
      <div className="flex items-center justify-center gap-2 py-20 text-sm text-gray-500">
        <Loader2 size={16} className="animate-spin" /> Loading the team…
      </div>
    )
  }

  const memberIds = new Set(data.members.map(m => m.userId))
  const canAddContest = contest.externalId.trim() !== '' && contest.name.trim() !== ''

  const submitContest = () => {
    if (!canAddContest) return
    addContest.mutate({
      groupId: id,
      platform: contest.platform,
      externalId: contest.externalId.trim(),
      name: contest.name.trim(),
      url: contest.url.trim() || undefined,
      startsAt: contest.startsAt ? new Date(contest.startsAt).toISOString() : undefined,
      endsAt: contest.endsAt ? new Date(contest.endsAt).toISOString() : undefined,
      lockdownRequired: contest.lockdownRequired,
    }, {
      onSuccess: () => setContest(c => ({ ...c, externalId: '', name: '', url: '' })),
    })
  }

  return (
    <div className="space-y-4">
      <div className="flex items-baseline gap-3">
        <Link to="/admin/teams" className="text-xs text-gray-500 hover:text-gray-300">
          ← All teams
        </Link>
        <h1 className="text-lg font-semibold text-gray-100">{data.group.name}</h1>
        {!data.group.active && <Pill tone="gray">retired</Pill>}
      </div>
      {data.group.description && (
        <p className="-mt-2 text-sm text-gray-500">{data.group.description}</p>
      )}

      <Panel
        title="Members"
        description={`${data.members.length} in this group`}
      >
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="px-4 py-2 font-medium">User</th>
                <th className="px-4 py-2 font-medium">Codeforces</th>
                <th className="px-4 py-2 font-medium">Handle on the judge</th>
                <th className="px-4 py-2 font-medium">DOMjudge account</th>
                <th className="px-4 py-2 font-medium">Joined</th>
                <th className="px-4 py-2 font-medium">Move to</th>
                <th className="px-4 py-2 font-medium text-right">Remove</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {data.members.length === 0 && (
                <EmptyRow colSpan={7}>Nobody yet. Add people below.</EmptyRow>
              )}
              {data.members.map(member => (
                <tr key={member.userId} className="transition-colors hover:bg-gray-800/40">
                  <td className="px-4 py-2.5">
                    <span className="block font-medium text-gray-200">{member.username}</span>
                    <span className="block text-xs text-gray-600">{member.email}</span>
                  </td>
                  <td className="px-4 py-2.5 text-xs">
                    {member.codeforcesHandle
                      ? <span className="font-mono text-gray-400">{member.codeforcesHandle}</span>
                      : <span className="text-gray-600">not linked</span>}
                  </td>
                  <td className="px-4 py-2.5">
                    <input
                      defaultValue={member.externalHandle ?? ''}
                      onBlur={e => {
                        const next = e.target.value.trim()
                        if (next === (member.externalHandle ?? '')) return
                        updateMember.mutate({
                          groupId: id, userId: member.userId, externalHandle: next || undefined,
                        })
                      }}
                      placeholder={member.codeforcesHandle ? 'override' : 'team name'}
                      className="w-40 rounded-md border border-gray-800 bg-gray-900 px-2 py-1
                                 font-mono text-xs text-gray-300 placeholder-gray-700
                                 outline-none focus:border-indigo-600"
                    />
                  </td>
                  <td className="px-4 py-2.5">
                    <DomjudgeAccountCell
                      userId={member.userId}
                      username={member.username}
                    />
                  </td>
                  <td className="px-4 py-2.5 text-xs text-gray-500">
                    <Ago at={member.joinedAt} />
                  </td>
                  <td className="px-4 py-2.5">
                    {/* One action rather than a remove and an add: between those two this
                        person is on no team, which is when an examination set for their old
                        team stops reaching them and the new one has not started to. */}
                    <select
                      value=""
                      onChange={e => {
                        const target = Number(e.target.value)
                        if (!target) return
                        moveMember.mutate({
                          groupId: id, userId: member.userId, targetGroupId: target,
                        })
                      }}
                      className="w-36 rounded-md border border-gray-800 bg-gray-900 px-2 py-1
                                 text-xs text-gray-400 outline-none focus:border-indigo-600"
                    >
                      <option value="">Move…</option>
                      {allTeams?.filter(team => team.groupId !== id).map(team => (
                        <option key={team.groupId} value={team.groupId}>{team.name}</option>
                      ))}
                    </select>
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <button
                      onClick={() => removeMember.mutate({ groupId: id, userId: member.userId })}
                      title={`Remove ${member.username} from the group`}
                      aria-label={`Remove ${member.username}`}
                      className="rounded-md border border-gray-800 p-1.5 text-gray-400
                                 transition-colors hover:bg-red-900/30 hover:text-red-400"
                    >
                      <Trash2 size={13} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="space-y-2 border-t border-gray-800 p-4">
          <span className="text-xs text-gray-500">Add someone individually</span>
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search by username or email"
            className="w-full max-w-md rounded-lg border border-gray-800 bg-gray-900 px-3 py-2
                       text-sm text-gray-200 placeholder-gray-600 outline-none
                       focus:border-indigo-600"
          />
          {search.trim() !== '' && (
            <div className="flex flex-wrap gap-1.5">
              {candidates?.users
                .filter(u => !memberIds.has(u.userId))
                .map(u => (
                  <button
                    key={u.userId}
                    onClick={() => addMember.mutate({ groupId: id, userId: u.userId })}
                    className="flex items-center gap-1.5 rounded-md border border-gray-800
                               bg-gray-900 px-2 py-1 text-xs text-gray-300 transition-colors
                               hover:border-indigo-700 hover:text-indigo-300"
                  >
                    <UserPlus size={12} /> {u.username}
                  </button>
                ))}
              {candidates?.users.filter(u => !memberIds.has(u.userId)).length === 0 && (
                <span className="text-xs text-gray-600">
                  Nobody new matches that — everyone found is already in the group.
                </span>
              )}
            </div>
          )}
        </div>
      </Panel>

      <RosterImportPanel groupId={id} />

      <TeamAnalyticsPanel teamId={id} />

      <Panel title="Contests" description="Rounds this team has sat, on Codeforces or DOMjudge">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="px-4 py-2 font-medium">Contest</th>
                <th className="px-4 py-2 font-medium">Where</th>
                <th className="px-4 py-2 font-medium">When</th>
                <th className="px-4 py-2 font-medium">Lock</th>
                <th className="px-4 py-2 font-medium" />
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {data.contests.length === 0 && (
                <EmptyRow colSpan={5}>
                  No contests yet. Point this group at one below.
                </EmptyRow>
              )}
              {data.contests.map(c => (
                <tr key={c.contestId} className="transition-colors hover:bg-gray-800/40">
                  <td className="px-4 py-2.5">
                    <Link
                      to={`/admin/groups/contests/${c.contestId}`}
                      className="font-medium text-gray-200 hover:text-indigo-400"
                    >
                      {c.name}
                    </Link>
                    <span className="ml-2"><Pill tone={STATUS_TONE[c.status]}>{c.status.toLowerCase()}</Pill></span>
                    {c.standingsError && (
                      <span className="ml-2"><Pill tone="amber">refresh failed</Pill></span>
                    )}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-gray-500">
                    {c.platform} · <span className="font-mono">{c.externalId}</span>
                  </td>
                  <td className="px-4 py-2.5 text-xs text-gray-500">
                    {c.startsAt ? <Ago at={c.startsAt} /> : '—'}
                  </td>
                  <td className="px-4 py-2.5">
                    {c.lockdownRequired
                      ? <Pill tone="indigo">required</Pill>
                      : <Pill tone="gray">off</Pill>}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <div className="flex items-center justify-end gap-2">
                      <Link
                        to={`/admin/groups/contests/${c.contestId}`}
                        className="inline-flex items-center gap-1 text-xs text-indigo-400
                                   hover:text-indigo-300"
                      >
                        Board <ArrowRight size={12} />
                      </Link>
                      <button
                        onClick={() => {
                          if (window.confirm(
                            `Remove "${c.name}"? Its standings and every violation recorded `
                            + 'for it are deleted with it. This cannot be undone.')) {
                            removeContest.mutate({ contestId: c.contestId })
                          }
                        }}
                        title="Remove this contest and everything recorded about it"
                        aria-label={`Remove ${c.name}`}
                        className="rounded-md border border-gray-800 p-1.5 text-gray-400
                                   transition-colors hover:bg-red-900/30 hover:text-red-400"
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="flex flex-wrap items-end gap-2 border-t border-gray-800 p-4">
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Judge</span>
            <select
              value={contest.platform}
              onChange={e => setContest(c => ({ ...c, platform: e.target.value }))}
              className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                         text-gray-300 outline-none focus:border-indigo-600"
            >
              <option value="CODEFORCES">Codeforces</option>
              <option value="DOMJUDGE">DOMjudge</option>
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Contest id</span>
            <input
              value={contest.externalId}
              onChange={e => setContest(c => ({ ...c, externalId: e.target.value }))}
              placeholder={contest.platform === 'CODEFORCES' ? '2258' : 'nwerc2024'}
              className="w-32 rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 font-mono
                         text-sm text-gray-200 placeholder-gray-600 outline-none
                         focus:border-indigo-600"
            />
          </label>
          <label className="flex min-w-[12rem] flex-1 flex-col gap-1">
            <span className="text-xs text-gray-500">Name</span>
            <input
              value={contest.name}
              onChange={e => setContest(c => ({ ...c, name: e.target.value }))}
              placeholder="Week 3 practice round"
              className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                         text-gray-200 placeholder-gray-600 outline-none focus:border-indigo-600"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Starts</span>
            <input
              type="datetime-local"
              value={contest.startsAt}
              onChange={e => setContest(c => ({ ...c, startsAt: e.target.value }))}
              className="rounded-lg border border-gray-800 bg-gray-900 px-2 py-2 text-xs
                         text-gray-300 outline-none focus:border-indigo-600"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Ends</span>
            <input
              type="datetime-local"
              value={contest.endsAt}
              onChange={e => setContest(c => ({ ...c, endsAt: e.target.value }))}
              className="rounded-lg border border-gray-800 bg-gray-900 px-2 py-2 text-xs
                         text-gray-300 outline-none focus:border-indigo-600"
            />
          </label>
          <label className="flex items-center gap-1.5 pb-2 text-xs text-gray-400">
            <input
              type="checkbox"
              checked={contest.lockdownRequired}
              onChange={e => setContest(c => ({ ...c, lockdownRequired: e.target.checked }))}
              className="accent-indigo-600"
            />
            Require the desktop lock
          </label>
          <button
            onClick={submitContest}
            disabled={!canAddContest || addContest.isPending}
            className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                       font-medium text-white transition-colors hover:bg-indigo-500
                       disabled:cursor-not-allowed disabled:opacity-40"
          >
            {addContest.isPending
              ? <Loader2 size={14} className="animate-spin" />
              : <Plus size={14} />}
            Add
          </button>
        </div>

        <p className="flex items-start gap-2 border-t border-gray-800 px-4 py-3 text-xs text-gray-500">
          <AlertTriangle size={13} className="mt-0.5 flex-shrink-0" />
          The start and end times decide when the lock is expected and which reports are
          accepted, so they should match the judge's own window rather than when you happen to
          add the contest here.
        </p>
      </Panel>
    </div>
  )
}
