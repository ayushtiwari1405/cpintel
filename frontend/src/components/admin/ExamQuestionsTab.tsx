import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { format } from 'date-fns'
import { ArrowDown, ArrowUp, Loader2, Megaphone, Plus, RefreshCw, Trash2, UploadCloud } from 'lucide-react'
import { Panel, Pill } from '@/components/admin/AdminUi'
import { ErrorState, LoadingState } from '@/components/common/States'
import { useExamBank, useExamBankActions } from '@/hooks/useExamBank'
import { useRejudgeProblem } from '@/hooks/useExams'
import type { EventDetail, ExamBankState, ExamChosenQuestion } from '@/types'

const when = (iso: string | null) => (iso ? format(new Date(iso), 'd MMM yyyy, HH:mm') : '—')

/**
 * The examination's questions from the classroom's question bank: choose them, publish them to
 * the judge, correct one during the paper, and tell candidates.
 */
export function ExamQuestionsTab({ detail }: { detail: EventDetail }) {
  const eventId = detail.event.eventId
  const { data: state, isLoading, isError, error, refetch } = useExamBank(eventId)
  if (isLoading) return <LoadingState label="Loading the questions…" />
  if (isError || !state) return <ErrorState title="Could not load the questions" error={error} onRetry={() => refetch()} />
  return (
    <div className="space-y-4">
      <Publication state={state} eventId={eventId} classroomId={detail.event.classroomId} />
      {state.editable
        ? <Choice state={state} eventId={eventId} />
        : <Published state={state} eventId={eventId} />}
      <Announcements state={state} eventId={eventId} />
    </div>
  )
}

function Publication({ state, eventId, classroomId }: { state: ExamBankState; eventId: number; classroomId: number }) {
  const { publish } = useExamBankActions(eventId)
  const pub = state.publication
  const notReady = state.chosen.filter(q => q.status !== 'READY' || q.archived)
  const blocked = !state.scheduled ? 'Set the start and end in Settings first.'
    : state.chosen.length === 0 ? 'Choose the questions first.'
    : notReady.length > 0 ? `${notReady.length} chosen question${notReady.length === 1 ? ' has' : 's have'} not passed a check.`
    : null
  return (
    <Panel title="On the judge"
      description={`Publishing creates contest "${state.judgeContestId}" on the classroom's judge, uploads each question, and sets this examination's problems.`}
      actions={state.editable && (
        <button type="button" className="btn-primary btn-sm" disabled={blocked != null || publish.isPending}
          title={blocked ?? undefined} onClick={() => publish.mutate()}>
          {publish.isPending ? <Loader2 size={13} className="animate-spin" /> : <UploadCloud size={13} aria-hidden="true" />}
          {pub?.status === 'PUBLISHED' ? 'Publish again' : 'Publish to the judge'}
        </button>
      )}>
      <div className="space-y-2 p-4 text-sm">
        {pub == null ? (
          <p className="text-xs text-gray-500">Not published yet.</p>
        ) : (
          <>
            <p className="flex flex-wrap items-center gap-2">
              {pub.status === 'PUBLISHED' ? <Pill tone="green">published</Pill> : <Pill tone="red">stopped part way</Pill>}
              <span className="text-xs text-gray-500">
                {when(pub.publishedAt)}{pub.publishedBy && <> by {pub.publishedBy}</>}
                {pub.contestCreated && pub.judgeStart && <> · judge contest runs {when(pub.judgeStart)} to {when(pub.judgeEnd)}</>}
              </span>
            </p>
            {pub.note && <p className="text-xs text-gray-400">{pub.note}</p>}
          </>
        )}
        {blocked && state.editable && <p className="text-xs text-amber-300">{blocked}</p>}
        <p className="text-xs text-gray-500">
          Write and check questions in the <Link className="text-accent-300 hover:underline"
            to={`/questions?classroom=${classroomId}`}>question bank</Link>.
          Publishing needs the classroom's DOMjudge service account to be a judge admin.
        </p>
      </div>
    </Panel>
  )
}

function StatusPill({ q }: { q: ExamChosenQuestion }) {
  if (q.archived) return <Pill tone="gray">archived</Pill>
  return q.status === 'READY' ? <Pill tone="green">checked</Pill> : <Pill tone="amber">not checked</Pill>
}

/** Before the start: which questions, in which order. */
function Choice({ state, eventId }: { state: ExamBankState; eventId: number }) {
  const { choose } = useExamBankActions(eventId)
  const [ids, setIds] = useState<number[]>(() => state.chosen.map(q => q.questionId))
  const [adding, setAdding] = useState('')
  useEffect(() => { setIds(state.chosen.map(q => q.questionId)) }, [state.chosen])

  const titles = new Map<number, { title: string; ready: boolean }>()
  state.available.forEach(q => titles.set(q.questionId, { title: q.title, ready: q.status === 'READY' }))
  state.chosen.forEach(q => titles.set(q.questionId, { title: q.title, ready: q.status === 'READY' && !q.archived }))
  const saved = state.chosen.map(q => q.questionId)
  const dirty = ids.length !== saved.length || ids.some((id, i) => id !== saved[i])
  const byId = new Map(state.chosen.map(q => [q.questionId, q]))
  const left = state.available.filter(q => !ids.includes(q.questionId))
  const move = (i: number, by: -1 | 1) => setIds(list => {
    const next = [...list]
    ;[next[i], next[i + by]] = [next[i + by], next[i]]
    return next
  })

  return (
    <Panel title="Questions" description="In the order candidates see them: A, B, C…"
      actions={
        <button type="button" className="btn-secondary btn-sm" disabled={!dirty || choose.isPending}
          onClick={() => choose.mutate(ids)}>
          {choose.isPending && <Loader2 size={13} className="animate-spin" />} Save the choice
        </button>
      }>
      <div className="space-y-3 p-4">
        {ids.length === 0 ? <p className="text-xs text-gray-500">None chosen yet.</p> : (
          <ol className="space-y-1.5">
            {ids.map((id, i) => {
              const q = byId.get(id)
              return (
                <li key={id} className="flex flex-wrap items-center gap-2 rounded-lg border border-gray-800 px-3 py-2 text-sm">
                  <span className="w-5 font-mono text-xs text-gray-500">{String.fromCharCode(65 + i)}</span>
                  <span className="min-w-0 flex-1 truncate text-gray-100">{titles.get(id)?.title ?? `Question ${id}`}</span>
                  {q ? <StatusPill q={q} /> : titles.get(id)?.ready ? <Pill tone="green">checked</Pill> : <Pill tone="amber">not checked</Pill>}
                  {q?.changedSincePublished && <Pill tone="accent">changed since published</Pill>}
                  <button type="button" className="btn-ghost btn-sm" disabled={i === 0} onClick={() => move(i, -1)}
                    aria-label={`Move ${titles.get(id)?.title} up`}><ArrowUp size={13} aria-hidden="true" /></button>
                  <button type="button" className="btn-ghost btn-sm" disabled={i === ids.length - 1} onClick={() => move(i, 1)}
                    aria-label={`Move ${titles.get(id)?.title} down`}><ArrowDown size={13} aria-hidden="true" /></button>
                  <button type="button" className="btn-ghost btn-sm" onClick={() => setIds(list => list.filter(x => x !== id))}
                    aria-label={`Remove ${titles.get(id)?.title}`}><Trash2 size={13} aria-hidden="true" /></button>
                </li>
              )
            })}
          </ol>
        )}
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex min-w-64 flex-col gap-1">
            <span className="text-xs text-gray-500">Add a question from the bank</span>
            <select className="input" value={adding} onChange={e => setAdding(e.target.value)}>
              <option value="">Choose…</option>
              {left.map(q => (
                <option key={q.questionId} value={q.questionId}>
                  {q.title}{q.status === 'READY' ? '' : ' (not checked)'}
                </option>
              ))}
            </select>
          </label>
          <button type="button" className="btn-secondary btn-sm" disabled={!adding || ids.length >= 26}
            onClick={() => { setIds(list => [...list, Number(adding)]); setAdding('') }}>
            <Plus size={13} aria-hidden="true" /> Add
          </button>
        </div>
        {dirty && <p className="text-xs text-amber-300">Save the choice, then publish, for the judge to follow it.</p>}
      </div>
    </Panel>
  )
}

/** After the start: the problems are fixed, but a checked correction can replace one. */
function Published({ state, eventId }: { state: ExamBankState; eventId: number }) {
  const { republish } = useExamBankActions(eventId)
  const rejudge = useRejudgeProblem()
  const [fixing, setFixing] = useState<number | null>(null)
  const [notice, setNotice] = useState('')
  const [rejudgeLabel, setRejudgeLabel] = useState<string | null>(null)

  return (
    <Panel title="Questions" description="The paper has started, so its problems are fixed. A question corrected and checked again in the bank can replace its published version.">
      <div className="space-y-2 p-4">
        {state.chosen.length === 0 && <p className="text-xs text-gray-500">This examination has no questions from the bank.</p>}
        {state.chosen.map(q => (
          <div key={q.questionId} className="rounded-lg border border-gray-800 px-3 py-2 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <span className="w-5 font-mono text-xs text-gray-500">{q.label ?? '–'}</span>
              <span className="min-w-0 flex-1 truncate text-gray-100">{q.title}</span>
              <span className="text-xs text-gray-500">judge has version {q.publishedVersion ?? '—'}</span>
              {q.changedSincePublished && <StatusPill q={q} />}
              {q.changedSincePublished && q.status === 'READY' && fixing !== q.questionId && (
                <button type="button" className="btn-secondary btn-sm" onClick={() => {
                  setFixing(q.questionId)
                  setNotice(`Question ${q.label} has been corrected. Your submissions to it will be judged again.`)
                }}>
                  <RefreshCw size={13} aria-hidden="true" /> Publish the correction
                </button>
              )}
            </div>
            {fixing === q.questionId && (
              <div role="group" aria-label={`Correct question ${q.label}`}
                className="mt-2 space-y-2 rounded-lg border border-amber-800/70 bg-amber-950/30 p-3">
                <p className="text-xs text-amber-200">
                  Candidates are sitting this paper. The judge will use version {q.version} of "{q.title}" for every
                  submission from now on. Earlier submissions keep their verdicts until you rejudge the problem.
                </p>
                <label className="flex flex-col gap-1">
                  <span className="text-xs text-gray-400">Notice to every candidate (leave empty to send none)</span>
                  <textarea className="input min-h-16 text-sm" maxLength={1000} value={notice}
                    onChange={e => setNotice(e.target.value)} />
                </label>
                <div className="flex gap-2">
                  <button type="button" className="btn-danger btn-sm" disabled={republish.isPending}
                    onClick={() => republish.mutate({ questionId: q.questionId, notice: notice.trim() || null }, {
                      onSuccess: () => { setFixing(null); setRejudgeLabel(q.label) },
                    })}>
                    {republish.isPending && <Loader2 size={13} className="animate-spin" />} Replace it on the judge
                  </button>
                  <button type="button" className="btn-ghost btn-sm" onClick={() => setFixing(null)}>Cancel</button>
                </div>
              </div>
            )}
          </div>
        ))}
        {rejudgeLabel && (
          <div role="status" className="flex flex-wrap items-center gap-2 rounded-lg border border-accent-800/70 bg-accent-950/40 px-3 py-2 text-xs text-accent-200">
            Question {rejudgeLabel} is corrected on the judge. Judge its earlier submissions again?
            <button type="button" className="btn-secondary btn-sm" disabled={rejudge.isPending}
              onClick={() => rejudge.mutate({ eventId, label: rejudgeLabel }, { onSuccess: () => setRejudgeLabel(null) })}>
              Rejudge problem {rejudgeLabel}
            </button>
            <button type="button" className="btn-ghost btn-sm" onClick={() => setRejudgeLabel(null)}>Not now</button>
          </div>
        )}
      </div>
    </Panel>
  )
}

function Announcements({ state, eventId }: { state: ExamBankState; eventId: number }) {
  const { announce } = useExamBankActions(eventId)
  const [message, setMessage] = useState('')
  return (
    <Panel title="Notices to candidates" description="Shown in the examination from its start, within about a minute.">
      <div className="space-y-3 p-4">
        {state.announcements.length === 0 ? <p className="text-xs text-gray-500">None sent.</p> : (
          <ul className="space-y-1.5">
            {state.announcements.map(a => (
              <li key={a.id} className="rounded-lg border border-gray-800 px-3 py-2 text-sm text-gray-200">
                <span className="mr-2 text-xs text-gray-500">{when(a.createdAt)}</span>{a.message}
              </li>
            ))}
          </ul>
        )}
        <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
          <label className="flex flex-1 flex-col gap-1">
            <span className="text-xs text-gray-500">New notice</span>
            <textarea className="input min-h-14 text-sm" maxLength={1000} value={message}
              onChange={e => setMessage(e.target.value)} />
          </label>
          <button type="button" className="btn-secondary btn-sm" disabled={!message.trim() || announce.isPending}
            onClick={() => announce.mutate(message.trim(), { onSuccess: () => setMessage('') })}>
            <Megaphone size={13} aria-hidden="true" /> Send to everyone
          </button>
        </div>
      </div>
    </Panel>
  )
}
