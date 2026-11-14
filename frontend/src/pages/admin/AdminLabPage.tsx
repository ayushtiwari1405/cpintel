import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { format } from 'date-fns'
import { ArrowLeft, BadgeCheck, Download, Pencil, RefreshCw, Trash2 } from 'lucide-react'
import { PageHeader } from '@/components/common/PageHeader'
import { ErrorState, LoadingState } from '@/components/common/States'
import { Ago, EmptyRow, Panel, Pill, StatCard } from '@/components/admin/AdminUi'
import { useAdminClassroom } from '@/hooks/useClassrooms'
import { useCheckLab, useDeleteLab, useDownloadLabMarks, useLabProgress } from '@/hooks/useLabs'
import type { LabCell, LabState, LabStudentRow } from '@/types'

const LAB_STATE: Record<LabState, { label: string; tone: 'gray' | 'green' | 'amber' }> = {
  UPCOMING: { label: 'Upcoming', tone: 'gray' },
  OPEN:     { label: 'Open', tone: 'green' },
  CLOSED:   { label: 'Closed', tone: 'amber' },
}

/** One lab: who has solved what, with marks, and the handles each solve was read from. */
export default function AdminLabPage() {
  const { classroomId: rawClassroom, labId: rawLab } = useParams()
  const classroomId = Number(rawClassroom)
  const labId = Number(rawLab)
  const navigate = useNavigate()
  const { data: room } = useAdminClassroom(Number.isFinite(classroomId) ? classroomId : null)
  const { data, isLoading, isError, error, refetch } = useLabProgress(classroomId, labId)
  const check = useCheckLab(classroomId, labId)
  const remove = useDeleteLab(classroomId)
  const download = useDownloadLabMarks(classroomId)
  const [filter, setFilter] = useState('')

  if (!Number.isFinite(classroomId) || !Number.isFinite(labId)) {
    return <ErrorState title="Not found" error={new Error('That address does not name a lab.')} />
  }
  if (isError) return <ErrorState title="Could not load this lab" error={error} onRetry={() => refetch()} />
  if (isLoading || !data) return <LoadingState label="Loading the lab…" />

  const { lab, students } = data
  const state = LAB_STATE[lab.state]
  const q = filter.trim().toLowerCase()
  const shown = q ? students.filter(s => s.username.toLowerCase().includes(q)
    || (s.fullName ?? '').toLowerCase().includes(q)) : students
  const unverified = students.filter(s => !ready(s, lab.problems.map(p => p.platform))).length
  const finished = students.filter(s => s.solved === lab.problems.length).length
  const average = students.length
    ? students.reduce((sum, s) => sum + Number(s.marks), 0) / students.length : 0

  return (
    <div className="space-y-4">
      <Link to={`/admin/classrooms/${classroomId}`}
        className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-300">
        <ArrowLeft size={12} /> {room?.name ?? 'Classroom'}
      </Link>
      <PageHeader eyebrow={room ? `${room.name} · Lab` : 'Lab'} title={lab.title}
        description={lab.description ?? undefined}
        meta={<>
          <Pill tone={state.tone}>{state.label}</Pill>
          <span className="text-gray-400">
            {format(new Date(lab.opensAt), 'd MMM yyyy, HH:mm')} → {format(new Date(lab.dueAt), 'd MMM yyyy, HH:mm')}
          </span>
          <span className="text-gray-500">
            {lab.groupNames.length ? lab.groupNames.join(', ') : 'Whole classroom'}
            {' · '}
            {lab.countEarlierSolves ? 'earlier solves count' : 'only solves in the window count'}
          </span>
        </>}
        actions={<>
          <button type="button" className="btn-secondary btn-sm" disabled={check.isPending || lab.state === 'UPCOMING'}
            onClick={() => check.mutate()}>
            <RefreshCw size={13} className={check.isPending ? 'animate-spin' : ''} aria-hidden="true" /> Check now
          </button>
          <button type="button" className="btn-secondary btn-sm" disabled={download.isPending}
            onClick={() => download.mutate({ labId, title: lab.title })}>
            <Download size={13} aria-hidden="true" /> Marks (.xlsx)
          </button>
          <Link to={`/admin/classrooms/${classroomId}/labs/${labId}/edit`} className="btn-secondary btn-sm">
            <Pencil size={13} aria-hidden="true" /> Edit
          </Link>
          <button type="button" className="btn btn-sm border border-red-900/60 text-red-300 hover:bg-red-950/50"
            disabled={remove.isPending}
            onClick={() => {
              if (confirm(`Delete "${lab.title}"? Its problems and every recorded solve go with it.`)) {
                remove.mutate(labId, { onSuccess: () => navigate(`/admin/classrooms/${classroomId}`) })
              }
            }}>
            <Trash2 size={13} aria-hidden="true" /> Delete
          </button>
        </>}
        className="mb-2" />

      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard label="Students" value={students.length} />
        <StatCard label="Finished" value={`${finished}`} hint={`solved all ${lab.problems.length} in time`} />
        <StatCard label="Average" value={`${round(average)} / ${round(lab.totalMarks)}`} hint="marks" />
        <StatCard label="Not counted" value={unverified} tone={unverified ? 'warn' : 'default'}
          hint="missing or unverified handle" />
      </div>

      <Panel title="Progress"
        description={lab.lastCheckedAt
          ? undefined
          : 'Not checked yet. Checks run every ten minutes, or press Check now.'}
        actions={<>
          {lab.lastCheckedAt && (
            <span className="text-xs text-gray-500">Checked <Ago at={lab.lastCheckedAt} /></span>
          )}
          <input value={filter} onChange={e => setFilter(e.target.value)} placeholder="Find a student"
            aria-label="Find a student"
            className="rounded-lg border border-gray-800 bg-gray-950 px-2.5 py-1 text-xs text-gray-200
                       placeholder-gray-500 outline-hidden focus:border-accent-600" />
        </>}>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-[11px] uppercase tracking-wide text-gray-500">
                <th className="sticky left-0 bg-gray-900 px-4 py-2 font-medium">Student</th>
                <th className="px-3 py-2 font-medium">Handles</th>
                {lab.problems.map((p, i) => (
                  <th key={p.problemId} className="px-2 py-2 text-center font-medium">
                    <a href={p.url} target="_blank" rel="noreferrer" title={`${p.title} (${p.marks} marks)`}
                      className="hover:text-accent-300">
                      {i + 1}<span className="block text-[10px] normal-case text-gray-600">
                        {p.platform === 'LEETCODE' ? 'LC' : 'CF'}
                      </span>
                    </a>
                  </th>
                ))}
                <th className="px-4 py-2 text-right font-medium">Marks</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {shown.length === 0 && (
                <EmptyRow colSpan={lab.problems.length + 3}>
                  {students.length === 0 ? 'Nobody is set this lab yet.' : 'No student matches.'}
                </EmptyRow>
              )}
              {shown.map(s => (
                <tr key={s.userId}>
                  <td className="sticky left-0 bg-gray-900 px-4 py-2">
                    <span className="text-gray-200">{s.username}</span>
                    {s.fullName && <span className="block text-xs text-gray-600">{s.fullName}</span>}
                  </td>
                  <td className="px-3 py-2 text-xs">
                    <HandleTag label="CF" handle={s.codeforcesHandle} verified={s.codeforcesVerified} />
                    <HandleTag label="LC" handle={s.leetcodeHandle} verified={s.leetcodeVerified} />
                  </td>
                  {s.cells.map(c => <CellView key={c.problemId} cell={c} />)}
                  <td className="px-4 py-2 text-right font-mono tabular-nums text-gray-100">
                    {round(s.marks)}
                    {s.late > 0 && <span className="block text-[11px] text-amber-400">{s.late} late</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel title="Problems">
        <ol className="divide-y divide-gray-800">
          {lab.problems.map((p, i) => (
            <li key={p.problemId} className="flex items-center gap-3 px-4 py-2 text-sm">
              <span className="w-5 text-xs text-gray-500">{i + 1}</span>
              <a href={p.url} target="_blank" rel="noreferrer" className="min-w-0 flex-1 truncate text-gray-200 hover:text-accent-300">
                {p.title}
              </a>
              <Pill tone={p.platform === 'LEETCODE' ? 'amber' : 'accent'}>
                {p.platform === 'LEETCODE' ? 'LeetCode' : 'Codeforces'}
              </Pill>
              {p.difficulty && <Pill>{p.difficulty}</Pill>}
              <span className="w-16 text-right font-mono text-xs text-gray-400">{round(p.marks)} marks</span>
            </li>
          ))}
        </ol>
      </Panel>
    </div>
  )
}

function ready(s: LabStudentRow, platforms: string[]) {
  return (!platforms.includes('CODEFORCES') || s.codeforcesVerified)
    && (!platforms.includes('LEETCODE') || s.leetcodeVerified)
}

function HandleTag({ label, handle, verified }: { label: string; handle: string | null; verified: boolean }) {
  if (!handle) return <span className="mr-2 text-gray-600">{label}: none</span>
  return (
    <span className={`mr-2 inline-flex items-center gap-0.5 font-mono ${verified ? 'text-gray-300' : 'text-amber-400'}`}
      title={verified ? 'Verified' : 'Not verified: solves are not counted'}>
      {label}: {handle}
      {verified && <BadgeCheck size={11} className="text-green-400" aria-label="verified" />}
    </span>
  )
}

function CellView({ cell }: { cell: LabCell }) {
  const when = cell.solvedAt ? format(new Date(cell.solvedAt), 'd MMM HH:mm') : ''
  if (cell.status === 'SOLVED') {
    return <td className="px-2 py-2 text-center" title={`Solved ${when}`}>
      <span className="inline-block rounded-sm bg-green-950/70 px-1.5 text-xs text-green-300">✓</span>
      <span className="sr-only">Solved {when}</span>
    </td>
  }
  if (cell.status === 'LATE') {
    return <td className="px-2 py-2 text-center" title={`Late: ${when}`}>
      <span className="inline-block rounded-sm bg-amber-950/70 px-1.5 text-xs text-amber-300">L</span>
      <span className="sr-only">Late {when}</span>
    </td>
  }
  return <td className="px-2 py-2 text-center text-gray-700"><span aria-hidden="true">·</span><span className="sr-only">Not solved</span></td>
}

function round(n: number) {
  return Number(n).toLocaleString(undefined, { maximumFractionDigits: 2 })
}
