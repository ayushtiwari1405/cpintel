import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { format } from 'date-fns'
import { Eye, FileText, Loader2, Plus, Trophy } from 'lucide-react'

import { NewTile, Panel, Pill, Shelf, Tile, TileGrid, TileNote, revealForm } from '@/components/admin/AdminUi'
import { useAdminEvents, useCreateEvent } from '@/hooks/useExams'
import { useAdminClassrooms } from '@/hooks/useClassrooms'
import { ClassroomSelect } from '@/components/admin/ClassroomSelect'
import { useAdminGroups } from '@/hooks/useGroups'
import type { EventKind, EventLifecycle, EventSummary } from '@/types'
import { PageHeader } from '@/components/common/PageHeader'

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
  // ?kind=CONTEST&classroom=3&new=1 — how a classroom's page links here to add one of its own.
  const [params] = useSearchParams()
  const fromClassroom = Number(params.get('classroom')) || null
  const [kind, setKind] = useState<EventKind>(params.get('kind') === 'CONTEST' ? 'CONTEST' : 'EXAM')
  const { data: allEvents, isLoading } = useAdminEvents(kind)
  const { data: allTeams } = useAdminGroups()
  const { data: classrooms } = useAdminClassrooms()
  const create = useCreateEvent()

  const [filter, setFilter] = useState<number | null>(fromClassroom)
  const [classroomId, setClassroomId] = useState<number | null>(fromClassroom)
  const askedForNew = params.get('new') === '1'
  useEffect(() => { if (askedForNew) revealForm('new-event') }, [askedForNew])
  const events = allEvents?.filter(e => filter == null || e.classroomId === filter)
  // An event is sat by teams in its own classroom, on that classroom's judge.
  const teams = allTeams?.filter(t => t.classroomId === classroomId)
  const classroomName = (id: number) =>
    classrooms?.find(c => c.classroomId === id)?.name ?? `Classroom ${id}`

  const [name, setName] = useState('')
  // Chosen first: the rest of the form depends on the judge.
  const [platform, setPlatform] = useState<Platform | null>(null)
  const [externalId, setExternalId] = useState('')
  // On DOMjudge CPIntel names and creates the contest; linking one made on the judge is optional.
  const [linking, setLinking] = useState(false)
  const needsId = platform === 'CODEFORCES' || linking
  const [startsAt, setStartsAt] = useState('')
  const [endsAt, setEndsAt] = useState('')
  const [teamId, setTeamId] = useState<number | ''>('')
  // Asked at creation rather than left to a separate screen: whether a candidate may open
  // their own notes is part of what the paper is, and it is fixed once the paper starts.
  const [files, setFiles] = useState<'' | 'allow' | 'deny'>('')

  const exam = kind === 'EXAM'
  const filesAllowed = files === '' ? !exam : files === 'allow'

  const submit = () => {
    if (!platform || !name.trim() || (needsId && !externalId.trim()) || classroomId == null) return
    create.mutate({
      classroomId,
      kind,
      platform,
      externalId: needsId ? externalId.trim() : null,
      name: name.trim(),
      startsAt: startsAt ? new Date(startsAt).toISOString() : null,
      endsAt: endsAt ? new Date(endsAt).toISOString() : null,
      teamId: teamId === '' ? null : Number(teamId),
      personalFilesAllowed: filesAllowed,
    }, {
      onSuccess: () => {
        setName(''); setExternalId(''); setStartsAt(''); setEndsAt(''); setTeamId('')
        setFiles(''); setPlatform(null); setLinking(false)
      },
    })
  }

  return (
    <div className="space-y-4">
      <PageHeader title="Examinations and contests"
        className="mb-2" />
      <div className="flex items-center gap-1 rounded-lg border border-gray-800 bg-gray-900 p-1
        w-fit">
        {(['EXAM', 'CONTEST'] as const).map(value => (
          <button
            key={value}
            onClick={() => setKind(value)}
            aria-pressed={kind === value}
            className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
              kind === value
                ? 'bg-accent-600/20 text-accent-300'
                : 'text-gray-500 hover:text-gray-300'
            }`}
          >
            {value === 'EXAM' ? 'Examinations' : 'Contests'}
          </button>
        ))}
      </div>

      <Shelf title={exam ? 'Examinations' : 'Contests'} count={events?.length}
        actions={<ClassroomSelect value={filter} onChange={setFilter} allowAll
          className="rounded-md border border-gray-800 bg-gray-900 px-2 py-1 text-xs
                     text-gray-300 outline-hidden" />}>
        <TileGrid label={exam ? 'Examinations' : 'Contests'}>
          <NewTile label={exam ? 'New examination' : 'New contest'}
            onClick={() => revealForm('new-event')} />
          {isLoading && <TileNote>Loading…</TileNote>}
          {!isLoading && events?.length === 0 && (
            <TileNote>{exam ? 'No examinations yet.' : 'No contests yet.'}</TileNote>
          )}
          {events?.map(event => (
            <EventTile key={event.eventId} event={event}
              classroom={filter == null ? classroomName(event.classroomId) : null} />
          ))}
        </TileGrid>
      </Shelf>

      <div id="new-event" className="scroll-mt-4">
      <Panel
        title={exam ? 'New examination' : 'New contest'}
        description={platform
          ? `On ${JUDGES[platform].name} · created as a draft`
          : 'Choose the judge it runs on'}
        actions={platform && (
          <button onClick={() => { setPlatform(null); setExternalId(''); setLinking(false) }}
            className="text-xs text-accent-400 hover:text-accent-300">
            Change judge
          </button>
        )}
      >
        {!platform ? (
          <div className="grid gap-2 p-4 sm:grid-cols-2">
            {(Object.keys(JUDGES) as Platform[]).map(value => (
              <button key={value} onClick={() => setPlatform(value)}
                className="rounded-lg border border-gray-800 bg-gray-900 p-4 text-left
                  transition-colors hover:border-accent-600 focus-visible:border-accent-600">
                <span className="block text-sm font-medium text-gray-100">
                  {JUDGES[value].name}
                </span>
                <span className="mt-1 block text-xs text-gray-500">{JUDGES[value].hint}</span>
              </button>
            ))}
          </div>
        ) : (
        <div className="flex flex-wrap items-end gap-2 p-4">
          <Field label="Classroom" className="min-w-48">
            <ClassroomSelect value={classroomId} onChange={id => { setClassroomId(id); setTeamId('') }} />
          </Field>
          <Field label="Name" className="min-w-56 flex-1">
            <input
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder={exam ? 'Mid-semester practical' : 'Week 3 round'}
              maxLength={200}
              className={INPUT}
            />
          </Field>
          {needsId && (
            <Field label={platform === 'CODEFORCES' ? 'Contest link' : 'Existing DOMjudge contest id'}
              className="min-w-56">
              <input
                value={externalId}
                onChange={e => setExternalId(e.target.value)}
                placeholder={platform === 'CODEFORCES'
                  ? 'https://codeforces.com/contest/2259' : 'midsem24'}
                maxLength={platform === 'CODEFORCES' ? 500 : 100}
                className={INPUT}
              />
            </Field>
          )}
          <Field label="Starts" className="min-w-48">
            <input type="datetime-local" value={startsAt}
              onChange={e => setStartsAt(e.target.value)} className={INPUT} />
          </Field>
          <Field label="Ends" className="min-w-48">
            <input type="datetime-local" value={endsAt}
              onChange={e => setEndsAt(e.target.value)} className={INPUT} />
          </Field>
          <Field label="Owning team (optional)" className="min-w-48">
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
          <Field label="Private files" className="min-w-48">
            <select
              value={files === '' ? (exam ? 'deny' : 'allow') : files}
              onChange={e => setFiles(e.target.value as 'allow' | 'deny')}
              className={INPUT}
            >
              <option value="deny">Not allowed</option>
              <option value="allow">Allowed — own notes and templates</option>
            </select>
          </Field>
          <button
            onClick={submit}
            disabled={!name.trim() || (needsId && !externalId.trim()) || classroomId == null
              || create.isPending}
            className="flex items-center gap-1.5 rounded-lg bg-accent-600 px-3 py-2 text-sm
                       font-medium text-white transition-colors hover:bg-accent-500
                       disabled:cursor-not-allowed disabled:opacity-40"
          >
            {create.isPending ? <Loader2 size={14} className="animate-spin" /> : <Plus size={14} />}
            Create draft
          </button>
          {platform === 'DOMJUDGE' && (
            <p className="basis-full text-xs text-gray-500">
              {linking
                ? 'Links a contest already on the judge. Bank questions can only go into contests CPIntel creates. '
                : 'CPIntel creates the contest on DOMjudge when you publish its questions. '}
              <button type="button" onClick={() => { setLinking(!linking); setExternalId('') }}
                className="text-accent-400 hover:text-accent-300">
                {linking ? 'Create it from CPIntel instead' : 'Link an existing contest instead'}
              </button>
            </p>
          )}
        </div>
        )}
      </Panel>
      </div>

    </div>
  )
}

type Platform = 'DOMJUDGE' | 'CODEFORCES'

const JUDGES: Record<Platform, { name: string, hint: string }> = {
  DOMJUDGE: {
    name: 'DOMjudge',
    hint: 'Built from the question bank.',
  },
  CODEFORCES: {
    name: 'Codeforces',
    hint: 'A contest link; candidates use their own accounts.',
  },
}

const INPUT = 'rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-200 '
  + 'placeholder-gray-400 outline-hidden focus:border-accent-600'

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

const LIFECYCLE_TONE = {
  DRAFT: 'gray', SCHEDULED: 'amber', ACTIVE: 'green', ENDED: 'gray', ARCHIVED: 'gray',
} as const

/**
 * An examination or contest as a tile. Shared with the classroom page, which shows its own
 * events the same way; `classroom` names the classroom when the list mixes several.
 */
export function EventTile({ event, classroom }: { event: EventSummary; classroom?: string | null }) {
  const exam = event.kind === 'EXAM'
  const when = event.startsAt ? format(new Date(event.startsAt), 'd MMM yyyy, HH:mm') : 'No start time'
  return (
    <Tile
      to={`/admin/exams/${event.eventId}`}
      icon={exam ? FileText : Trophy}
      kind={exam ? 'Exam' : 'Contest'}
      tone={event.completedAt ? 'gray' : LIFECYCLE_TONE[event.lifecycle]}
      status={event.completedAt ? <Pill tone="green">done</Pill> : <LifecyclePill lifecycle={event.lifecycle} />}
      corner={`${event.problemCount} problem${event.problemCount === 1 ? '' : 's'}`}
      title={event.name}
      meta={[
        when,
        <>
          {event.participantCount} assigned · {event.platform === 'CODEFORCES' ? 'Codeforces' : 'DOMjudge'}
          {event.lockdownRequired && (
            <span className="ml-1 inline-flex items-center gap-0.5 text-accent-300">
              · <Eye size={11} aria-hidden="true" /> monitored
            </span>
          )}
        </>,
        [classroom, event.teamName].filter(Boolean).join(' · ') || null,
      ]}
    />
  )
}

export function LifecyclePill({ lifecycle }: { lifecycle: EventLifecycle }) {
  const tone = LIFECYCLE_TONE[lifecycle]

  const label = ({
    DRAFT: 'draft', SCHEDULED: 'scheduled', ACTIVE: 'live', ENDED: 'ended', ARCHIVED: 'archived',
  })[lifecycle]

  return <Pill tone={tone}>{label}</Pill>
}

export type { EventSummary }
