import { Link } from 'react-router-dom'
import { Clock, ExternalLink, Lock, Trophy, Users, ArrowRight } from 'lucide-react'
import { clsx } from 'clsx'
import { useMyGroupContests, useMyGroups } from '@/hooks/useGroups'
import { formatDistanceToNow } from 'date-fns'
import { judgeIdOf } from '@/utils/contestRef'
import { PageHeader } from '@/components/common/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/common/States'

/**
 * A participant's own view of the teams they are on.
 *
 * Their placing and nothing about anyone else. Whether a team publishes its internal board to
 * its members is the admin's call, and defaulting to "everyone sees everyone" would make that
 * decision for them.
 *
 * Monitoring is stated plainly on every contest that has it, along with the threshold. Watching
 * someone without telling them is a different and much worse product than saying so up front.
 */
export default function GroupsPage() {
  const groupsQ = useMyGroups()
  const contestsQ = useMyGroupContests()
  const groups = groupsQ.data
  const contests = contestsQ.data

  const header = <PageHeader title="Teams"
    description="The teams you are on, the contests they sit, and where you placed among your teammates." />

  if (groupsQ.isLoading || contestsQ.isLoading) {
    return <>{header}<LoadingState label="Loading your teams…" /></>
  }
  if (groupsQ.isError || contestsQ.isError) {
    return <>{header}<ErrorState title="Could not load your teams"
      error={groupsQ.error ?? contestsQ.error}
      onRetry={() => { groupsQ.refetch(); contestsQ.refetch() }} /></>
  }

  if (!groups?.length) {
    return (
      <>
        {header}
        <div className="rounded-xl border border-gray-800">
          <EmptyState icon={<Users size={18} />} title="You are not on any teams yet">
            Teams are set up by an admin. When you are added to one, the contests it sits appear
            here along with where you placed among its members.
          </EmptyState>
        </div>
      </>
    )
  }

  const live = contests?.filter(c => c.contest.status === 'LIVE') ?? []
  const upcoming = contests?.filter(c => c.contest.status === 'SCHEDULED') ?? []
  const past = contests?.filter(c => c.contest.status === 'FINISHED') ?? []

  return (
    <div className="space-y-8">
      {header}

      <section aria-labelledby="your-teams">
        <h2 id="your-teams" className="mb-2 text-[11px] font-semibold uppercase tracking-[0.08em] text-gray-500">
          Your teams
        </h2>
        <ul className="flex flex-wrap gap-2">
          {groups.map(group => (
            <li key={group.groupId}
              className="flex items-center gap-2 rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm">
              <Users size={14} className="text-gray-500" aria-hidden="true" />
              <span className="text-gray-100">{group.name}</span>
              <span className="font-mono text-xs tabular-nums text-gray-500">{group.memberCount} members</span>
            </li>
          ))}
        </ul>
      </section>

      {live.length > 0 && <ContestSection title="Running now" contests={live} />}
      {upcoming.length > 0 && <ContestSection title="Coming up" contests={upcoming} />}
      {past.length > 0 && <ContestSection title="Finished" contests={past} />}

      {contests?.length === 0 && (
        <div className="rounded-xl border border-gray-800">
          <EmptyState compact title="No contests yet">
            No contests have been set for your teams yet.
          </EmptyState>
        </div>
      )}
    </div>
  )
}

const STATUS: Record<string, { label: string; className: string }> = {
  LIVE:      { label: 'Live',     className: 'badge-green' },
  SCHEDULED: { label: 'Upcoming', className: 'badge-blue' },
  FINISHED:  { label: 'Finished', className: 'badge-gray' },
}

function ContestSection({ title, contests }: {
  title: string
  contests: NonNullable<ReturnType<typeof useMyGroupContests>['data']>
}) {
  const id = `teams-${title.toLowerCase().replace(/\s+/g, '-')}`
  return (
    <section aria-labelledby={id}>
      <h2 id={id} className="mb-2 text-[11px] font-semibold uppercase tracking-[0.08em] text-gray-500">
        {title}
      </h2>
      <ul className="divide-y divide-gray-800 overflow-hidden rounded-xl border border-gray-800 bg-gray-900">
        {contests.map(({ contest, myRank, groupSize, mySolved }) => {
          const st = STATUS[contest.status]
          return (
            <li key={contest.contestId} className="p-4">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium text-gray-50">{contest.name}</span>
                {st && (
                  <span className={st.className}>
                    {contest.status === 'LIVE' && (
                      <span className="h-1.5 w-1.5 rounded-full bg-green-400 motion-safe:animate-pulse" aria-hidden="true" />
                    )}
                    {st.label}
                  </span>
                )}
                <span className="text-xs text-gray-500">{contest.groupName}</span>
              </div>

              <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-gray-500">
                <span className="flex items-center gap-1">
                  <Clock size={11} aria-hidden="true" />
                  {contest.startsAt
                    ? formatDistanceToNow(new Date(contest.startsAt), { addSuffix: true })
                    : 'No start time set'}
                </span>
                <span>
                  {contest.platform === 'CODEFORCES' ? 'Codeforces' : contest.platform}
                  {' · '}<span className="font-mono text-gray-400">{judgeIdOf(contest.externalId)}</span>
                </span>
                {contest.url && (
                  <a
                    href={contest.url}
                    target="_blank"
                    rel="noreferrer"
                    className="flex items-center gap-1 text-accent-400 hover:text-accent-300"
                  >
                    Open on the judge <ExternalLink size={10} aria-hidden="true" />
                    <span className="sr-only">(opens in a new tab)</span>
                  </a>
                )}
                {contest.status === 'LIVE' && (
                  <Link to="/compete" className="flex items-center gap-1 font-medium text-accent-400 hover:text-accent-300">
                    Go to Compete <ArrowRight size={11} aria-hidden="true" />
                  </Link>
                )}
              </div>

              {/* Said in words on the contest itself, not hidden in a hover: being watched is
                  something to be told plainly. */}
              {contest.lockdownRequired && (
                <p className="mt-2 flex items-start gap-1.5 rounded-md border border-amber-900/50
                              bg-amber-950/20 px-2.5 py-1.5 text-xs text-amber-200/90">
                  <Lock size={12} className="mt-0.5 flex-shrink-0 text-amber-400" aria-hidden="true" />
                  <span>
                    <span className="font-medium text-amber-200">Monitored.</span> Leaving the
                    contest window is noticed while this round runs: you are warned after ten
                    seconds away, and longer absences are reported to whoever runs the team.
                  </span>
                </p>
              )}

              {myRank != null && (
                <div className={clsx(
                  'mt-2.5 flex items-center gap-2 text-sm',
                  myRank === 1 ? 'text-yellow-300' : 'text-gray-200'
                )}>
                  <Trophy size={14} aria-hidden="true" />
                  <span>
                    <span className="font-mono font-semibold tabular-nums">{myRank}</span>
                    {groupSize ? <> of <span className="font-mono tabular-nums">{groupSize}</span></> : ''} in your team
                  </span>
                  {mySolved != null && (
                    <span className="text-xs text-gray-500">
                      · <span className="font-mono tabular-nums">{mySolved}</span> solved
                    </span>
                  )}
                </div>
              )}
              {myRank == null && contest.status !== 'SCHEDULED' && (
                <p className="mt-2 text-xs text-gray-500">
                  No placing yet. The board is built from the judge a few minutes behind live.
                </p>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
