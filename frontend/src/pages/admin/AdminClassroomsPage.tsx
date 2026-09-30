import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, Loader2, Plus, School } from 'lucide-react'
import { useAdminClassrooms, useCreateClassroom } from '@/hooks/useClassrooms'
import { Ago, EmptyRow, Panel, Pill } from '@/components/admin/AdminUi'

const field = `rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-200
               placeholder-gray-600 outline-none focus:border-indigo-600`

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

  const [name, setName] = useState('')
  const [url, setUrl] = useState('')
  const [description, setDescription] = useState('')
  const [serviceUsername, setServiceUsername] = useState('')
  const [servicePassword, setServicePassword] = useState('')

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
      },
    })
  }

  return (
    <div className="space-y-4">
      <Panel title="Classrooms" description="One DOMjudge each, with the students, teams and examinations on it">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="px-4 py-2 font-medium">Classroom</th>
                <th className="px-4 py-2 font-medium">Judge</th>
                <th className="px-4 py-2 font-medium">Students</th>
                <th className="px-4 py-2 font-medium">Teams</th>
                <th className="px-4 py-2 font-medium">Events</th>
                <th className="px-4 py-2 font-medium">Created</th>
                <th className="px-4 py-2 font-medium" />
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {isLoading && <EmptyRow colSpan={7}>Loading classrooms…</EmptyRow>}
              {!isLoading && classrooms?.length === 0 && (
                <EmptyRow colSpan={7}>
                  No classrooms you run yet. Create one below for each DOMjudge instance, or ask
                  a superadmin to add you to an existing one.
                </EmptyRow>
              )}
              {classrooms?.map(room => (
                <tr key={room.classroomId} className="transition-colors hover:bg-gray-800/40">
                  <td className="px-4 py-2.5">
                    <Link
                      to={`/admin/classrooms/${room.classroomId}`}
                      className="font-medium text-gray-200 hover:text-indigo-400"
                    >
                      {room.name}
                    </Link>
                    {!room.active && <span className="ml-2"><Pill tone="gray">archived</Pill></span>}
                    {room.description && (
                      <p className="text-xs text-gray-600">{room.description}</p>
                    )}
                  </td>
                  <td className="px-4 py-2.5 font-mono text-xs text-gray-400">
                    {room.domjudgeUrl ?? <Pill tone="amber">not set</Pill>}
                    {room.hasServiceAccount && (
                      <span className="ml-2"><Pill tone="indigo">service account</Pill></span>
                    )}
                  </td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">{room.memberCount}</td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">{room.groupCount}</td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">{room.eventCount}</td>
                  <td className="px-4 py-2.5 text-xs text-gray-500"><Ago at={room.createdAt} /></td>
                  <td className="px-4 py-2.5 text-right">
                    <Link
                      to={`/admin/classrooms/${room.classroomId}`}
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

      <Panel title="New classroom" description="Point it at the DOMjudge this class runs on">
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
            The service account lets one read of the judge serve the whole room. Without it,
            each student's page reads DOMjudge under their own login, which works but costs
            one set of calls per person.
          </p>
          <div className="sm:col-span-2">
            <button
              onClick={submit}
              disabled={!valid || create.isPending}
              className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                         font-medium text-white transition-colors hover:bg-indigo-500
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

      <p className="flex items-start gap-2 px-1 text-xs text-gray-600">
        <School size={13} className="mt-0.5 flex-shrink-0" />
        A student in two classrooms has a DOMjudge login in each, kept side by side. Import them
        into a team in the second classroom with the same username and their existing account
        is matched; the new login is attached next to the old one.
      </p>
    </div>
  )
}
