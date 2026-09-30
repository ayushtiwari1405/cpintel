import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  AlertCircle, ArrowLeft, CheckCircle2, Loader2, RefreshCw, Save, UserMinus, UserPlus,
} from 'lucide-react'
import {
  useAdminClassroom, useArchiveClassroom, useCheckJudge, useClassroomMemberActions,
  useClassroomMembers, useClassroomStaff, useClassroomStaffActions, useUpdateClassroom,
} from '@/hooks/useClassrooms'
import { useAdminUsers, useIsSuperAdmin } from '@/hooks/useAdmin'
import { useAdminGroups } from '@/hooks/useGroups'
import { Ago, EmptyRow, Panel, Pill } from '@/components/admin/AdminUi'
import type { ClassroomSummary } from '@/types'

const field = `rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-200
               placeholder-gray-600 outline-none focus:border-indigo-600`

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
  const { data: room, isLoading } = useAdminClassroom(valid ? id : null)

  if (isLoading || !room) {
    return (
      <div className="flex items-center justify-center gap-2 py-20 text-sm text-gray-500">
        <Loader2 size={16} className="animate-spin" /> Loading the classroom…
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <Link to="/admin/classrooms"
        className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-300">
        <ArrowLeft size={12} /> All classrooms
      </Link>
      <Settings room={room} />
      <Teams classroomId={id} />
      <Members classroomId={id} />
      <Staff classroomId={id} />
    </div>
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
      description="The judge this classroom runs on, and how CPIntel signs in to it"
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
            The judge can't be changed now that students or events are in this classroom: every
            login was checked against it. Create a new classroom for another judge.
          </p>
        )}
        <div className="flex flex-wrap items-center gap-2 sm:col-span-2">
          <button
            onClick={save}
            disabled={update.isPending || !name.trim() || !url.trim()}
            className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                       font-medium text-white hover:bg-indigo-500 disabled:opacity-40"
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

function Teams({ classroomId }: { classroomId: number }) {
  const { data: groups } = useAdminGroups()
  const mine = groups?.filter(g => g.classroomId === classroomId) ?? []
  return (
    <Panel title="Teams" description="Sections in this classroom. Create them from the Teams page">
      <div className="flex flex-wrap gap-1.5 p-4">
        {mine.length === 0 && <span className="text-xs text-gray-600">No teams yet.</span>}
        {mine.map(g => (
          <Link key={g.groupId} to={`/admin/teams/${g.groupId}`}
            className="rounded-md border border-gray-800 px-2 py-1 text-xs text-gray-300
                       hover:border-indigo-700 hover:text-indigo-300">
            {g.name} <span className="text-gray-600">· {g.memberCount}</span>
          </Link>
        ))}
      </div>
    </Panel>
  )
}

function Members({ classroomId }: { classroomId: number }) {
  const { data: members, isLoading } = useClassroomMembers(classroomId)
  const { add, remove } = useClassroomMemberActions(classroomId)
  const [search, setSearch] = useState('')
  const { data: candidates } = useAdminUsers({ query: search, size: 8 })
  const enrolled = new Set(members?.map(m => m.userId))

  return (
    <Panel title="Students" description="Everyone enrolled, and whether their DOMjudge login is attached">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
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
                Nobody yet. Import a roster into one of this classroom's teams.
              </EmptyRow>
            )}
            {members?.map(m => (
              <tr key={m.userId}>
                <td className="px-4 py-2.5">
                  <span className="text-gray-200">{m.username}</span>
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
                           bg-gray-900 px-2 py-1 text-xs text-gray-300 hover:border-indigo-700
                           hover:text-indigo-300">
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
    <Panel title="Admins" description="Admins who run this classroom — any number of them. Superadmins run every classroom">
      <div className="flex flex-wrap gap-1.5 p-4">
        {staff?.map(s => (
          <span key={s.userId}
            className="flex items-center gap-1.5 rounded-md border border-gray-800 px-2 py-1
                       text-xs text-gray-300">
            {s.username}
            {s.owner
              ? <Pill tone="indigo">created it</Pill>
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
                           bg-gray-900 px-2 py-1 text-xs text-gray-300 hover:border-indigo-700
                           hover:text-indigo-300">
                <UserPlus size={12} /> {u.username}
              </button>
            ))}
          </div>
        )}
      </div>}
    </Panel>
  )
}
