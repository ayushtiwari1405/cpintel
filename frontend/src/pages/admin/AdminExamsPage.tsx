import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, Eye, Loader2, Plus, ShieldCheck } from 'lucide-react'

import { Ago, EmptyRow, Panel, Pill } from '@/components/admin/AdminUi'
import { useAdminEvents, useCreateEvent } from '@/hooks/useExams'
import { useAdminGroups } from '@/hooks/useGroups'
import type { EventKind, EventLifecycle, EventSummary } from '@/types'

/**
 * Examinations and contests, listed side by side.
 *
 * <p>One screen for both because an admin thinks of them together — this week's round and next
 * week's paper — and because they are the same object underneath. The kind switch is at the top
 * rather than being two pages, so the difference stays a property of the event rather than a
 * fact about where you happened to click.
 *
 * <p>A new event is created as a draft, and the list says so plainly. Nothing reaches the people
 * sitting it until somebody publishes it, which is the one irreversible-feeling step here and
 * therefore the one that gets its own click on the event's own page.
 */
export default function AdminExamsPage() {
  const [kind, setKind] = useState<EventKind>('EXAM')
  const { data: events, isLoading } = useAdminEvents(kind)
  const { data: teams } = useAdminGroups()
  const create = useCreateEvent()

  const [name, setName] = useState('')
  const [externalId, setExternalId] = useState('')
  const [startsAt, setStartsAt] = useState('')
  const [endsAt, setEndsAt] = useState('')
  const [teamId, setTeamId] = useState<number | ''>('')

  const exam = kind === 'EXAM'

  const submit = () => {
    if (!name.trim() || !externalId.trim()) return
    create.mutate({
      kind,
      // An examination runs on the judge this deployment controls; the server refuses anything
      // else, and offering the choice here would only produce a rejected form.
      platform: exam ? 'DOMJUDGE' : 'DOMJUDGE',
      externalId: externalId.trim(),
      name: name.trim(),
      startsAt: startsAt ? new Date(startsAt).toISOString() : null,
      endsAt: endsAt ? new Date(endsAt).toISOString() : null,
      teamId: teamId === '' ? null : Number(teamId),
    }, {
      onSuccess: () => {
        setName(''); setExternalId(''); setStartsAt(''); setEndsAt(''); setTeamId('')
      },
    })
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-1 rounded-lg border border-gray-800 bg-gray-900 p-1
        w-fit">
        {(['EXAM', 'CONTEST'] as const).map(value => (
          <button
            key={value}
            onClick={() => setKind(value)}
            className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
              kind === value
                ? 'bg-indigo-600/20 text-indigo-300'
                : 'text-gray-500 hover:text-gray-300'
            }`}
          >
            {value === 'EXAM' ? 'Examinations' : 'Contests'}
          </button>
        ))}
      </div>

      <Panel
        title={exam ? 'Examinations' : 'Contests'}
        description={exam
          ? 'Assigned, monitored, and logged — every session is recorded'
          : 'Rounds laid over a judge, for teams or for everybody'}
      >
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="px-4 py-2 font-medium">Name</th>
                <th className="px-4 py-2 font-medium">State</th>
                <th className="px-4 py-2 font-medium">Window</th>
                <th className="px-4 py-2 font-medium">Assigned</th>
                <th className="px-4 py-2 font-medium">Problems</th>
                <th className="px-4 py-2 font-medium" />
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {isLoading && <EmptyRow colSpan={6}>Loading…</EmptyRow>}
              {!isLoading && events?.length === 0 && (
                <EmptyRow colSpan={6}>
                  {exam
                    ? 'No examinations yet. Create one below, assign the teams or people '
                      + 'sitting it, then publish it.'
                    : 'No contests yet.'}
                </EmptyRow>
              )}
              {events?.map(event => (
                <tr key={event.eventId} className="transition-colors hover:bg-gray-800/40">
                  <td className="px-4 py-2.5">
                    <Link
                      to={`/admin/exams/${event.eventId}`}
                      className="font-medium text-gray-200 hover:text-indigo-400"
                    >
                      {event.name}
                    </Link>
                    <p className="text-xs text-gray-600">
                      {event.platform} · {event.externalId}
                      {event.teamName && <> · {event.teamName}</>}
                    </p>
                  </td>
                  <td className="px-4 py-2.5">
                    <LifecyclePill lifecycle={event.lifecycle} />
                    {event.lockdownRequired && (
                      <span className="ml-2 inline-flex items-center gap-1 text-[11px]
                        text-indigo-300">
                        <Eye size={11} /> monitored
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-gray-500">
                    {event.startsAt ? <Ago at={event.startsAt} /> : 'not set'}
                  </td>
                  <td className="px-4 py-2.5 text-xs tabular-nums text-gray-400">
                    {event.participantCount}
                    <span className="text-gray-600">
                      {' '}({event.assignedTeams} team{event.assignedTeams === 1 ? '' : 's'},
                      {' '}{event.assignedUsers} named)
                    </span>
                  </td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">{event.problemCount}</td>
                  <td className="px-4 py-2.5 text-right">
                    <Link
                      to={`/admin/exams/${event.eventId}`}
                      className="inline-flex items-center gap-1 text-xs text-indigo-400
                                 hover:text-indigo-300"
                    >
                      Open <ArrowRight size={12} />
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel
        title={exam ? 'New examination' : 'New contest'}
        description="Created as a draft — nobody sees it until you publish it"
      >
        <div className="flex flex-wrap items-end gap-2 p-4">
          <Field label="Name" className="min-w-[14rem] flex-1">
            <input
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder={exam ? 'Mid-semester practical' : 'Week 3 round'}
              maxLength={200}
              className={INPUT}
            />
          </Field>
          <Field label="DOMjudge contest id" className="min-w-[10rem]">
            <input
              value={externalId}
              onChange={e => setExternalId(e.target.value)}
              placeholder="midsem24"
              maxLength={100}
              className={INPUT}
            />
          </Field>
          <Field label="Starts" className="min-w-[12rem]">
            <input type="datetime-local" value={startsAt}
              onChange={e => setStartsAt(e.target.value)} className={INPUT} />
          </Field>
          <Field label="Ends" className="min-w-[12rem]">
            <input type="datetime-local" value={endsAt}
              onChange={e => setEndsAt(e.target.value)} className={INPUT} />
          </Field>
          <Field label="Owning team (optional)" className="min-w-[12rem]">
            <select
              value={teamId}
              onChange={e => setTeamId(e.target.value === '' ? '' : Number(e.target.value))}
              className={INPUT}
            >
              <option value="">None — assign people on the next screen</option>
              {teams?.map(team => (
                <option key={team.groupId} value={team.groupId}>{team.name}</option>
              ))}
            </select>
          </Field>
          <button
            onClick={submit}
            disabled={!name.trim() || !externalId.trim() || create.isPending}
            className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                       font-medium text-white transition-colors hover:bg-indigo-500
                       disabled:cursor-not-allowed disabled:opacity-40"
          >
            {create.isPending ? <Loader2 size={14} className="animate-spin" /> : <Plus size={14} />}
            Create draft
          </button>
        </div>
      </Panel>

      <p className="flex items-start gap-2 px-1 text-xs leading-relaxed text-gray-600">
        <ShieldCheck size={13} className="mt-0.5 flex-shrink-0" />
        CPIntel does not judge an examination — DOMjudge does, with its own clock and its own
        verdicts. What CPIntel adds is who may sit it, what is recorded while they do, and a
        log an invigilator can read afterwards. Every figure in that log is an observation
        rather than a finding.
      </p>
    </div>
  )
}

const INPUT = 'rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-200 '
  + 'placeholder-gray-600 outline-none focus:border-indigo-600'

function Field({ label, className, children }: {
  label: string
  className?: string
  children: React.ReactNode
}) {
  return (
    <label className={`flex flex-col gap-1 ${className ?? ''}`}>
      <span className="text-xs text-gray-500">{label}</span>
      {children}
    </label>
  )
}

export function LifecyclePill({ lifecycle }: { lifecycle: EventLifecycle }) {
  const tone = ({
    DRAFT:     'gray',
    SCHEDULED: 'amber',
    ACTIVE:    'green',
    ENDED:     'gray',
    ARCHIVED:  'gray',
  } as const)[lifecycle]

  const label = ({
    DRAFT: 'draft', SCHEDULED: 'scheduled', ACTIVE: 'live', ENDED: 'ended', ARCHIVED: 'archived',
  })[lifecycle]

  return <Pill tone={tone}>{label}</Pill>
}

export type { EventSummary }
