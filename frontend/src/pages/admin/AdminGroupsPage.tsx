import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, Loader2, Plus, Users } from 'lucide-react'
import { useAdminGroups, useCreateGroup } from '@/hooks/useGroups'
import { Ago, EmptyRow, Panel, Pill } from '@/components/admin/AdminUi'

/**
 * The groups an admin runs contests for.
 *
 * A group is the durable thing and contests come and go beneath it, so this list is short and
 * changes rarely — which is why creating one lives inline here rather than behind its own page.
 */
export default function AdminGroupsPage() {
  const { data: groups, isLoading } = useAdminGroups()
  const create = useCreateGroup()

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const submit = () => {
    if (!name.trim()) return
    create.mutate(
      { name: name.trim(), description: description.trim() || undefined },
      { onSuccess: () => { setName(''); setDescription('') } }
    )
  }

  return (
    <div className="space-y-4">
      <Panel title="Groups" description="A named set of people, measured against each other">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="px-4 py-2 font-medium">Group</th>
                <th className="px-4 py-2 font-medium">Members</th>
                <th className="px-4 py-2 font-medium">Contests</th>
                <th className="px-4 py-2 font-medium">Created</th>
                <th className="px-4 py-2 font-medium" />
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {isLoading && <EmptyRow colSpan={5}>Loading groups…</EmptyRow>}
              {!isLoading && groups?.length === 0 && (
                <EmptyRow colSpan={5}>
                  No groups yet. Create one below, add the people in it, then point it at a
                  contest on Codeforces or DOMjudge.
                </EmptyRow>
              )}
              {groups?.map(group => (
                <tr key={group.groupId} className="transition-colors hover:bg-gray-800/40">
                  <td className="px-4 py-2.5">
                    <Link
                      to={`/admin/groups/${group.groupId}`}
                      className="font-medium text-gray-200 hover:text-indigo-400"
                    >
                      {group.name}
                    </Link>
                    {!group.active && <span className="ml-2"><Pill tone="gray">retired</Pill></span>}
                    {group.description && (
                      <p className="text-xs text-gray-600">{group.description}</p>
                    )}
                  </td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">{group.memberCount}</td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">{group.contestCount}</td>
                  <td className="px-4 py-2.5 text-xs text-gray-500">
                    <Ago at={group.createdAt} />
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <Link
                      to={`/admin/groups/${group.groupId}`}
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

      <Panel title="New group" description="Name it after the people in it — a class, a squad, a cohort">
        <div className="flex flex-wrap items-end gap-2 p-4">
          <label className="flex min-w-[14rem] flex-1 flex-col gap-1">
            <span className="text-xs text-gray-500">Name</span>
            <input
              value={name}
              onChange={e => setName(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') submit() }}
              placeholder="Autumn training squad"
              maxLength={120}
              className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                         text-gray-200 placeholder-gray-600 outline-none focus:border-indigo-600"
            />
          </label>
          <label className="flex min-w-[16rem] flex-[2] flex-col gap-1">
            <span className="text-xs text-gray-500">Description (optional)</span>
            <input
              value={description}
              onChange={e => setDescription(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') submit() }}
              placeholder="Second years, Tuesday sessions"
              maxLength={500}
              className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                         text-gray-200 placeholder-gray-600 outline-none focus:border-indigo-600"
            />
          </label>
          <button
            onClick={submit}
            disabled={!name.trim() || create.isPending}
            className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                       font-medium text-white transition-colors hover:bg-indigo-500
                       disabled:cursor-not-allowed disabled:opacity-40"
          >
            {create.isPending ? <Loader2 size={14} className="animate-spin" /> : <Plus size={14} />}
            Create
          </button>
        </div>
      </Panel>

      <p className="flex items-start gap-2 px-1 text-xs text-gray-600">
        <Users size={13} className="mt-0.5 flex-shrink-0" />
        CPIntel does not run the contest. Codeforces or DOMjudge does — a group is laid over
        their contest to rank its own members against each other, out of a board that may hold
        thousands of people nobody here is measuring.
      </p>
    </div>
  )
}
