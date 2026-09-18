import { Link } from 'react-router-dom'
import { Clock, ExternalLink, Loader2, Lock, Trophy, Users } from 'lucide-react'
import { clsx } from 'clsx'
import { useMyGroupContests, useMyGroups } from '@/hooks/useGroups'
import { formatDistanceToNow } from 'date-fns'

/**
 * A participant's own view of the groups they are in.
 *
 * Their placing and nothing about anyone else. Whether a group publishes its internal board to
 * its members is the admin's call, and defaulting to "everyone sees everyone" would make that
 * decision for them.
 *
 * Monitoring is stated plainly on every contest that has it, along with the threshold. Watching
 * someone without telling them is a different and much worse product than saying so up front.
 */
export default function GroupsPage() {
  const { data: groups, isLoading: groupsLoading } = useMyGroups()
  const { data: contests, isLoading: contestsLoading } = useMyGroupContests()

  if (groupsLoading || contestsLoading) {
    return (
      <div className="flex items-center justify-center gap-2 py-20 text-sm text-gray-500">
        <Loader2 size={16} className="animate-spin" /> Loading your groups…
      </div>
    )
  }

  if (!groups?.length) {
    return (
      <div className="mx-auto max-w-lg py-20 text-center">
        <Users size={28} className="mx-auto text-gray-700" />
        <p className="mt-3 text-sm text-gray-400">You are not in any groups yet.</p>
        <p className="mt-1 text-xs leading-relaxed text-gray-600">
          Groups are set up by an admin. When you are added to one, the contests it sits appear
          here along with where you placed among its members.
        </p>
      </div>
    )
  }

  const live = contests?.filter(c => c.contest.status === 'LIVE') ?? []
  const upcoming = contests?.filter(c => c.contest.status === 'SCHEDULED') ?? []
  const past = contests?.filter(c => c.contest.status === 'FINISHED') ?? []

  return (
    <div className="space-y-6">
      <section>
        <h2 className="mb-2 text-xs font-semibold uppercase tracking-wider text-gray-500">
          Your groups
        </h2>
        <div className="flex flex-wrap gap-2">
          {groups.map(group => (
            <span
              key={group.groupId}
              className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                         text-gray-300"
            >
              {group.name}
              <span className="ml-2 text-xs text-gray-600">{group.memberCount} members</span>
            </span>
          ))}
        </div>
      </section>

      {live.length > 0 && <ContestSection title="Running now" contests={live} />}
      {upcoming.length > 0 && <ContestSection title="Coming up" contests={upcoming} />}
      {past.length > 0 && <ContestSection title="Finished" contests={past} />}

      {contests?.length === 0 && (
        <p className="py-10 text-center text-sm text-gray-600">
          No contests have been set for your groups yet.
        </p>
      )}
    </div>
  )
}

function ContestSection({ title, contests }: {
  title: string
  contests: NonNullable<ReturnType<typeof useMyGroupContests>['data']>
}) {
  return (
    <section>
      <h2 className="mb-2 text-xs font-semibold uppercase tracking-wider text-gray-500">
        {title}
      </h2>
      <div className="space-y-2">
        {contests.map(({ contest, myRank, groupSize, mySolved }) => (
          <div
            key={contest.contestId}
            className="rounded-xl border border-gray-800 bg-gray-900 p-4"
          >
            <div className="flex flex-wrap items-baseline gap-2">
              <span className="font-medium text-gray-100">{contest.name}</span>
              <span className="text-xs text-gray-600">{contest.groupName}</span>
              {contest.lockdownRequired && (
                <span
                  title="Leaving the contest window is noticed while this round is running.
                         You are warned after ten seconds away, and longer absences are
                         reported to whoever runs the group."
                  className="flex items-center gap-1 rounded bg-indigo-950/60 px-1.5 py-0.5
                             text-[11px] text-indigo-300"
                >
                  <Lock size={10} /> monitored
                </span>
              )}
            </div>

            <div className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-gray-500">
              <span className="flex items-center gap-1">
                <Clock size={11} />
                {contest.startsAt
                  ? formatDistanceToNow(new Date(contest.startsAt), { addSuffix: true })
                  : 'no start time set'}
              </span>
              <span>{contest.platform} · {contest.externalId}</span>
              {contest.url && (
                <a
                  href={contest.url}
                  target="_blank"
                  rel="noreferrer"
                  className="flex items-center gap-1 text-indigo-400 hover:text-indigo-300"
                >
                  Open <ExternalLink size={10} />
                </a>
              )}
              {contest.status === 'LIVE' && (
                <Link to="/compete" className="text-indigo-400 hover:text-indigo-300">
                  Go to Compete →
                </Link>
              )}
            </div>

            {myRank != null && (
              <div className={clsx(
                'mt-2 flex items-center gap-2 text-sm',
                myRank === 1 ? 'text-yellow-400' : 'text-gray-300'
              )}>
                <Trophy size={14} />
                <span className="font-medium">
                  {myRank}
                  {groupSize ? ` of ${groupSize}` : ''} in your group
                </span>
                {mySolved != null && (
                  <span className="text-xs text-gray-600">{mySolved} solved</span>
                )}
              </div>
            )}
            {myRank == null && contest.status !== 'SCHEDULED' && (
              <p className="mt-2 text-xs text-gray-600">
                No placing yet — the board is built from the judge a few minutes behind live.
              </p>
            )}
          </div>
        ))}
      </div>
    </section>
  )
}
