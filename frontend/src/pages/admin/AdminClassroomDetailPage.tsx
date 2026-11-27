import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  AlertCircle, ArrowLeft, CheckCircle2, Copy, FileText, FlaskConical, LayoutDashboard, Loader2, RefreshCw, Save,
  UserMinus, UserPlus, Users,
} from 'lucide-react'
import {
  useAdminClassroom, useArchiveClassroom, useCheckJudge, useClassroomMemberActions, useClassroomSettings,
  useClassroomMembers, useClassroomStaff, useClassroomStaffActions, useUpdateClassroom,
} from '@/hooks/useClassrooms'
import { useAdminUsers, useIsSuperAdmin } from '@/hooks/useAdmin'
import { useAdminGroups } from '@/hooks/useGroups'
import { useClassroomTaActions, useClassroomTas } from '@/hooks/useEvaluation'
import { useAdminLabs } from '@/hooks/useLabs'
import { format } from 'date-fns'
import { Ago, EmptyRow, NewTile, Panel, Pill, Shelf, Tile, TileGrid, TileNote } from '@/components/admin/AdminUi'
import { Tabs, panelProps } from '@/components/common/Tabs'
import { useAdminEvents } from '@/hooks/useExams'
import { EventTile } from '@/pages/admin/AdminExamsPage'
import type { ClassroomSummary, EventKind, TaAdded } from '@/types'
import { copyText } from '@/utils/clipboard'
import { ErrorState, LoadingState } from '@/components/common/States'
import { PageHeader } from '@/components/common/PageHeader'
import { PasswordSheetDialog } from '@/components/admin/PasswordSheetDialog'

const field = `rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-200
               placeholder-gray-400 outline-hidden focus:border-accent-600`

/**
 * One classroom: its judge, who is enrolled, who runs it, and the teams inside it.
 *
 * Students are usually enrolled by importing a roster into one of its teams, which attaches
 * their DOMjudge login in the same pass. Enrolling someone here by hand only makes them a
 * member; their login is still attached from the team page.
 */
export default function AdminClassroomDetailPage() {
  const { classroomId } = useParams()
  const id = Number(classroomId)
  const valid = Number.isFinite(id)
  const { data: room, isLoading, isError, error, refetch } = useAdminClassroom(valid ? id : null)

  if (!valid) return <ErrorState title="Not found" error={new Error('That address does not name one.')} />
  if (isError) return <ErrorState title="Could not load this classroom" error={error} onRetry={() => refetch()} />
  if (isLoading || !room) return <LoadingState label="Loading the classroom…" />

  return (
    <div className="space-y-4">
      <Link to="/admin/classrooms"
        className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-300">
        <ArrowLeft size={12} /> All classrooms
      </Link>
      <PageHeader eyebrow="Classroom" title={room.name}
        description={room.domjudgeUrl
          ? <span className="font-mono text-xs">{room.domjudgeUrl}</span>
          : 'No judge set.'}
        actions={
          <Link to={`/admin/classrooms/${id}/dashboard`} className="btn-primary btn-sm">
            <LayoutDashboard size={13} aria-hidden="true" /> Dashboard
          </Link>
        }
        className="mb-2" />
      <ClassroomTabs room={room} />
    </div>
  )
}

type Section = 'overview' | 'people' | 'settings'
const SECTIONS: Section[] = ['overview', 'people', 'settings']

/**
 * The classroom split three ways, so what an admin opens most — its labs, examinations and
 * teams — is the first screen rather than below two settings forms. The tab is in the address
 * (?tab=people), so a reload or a shared link lands on the same one.
 */
function ClassroomTabs({ room }: { room: ClassroomSummary }) {
  const id = room.classroomId
  const [params, setParams] = useSearchParams()
  const asked = params.get('tab') as Section | null
  const tab: Section = asked && SECTIONS.includes(asked) ? asked : 'overview'
  const choose = (next: Section) => setParams(p => {
    const copy = new URLSearchParams(p)
    if (next === 'overview') copy.delete('tab'); else copy.set('tab', next)
    return copy
  }, { replace: true })

  return (
    <>
      <Tabs id="classroom" label="Classroom sections" value={tab} onChange={choose}
        tabs={[
          { key: 'overview', label: 'Overview' },
          { key: 'people', label: 'People', badge: room.memberCount },
          { key: 'settings', label: 'Settings' },
        ]} />
      <div {...panelProps('classroom', tab)} className="space-y-8 pt-2 outline-hidden">
        {tab === 'overview' && <>
          <Labs classroomId={id} />
          <Events classroomId={id} kind="EXAM" />
          <Events classroomId={id} kind="CONTEST" />
          <Teams classroomId={id} />
        </>}
        {tab === 'people' && <div className="space-y-4">
          <Members classroomId={id} />
          <Staff classroomId={id} />
          <TeachingAssistants classroomId={id} room={room} />
        </div>}
        {tab === 'settings' && <div className="space-y-4">
          <Settings room={room} />
          <CodeforcesKey room={room} />
        </div>}
      </div>
    </>
  )
}

function Settings({ room }: { room: ClassroomSummary }) {
  const update = useUpdateClassroom(room.classroomId)
  const archive = useArchiveClassroom(room.classroomId)
  const check = useCheckJudge(room.classroomId)
  const isSuper = useIsSuperAdmin()

  const [name, setName] = useState(room.name)
  const [description, setDescription] = useState(room.description ?? '')
  const [url, setUrl] = useState(room.domjudgeUrl ?? '')
  const [serviceUsername, setServiceUsername] = useState(room.serviceUsername ?? '')
  const [servicePassword, setServicePassword] = useState('')
  const [clearService, setClearService] = useState(false)

  useEffect(() => {
    setName(room.name)
    setDescription(room.description ?? '')
    setUrl(room.domjudgeUrl ?? '')
    setServiceUsername(room.serviceUsername ?? '')
  }, [room])

  // A judge that has students or events on it is fixed — every login was checked against it.
  const judgeLocked = !!room.domjudgeUrl && (room.memberCount > 0 || room.eventCount > 0)

  const save = () => {
    update.mutate({
      name: name.trim(),
      description: description.trim() || null,
      domjudgeUrl: url.trim(),
      serviceUsername: clearService ? null : serviceUsername.trim() || null,
      // Empty clears, omitted keeps: only send a password when one was typed or clearing.
      servicePassword: clearService ? '' : servicePassword || undefined,
    }, { onSuccess: () => { setServicePassword(''); setClearService(false) } })
  }

  return (
    <Panel
      title={room.name}
      description="Judge connection"
      actions={
        <div className="flex items-center gap-2">
          {!room.active && <Pill tone="gray">archived</Pill>}
          {isSuper && <button
            onClick={() => archive.mutate(!room.active)}
            disabled={archive.isPending}
            className="rounded-md border border-gray-800 px-2 py-1 text-xs text-gray-400
                       hover:border-gray-700 hover:text-gray-200"
          >
            {room.active ? 'Archive' : 'Restore'}
          </button>}
        </div>
      }
    >
      <div className="grid gap-3 p-4 sm:grid-cols-2">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-500">Name</span>
          <input value={name} onChange={e => setName(e.target.value)} maxLength={120}
            className={field} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-500">DOMjudge URL</span>
          <input value={url} onChange={e => setUrl(e.target.value)} maxLength={500}
            disabled={judgeLocked}
            title={judgeLocked ? 'Fixed once students or events are in this classroom' : undefined}
            className={`${field} font-mono disabled:opacity-50`} />
        </label>
        <label className="flex flex-col gap-1 sm:col-span-2">
          <span className="text-xs text-gray-500">Description</span>
          <input value={description} onChange={e => setDescription(e.target.value)}
            maxLength={500} className={field} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-500">Service account</span>
          <input value={serviceUsername} onChange={e => setServiceUsername(e.target.value)}
            disabled={clearService} maxLength={100} autoComplete="off"
            placeholder="none" className={`${field} font-mono disabled:opacity-40`} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-500">
            {room.hasServiceAccount ? 'New password (leave empty to keep)' : 'Password'}
          </span>
          <input type="password" value={servicePassword}
            onChange={e => setServicePassword(e.target.value)}
            disabled={clearService || serviceUsername.trim() === ''}
            autoComplete="new-password" className={`${field} disabled:opacity-40`} />
        </label>
        {room.hasServiceAccount && (
          <label className="flex items-center gap-2 text-xs text-gray-400 sm:col-span-2">
            <input type="checkbox" checked={clearService}
              onChange={e => setClearService(e.target.checked)} />
            Remove the service account
          </label>
        )}
        {judgeLocked && (
          <p className="text-xs text-gray-600 sm:col-span-2">
            The judge is fixed once a classroom has students or events. Create a new classroom
            for a different judge.
          </p>
        )}
        <div className="flex flex-wrap items-center gap-2 sm:col-span-2">
          <button
            onClick={save}
            disabled={update.isPending || !name.trim() || !url.trim()}
            className="flex items-center gap-1.5 rounded-lg bg-accent-600 px-3 py-2 text-sm
                       font-medium text-white hover:bg-accent-500 disabled:opacity-40"
          >
            {update.isPending ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
            Save
          </button>
          <button
            onClick={() => check.mutate()}
            disabled={check.isPending}
            className="flex items-center gap-1.5 rounded-lg border border-gray-800 px-3 py-2
                       text-sm text-gray-300 hover:border-gray-700"
          >
            {check.isPending
              ? <Loader2 size={14} className="animate-spin" />
              : <RefreshCw size={14} />}
            Check the judge
          </button>
          {check.data && (check.data.reachable ? (
            <span className="flex items-center gap-1 text-xs text-green-400">
              <CheckCircle2 size={12} /> Reachable
              {check.data.version && <> · DOMjudge {check.data.version}</>}
            </span>
          ) : (
            <span className="flex items-center gap-1 text-xs text-red-400">
              <AlertCircle size={12} /> {check.data.message}
            </span>
          ))}
        </div>
      </div>
    </Panel>
  )
}

/**
 * A Codeforces group manager's API key, for exams on the classroom's private group contests.
 * Write-only: the secret never comes back, only the key's first characters to recognise it.
 */
function CodeforcesKey({ room }: { room: ClassroomSummary }) {
  const { codeforcesKey } = useClassroomSettings(room.classroomId)
  const [key, setKey] = useState('')
  const [secret, setSecret] = useState('')
  const save = (k: string, s: string) => codeforcesKey.mutate({ key: k, secret: s }, {
    onSuccess: () => { setKey(''); setSecret('') },
  })
  return (
    <Panel title="Codeforces API key"
      description="For private group contests"
      actions={room.codeforcesKeySet
        ? <Pill tone="green">key {room.codeforcesKeyHint}…</Pill>
        : <Pill tone="gray">none</Pill>}>
      <div className="space-y-3 p-4">
        <p className="text-xs leading-relaxed text-gray-500">
          From a group manager at codeforces.com/settings/api.
        </p>
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex min-w-48 flex-1 flex-col gap-1">
            <span className="text-xs text-gray-500">Key</span>
            <input value={key} onChange={e => setKey(e.target.value)} maxLength={100}
              autoComplete="off" className={`${field} font-mono`} />
          </label>
          <label className="flex min-w-48 flex-1 flex-col gap-1">
            <span className="text-xs text-gray-500">Secret</span>
            <input type="password" value={secret} onChange={e => setSecret(e.target.value)}
              maxLength={200} autoComplete="new-password" className={`${field} font-mono`} />
          </label>
          <button type="button" onClick={() => save(key.trim(), secret.trim())}
            disabled={!key.trim() || !secret.trim() || codeforcesKey.isPending}
            className="flex items-center gap-1.5 rounded-lg bg-accent-600 px-3 py-2 text-sm
              font-medium text-white hover:bg-accent-500 disabled:opacity-40">
            {codeforcesKey.isPending ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
            {room.codeforcesKeySet ? 'Replace' : 'Save'}
          </button>
          {room.codeforcesKeySet && (
            <button type="button" onClick={() => { if (confirm('Remove the Codeforces key?')) save('', '') }}
              disabled={codeforcesKey.isPending}
              className="rounded-lg border border-gray-800 px-3 py-2 text-sm text-gray-400
                hover:border-gray-700 hover:text-gray-200">
              Remove
            </button>
          )}
        </div>
      </div>
    </Panel>
  )
}

const LAB_STATE = {
  UPCOMING: { label: 'Upcoming', tone: 'gray' },
  OPEN:     { label: 'Open', tone: 'green' },
  CLOSED:   { label: 'Closed', tone: 'amber' },
} as const

/** Lab assignments: Codeforces, LeetCode and CSES problems the classroom's students are set. */
function Labs({ classroomId }: { classroomId: number }) {
  const { data: labs, isLoading, isError } = useAdminLabs(classroomId)
  return (
    <Shelf title="Lab assignments" count={labs?.length}>
      <TileGrid label="Lab assignments">
        <NewTile label="New lab" to={`/admin/classrooms/${classroomId}/labs/new`} />
        {isLoading && <TileNote>Loading labs…</TileNote>}
        {isError && <TileNote>Could not load the labs.</TileNote>}
        {labs?.length === 0 && <TileNote>No labs yet.</TileNote>}
        {labs?.map(l => (
          <Tile key={l.labId}
            to={`/admin/classrooms/${classroomId}/labs/${l.labId}`}
            icon={FlaskConical}
            kind="Lab"
            tone={LAB_STATE[l.state].tone}
            status={<Pill tone={LAB_STATE[l.state].tone}>{LAB_STATE[l.state].label}</Pill>}
            corner={`${l.problems.length} · ${Number(l.totalMarks)} marks`}
            title={l.title}
            meta={[
              `Due ${format(new Date(l.dueAt), 'd MMM yyyy, HH:mm')}`,
              `${l.groupNames.length ? l.groupNames.join(', ') : 'Whole classroom'} · `
                + `${l.studentCount} student${l.studentCount === 1 ? '' : 's'}`,
            ]}
          />
        ))}
      </TileGrid>
    </Shelf>
  )
}

/** This classroom's examinations or contests; they are created on the Examinations page. */
function Events({ classroomId, kind }: { classroomId: number; kind: EventKind }) {
  const { data, isLoading, isError } = useAdminEvents(kind)
  const events = data?.filter(e => e.classroomId === classroomId)
  const exam = kind === 'EXAM'
  return (
    <Shelf title={exam ? 'Examinations' : 'Contests'} count={events?.length}
      actions={
        <Link to={`/admin/exams?kind=${kind}&classroom=${classroomId}`}
          className="text-xs text-accent-400 hover:text-accent-300">
          All {exam ? 'examinations' : 'contests'}
        </Link>
      }>
      <TileGrid label={exam ? 'Examinations' : 'Contests'}>
        <NewTile label={exam ? 'New examination' : 'New contest'}
          to={`/admin/exams?kind=${kind}&classroom=${classroomId}&new=1`} />
        {isLoading && <TileNote>Loading…</TileNote>}
        {isError && <TileNote>Could not load them.</TileNote>}
        {events?.length === 0 && <TileNote>{exam ? 'No examinations yet.' : 'No contests yet.'}</TileNote>}
        {events?.map(e => <EventTile key={e.eventId} event={e} />)}
      </TileGrid>
    </Shelf>
  )
}

function Teams({ classroomId }: { classroomId: number }) {
  const { data: groups, isLoading } = useAdminGroups()
  const mine = groups?.filter(g => g.classroomId === classroomId)
  return (
    <Shelf title="Teams" count={mine?.length}>
      <TileGrid label="Teams">
        <NewTile label="New team" to={`/admin/teams?classroom=${classroomId}&new=1`} />
        {isLoading && <TileNote>Loading teams…</TileNote>}
        {mine?.length === 0 && <TileNote>No teams yet.</TileNote>}
        {mine?.map(g => (
          <Tile key={g.groupId}
            to={`/admin/teams/${g.groupId}`}
            icon={Users}
            kind="Team"
            tone={g.active ? 'accent' : 'gray'}
            status={!g.active && <Pill tone="gray">retired</Pill>}
            corner={`${g.memberCount} member${g.memberCount === 1 ? '' : 's'}`}
            title={g.name}
            meta={[
              `${g.contestCount} contest${g.contestCount === 1 ? '' : 's'}`,
              g.description,
            ]}
          />
        ))}
      </TileGrid>
    </Shelf>
  )
}

function Members({ classroomId }: { classroomId: number }) {
  const { data: members, isLoading } = useClassroomMembers(classroomId)
  const { add, remove } = useClassroomMemberActions(classroomId)
  const [search, setSearch] = useState('')
  const { data: candidates } = useAdminUsers({ query: search, size: 8 })
  const enrolled = new Set(members?.map(m => m.userId))

  return (
    <Panel title="Students">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-[11px] uppercase tracking-wide text-gray-500">
              <th className="px-4 py-2 font-medium">Student</th>
              <th className="px-4 py-2 font-medium">DOMjudge login</th>
              <th className="px-4 py-2 font-medium">Enrolled</th>
              <th className="px-4 py-2 font-medium" />
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {isLoading && <EmptyRow colSpan={4}>Loading students…</EmptyRow>}
            {!isLoading && members?.length === 0 && (
              <EmptyRow colSpan={4}>
                No students. Import a roster into one of this classroom's teams.
              </EmptyRow>
            )}
            {members?.map(m => (
              <tr key={m.userId}>
                <td className="px-4 py-2.5">
                  <Link to={`/admin/classrooms/${classroomId}/students/${m.userId}`}
                    className="text-gray-200 hover:text-accent-300">{m.username}</Link>
                  {m.fullName && <span className="block text-xs text-gray-600">{m.fullName}</span>}
                </td>
                <td className="px-4 py-2.5 font-mono text-xs">
                  {m.judgeLoginAttached
                    ? <span className="text-gray-300">{m.domjudgeUsername ?? 'attached'}</span>
                    : m.domjudgeUsername
                      ? <Pill tone="amber">{m.domjudgeUsername} · expired</Pill>
                      : <span className="text-gray-600">none</span>}
                </td>
                <td className="px-4 py-2.5 text-xs text-gray-500"><Ago at={m.joinedAt} /></td>
                <td className="px-4 py-2.5 text-right">
                  <button
                    onClick={() => {
                      if (confirm(`Take ${m.username} out of this classroom? They leave its `
                        + 'teams and their DOMjudge login here is detached. Past results stay.')) {
                        remove.mutate(m.userId)
                      }
                    }}
                    className="inline-flex items-center gap-1 text-xs text-gray-500
                               hover:text-red-400"
                  >
                    <UserMinus size={12} /> Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="space-y-2 border-t border-gray-800 p-4">
        <span className="text-xs text-gray-500">Enrol someone individually</span>
        <input value={search} onChange={e => setSearch(e.target.value)}
          placeholder="Search by username or email" className={`${field} w-full max-w-md`} />
        {search.trim() !== '' && (
          <div className="flex flex-wrap gap-1.5">
            {candidates?.users.filter(u => !enrolled.has(u.userId)).map(u => (
              <button key={u.userId} onClick={() => add.mutate(u.userId)}
                className="flex items-center gap-1.5 rounded-md border border-gray-800
                           bg-gray-900 px-2 py-1 text-xs text-gray-300 hover:border-accent-700
                           hover:text-accent-300">
                <UserPlus size={12} /> {u.username}
              </button>
            ))}
          </div>
        )}
      </div>
    </Panel>
  )
}

function Staff({ classroomId }: { classroomId: number }) {
  const { data: staff } = useClassroomStaff(classroomId)
  const { add, remove } = useClassroomStaffActions(classroomId)
  const isSuper = useIsSuperAdmin()
  const [search, setSearch] = useState('')
  const { data: admins } = useAdminUsers({ query: search, role: 'ADMIN', size: 8 })
  const already = new Set(staff?.map(s => s.userId))

  return (
    <Panel title="Admins">
      <div className="flex flex-wrap gap-1.5 p-4">
        {staff?.map(s => (
          <span key={s.userId}
            className="flex items-center gap-1.5 rounded-md border border-gray-800 px-2 py-1
                       text-xs text-gray-300">
            {s.username}
            {s.owner
              ? <Pill tone="accent">created it</Pill>
              : isSuper && (
                <button onClick={() => remove.mutate(s.userId)} aria-label={`Remove ${s.username}`}
                  className="text-gray-600 hover:text-red-400">
                  <UserMinus size={12} />
                </button>
              )}
          </span>
        ))}
        {staff?.length === 0 && <span className="text-xs text-gray-600">Nobody besides superadmins.</span>}
      </div>
      {isSuper && <div className="space-y-2 border-t border-gray-800 p-4">
        <input value={search} onChange={e => setSearch(e.target.value)}
          placeholder="Add an admin by username" className={`${field} w-full max-w-md`} />
        {search.trim() !== '' && (
          <div className="flex flex-wrap gap-1.5">
            {admins?.users.filter(u => !already.has(u.userId)).map(u => (
              <button key={u.userId} onClick={() => add.mutate(u.userId)}
                className="flex items-center gap-1.5 rounded-md border border-gray-800
                           bg-gray-900 px-2 py-1 text-xs text-gray-300 hover:border-accent-700
                           hover:text-accent-300">
                <UserPlus size={12} /> {u.username}
              </button>
            ))}
          </div>
        )}
      </div>}
    </Panel>
  )
}

/**
 * The classroom's teaching assistants, who mark examinations by hand.
 *
 * Adding one follows the roster's rules: an existing account is found by email, then username,
 * and linked; otherwise a new one is created and its password shown here, once. What each TA
 * marks is set per examination, on its Evaluation tab.
 */
function TeachingAssistants({ classroomId, room }: { classroomId: number; room: ClassroomSummary }) {
  const { taQuestions } = useClassroomSettings(classroomId)
  const { data: tas, isLoading } = useClassroomTas(classroomId)
  const { add, remove } = useClassroomTaActions(classroomId)
  const [email, setEmail] = useState('')
  const [username, setUsername] = useState('')
  const [fullName, setFullName] = useState('')
  const [added, setAdded] = useState<TaAdded | null>(null)
  const [sheet, setSheet] = useState(false)

  const submit = () => {
    add.mutate({
      email: email.trim() || undefined,
      username: username.trim() || undefined,
      fullName: fullName.trim() || undefined,
    }, {
      onSuccess: result => {
        setAdded(result)
        setEmail('')
        setUsername('')
        setFullName('')
      },
    })
  }

  return (
    <Panel title="Teaching assistants"
      description="Assign work on an exam's Evaluation tab"
      actions={
        <label className="flex items-center gap-2 text-xs text-gray-400">
          <input type="checkbox" checked={room.tasWriteQuestions} disabled={taQuestions.isPending}
            onChange={e => taQuestions.mutate(e.target.checked)} />
          Can add questions to the bank
        </label>
      }>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <tbody className="divide-y divide-gray-800">
            {isLoading && <EmptyRow colSpan={3}>Loading…</EmptyRow>}
            {!isLoading && tas?.length === 0 && <EmptyRow colSpan={3}>No TAs yet.</EmptyRow>}
            {tas?.map(ta => (
              <tr key={ta.userId}>
                <td className="px-4 py-2.5">
                  <span className="text-gray-200">{ta.username}</span>
                  {ta.fullName && <span className="block text-xs text-gray-600">{ta.fullName}</span>}
                </td>
                <td className="px-4 py-2.5 text-xs text-gray-500">{ta.email}</td>
                <td className="px-4 py-2.5 text-right">
                  <button
                    onClick={() => {
                      if (confirm(`Stop ${ta.username} being a TA here? What they were given to `
                        + 'mark goes with it; marks they already gave stay.')) {
                        remove.mutate(ta.userId)
                      }
                    }}
                    className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-red-400"
                  >
                    <UserMinus size={12} /> Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="space-y-2 border-t border-gray-800 p-4">
        <span className="text-xs text-gray-500">
          Uses an existing account if one matches.
        </span>
        <div className="flex flex-wrap gap-2">
          <input value={email} onChange={e => setEmail(e.target.value)} placeholder="Email"
            type="email" autoComplete="off" className={`${field} w-64`} />
          <input value={username} onChange={e => setUsername(e.target.value)}
            placeholder="Username (optional)" autoComplete="off" className={`${field} w-48`} />
          <input value={fullName} onChange={e => setFullName(e.target.value)}
            placeholder="Full name (optional)" className={`${field} w-48`} />
          <button onClick={submit} disabled={add.isPending || (!email.trim() && !username.trim())}
            className="flex items-center gap-1.5 rounded-lg bg-accent-600 px-3 py-2 text-sm
                       font-medium text-white hover:bg-accent-500 disabled:opacity-40">
            {add.isPending ? <Loader2 size={14} className="animate-spin" /> : <UserPlus size={14} />}
            Add TA
          </button>
        </div>
        {added && (
          <div className="rounded-lg border border-gray-800 bg-gray-950 px-3 py-2.5 text-xs">
            <p className="text-gray-300">{added.message}</p>
            {added.password && (
              <p className="mt-1.5 flex flex-wrap items-center gap-2 text-gray-400">
                Password for {added.ta.username}:
                <code className="rounded-sm bg-gray-900 px-1.5 py-0.5 text-gray-100">{added.password}</code>
                <button onClick={() => copyText(added.password!).catch(() => {})}
                  className="inline-flex items-center gap-1 text-gray-500 hover:text-gray-300">
                  <Copy size={11} /> Copy
                </button>
                <button onClick={() => setSheet(true)}
                  className="inline-flex items-center gap-1 text-gray-500 hover:text-gray-300">
                  <FileText size={11} /> Create password sheet
                </button>
                <span className="text-amber-400/80">Shown only now. Hand it over before leaving the page.</span>
              </p>
            )}
          </div>
        )}
        {added?.password && (
          <PasswordSheetDialog
            key={added.ta.userId}
            open={sheet}
            onClose={() => setSheet(false)}
            kind="ta"
            fileName={`${added.ta.username}-password-sheet.pdf`}
            shared={[{ key: 'classroom', label: 'The classroom’s name', value: room.name }]}
            people={[{
              userId: added.ta.userId, username: added.ta.username, password: added.password, email: added.ta.email,
              fullName: added.ta.fullName,
            }]}
          />
        )}
      </div>
    </Panel>
  )
}
