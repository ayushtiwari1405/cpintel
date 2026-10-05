import { useEffect, useMemo, useState } from 'react'
import { formatDistanceToNow } from 'date-fns'
import {
  CheckCircle2, Code2, Loader2, Lock, RotateCcw, Save, Search, Snowflake, Undo2,
} from 'lucide-react'
import { clsx } from 'clsx'

import type { EvaluationSource } from '@/api/evaluationApi'
import {
  useEvaluationSheet, useEvaluationSubmission, useFreeze, useReopen, useSetMark,
} from '@/hooks/useEvaluation'
import type { EvaluationCell } from '@/types'

const field = `rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-200
               placeholder-gray-600 outline-none focus:border-indigo-600`

/** Marks as a person would write them: 30, 12.5 — never 30.0. */
function marks(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0+$/, '')
}

const keyOf = (c: Pick<EvaluationCell, 'userId' | 'label'>) => `${c.userId}:${c.label}`

/**
 * Marking one examination: the answers this marker covers on the left, the one being read on
 * the right with its code and the mark.
 *
 * <p>For each student and question there is one submission to read: the latest accepted one,
 * or the latest of all when none was accepted. Until a mark is set by hand the judge's stands,
 * which is full marks once accepted and none otherwise. Saving moves on to the next answer
 * still unmarked, so a range can be worked through without reaching for the mouse.
 *
 * <p>Shared by the TA's page and the admin's Evaluation tab; `source` says which door it uses.
 */
export function EvaluationSheet({ source }: { source: EvaluationSource }) {
  const { data: sheet, isLoading, error } = useEvaluationSheet(source)
  const [problem, setProblem] = useState<string>('')
  const [search, setSearch] = useState('')
  const [unmarkedOnly, setUnmarkedOnly] = useState(false)
  const [sentBackOnly, setSentBackOnly] = useState(false)
  const freeze = useFreeze(source)
  const [selected, setSelected] = useState<string | null>(null)

  const cells = useMemo(() => sheet?.cells ?? [], [sheet])
  const shown = useMemo(() => {
    const q = search.trim().toLowerCase()
    return cells.filter(c =>
      (!problem || c.label === problem)
      && (!unmarkedOnly || c.marks == null)
      && (!sentBackOnly || c.reopened)
      && (!q || c.username.toLowerCase().includes(q)
        || (c.fullName ?? '').toLowerCase().includes(q)))
  }, [cells, problem, search, unmarkedOnly, sentBackOnly])

  const current = cells.find(c => keyOf(c) === selected) ?? null
  const markedCount = cells.filter(c => c.marks != null).length

  // Start on the first answer in view, so there is always something to read.
  useEffect(() => {
    if (!current && shown.length > 0) setSelected(keyOf(shown[0]))
  }, [current, shown])

  if (isLoading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-sm text-gray-500">
        <Loader2 size={14} className="animate-spin" /> Loading the answers…
      </div>
    )
  }
  if (error || !sheet) {
    return (
      <p className="rounded-xl border border-gray-800 bg-gray-900 px-5 py-8 text-center text-sm
                    text-gray-500">
        {(error as any)?.response?.data?.message ?? 'Could not load the answers.'}
      </p>
    )
  }

  const editable = sheet.state === 'OPEN'
  // Only a TA's sheet can be frozen; the admin's has no freeze of its own.
  const isTa = !!source.freeze
  const frozen = sheet.frozenAt != null
  const sentBack = cells.filter(c => c.reopened).length
  const unmarked = cells.length - markedCount

  const doFreeze = () => {
    const ask = frozen
      ? 'Freeze again? The answers sent back to you close, and your marks are fixed once more.'
      : `Freeze your marking?${unmarked > 0 ? ` ${unmarked} answer${unmarked === 1 ? ' still has' : 's still have'} `
        + "the judge's mark, which will stand." : ''} After this you can change only answers an `
        + 'admin sends back to you.'
    if (confirm(ask)) freeze.mutate()
  }

  const nextUnmarked = (after: EvaluationCell) => {
    const at = shown.findIndex(c => keyOf(c) === keyOf(after))
    const rest = [...shown.slice(at + 1), ...shown.slice(0, Math.max(at, 0))]
    return rest.find(c => c.marks == null && keyOf(c) !== keyOf(after)) ?? null
  }

  return (
    <div className="space-y-3">
      {sheet.stateMessage && (
        <div className="flex items-center gap-2 rounded-lg border border-gray-800 bg-gray-900
                        px-4 py-2.5 text-xs text-gray-400">
          <Lock size={13} className="flex-shrink-0 text-gray-500" /> {sheet.stateMessage}
        </div>
      )}

      {isTa && editable && (
        <div className={clsx('flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-2.5 text-xs',
          frozen ? 'border-sky-900 bg-sky-950/30 text-sky-200' : 'border-gray-800 bg-gray-900 text-gray-400')}>
          <span className="flex items-center gap-2">
            <Snowflake size={13} className="flex-shrink-0" />
            {frozen
              ? sentBack > 0
                ? `Frozen. An admin sent ${sentBack} answer${sentBack === 1 ? '' : 's'} back to you; change ${sentBack === 1 ? 'it' : 'them'}, then freeze again.`
                : `Frozen ${formatDistanceToNow(new Date(sheet.frozenAt!), { addSuffix: true })}. Only answers an admin sends back can be changed.`
              : 'When you have finished, freeze your marking. Your marks are then fixed.'}
          </span>
          {(!frozen || sentBack > 0) && (
            <button onClick={doFreeze} disabled={freeze.isPending}
              className="flex items-center gap-1.5 rounded-lg bg-sky-700 px-3 py-1.5 text-xs
                         font-medium text-white hover:bg-sky-600 disabled:opacity-40">
              {freeze.isPending ? <Loader2 size={12} className="animate-spin" /> : <Snowflake size={12} />}
              {frozen ? 'Freeze again' : 'Freeze my marking'}
            </button>
          )}
        </div>
      )}

      {sheet.state !== 'NOT_ENDED' && (
        <div className="flex flex-wrap items-center gap-2">
          <select value={problem} onChange={e => setProblem(e.target.value)} className={field}>
            <option value="">Every question</option>
            {sheet.problems.map(p => (
              <option key={p.label} value={p.label}>
                {p.label}{p.title ? ` · ${p.title}` : ''} ({marks(p.maxMarks)})
              </option>
            ))}
          </select>
          <div className="relative">
            <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-600" />
            <input value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Find a student" className={`${field} pl-8`} />
          </div>
          <label className="flex items-center gap-1.5 text-xs text-gray-400">
            <input type="checkbox" checked={unmarkedOnly}
              onChange={e => setUnmarkedOnly(e.target.checked)} />
            Unmarked only
          </label>
          {cells.some(c => c.reopened) && (
            <label className="flex items-center gap-1.5 text-xs text-gray-400">
              <input type="checkbox" checked={sentBackOnly}
                onChange={e => setSentBackOnly(e.target.checked)} />
              Sent back only
            </label>
          )}
          <span className="ml-auto text-xs tabular-nums text-gray-500">
            {markedCount} of {cells.length} marked by hand
          </span>
        </div>
      )}

      {sheet.state !== 'NOT_ENDED' && (cells.length === 0 ? (
        <p className="rounded-xl border border-gray-800 bg-gray-900 px-5 py-8 text-center text-sm
                      text-gray-500">
          Nothing to mark: nobody on the roster falls in what you were given.
        </p>
      ) : (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,380px)_minmax(0,1fr)]">
          <div className="max-h-[70vh] overflow-y-auto rounded-xl border border-gray-800
                          bg-gray-900">
            <table className="w-full text-sm">
              <thead className="sticky top-0 bg-gray-900">
                <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                  <th className="px-3 py-2 font-medium">Student</th>
                  <th className="px-2 py-2 font-medium">Q</th>
                  <th className="px-2 py-2 font-medium">Judge</th>
                  <th className="px-3 py-2 text-right font-medium">Mark</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-800">
                {shown.length === 0 && (
                  <tr><td colSpan={4} className="px-3 py-6 text-center text-xs text-gray-600">
                    Nothing matches.
                  </td></tr>
                )}
                {shown.map(c => (
                  <tr key={keyOf(c)} onClick={() => setSelected(keyOf(c))}
                    className={clsx('cursor-pointer',
                      keyOf(c) === selected ? 'bg-indigo-950/50' : 'hover:bg-gray-800/50')}>
                    <td className="px-3 py-2">
                      <span className="text-gray-200">{c.username}</span>
                      {c.fullName && (
                        <span className="block truncate text-[11px] text-gray-600">{c.fullName}</span>
                      )}
                    </td>
                    <td className="px-2 py-2 font-mono text-xs text-gray-400">
                      {c.label}
                      {c.reopened && <Undo2 size={11} className="ml-1 inline text-amber-400"
                        aria-label="sent back" />}
                      {!c.reopened && c.locked && <Lock size={10} className="ml-1 inline text-gray-600" />}
                    </td>
                    <td className="px-2 py-2"><Verdict cell={c} /></td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      {c.marks != null
                        ? <span className="text-indigo-300">{marks(c.marks)}</span>
                        : <span className="text-gray-600">{marks(c.autoMarks)}</span>}
                      <span className="text-gray-700">/{marks(c.maxMarks)}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {current ? (
            <Reader key={keyOf(current)} source={source} cell={current}
              editable={editable && !current.locked} open={editable}
              onSaved={saved => {
                const next = nextUnmarked(saved)
                if (next) setSelected(keyOf(next))
              }} />
          ) : (
            <div className="flex min-h-[240px] items-center justify-center rounded-xl border
                            border-gray-800 bg-gray-950 text-sm text-gray-500">
              Pick an answer to read it.
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

function Verdict({ cell }: { cell: EvaluationCell }) {
  if (!cell.submission) return <span className="text-[11px] text-gray-600">nothing sent</span>
  const v = cell.submission.verdict
  return (
    <span className={clsx('rounded-full px-1.5 py-0.5 text-[10px] font-medium',
      cell.submission.accepted ? 'bg-green-900/40 text-green-400'
        : !v || v === 'TESTING' || v === 'SUBMITTING' ? 'bg-gray-800 text-gray-400'
          : 'bg-red-900/40 text-red-400')}>
      {(v ?? 'judging').toLowerCase().replace(/_/g, ' ')}
    </span>
  )
}

/** One answer: its code, and the mark. */
function Reader({ source, cell, editable, open, onSaved }: {
  source: EvaluationSource
  cell: EvaluationCell
  /** This marker can change this mark now. */
  editable: boolean
  /** Marking is open on the examination at all. */
  open: boolean
  onSaved: (cell: EvaluationCell) => void
}) {
  const reopen = useReopen(source)
  const { data: submission, isFetching } = useEvaluationSubmission(source,
    cell.submission ? cell.userId : null, cell.submission ? cell.label : null)
  const save = useSetMark(source)
  const [value, setValue] = useState(cell.marks != null ? String(cell.marks) : '')
  const [remark, setRemark] = useState(cell.remark ?? '')

  const parsed = value.trim() === '' ? null : Number(value)
  const invalid = parsed != null && (!Number.isFinite(parsed) || parsed < 0 || parsed > cell.maxMarks)

  const submit = () => {
    if (parsed == null || invalid) return
    save.mutate({ userId: cell.userId, label: cell.label, marks: parsed, remark: remark.trim() || null },
      { onSuccess: onSaved })
  }

  return (
    <div className="flex min-w-0 flex-col rounded-xl border border-gray-800 bg-gray-950">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-gray-800
                      px-4 py-2.5">
        <div className="min-w-0">
          <span className="text-sm text-gray-200">{cell.username}</span>
          {cell.fullName && <span className="ml-1.5 text-xs text-gray-500">{cell.fullName}</span>}
          <span className="ml-2 font-mono text-xs text-gray-400">· {cell.label}</span>
        </div>
        <div className="flex items-center gap-2 text-[11px] text-gray-500">
          {cell.submission && (
            <>
              <Verdict cell={cell} />
              {cell.submission.languageLabel && <span>{cell.submission.languageLabel}</span>}
              <span>{formatDistanceToNow(new Date(cell.submission.submittedAt), { addSuffix: true })}</span>
              <span>· {cell.attempts} attempt{cell.attempts === 1 ? '' : 's'}</span>
            </>
          )}
        </div>
      </div>

      {!cell.submission ? (
        <div className="flex min-h-[200px] flex-col items-center justify-center px-6 text-center">
          <Code2 size={20} className="text-gray-700" />
          <p className="mt-2 text-sm text-gray-500">{cell.username} sent nothing to {cell.label}.</p>
        </div>
      ) : isFetching && !submission ? (
        <div className="flex min-h-[200px] items-center justify-center gap-2 text-sm text-gray-500">
          <Loader2 size={14} className="animate-spin" /> Fetching the code…
        </div>
      ) : (
        <pre className="max-h-[50vh] min-h-[200px] overflow-auto px-4 py-3 text-xs leading-relaxed
                        text-gray-300">
          <code>{submission?.source ?? ''}</code>
        </pre>
      )}

      <div className="space-y-2 border-t border-gray-800 p-4">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Mark (out of {marks(cell.maxMarks)})</span>
            <input value={value} onChange={e => setValue(e.target.value)} disabled={!editable}
              inputMode="decimal" placeholder={marks(cell.autoMarks)}
              onKeyDown={e => { if (e.key === 'Enter') submit() }}
              className={clsx(field, 'w-28 tabular-nums disabled:opacity-50',
                invalid && 'border-red-700')} />
          </label>
          <p className="pb-2 text-xs text-gray-600">
            Judge's mark: {marks(cell.autoMarks)}
            {cell.marks != null && cell.markedBy && (
              <> · set by {cell.markedBy}
                {cell.markedAt && <> {formatDistanceToNow(new Date(cell.markedAt), { addSuffix: true })}</>}
              </>
            )}
          </p>
        </div>
        <textarea value={remark} onChange={e => setRemark(e.target.value)} disabled={!editable}
          maxLength={1000} rows={2} placeholder="Remark (optional)"
          className={clsx(field, 'w-full resize-y disabled:opacity-50')} />
        {invalid && (
          <p className="text-xs text-red-400">Between 0 and {marks(cell.maxMarks)}.</p>
        )}
        {open && !editable && (
          <p className="flex items-center gap-1.5 text-xs text-gray-500">
            <Lock size={11} /> You froze your marking. An admin can send this answer back to you.
          </p>
        )}
        {cell.reopened && (
          <p className="flex items-center gap-1.5 text-xs text-amber-400/90">
            <Undo2 size={11} /> Sent back by an admin for another look.
          </p>
        )}
        {open && source.reopen && cell.frozen && (
          <button
            onClick={() => reopen.mutate({ userId: cell.userId, label: cell.label, reopen: !cell.reopened })}
            disabled={reopen.isPending}
            className="flex items-center gap-1.5 rounded-lg border border-amber-900/60 px-3 py-1.5
                       text-xs text-amber-300 hover:border-amber-700 disabled:opacity-40"
            title="The TA who marked this has frozen their marking"
          >
            <Undo2 size={12} /> {cell.reopened ? 'Take it back from the TA' : 'Send back to the TA'}
          </button>
        )}
        {editable && (
          <div className="flex flex-wrap items-center gap-2">
            <button onClick={submit} disabled={save.isPending || parsed == null || invalid}
              className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                         font-medium text-white hover:bg-indigo-500 disabled:opacity-40">
              {save.isPending ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
              Save and next
            </button>
            <button onClick={() => setValue(String(cell.autoMarks))}
              className="flex items-center gap-1.5 rounded-lg border border-gray-800 px-3 py-2
                         text-sm text-gray-300 hover:border-gray-700">
              <CheckCircle2 size={14} /> Use the judge's mark
            </button>
            {cell.marks != null && (
              <button
                onClick={() => save.mutate({ userId: cell.userId, label: cell.label, marks: null },
                  { onSuccess: () => { setValue(''); setRemark('') } })}
                disabled={save.isPending}
                className="flex items-center gap-1.5 rounded-lg px-3 py-2 text-xs text-gray-500
                           hover:text-gray-300"
                title="Take the hand-set mark away; the judge's mark stands again"
              >
                <RotateCcw size={13} /> Clear
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
