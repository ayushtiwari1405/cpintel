import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { addDays, format, set } from 'date-fns'
import { ArrowDown, ArrowLeft, ArrowUp, ExternalLink, Loader2, Plus, Save, Trash2 } from 'lucide-react'
import { PageHeader } from '@/components/common/PageHeader'
import { ErrorState, LoadingState, errorMessage } from '@/components/common/States'
import { EmptyRow, Panel, Pill } from '@/components/admin/AdminUi'
import { useAdminClassroom } from '@/hooks/useClassrooms'
import { useAdminGroups } from '@/hooks/useGroups'
import { useAdminLab, useSaveLab } from '@/hooks/useLabs'
import { adminLabsApi } from '@/api/labsApi'
import type { LabProblemView, LabSummary } from '@/types'

const field = `rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-200
               placeholder-gray-400 outline-hidden focus:border-accent-600`

const LOCAL = "yyyy-MM-dd'T'HH:mm"

/** A problem in the editor: what it resolved to, and the marks the admin gives it. */
interface Row { link: string; view: LabProblemView; marks: string }

/**
 * Setting or changing a lab. Each pasted link is looked up on Codeforces or LeetCode as it is
 * added, so the admin sees the problem's real title before saving and a typo is caught here.
 */
export default function AdminLabEditPage() {
  const { classroomId: rawClassroom, labId: rawLab } = useParams()
  const classroomId = Number(rawClassroom)
  const labId = rawLab ? Number(rawLab) : null
  const { data: room } = useAdminClassroom(Number.isFinite(classroomId) ? classroomId : null)
  const { data: lab, isLoading, isError, error, refetch } = useAdminLab(classroomId, labId)

  if (!Number.isFinite(classroomId) || (rawLab && !Number.isFinite(labId))) {
    return <ErrorState title="Not found" error={new Error('That address does not name a lab.')} />
  }
  if (labId != null && isError) {
    return <ErrorState title="Could not load this lab" error={error} onRetry={() => refetch()} />
  }
  if (labId != null && (isLoading || !lab)) return <LoadingState label="Loading the lab…" />

  return (
    <div className="space-y-4">
      <Link to={labId != null ? `/admin/classrooms/${classroomId}/labs/${labId}` : `/admin/classrooms/${classroomId}`}
        className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-300">
        <ArrowLeft size={12} /> {labId != null ? 'Back to the lab' : room?.name ?? 'Classroom'}
      </Link>
      <PageHeader eyebrow={room ? `${room.name} · Lab` : 'Lab'}
        title={labId != null ? `Edit ${lab?.title}` : 'New lab'}
        description="Problems from Codeforces and LeetCode. Students solve them there; CPIntel reads their accepted submissions from their verified handles."
        className="mb-2" />
      <Editor classroomId={classroomId} lab={lab ?? null} />
    </div>
  )
}

function Editor({ classroomId, lab }: { classroomId: number; lab: LabSummary | null }) {
  const navigate = useNavigate()
  const save = useSaveLab(classroomId, lab?.labId ?? null)
  const { data: groups } = useAdminGroups()
  const teams = groups?.filter(g => g.classroomId === classroomId) ?? []

  const [title, setTitle] = useState(lab?.title ?? '')
  const [description, setDescription] = useState(lab?.description ?? '')
  const [opensAt, setOpensAt] = useState(() => format(lab ? new Date(lab.opensAt) : new Date(), LOCAL))
  const [dueAt, setDueAt] = useState(() => format(lab ? new Date(lab.dueAt)
    : set(addDays(new Date(), 7), { hours: 23, minutes: 59, seconds: 0 }), LOCAL))
  const [countEarlier, setCountEarlier] = useState(lab?.countEarlierSolves ?? false)
  const [groupIds, setGroupIds] = useState<number[]>(lab?.groupIds ?? [])
  const [rows, setRows] = useState<Row[]>(() => (lab?.problems ?? []).map(p => ({
    link: p.url, view: p, marks: String(Number(p.marks)),
  })))

  const [link, setLink] = useState('')
  const [adding, setAdding] = useState(false)
  const [addError, setAddError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  useEffect(() => { setAddError(null) }, [link])

  const add = async () => {
    const value = link.trim()
    if (!value) return
    setAdding(true)
    try {
      const view = (await adminLabsApi.resolve(classroomId, value)).data
      if (rows.some(r => r.view.platform === view.platform && r.view.key === view.key)) {
        setAddError('That problem is already in this lab.')
      } else {
        setRows(rs => [...rs, { link: view.url, view, marks: '1' }])
        setLink('')
      }
    } catch (e) {
      setAddError(errorMessage(e, 'Could not look that problem up.'))
    } finally {
      setAdding(false)
    }
  }

  const move = (i: number, by: number) => setRows(rs => {
    const next = [...rs]
    const j = i + by
    if (j < 0 || j >= next.length) return rs
    ;[next[i], next[j]] = [next[j], next[i]]
    return next
  })

  const total = rows.reduce((sum, r) => sum + (Number(r.marks) || 0), 0)

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setFormError(null)
    if (!title.trim()) return setFormError('Give the lab a title.')
    if (rows.length === 0) return setFormError('Add at least one problem.')
    const opens = new Date(opensAt)
    const due = new Date(dueAt)
    if (Number.isNaN(opens.getTime()) || Number.isNaN(due.getTime())) return setFormError('Set both dates.')
    if (due <= opens) return setFormError('The deadline has to be after the lab opens.')
    if (rows.some(r => !(Number(r.marks) >= 0))) return setFormError('Marks have to be 0 or more.')
    if (lab && (new Date(lab.opensAt).getTime() !== opens.getTime()
        || new Date(lab.dueAt).getTime() !== due.getTime()
        || lab.countEarlierSolves !== countEarlier)
        && !confirm('Changing the dates or whether earlier solves count rechecks every student '
          + 'from scratch. Their solves will show again after the check. Continue?')) return
    save.mutate({
      title: title.trim(),
      description: description.trim() || null,
      opensAt: opens.toISOString(),
      dueAt: due.toISOString(),
      countEarlierSolves: countEarlier,
      groupIds,
      problems: rows.map(r => ({ link: r.link, marks: Number(r.marks) })),
    }, {
      onSuccess: saved => navigate(`/admin/classrooms/${classroomId}/labs/${saved.labId}`),
    })
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <Panel title="Details">
        <div className="grid gap-4 p-4 md:grid-cols-2">
          <label className="block md:col-span-2">
            <span className="mb-1 block text-xs text-gray-400">Title</span>
            <input value={title} onChange={e => setTitle(e.target.value)} maxLength={200}
              placeholder="Lab 3: Two pointers" className={`${field} w-full`} required />
          </label>
          <label className="block md:col-span-2">
            <span className="mb-1 block text-xs text-gray-400">Instructions (optional)</span>
            <textarea value={description} onChange={e => setDescription(e.target.value)} maxLength={4000}
              rows={3} placeholder="Anything students should know." className={`${field} w-full`} />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs text-gray-400">Opens</span>
            <input type="datetime-local" value={opensAt} onChange={e => setOpensAt(e.target.value)}
              className={`${field} w-full`} required />
            <span className="mt-1 block text-[11px] text-gray-500">Students see the lab from here.</span>
          </label>
          <label className="block">
            <span className="mb-1 block text-xs text-gray-400">Due</span>
            <input type="datetime-local" value={dueAt} onChange={e => setDueAt(e.target.value)}
              className={`${field} w-full`} required />
            <span className="mt-1 block text-[11px] text-gray-500">
              Solves after this are shown as late and earn no marks.
            </span>
          </label>
          <label className="flex items-start gap-2 md:col-span-2">
            <input type="checkbox" checked={countEarlier} onChange={e => setCountEarlier(e.target.checked)}
              className="mt-0.5" />
            <span className="text-sm text-gray-300">
              Count problems solved before the lab opened
              <span className="block text-xs text-gray-500">
                Off: only solves between opening and the deadline count. On LeetCode only a student's
                recent solves are visible, so they may need to resubmit an old solution.
              </span>
            </span>
          </label>
        </div>
      </Panel>

      <Panel title="Who it is for"
        description={groupIds.length === 0 ? 'Every student in the classroom.' : 'Only students in the teams ticked below.'}>
        <div className="flex flex-wrap gap-2 p-4">
          {teams.length === 0 && (
            <span className="text-xs text-gray-500">This classroom has no teams; the lab goes to every student.</span>
          )}
          {teams.map(t => {
            const on = groupIds.includes(t.groupId)
            return (
              <label key={t.groupId} className={`flex cursor-pointer items-center gap-1.5 rounded-md border
                px-2 py-1 text-xs ${on ? 'border-accent-700 text-accent-200' : 'border-gray-800 text-gray-300'}`}>
                <input type="checkbox" checked={on}
                  onChange={() => setGroupIds(ids => on ? ids.filter(i => i !== t.groupId) : [...ids, t.groupId])} />
                {t.name} <span className="text-gray-600">· {t.memberCount}</span>
              </label>
            )
          })}
        </div>
      </Panel>

      <Panel title="Problems" description={`${rows.length} problem${rows.length === 1 ? '' : 's'} · ${total} mark${total === 1 ? '' : 's'} in all`}>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-[11px] uppercase tracking-wide text-gray-500">
                <th className="px-4 py-2 font-medium">#</th>
                <th className="px-4 py-2 font-medium">Problem</th>
                <th className="px-4 py-2 font-medium">Marks</th>
                <th className="px-4 py-2 font-medium"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {rows.length === 0 && <EmptyRow colSpan={4}>No problems yet. Paste a link below.</EmptyRow>}
              {rows.map((r, i) => (
                <tr key={`${r.view.platform}:${r.view.key}`}>
                  <td className="px-4 py-2 text-xs text-gray-500">{i + 1}</td>
                  <td className="px-4 py-2">
                    <a href={r.view.url} target="_blank" rel="noreferrer"
                      className="inline-flex items-center gap-1 text-gray-200 hover:text-accent-300">
                      {r.view.title} <ExternalLink size={11} className="text-gray-500" aria-hidden="true" />
                    </a>
                    <span className="mt-0.5 flex gap-1.5">
                      <Pill tone={r.view.platform === 'LEETCODE' ? 'amber' : 'accent'}>
                        {r.view.platform === 'LEETCODE' ? 'LeetCode' : 'Codeforces'}
                      </Pill>
                      {r.view.difficulty && <Pill>{r.view.difficulty}</Pill>}
                    </span>
                  </td>
                  <td className="px-4 py-2">
                    <input type="number" min={0} max={1000} step="0.5" value={r.marks}
                      aria-label={`Marks for ${r.view.title}`}
                      onChange={e => setRows(rs => rs.map((x, j) => j === i ? { ...x, marks: e.target.value } : x))}
                      className={`${field} w-20`} />
                  </td>
                  <td className="whitespace-nowrap px-4 py-2 text-right">
                    <button type="button" onClick={() => move(i, -1)} disabled={i === 0}
                      className="p-1 text-gray-500 hover:text-gray-200 disabled:opacity-30" aria-label="Move up">
                      <ArrowUp size={14} />
                    </button>
                    <button type="button" onClick={() => move(i, 1)} disabled={i === rows.length - 1}
                      className="p-1 text-gray-500 hover:text-gray-200 disabled:opacity-30" aria-label="Move down">
                      <ArrowDown size={14} />
                    </button>
                    <button type="button" onClick={() => setRows(rs => rs.filter((_, j) => j !== i))}
                      className="p-1 text-gray-500 hover:text-red-400" aria-label={`Remove ${r.view.title}`}>
                      <Trash2 size={14} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="space-y-1.5 border-t border-gray-800 p-4">
          <div className="flex max-w-2xl gap-2">
            <input value={link} onChange={e => setLink(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); add() } }}
              placeholder="https://codeforces.com/problemset/problem/1700/A or https://leetcode.com/problems/two-sum/"
              aria-label="Problem link" className={`${field} min-w-0 flex-1 font-mono text-xs`} />
            <button type="button" onClick={add} disabled={adding || !link.trim()}
              className="btn-secondary btn-sm whitespace-nowrap">
              {adding ? <Loader2 size={13} className="animate-spin" aria-hidden="true" /> : <Plus size={13} aria-hidden="true" />}
              Add
            </button>
          </div>
          {addError && <p role="alert" className="text-xs text-red-400">{addError}</p>}
          <p className="text-[11px] text-gray-500">
            Codeforces problemset, contest and gym links, or shorthand like 1700A. LeetCode problem links.
          </p>
        </div>
      </Panel>

      {formError && <p role="alert" className="text-sm text-red-400">{formError}</p>}
      <div className="flex gap-2">
        <button type="submit" className="btn-primary" disabled={save.isPending}>
          {save.isPending ? <Loader2 size={14} className="animate-spin" aria-hidden="true" /> : <Save size={14} aria-hidden="true" />}
          {lab ? 'Save changes' : 'Create lab'}
        </button>
      </div>
    </form>
  )
}
