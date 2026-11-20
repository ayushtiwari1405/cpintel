import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { format } from 'date-fns'
import { ArrowLeft, Download, Settings } from 'lucide-react'
import { clsx } from 'clsx'
import { PageHeader } from '@/components/common/PageHeader'
import { ErrorState, LoadingState } from '@/components/common/States'
import { Ago, EmptyRow, Panel, Pill, StatCard } from '@/components/admin/AdminUi'
import {
  useClassroomDashboard, useDownloadClassReport, useDownloadEventReport,
} from '@/hooks/useClassrooms'
import { useDownloadLabMarks } from '@/hooks/useLabs'
import type {
  ClassDashboard, DashboardEventCell, DashboardEventColumn, DashboardLabCell, DashboardLabColumn,
  DashboardRosterRow, LabState,
} from '@/types'

const LAB_STATE: Record<LabState, { label: string; tone: 'gray' | 'green' | 'amber' }> = {
  UPCOMING: { label: 'Upcoming', tone: 'gray' },
  OPEN:     { label: 'Open', tone: 'green' },
  CLOSED:   { label: 'Closed', tone: 'amber' },
}

type Sort = 'name' | 'labs' | 'missed' | 'focus'

/**
 * How a classroom is doing: each lab and each contest or examination summed over the class,
 * then a line per student across all of them.
 *
 * The roster is the part an instructor reads most, so it can be narrowed to the students who
 * need a look — anyone who missed an event that has started, or solved nothing in a lab that
 * has closed — and each name opens that student's own page.
 */
export default function AdminClassroomDashboardPage() {
  const { classroomId } = useParams()
  const id = Number(classroomId)
  const valid = Number.isFinite(id)
  const { data, isLoading, isError, error, refetch } = useClassroomDashboard(valid ? id : null)
  const report = useDownloadClassReport(id)

  if (!valid) return <ErrorState title="Not found" error={new Error('That address does not name a classroom.')} />
  if (isError) return <ErrorState title="Could not load the dashboard" error={error} onRetry={() => refetch()} />
  if (isLoading || !data) return <LoadingState label="Adding up the classroom…" />

  const exams = data.events.filter(e => e.kind === 'EXAM').length
  const contests = data.events.length - exams

  return (
    <div className="space-y-4">
      <Link to={`/admin/classrooms/${id}`}
        className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-300">
        <ArrowLeft size={12} /> {data.name}
      </Link>
      <PageHeader eyebrow="Classroom dashboard" title={data.name}
        meta={<span className="text-gray-500">
          {data.students} students · {plural(data.labs.length, 'lab')} · {plural(exams, 'exam')}
          {' · '}{plural(contests, 'contest')} · as of <Ago at={data.generatedAt} />
        </span>}
        actions={<>
          <button type="button" className="btn-primary btn-sm" disabled={report.isPending}
            onClick={() => report.mutate(data.name)}>
            <Download size={13} aria-hidden="true" />
            {report.isPending ? 'Preparing…' : 'Class report (.xlsx)'}
          </button>
          <Link to={`/admin/classrooms/${id}`} className="btn-secondary btn-sm">
            <Settings size={13} aria-hidden="true" /> Manage classroom
          </Link>
        </>}
        className="mb-2" />

      <Headline data={data} />
      <Labs classroomId={id} labs={data.labs} />
      <Events classroomId={id} events={data.events} />
      <Roster data={data} />
    </div>
  )
}

// ----------------------------------------------------------------- headline

function Headline({ data }: { data: ClassDashboard }) {
  const solved = sum(data.roster.map(r => r.labSolved))
  const problems = sum(data.roster.map(r => r.labProblems))
  const sat = sum(data.roster.map(r => r.eventsSat))
  const due = sum(data.roster.map(r => r.eventsAssigned))
  const flagged = data.roster.filter(r => r.needsLook).length
  const focus = sum(data.roster.map(r => r.focusLosses))

  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <StatCard label="Students" value={data.students}
        hint={focus ? `${focus} focus losses in exams` : 'no focus losses recorded'} />
      <StatCard label="Lab problems solved" value={problems ? pct(solved / problems) : '—'}
        hint={problems ? `${solved} of ${problems} set, in time` : 'no labs set yet'}
        tone={!problems ? 'default' : solved / problems >= 0.75 ? 'good' : solved / problems >= 0.4 ? 'warn' : 'danger'} />
      <StatCard label="Attendance" value={due ? pct(sat / due) : '—'}
        hint={due ? `${sat} of ${due} seats in started events` : 'nothing has started yet'}
        tone={!due ? 'default' : sat / due >= 0.9 ? 'good' : sat / due >= 0.6 ? 'warn' : 'danger'} />
      <StatCard label="Need a look" value={flagged} tone={flagged ? 'warn' : 'good'}
        hint="missed an event, or nothing in a closed lab" />
    </div>
  )
}

// --------------------------------------------------------------------- labs

function Labs({ classroomId, labs }: { classroomId: number; labs: DashboardLabColumn[] }) {
  const download = useDownloadLabMarks(classroomId)
  return (
    <Panel title="Labs">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-[11px] uppercase tracking-wide text-gray-500">
              <th className="px-4 py-2 font-medium">Lab</th>
              <th className="px-3 py-2 font-medium">Due</th>
              <th className="px-3 py-2 font-medium">Solved on average</th>
              <th className="px-3 py-2 text-right font-medium">Average marks</th>
              <th className="px-3 py-2 text-right font-medium">Finished</th>
              <th className="px-3 py-2 text-right font-medium">Nothing solved</th>
              <th className="px-4 py-2"><span className="sr-only">Download</span></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {labs.length === 0 && <EmptyRow colSpan={7}>No labs in this classroom yet.</EmptyRow>}
            {labs.map(lab => {
              const state = LAB_STATE[lab.state]
              const ratio = lab.problemCount ? lab.averageSolved / lab.problemCount : 0
              return (
                <tr key={lab.labId}>
                  <td className="px-4 py-2.5">
                    <Link to={`/admin/classrooms/${classroomId}/labs/${lab.labId}`}
                      className="text-gray-200 hover:text-accent-300">{lab.title}</Link>
                    <span className="ml-2"><Pill tone={state.tone}>{state.label}</Pill></span>
                  </td>
                  <td className="whitespace-nowrap px-3 py-2.5 text-xs text-gray-400">
                    {format(new Date(lab.dueAt), 'd MMM, HH:mm')}
                  </td>
                  <td className="px-3 py-2.5">
                    <Meter ratio={ratio}
                      label={`${num(lab.averageSolved)} / ${lab.problemCount}`} />
                  </td>
                  <td className="px-3 py-2.5 text-right font-mono text-xs tabular-nums text-gray-300">
                    {num(lab.averageMarks)} / {num(lab.totalMarks)}
                  </td>
                  <td className="px-3 py-2.5 text-right tabular-nums text-gray-300">
                    {lab.finishedAll}<span className="text-gray-600"> / {lab.studentCount}</span>
                  </td>
                  <td className={clsx('px-3 py-2.5 text-right tabular-nums',
                    lab.state === 'CLOSED' && lab.solvedNone > 0 ? 'text-red-300' : 'text-gray-400')}>
                    {lab.solvedNone}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <DownloadButton label={`Download marks for ${lab.title}`}
                      busy={download.isPending && download.variables?.labId === lab.labId}
                      onClick={() => download.mutate({ labId: lab.labId, title: lab.title })} />
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </Panel>
  )
}

// ------------------------------------------------------------------- events

function Events({ classroomId, events }: { classroomId: number; events: DashboardEventColumn[] }) {
  const download = useDownloadEventReport(classroomId)
  return (
    <Panel title="Contests and examinations"
      description="TA marks included">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-[11px] uppercase tracking-wide text-gray-500">
              <th className="px-4 py-2 font-medium">Event</th>
              <th className="px-3 py-2 font-medium">When</th>
              <th className="px-3 py-2 font-medium">Turned up</th>
              <th className="px-3 py-2 text-right font-medium">Average score</th>
              <th className="px-3 py-2 text-right font-medium">Average solved</th>
              <th className="px-3 py-2 text-right font-medium">Focus losses</th>
              <th className="px-4 py-2"><span className="sr-only">Download</span></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {events.length === 0 && (
              <EmptyRow colSpan={7}>No contests or examinations in this classroom yet.</EmptyRow>
            )}
            {events.map(e => {
              const started = e.lifecycle !== 'SCHEDULED'
              return (
                <tr key={e.eventId}>
                  <td className="px-4 py-2.5">
                    <Link to={`/admin/exams/${e.eventId}`} className="text-gray-200 hover:text-accent-300">
                      {e.name}
                    </Link>
                    <span className="ml-2"><KindPill kind={e.kind} /></span>
                    {!started && <span className="ml-1"><Pill>Upcoming</Pill></span>}
                  </td>
                  <td className="whitespace-nowrap px-3 py-2.5 text-xs text-gray-400">
                    {e.startsAt ? format(new Date(e.startsAt), 'd MMM, HH:mm') : '—'}
                  </td>
                  <td className="px-3 py-2.5">
                    {started && e.assigned > 0
                      ? <Meter ratio={e.sat / e.assigned} label={`${e.sat} / ${e.assigned}`} />
                      : <span className="text-xs text-gray-600">{e.assigned} assigned</span>}
                  </td>
                  <td className="px-3 py-2.5 text-right font-mono text-xs tabular-nums text-gray-300">
                    {e.averageScore == null
                      ? <span className="text-gray-600" title="No leaderboard has been built yet">—</span>
                      : <>{num(e.averageScore)}{e.maxScore != null && <> / {num(e.maxScore)}</>}</>}
                  </td>
                  <td className="px-3 py-2.5 text-right tabular-nums text-gray-300">
                    {e.averageSolved == null ? '—' : num(e.averageSolved)}
                    {e.problemCount > 0 && <span className="text-gray-600"> / {e.problemCount}</span>}
                  </td>
                  <td className={clsx('px-3 py-2.5 text-right tabular-nums',
                    e.focusLosses > 0 ? 'text-amber-300' : 'text-gray-500')}>
                    {e.focusLosses}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <DownloadButton label={`Download the report for ${e.name}`}
                      busy={download.isPending && download.variables?.eventId === e.eventId}
                      onClick={() => download.mutate({ eventId: e.eventId, name: e.name })} />
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </Panel>
  )
}

// ------------------------------------------------------------------- roster

function Roster({ data }: { data: ClassDashboard }) {
  const [query, setQuery] = useState('')
  const [onlyFlagged, setOnlyFlagged] = useState(false)
  const [sort, setSort] = useState<Sort>('name')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    const out = data.roster.filter(r =>
      (!q || r.username.toLowerCase().includes(q) || (r.fullName ?? '').toLowerCase().includes(q))
      && (!onlyFlagged || r.needsLook))
    const by: Record<Sort, (a: DashboardRosterRow, b: DashboardRosterRow) => number> = {
      name: (a, b) => a.username.localeCompare(b.username, undefined, { numeric: true }),
      labs: (a, b) => share(a.labMarks, a.labMaxMarks) - share(b.labMarks, b.labMaxMarks),
      missed: (a, b) => (b.eventsAssigned - b.eventsSat) - (a.eventsAssigned - a.eventsSat),
      focus: (a, b) => b.focusLosses - a.focusLosses,
    }
    return [...out].sort(by[sort])
  }, [data, query, onlyFlagged, sort])

  const cols = data.labs.length + data.events.length + 4
  const control = `rounded-lg border border-gray-800 bg-gray-950 px-2.5 py-1 text-xs text-gray-200
                   outline-hidden focus:border-accent-600`

  return (
    <Panel title="Students"
      description="Labs: solved / set · Events: score"
      actions={<>
        <label className="flex items-center gap-1.5 text-xs text-gray-400">
          <input type="checkbox" checked={onlyFlagged} onChange={e => setOnlyFlagged(e.target.checked)}
            className="accent-accent-500" />
          Only those needing a look
        </label>
        <select value={sort} onChange={e => setSort(e.target.value as Sort)} aria-label="Sort students"
          className={control}>
          <option value="name">By name</option>
          <option value="labs">Lowest lab marks first</option>
          <option value="missed">Most events missed first</option>
          <option value="focus">Most focus losses first</option>
        </select>
        <input value={query} onChange={e => setQuery(e.target.value)} placeholder="Find a student"
          aria-label="Find a student" className={`${control} placeholder-gray-500`} />
      </>}>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-[11px] uppercase tracking-wide text-gray-500">
              <th className="sticky left-0 z-10 bg-gray-900 px-4 py-2 font-medium">Student</th>
              {data.labs.map(l => (
                <th key={`l${l.labId}`} className="px-2 py-2 text-center font-medium" title={l.title}>
                  <span className="block max-w-24 truncate normal-case">{l.title}</span>
                  <span className="block text-[10px] text-gray-600">lab</span>
                </th>
              ))}
              {data.events.map(e => (
                <th key={`e${e.eventId}`} className="px-2 py-2 text-center font-medium" title={e.name}>
                  <span className="block max-w-24 truncate normal-case">{e.name}</span>
                  <span className="block text-[10px] text-gray-600">{e.kind === 'EXAM' ? 'exam' : 'contest'}</span>
                </th>
              ))}
              <th className="px-3 py-2 text-right font-medium">Lab marks</th>
              <th className="px-3 py-2 text-right font-medium">Attended</th>
              <th className="px-4 py-2 text-right font-medium">Focus</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {rows.length === 0 && (
              <EmptyRow colSpan={cols}>
                {data.roster.length === 0 ? 'No students enrolled.' : 'No student matches.'}
              </EmptyRow>
            )}
            {rows.map(r => (
              <tr key={r.userId} className="hover:bg-gray-850/40">
                <td className="sticky left-0 z-10 bg-gray-900 px-4 py-2">
                  <Link to={`/admin/classrooms/${data.classroomId}/students/${r.userId}`}
                    className="text-gray-200 hover:text-accent-300">{r.username}</Link>
                  {r.fullName && <span className="block text-xs text-gray-600">{r.fullName}</span>}
                </td>
                {r.labs.map(c => <LabCellView key={`l${c.labId}`} cell={c}
                  lab={data.labs.find(l => l.labId === c.labId)} />)}
                {r.events.map(c => <EventCellView key={`e${c.eventId}`} cell={c}
                  event={data.events.find(e => e.eventId === c.eventId)} />)}
                <td className="px-3 py-2 text-right font-mono text-xs tabular-nums text-gray-200">
                  {num(r.labMarks)}<span className="text-gray-600"> / {num(r.labMaxMarks)}</span>
                </td>
                <td className={clsx('px-3 py-2 text-right tabular-nums',
                  r.eventsSat < r.eventsAssigned ? 'text-red-300' : 'text-gray-300')}>
                  {r.eventsSat}<span className="text-gray-600"> / {r.eventsAssigned}</span>
                </td>
                <td className={clsx('px-4 py-2 text-right tabular-nums',
                  r.focusLosses > 0 ? 'text-amber-300' : 'text-gray-600')}>
                  {r.focusLosses}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Panel>
  )
}

function LabCellView({ cell, lab }: { cell: DashboardLabCell; lab?: DashboardLabColumn }) {
  if (!cell.assigned) {
    return <td className="px-2 py-2 text-center text-xs text-gray-700" title="Not set for this student">—</td>
  }
  const ratio = cell.problems ? cell.solved / cell.problems : 0
  const closed = lab?.state === 'CLOSED'
  return (
    <td className="px-2 py-2 text-center"
      title={`${cell.solved} of ${cell.problems} solved in time${cell.late ? `, ${cell.late} late` : ''}; ${num(cell.marks)} marks`}>
      <span className={clsx('inline-block min-w-10 rounded-sm px-1.5 font-mono text-xs tabular-nums',
        ratio === 1 ? 'bg-green-950/70 text-green-300'
          : cell.solved > 0 ? 'bg-amber-950/60 text-amber-300'
            : closed ? 'bg-red-950/50 text-red-300' : 'text-gray-500')}>
        {cell.solved}/{cell.problems}
      </span>
      {cell.late > 0 && <span className="block text-[10px] text-amber-400">+{cell.late} late</span>}
    </td>
  )
}

function EventCellView({ cell, event }: { cell: DashboardEventCell; event?: DashboardEventColumn }) {
  if (!cell.assigned) {
    return <td className="px-2 py-2 text-center text-xs text-gray-700" title="Not assigned">—</td>
  }
  if (event?.lifecycle === 'SCHEDULED') {
    return <td className="px-2 py-2 text-center text-xs text-gray-600">upcoming</td>
  }
  if (!cell.sat) {
    return <td className="px-2 py-2 text-center"><Pill tone="red">absent</Pill></td>
  }
  return (
    <td className="px-2 py-2 text-center font-mono text-xs tabular-nums text-gray-200"
      title={[cell.rank != null && `rank ${cell.rank}`, cell.solved != null && `${cell.solved} solved`,
        cell.focusLosses > 0 && `${cell.focusLosses} focus losses`].filter(Boolean).join(', ')}>
      {cell.score != null ? num(cell.score) : cell.solved != null ? `${cell.solved} solved` : 'sat'}
      {cell.rank != null && <span className="block text-[10px] text-gray-500">#{cell.rank}</span>}
    </td>
  )
}

// ------------------------------------------------------------------ helpers

/** An icon button that downloads a spreadsheet. */
function DownloadButton({ label, busy, onClick }: { label: string; busy: boolean; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} disabled={busy} aria-label={label} title={label}
      className="inline-flex items-center gap-1 rounded-md px-1.5 py-1 text-xs text-gray-500
                 hover:bg-gray-800 hover:text-gray-200 disabled:opacity-50">
      <Download size={13} aria-hidden="true" className={busy ? 'animate-pulse' : ''} />
      .xlsx
    </button>
  )
}

function KindPill({ kind }: { kind: 'EXAM' | 'CONTEST' }) {
  return <Pill tone={kind === 'EXAM' ? 'accent' : 'gray'}>{kind === 'EXAM' ? 'exam' : 'contest'}</Pill>
}

/** A thin bar with its figure beside it. */
function Meter({ ratio, label }: { ratio: number; label: string }) {
  const clamped = Math.max(0, Math.min(1, ratio))
  return (
    <div className="flex items-center gap-2">
      <div className="h-1.5 w-20 overflow-hidden rounded-full bg-gray-800" aria-hidden="true">
        <div className={clsx('h-full rounded-full',
          clamped >= 0.75 ? 'bg-green-500' : clamped >= 0.4 ? 'bg-amber-500' : 'bg-red-500')}
          style={{ width: `${clamped * 100}%` }} />
      </div>
      <span className="whitespace-nowrap font-mono text-xs tabular-nums text-gray-300">{label}</span>
    </div>
  )
}

function sum(xs: number[]) { return xs.reduce((a, b) => a + Number(b), 0) }
function share(a: number, b: number) { return Number(b) ? Number(a) / Number(b) : 1 }
function pct(r: number) { return `${Math.round(r * 100)}%` }
function plural(n: number, word: string) { return `${n} ${word}${n === 1 ? '' : 's'}` }
function num(n: number) { return Number(n).toLocaleString(undefined, { maximumFractionDigits: 1 }) }
