import { useState } from 'react'
import { Loader2, Plus, School } from 'lucide-react'
import { useAdminClassrooms, useCreateClassroom } from '@/hooks/useClassrooms'
import { useIsSuperAdmin } from '@/hooks/useAdmin'
import { Ago, NewTile, Panel, Pill, Tile, TileGrid, TileNote, revealForm } from '@/components/admin/AdminUi'
import { PageHeader } from '@/components/common/PageHeader'

const field = `rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-200
               placeholder-gray-400 outline-hidden focus:border-accent-600`

/**
 * The classrooms an admin runs. Each is one DOMjudge instance, and the teams and examinations
 * inside it all run on that judge.
 *
 * Creating one checks the judge answers before anything is saved, so a mistyped URL is found
 * here rather than by the first student to open a paper.
 */
export default function AdminClassroomsPage() {
  const { data: classrooms, isLoading } = useAdminClassrooms()
  const create = useCreateClassroom()
  // Only a superadmin creates classrooms and decides which admins run each one.
  const isSuper = useIsSuperAdmin()

  const [name, setName] = useState('')
  const [url, setUrl] = useState('')
  const [description, setDescription] = useState('')
  const [serviceUsername, setServiceUsername] = useState('')
  const [servicePassword, setServicePassword] = useState('')

  const [showForm, setShowForm] = useState(false)
  const openForm = () => { setShowForm(true); revealForm('new-classroom') }

  const valid = name.trim() !== '' && url.trim() !== ''
    && (serviceUsername.trim() === '' || servicePassword !== '')

  const submit = () => {
    if (!valid) return
    create.mutate({
      name: name.trim(),
      description: description.trim() || null,
      domjudgeUrl: url.trim(),
      serviceUsername: serviceUsername.trim() || null,
      servicePassword: serviceUsername.trim() ? servicePassword : null,
    }, {
      onSuccess: () => {
        setName(''); setUrl(''); setDescription(''); setServiceUsername(''); setServicePassword('')
        setShowForm(false)
      },
    })
  }

  return (
    <div className="space-y-4">
      <PageHeader title="Classrooms"
        className="mb-2" />
      <TileGrid label="Classrooms">
        {isSuper && <NewTile label="New classroom" onClick={openForm} />}
        {isLoading && <TileNote>Loading classrooms…</TileNote>}
        {!isLoading && classrooms?.length === 0 && (
          <TileNote>
            {isSuper
              ? 'No classrooms yet.'
              : 'You are not in a classroom yet. A superadmin can add you.'}
          </TileNote>
        )}
        {classrooms?.map(room => (
          <Tile key={room.classroomId}
            to={`/admin/classrooms/${room.classroomId}`}
            icon={School}
            kind="Classroom"
            tone={room.active ? 'accent' : 'gray'}
            status={!room.active
              ? <Pill tone="gray">archived</Pill>
              : !room.domjudgeUrl ? <Pill tone="amber">no judge</Pill> : undefined}
            corner={`${room.memberCount} student${room.memberCount === 1 ? '' : 's'}`}
            title={room.name}
            meta={[
              `${room.groupCount} team${room.groupCount === 1 ? '' : 's'} · `
                + `${room.eventCount} event${room.eventCount === 1 ? '' : 's'}`,
              room.domjudgeUrl
                ? <span className="font-mono">{room.domjudgeUrl.replace(/^https?:\/\//, '')}</span>
                : room.description,
              <>Created <Ago at={room.createdAt} /></>,
            ]}
          />
        ))}
      </TileGrid>

      {isSuper && showForm && (
      <div id="new-classroom" className="scroll-mt-4">
      <Panel title="New classroom"
        actions={<button type="button" onClick={() => setShowForm(false)}
          className="text-xs text-gray-500 hover:text-gray-300">Cancel</button>}>
        <div className="grid gap-3 p-4 sm:grid-cols-2">
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Name</span>
            <input value={name} onChange={e => setName(e.target.value)} maxLength={120}
              placeholder="Data Structures — Autumn" className={field} />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">DOMjudge URL</span>
            <input value={url} onChange={e => setUrl(e.target.value)} maxLength={500}
              placeholder="https://judge.example.edu" className={`${field} font-mono`} />
          </label>
          <label className="flex flex-col gap-1 sm:col-span-2">
            <span className="text-xs text-gray-500">Description (optional)</span>
            <input value={description} onChange={e => setDescription(e.target.value)}
              maxLength={500} placeholder="Second years, lab 3" className={field} />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Service account (optional)</span>
            <input value={serviceUsername} onChange={e => setServiceUsername(e.target.value)}
              maxLength={100} placeholder="admin" autoComplete="off"
              className={`${field} font-mono`} />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Service account password</span>
            <input type="password" value={servicePassword}
              onChange={e => setServicePassword(e.target.value)}
              disabled={serviceUsername.trim() === ''} autoComplete="new-password"
              className={`${field} disabled:opacity-40`} />
          </label>
          <p className="text-xs leading-relaxed text-gray-600 sm:col-span-2">
            Optional — lets one judge read serve the whole class.
          </p>
          <div className="sm:col-span-2">
            <button
              onClick={submit}
              disabled={!valid || create.isPending}
              className="flex items-center gap-1.5 rounded-lg bg-accent-600 px-3 py-2 text-sm
                         font-medium text-white transition-colors hover:bg-accent-500
                         disabled:cursor-not-allowed disabled:opacity-40"
            >
              {create.isPending
                ? <Loader2 size={14} className="animate-spin" />
                : <Plus size={14} />}
              {create.isPending ? 'Checking the judge…' : 'Create'}
            </button>
          </div>
        </div>
      </Panel>
      </div>
      )}

    </div>
  )
}
