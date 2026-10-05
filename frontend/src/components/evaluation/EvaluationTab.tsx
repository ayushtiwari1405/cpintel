import { useMemo, useState } from 'react'
import { Loader2, Plus, Split, Trash2 } from 'lucide-react'

import { adminEvaluationApi } from '@/api/evaluationApi'
import { EmptyRow, Panel } from '@/components/admin/AdminUi'
import { EvaluationSheet } from '@/components/evaluation/EvaluationSheet'
import { useAssignmentActions, useAssignmentBoard } from '@/hooks/useEvaluation'
import type { AssignmentBoard } from '@/types'

const field = `rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-200
               placeholder-gray-600 outline-none focus:border-indigo-600`

/**
 * An examination's marking, from the admin's side: who marks what, and every mark.
 *
 * <p>A TA is given a question, a range of usernames, or that question for that range; their
 * rows add up. Ranges follow natural order (cs2 before cs10), the same order the roster is
 * listed in here, and "Split the roster" cuts it into equal consecutive ranges, one per TA.
 */
export function EvaluationTab({ eventId, done }: { eventId: number; done: boolean }) {
  const { data: board, isLoading } = useAssignmentBoard(eventId)
  const source = useMemo(() => adminEvaluationApi.source(eventId), [eventId])

  return (
    <div className="space-y-4">
      {isLoading || !board ? (
        <div className="flex items-center justify-center gap-2 py-10 text-sm text-gray-500">
          <Loader2 size={14} className="animate-spin" /> Loading…
        </div>
      ) : (
        <Assignments eventId={eventId} board={board} done={done} />
      )}
      <Panel title="Marks"
        description="Every answer, with the submission to read: the latest accepted one, else the latest. A mark set here replaces the judge's on the leaderboard">
        <div className="p-4"><EvaluationSheet source={source} /></div>
      </Panel>
    </div>
  )
}

function Assignments({ eventId, board, done }: {
  eventId: number
  board: AssignmentBoard
  done: boolean
}) {
  const { assign, unassign } = useAssignmentActions(eventId)
  const [ta, setTa] = useState('')
  const [label, setLabel] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [splitting, setSplitting] = useState(false)

  const add = () => {
    if (!ta) return
    assign.mutate({
      taUserId: Number(ta),
      problemLabel: label || null,
      rangeFrom: from.trim() || null,
      rangeTo: to.trim() || null,
    }, { onSuccess: () => { setFrom(''); setTo('') } })
  }

  const noTas = board.tas.length === 0

  return (
    <Panel title="Who marks what"
      description="A question, a range of usernames, or that question for that range. Empty means all">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="px-4 py-2 font-medium">TA</th>
              <th className="px-4 py-2 font-medium">Question</th>
              <th className="px-4 py-2 font-medium">Students</th>
              <th className="px-4 py-2 font-medium" />
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {board.assignments.length === 0 && (
              <EmptyRow colSpan={4}>
                {noTas ? "This classroom has no TAs. Add them on the classroom's page."
                  : 'Nobody has been given anything to mark yet.'}
              </EmptyRow>
            )}
            {board.assignments.map(a => (
              <tr key={a.assignmentId}>
                <td className="px-4 py-2.5 text-gray-200">{a.taUsername ?? `#${a.taUserId}`}</td>
                <td className="px-4 py-2.5 font-mono text-xs text-gray-300">
                  {a.problemLabel ?? <span className="font-sans text-gray-500">every question</span>}
                </td>
                <td className="px-4 py-2.5 text-xs text-gray-400">
                  {a.rangeFrom == null && a.rangeTo == null
                    ? 'everyone'
                    : <span className="font-mono">{a.rangeFrom ?? 'first'} – {a.rangeTo ?? 'last'}</span>}
                  <span className="ml-2 text-gray-600">({a.studentCount})</span>
                </td>
                <td className="px-4 py-2.5 text-right">
                  <button onClick={() => unassign.mutate(a.assignmentId)} disabled={unassign.isPending}
                    aria-label="Take this back"
                    className="text-gray-600 hover:text-red-400">
                    <Trash2 size={13} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {!done && !noTas && (
        <div className="space-y-3 border-t border-gray-800 p-4">
          <div className="flex flex-wrap items-end gap-2">
            <select value={ta} onChange={e => setTa(e.target.value)} className={field}>
              <option value="">Pick a TA</option>
              {board.tas.map(t => <option key={t.userId} value={t.userId}>{t.username}</option>)}
            </select>
            <select value={label} onChange={e => setLabel(e.target.value)} className={field}>
              <option value="">Every question</option>
              {board.problems.map(p => <option key={p} value={p}>{p}</option>)}
            </select>
            <input value={from} onChange={e => setFrom(e.target.value)} list={`roster-${eventId}`}
              placeholder="From username" className={`${field} w-40 font-mono`} />
            <input value={to} onChange={e => setTo(e.target.value)} list={`roster-${eventId}`}
              placeholder="To username" className={`${field} w-40 font-mono`} />
            <datalist id={`roster-${eventId}`}>
              {board.students.map(s => <option key={s} value={s} />)}
            </datalist>
            <button onClick={add} disabled={!ta || assign.isPending}
              className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                         font-medium text-white hover:bg-indigo-500 disabled:opacity-40">
              <Plus size={14} /> Assign
            </button>
            <button onClick={() => setSplitting(v => !v)}
              className="flex items-center gap-1.5 rounded-lg border border-gray-800 px-3 py-2
                         text-sm text-gray-300 hover:border-gray-700">
              <Split size={14} /> Split the roster
            </button>
          </div>
          {splitting && <SplitRoster eventId={eventId} board={board}
            onDone={() => setSplitting(false)} />}
          <p className="text-xs text-gray-600">
            {board.students.length} student{board.students.length === 1 ? '' : 's'} on the
            roster
            {board.students.length > 0 && <>, {board.students[0]} to {board.students[board.students.length - 1]}</>}.
            Ranges include both ends and follow this order.
          </p>
        </div>
      )}
    </Panel>
  )
}

/** Cuts the roster into equal consecutive ranges, one per chosen TA. */
function SplitRoster({ eventId, board, onDone }: {
  eventId: number
  board: AssignmentBoard
  onDone: () => void
}) {
  const { assign } = useAssignmentActions(eventId)
  const [chosen, setChosen] = useState<number[]>([])
  const [label, setLabel] = useState('')
  const [busy, setBusy] = useState(false)

  const students = board.students
  const plan = useMemo(() => {
    if (chosen.length === 0 || students.length === 0) return []
    const per = Math.ceil(students.length / chosen.length)
    return chosen.map((taUserId, i) => {
      const slice = students.slice(i * per, (i + 1) * per)
      return slice.length === 0 ? null
        : { taUserId, from: slice[0], to: slice[slice.length - 1], count: slice.length }
    }).filter((p): p is NonNullable<typeof p> => p != null)
  }, [chosen, students])

  const apply = async () => {
    setBusy(true)
    try {
      for (const p of plan) {
        await assign.mutateAsync({
          taUserId: p.taUserId, problemLabel: label || null, rangeFrom: p.from, rangeTo: p.to,
        })
      }
      onDone()
    } finally {
      setBusy(false)
    }
  }

  const name = (id: number) => board.tas.find(t => t.userId === id)?.username ?? `#${id}`

  return (
    <div className="space-y-2 rounded-lg border border-gray-800 bg-gray-950 p-3">
      <p className="text-xs text-gray-500">Tick the TAs to share the roster between, in order.</p>
      <div className="flex flex-wrap gap-3">
        {board.tas.map(t => (
          <label key={t.userId} className="flex items-center gap-1.5 text-xs text-gray-300">
            <input type="checkbox" checked={chosen.includes(t.userId)}
              onChange={e => setChosen(c => e.target.checked
                ? [...c, t.userId] : c.filter(id => id !== t.userId))} />
            {t.username}
          </label>
        ))}
      </div>
      <select value={label} onChange={e => setLabel(e.target.value)} className={field}>
        <option value="">Every question</option>
        {board.problems.map(p => <option key={p} value={p}>Only {p}</option>)}
      </select>
      {plan.length > 0 && (
        <ul className="space-y-0.5 text-xs text-gray-400">
          {plan.map(p => (
            <li key={p.taUserId}>
              {name(p.taUserId)}: <span className="font-mono">{p.from} – {p.to}</span>
              <span className="text-gray-600"> ({p.count})</span>
            </li>
          ))}
        </ul>
      )}
      <button onClick={apply} disabled={plan.length === 0 || busy}
        className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                   font-medium text-white hover:bg-indigo-500 disabled:opacity-40">
        {busy && <Loader2 size={14} className="animate-spin" />}
        Assign these ranges
      </button>
    </div>
  )
}
