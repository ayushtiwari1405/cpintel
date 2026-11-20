import { Link, useParams } from 'react-router-dom'
import { format } from 'date-fns'
import { ArrowLeft, BadgeCheck, ExternalLink } from 'lucide-react'
import { clsx } from 'clsx'
import { PageHeader } from '@/components/common/PageHeader'
import { ErrorState, LoadingState } from '@/components/common/States'
import { Panel, Pill, StatCard } from '@/components/admin/AdminUi'
import { PlatformPill } from '@/components/admin/PlatformPill'
import { useClassroomStudentDashboard } from '@/hooks/useClassrooms'
import type {
  LabState, StudentDashboard, StudentDashboardEvent, StudentDashboardLab,
} from '@/types'

const LAB_STATE: Record<LabState, { label: string; tone: 'gray' | 'green' | 'amber' }> = {
  UPCOMING: { label: 'Upcoming', tone: 'gray' },
  OPEN:     { label: 'Open', tone: 'green' },
  CLOSED:   { label: 'Closed', tone: 'amber' },
}

/**
 * One student inside one classroom: every lab set for them, problem by problem, and every
 * contest and examination, question by question.
 *
 * Only the classroom's own work. Their personal practice — roadmap, gauntlet, recommendations —
 * is theirs and is not shown to staff.
 */
export default function AdminStudentDashboardPage() {
  const { classroomId: rawRoom, userId: rawUser } = useParams()
  const classroomId = Number(rawRoom)
  const userId = Number(rawUser)
  const valid = Number.isFinite(classroomId) && Number.isFinite(userId)
  const { data, isLoading, isError, error, refetch } =
    useClassroomStudentDashboard(valid ? classroomId : null, valid ? userId : null)

  if (!valid) return <ErrorState title="Not found" error={new Error('That address does not name a student.')} />
  if (isError) return <ErrorState title="Could not load this student" error={error} onRetry={() => refetch()} />
  if (isLoading || !data) return <LoadingState label="Loading the student…" />

  return (
    <div className="space-y-4">
      <Link to={`/admin/classrooms/${classroomId}/dashboard`}
        className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-300">
        <ArrowLeft size={12} /> {data.classroomName} dashboard
      </Link>
      <PageHeader eyebrow={`${data.classroomName} · Student`} title={data.username}
        description={[data.fullName, data.email].filter(Boolean).join(' · ') || undefined}
        meta={<>
          {data.teams.length
            ? data.teams.map(t => <Pill key={t}>{t}</Pill>)
            : <span className="text-gray-500">In no team</span>}
          <Handles data={data} />
        </>}
        className="mb-2" />

      <Headline data={data} />
      <Labs classroomId={classroomId} labs={data.labs} />
      <Events events={data.events} />
    </div>
  )
}

function Handles({ data }: { data: StudentDashboard }) {
  const h = data.handles
  const tags: Array<[string, string | null, boolean]> = [
    ['CF', h.codeforces, h.codeforcesVerified],
    ['LC', h.leetcode, h.leetcodeVerified],
    ['CSES', h.cses, h.csesConnected],
  ]
  return (
    <span className="flex flex-wrap gap-x-3 text-xs">
      {tags.map(([label, handle, ok]) => handle
        ? (
          <span key={label} className={clsx('inline-flex items-center gap-0.5 font-mono',
            ok ? 'text-gray-300' : 'text-amber-400')}
            title={ok ? 'Verified' : 'Not verified: lab solves on it are not counted'}>
            {label}: {handle}
            {ok && <BadgeCheck size={11} className="text-green-400" aria-label="verified" />}
          </span>
        )
        : <span key={label} className="text-gray-600">{label}: none</span>)}
    </span>
  )
}

function Headline({ data }: { data: StudentDashboard }) {
  const solved = sum(data.labs.map(l => l.solved))
  const late = sum(data.labs.map(l => l.late))
  const problems = sum(data.labs.map(l => l.problems.length))
  const marks = sum(data.labs.map(l => l.marks))
  const total = sum(data.labs.map(l => l.totalMarks))
  const started = data.events.filter(e => e.lifecycle !== 'SCHEDULED')
  const sat = started.filter(e => e.sat).length
  const focus = sum(data.events.map(e => e.focusLosses))

  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <StatCard label="Lab problems solved" value={problems ? `${solved} / ${problems}` : '—'}
        hint={late ? `plus ${late} solved late` : 'all in time'}
        tone={!problems ? 'default' : solved === problems ? 'good' : solved === 0 ? 'danger' : 'default'} />
      <StatCard label="Lab marks" value={total ? `${num(marks)} / ${num(total)}` : '—'}
        hint={total ? `${Math.round((marks / total) * 100)}%` : 'no labs set'} />
      <StatCard label="Contests and exams" value={started.length ? `${sat} / ${started.length}` : '—'}
        hint={started.length - sat > 0 ? `missed ${started.length - sat}` : 'attended every one so far'}
        tone={started.length - sat > 0 ? 'danger' : 'default'} />
      <StatCard label="Focus losses" value={focus} tone={focus > 0 ? 'warn' : 'default'}
        hint="left the exam window" />
    </div>
  )
}

// --------------------------------------------------------------------- labs

function Labs({ classroomId, labs }: { classroomId: number; labs: StudentDashboardLab[] }) {
  return (
    <Panel title="Labs">
      {labs.length === 0 && <p className="px-4 py-8 text-center text-sm text-gray-600">No labs set for this student.</p>}
      <div className="divide-y divide-gray-800">
        {labs.map(lab => {
          const state = LAB_STATE[lab.state]
          return (
            <section key={lab.labId} className="px-4 py-3">
              <header className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <Link to={`/admin/classrooms/${classroomId}/labs/${lab.labId}`}
                  className="text-sm font-medium text-gray-100 hover:text-accent-300">{lab.title}</Link>
                <Pill tone={state.tone}>{state.label}</Pill>
                <span className="text-xs text-gray-500">due {format(new Date(lab.dueAt), 'd MMM yyyy, HH:mm')}</span>
                <span className="ml-auto font-mono text-xs tabular-nums text-gray-300">
                  {lab.solved}/{lab.problems.length} solved · {num(lab.marks)} / {num(lab.totalMarks)} marks
                </span>
              </header>
              <ol className="mt-2 space-y-1">
                {lab.problems.map((p, i) => (
                  <li key={p.problemId} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                    <span className="w-5 text-xs text-gray-600">{i + 1}</span>
                    <a href={p.url} target="_blank" rel="noreferrer"
                      className="inline-flex min-w-0 flex-1 items-center gap-1 truncate text-gray-300 hover:text-accent-300">
                      {p.title} <ExternalLink size={11} className="shrink-0 text-gray-600" aria-hidden="true" />
                    </a>
                    <PlatformPill platform={p.platform} />
                    <span className="w-40 text-right">
                      {p.status === 'SOLVED' && <Pill tone="green">Solved {when(p.solvedAt)}</Pill>}
                      {p.status === 'LATE' && <Pill tone="amber">Late {when(p.solvedAt)}</Pill>}
                      {!p.status && <span className="text-xs text-gray-600">not solved</span>}
                    </span>
                    <span className="w-16 text-right font-mono text-xs text-gray-500">{num(p.marks)} marks</span>
                  </li>
                ))}
              </ol>
            </section>
          )
        })}
      </div>
    </Panel>
  )
}

// ------------------------------------------------------------------- events

function Events({ events }: { events: StudentDashboardEvent[] }) {
  return (
    <Panel title="Contests and examinations"
      description="TA marks included">
      {events.length === 0 && (
        <p className="px-4 py-8 text-center text-sm text-gray-600">Nothing assigned to this student.</p>
      )}
      <div className="divide-y divide-gray-800">
        {events.map(e => <EventRow key={e.eventId} event={e} />)}
      </div>
    </Panel>
  )
}

function EventRow({ event: e }: { event: StudentDashboardEvent }) {
  const upcoming = e.lifecycle === 'SCHEDULED'
  return (
    <section className="px-4 py-3">
      <header className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <Link to={`/admin/exams/${e.eventId}`} className="text-sm font-medium text-gray-100 hover:text-accent-300">
          {e.name}
        </Link>
        <Pill tone={e.kind === 'EXAM' ? 'accent' : 'gray'}>{e.kind === 'EXAM' ? 'exam' : 'contest'}</Pill>
        {upcoming ? <Pill>Upcoming</Pill> : e.sat ? <Pill tone="green">Sat it</Pill> : <Pill tone="red">Absent</Pill>}
        {e.startsAt && <span className="text-xs text-gray-500">{format(new Date(e.startsAt), 'd MMM yyyy, HH:mm')}</span>}
      </header>

      {!upcoming && (
        <dl className="mt-2 flex flex-wrap gap-x-6 gap-y-1 text-xs">
          <Figure label="Score" value={e.score == null ? '—'
            : `${num(e.score)}${e.maxScore != null ? ` / ${num(e.maxScore)}` : ''}`} />
          <Figure label="Rank" value={e.rank == null ? '—' : `${e.rank}${e.rankedOf ? ` of ${e.rankedOf}` : ''}`} />
          <Figure label="Solved" value={e.solved == null ? '—' : String(e.solved)} />
          <Figure label="Submissions" value={String(e.submissions)} />
          <Figure label="Focus losses" value={String(e.focusLosses)} warn={e.focusLosses > 0} />
        </dl>
      )}

      {e.problems.length > 0 && (
        <ul className="mt-2 flex flex-wrap gap-1.5" aria-label="Per question">
          {e.problems.map(p => (
            <li key={p.label}
              title={`${p.solved ? 'Accepted' : 'Not accepted'}${p.wrongAttempts ? `, ${p.wrongAttempts} wrong attempts` : ''}`}
              className={clsx('rounded-md border px-2 py-1 text-xs',
                p.solved ? 'border-green-900 bg-green-950/40 text-green-300'
                  : (p.marks ?? 0) > 0 ? 'border-amber-900 bg-amber-950/40 text-amber-300'
                    : 'border-gray-800 bg-gray-950 text-gray-500')}>
              <span className="font-medium">{p.label}</span>
              <span className="ml-1.5 font-mono tabular-nums">
                {num(p.marks ?? 0)}{p.maxMarks != null && `/${num(p.maxMarks)}`}
              </span>
              {p.wrongAttempts > 0 && <span className="ml-1 text-gray-500">· {p.wrongAttempts}✗</span>}
            </li>
          ))}
        </ul>
      )}
      {!upcoming && e.problems.length === 0 && e.boardAt == null && (
        <p className="mt-2 text-xs text-gray-600">No leaderboard has been built for this event yet.</p>
      )}
    </section>
  )
}

function Figure({ label, value, warn }: { label: string; value: string; warn?: boolean }) {
  return (
    <div className="flex gap-1.5">
      <dt className="text-gray-500">{label}</dt>
      <dd className={clsx('font-mono tabular-nums', warn ? 'text-amber-300' : 'text-gray-200')}>{value}</dd>
    </div>
  )
}

function when(at: string | null) { return at ? format(new Date(at), 'd MMM HH:mm') : '' }
function sum(xs: number[]) { return xs.reduce((a, b) => a + Number(b), 0) }
function num(n: number) { return Number(n).toLocaleString(undefined, { maximumFractionDigits: 2 }) }
