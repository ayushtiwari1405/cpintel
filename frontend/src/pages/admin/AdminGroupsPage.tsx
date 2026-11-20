import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Loader2, Plus, Users } from 'lucide-react'
import { useAdminGroups, useCreateGroup } from '@/hooks/useGroups'
import { useAdminClassrooms } from '@/hooks/useClassrooms'
import { ClassroomSelect } from '@/components/admin/ClassroomSelect'
import { Ago, NewTile, Panel, Pill, Shelf, Tile, TileGrid, TileNote, revealForm } from '@/components/admin/AdminUi'
import { PageHeader } from '@/components/common/PageHeader'

/**
 * The groups an admin runs contests for.
 *
 * A team is the durable thing and contests come and go beneath it, so this list is short and
 * changes rarely — which is why creating one lives inline here rather than behind its own page.
 */
export default function AdminGroupsPage() {
  const { data: allGroups, isLoading } = useAdminGroups()
  const { data: classrooms } = useAdminClassrooms()
  const create = useCreateGroup()

  // ?classroom= (from a classroom's page) narrows the list and preselects the form.
  const [params] = useSearchParams()
  const fromClassroom = Number(params.get('classroom')) || null
  const [filter, setFilter] = useState<number | null>(fromClassroom)
  const [classroomId, setClassroomId] = useState<number | null>(fromClassroom)
  const askedForNew = params.get('new') === '1'
  useEffect(() => { if (askedForNew) revealForm('new-team') }, [askedForNew])
  const groups = allGroups?.filter(g => filter == null || g.classroomId === filter)
  const classroomName = (id: number) =>
    classrooms?.find(c => c.classroomId === id)?.name ?? `Classroom ${id}`

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const submit = () => {
    if (!name.trim() || classroomId == null) return
    create.mutate(
      { name: name.trim(), description: description.trim() || undefined, classroomId },
      { onSuccess: () => { setName(''); setDescription('') } }
    )
  }

  return (
    <div className="space-y-4">
      <PageHeader title="Teams" className="mb-2" />
      <Shelf title="All teams" count={groups?.length}
        actions={<ClassroomSelect value={filter} onChange={setFilter} allowAll
          className="rounded-md border border-gray-800 bg-gray-900 px-2 py-1 text-xs
                     text-gray-300 outline-hidden" />}>
        <TileGrid label="Teams">
          <NewTile label="New team" onClick={() => revealForm('new-team')} />
          {isLoading && <TileNote>Loading teams…</TileNote>}
          {!isLoading && groups?.length === 0 && (
            <TileNote>No teams yet. Create one, add its people, then point it at a contest.</TileNote>
          )}
          {groups?.map(group => (
            <Tile key={group.groupId}
              to={`/admin/teams/${group.groupId}`}
              icon={Users}
              kind="Team"
              tone={group.active ? 'accent' : 'gray'}
              status={!group.active && <Pill tone="gray">retired</Pill>}
              corner={`${group.memberCount} member${group.memberCount === 1 ? '' : 's'}`}
              title={group.name}
              meta={[
                classroomName(group.classroomId),
                `${group.contestCount} contest${group.contestCount === 1 ? '' : 's'}`
                  + (group.description ? ` · ${group.description}` : ''),
                <>Created <Ago at={group.createdAt} /></>,
              ]}
            />
          ))}
        </TileGrid>
      </Shelf>

      <div id="new-team" className="scroll-mt-4">
      <Panel title="New team">
        <div className="flex flex-wrap items-end gap-2 p-4">
          <label className="flex min-w-48 flex-col gap-1">
            <span className="text-xs text-gray-500">Classroom</span>
            <ClassroomSelect value={classroomId} onChange={setClassroomId} />
          </label>
          <label className="flex min-w-56 flex-1 flex-col gap-1">
            <span className="text-xs text-gray-500">Name</span>
            <input
              value={name}
              onChange={e => setName(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') submit() }}
              placeholder="Autumn training squad"
              maxLength={120}
              className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                         text-gray-200 placeholder-gray-400 outline-hidden focus:border-accent-600"
            />
          </label>
          <label className="flex min-w-[16rem] flex-2 flex-col gap-1">
            <span className="text-xs text-gray-500">Description (optional)</span>
            <input
              value={description}
              onChange={e => setDescription(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') submit() }}
              placeholder="Second years, Tuesday sessions"
              maxLength={500}
              className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                         text-gray-200 placeholder-gray-400 outline-hidden focus:border-accent-600"
            />
          </label>
          <button
            onClick={submit}
            disabled={!name.trim() || classroomId == null || create.isPending}
            className="flex items-center gap-1.5 rounded-lg bg-accent-600 px-3 py-2 text-sm
                       font-medium text-white transition-colors hover:bg-accent-500
                       disabled:cursor-not-allowed disabled:opacity-40"
          >
            {create.isPending ? <Loader2 size={14} className="animate-spin" /> : <Plus size={14} />}
            Create
          </button>
        </div>
      </Panel>
      </div>

    </div>
  )
}
