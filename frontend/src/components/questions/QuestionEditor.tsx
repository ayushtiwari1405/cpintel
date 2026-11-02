import { useEffect, useRef, useState } from 'react'
import { format } from 'date-fns'
import { Archive, ArchiveRestore, ArrowLeft, Download, Loader2, Save } from 'lucide-react'
import { questionBankApi } from '@/api/questionBankApi'
import { useToast } from '@/components/common/Toaster'
import { PageHeader } from '@/components/common/PageHeader'
import { ErrorState, LoadingState } from '@/components/common/States'
import { Panel, Pill } from '@/components/admin/AdminUi'
import { QuestionTests, packageName, saveBlob } from '@/components/questions/QuestionTests'
import { StatementPanel } from '@/components/questions/StatementPanel'
import { useBankQuestion, useQuestionActions } from '@/hooks/useQuestionBank'
import type { ModelLanguage, QuestionChecker, QuestionDetail, QuestionRequest } from '@/types'

const CHECKERS: { value: QuestionChecker; label: string; hint: string }[] = [
  { value: 'EXACT', label: 'Exact words', hint: 'Every word and number must match, case included. Spaces and line breaks may differ.' },
  { value: 'IGNORE_CASE', label: 'Ignore case', hint: 'As exact, but YES and yes count as the same.' },
  { value: 'FLOAT', label: 'Numbers within a tolerance', hint: 'Numbers may differ by the tolerance, absolute or relative.' },
]

const LANGUAGES: { value: ModelLanguage; label: string }[] = [
  { value: 'cpp', label: 'C++' },
  { value: 'c', label: 'C' },
  { value: 'python3', label: 'Python 3' },
]

interface Draft {
  title: string
  statement: string
  timeLimitMs: string
  memoryLimitMb: string
  checker: QuestionChecker
  floatTolerance: string
  modelLanguage: ModelLanguage
  modelSource: string
}

function draftOf(q: QuestionDetail): Draft {
  return {
    title: q.title,
    statement: q.statement,
    timeLimitMs: String(q.timeLimitMs),
    memoryLimitMb: String(q.memoryLimitMb),
    checker: q.checker,
    floatTolerance: q.floatTolerance != null ? String(q.floatTolerance) : '0.000001',
    modelLanguage: q.modelLanguage ?? 'cpp',
    modelSource: q.modelSource ?? '',
  }
}

function requestOf(d: Draft, version: number): QuestionRequest {
  const source = d.modelSource.trim() ? d.modelSource : null
  return {
    title: d.title.trim(),
    statement: d.statement,
    timeLimitMs: Number(d.timeLimitMs),
    memoryLimitMb: Number(d.memoryLimitMb),
    checker: d.checker,
    floatTolerance: d.checker === 'FLOAT' ? Number(d.floatTolerance) : null,
    modelLanguage: source ? d.modelLanguage : null,
    modelSource: source,
    version,
  }
}

const same = (a: Draft, b: Draft) => JSON.stringify(a) === JSON.stringify(b)

/** One question: its statement and settings, its model solution, and its tests. */
export function QuestionEditor({ questionId, readOnly, onBack }: {
  questionId: number
  readOnly: boolean
  onBack: () => void
}) {
  const { data: question, isLoading, isError, error, refetch } = useBankQuestion(questionId)
  if (isLoading) return <LoadingState label="Loading the question…" />
  if (isError || !question) {
    return <ErrorState title="Could not load the question" error={error} onRetry={() => refetch()} />
  }
  return <Editor question={question} readOnly={readOnly || question.archived} onBack={onBack} />
}

function Editor({ question, readOnly, onBack }: {
  question: QuestionDetail
  readOnly: boolean
  onBack: () => void
}) {
  const { save, setArchived } = useQuestionActions(question.questionId)
  const toast = useToast()
  const [exporting, setExporting] = useState(false)

  async function downloadPackage() {
    setExporting(true)
    try {
      saveBlob(await questionBankApi.exportPackage(question.questionId), packageName(question.title))
    } catch {
      toast.push('error', 'Could not build the package')
    } finally {
      setExporting(false)
    }
  }
  // What the form was filled from. A newer version arriving (someone else's save, or this
  // person's own test upload) leaves an edited form alone and refills an untouched one.
  const baseRef = useRef<Draft>(draftOf(question))
  const [base, setBase] = useState<Draft>(baseRef.current)
  const [draft, setDraft] = useState<Draft>(baseRef.current)
  const [conflict, setConflict] = useState(false)
  const dirty = !same(draft, base)

  useEffect(() => {
    const old = baseRef.current
    const fresh = draftOf(question)
    baseRef.current = fresh
    setBase(fresh)
    setDraft(d => (same(d, old) ? fresh : d))
  }, [question])

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft(d => ({ ...d, [key]: value }))

  const time = Number(draft.timeLimitMs)
  const memory = Number(draft.memoryLimitMb)
  const tolerance = Number(draft.floatTolerance)
  const limitsValid =
    !!draft.title.trim()
    && Number.isInteger(time) && time >= 100 && time <= 20000
    && Number.isInteger(memory) && memory >= 16 && memory <= 2048
  const invalid = !limitsValid || (draft.checker === 'FLOAT' && !(tolerance > 0 && tolerance < 1))

  function submit() {
    setConflict(false)
    save.mutate(requestOf(draft, question.version), {
      onSuccess: saved => { const d = draftOf(saved); baseRef.current = d; setBase(d); setDraft(d) },
      onError: (err: any) => { if (err?.response?.status === 409) setConflict(true) },
    })
  }

  const checker = CHECKERS.find(c => c.value === draft.checker)!
  const label = 'flex flex-col gap-1'
  const caption = 'text-xs text-gray-500'

  return (
    <div className="space-y-4">
      <button type="button" onClick={onBack}
        className="inline-flex items-center gap-1 rounded-md px-1.5 py-1 text-xs text-gray-400
                   hover:bg-gray-850 hover:text-gray-200">
        <ArrowLeft size={12} aria-hidden="true" /> All questions
      </button>
      <PageHeader
        eyebrow="Question bank"
        title={question.title}
        meta={
          <span className="flex flex-wrap items-center gap-2 text-xs text-gray-500">
            {question.status === 'READY'
              ? <Pill tone="green">checked</Pill>
              : <Pill tone="amber">draft</Pill>}
            {question.archived && <Pill tone="gray">archived</Pill>}
            <span>version {question.version}</span>
            <span>
              changed {format(new Date(question.updatedAt), 'd MMM yyyy, HH:mm')}
              {question.updatedBy && <> by {question.updatedBy}</>}
            </span>
          </span>
        }
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {!readOnly && (
              <button type="button" className="btn-primary" onClick={submit}
                disabled={!dirty || invalid || save.isPending}>
                {save.isPending ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
                Save
              </button>
            )}
            <button type="button" className="btn-secondary" onClick={downloadPackage}
              disabled={exporting || dirty}
              title={dirty ? 'Save your changes first: the package is built from the saved question'
                : 'The problem package, as it will be sent to the judge'}>
              {exporting ? <Loader2 size={14} className="animate-spin" /> : <Download size={14} aria-hidden="true" />}
              Download package
            </button>
            {(!readOnly || question.archived) && (
              <button type="button" className="btn-secondary"
                disabled={setArchived.isPending || dirty}
                title={dirty ? 'Save or undo your changes first' : undefined}
                onClick={() => setArchived.mutate(!question.archived)}>
                {question.archived
                  ? <><ArchiveRestore size={14} aria-hidden="true" /> Restore</>
                  : <><Archive size={14} aria-hidden="true" /> Archive</>}
              </button>
            )}
          </div>
        }
      />

      {conflict && (
        <div role="alert" className="rounded-lg border border-amber-800/70 bg-amber-950/40 px-4 py-3 text-sm text-amber-200">
          <p>Someone else saved this question after you opened it, so your changes were not saved.</p>
          <div className="mt-2 flex flex-wrap gap-2">
            <button type="button" className="btn-secondary btn-sm"
              onClick={() => { setDraft(base); setConflict(false) }}>
              Discard mine, show theirs
            </button>
            <button type="button" className="btn-secondary btn-sm" disabled={save.isPending}
              onClick={submit}>
              Save mine over theirs
            </button>
          </div>
        </div>
      )}

      {question.status === 'DRAFT' && (
        <p className="text-xs text-gray-500">
          A question becomes ready for an examination once its model solution has been run on
          every test and agrees with each answer. That check comes in a later update; until then
          every question stays a draft.
        </p>
      )}

      <Panel title="Question" description="Its title, limits, and how output is judged">
        <fieldset disabled={readOnly} className="grid gap-3 p-4 sm:grid-cols-2">
          <label className={`${label} sm:col-span-2`}>
            <span className={caption}>Title</span>
            <input className="input" value={draft.title} maxLength={200}
              onChange={e => set('title', e.target.value)} />
          </label>
          <label className={label}>
            <span className={caption}>Time limit (milliseconds, 100–20000)</span>
            <input className="input tabular-nums" inputMode="numeric" value={draft.timeLimitMs}
              onChange={e => set('timeLimitMs', e.target.value.replace(/\D/g, ''))} />
          </label>
          <label className={label}>
            <span className={caption}>Memory limit (MB, 16–2048)</span>
            <input className="input tabular-nums" inputMode="numeric" value={draft.memoryLimitMb}
              onChange={e => set('memoryLimitMb', e.target.value.replace(/\D/g, ''))} />
          </label>
          <label className={label}>
            <span className={caption}>Comparing output with the answer</span>
            <select className="input" value={draft.checker}
              onChange={e => set('checker', e.target.value as QuestionChecker)}>
              {CHECKERS.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
            </select>
            <span className="text-xs text-gray-600">{checker.hint}</span>
          </label>
          {draft.checker === 'FLOAT' && (
            <label className={label}>
              <span className={caption}>Tolerance (above 0, below 1)</span>
              <input className="input font-mono" inputMode="decimal" value={draft.floatTolerance}
                onChange={e => set('floatTolerance', e.target.value.trim())} />
            </label>
          )}
        </fieldset>
      </Panel>

      <StatementPanel question={question} statement={draft.statement} readOnly={readOnly}
        onStatementChange={value => set('statement', value)}
        preview={limitsValid ? {
          title: draft.title.trim(), statement: draft.statement, timeLimitMs: time, memoryLimitMb: memory,
        } : null} />

      <Panel title="Model solution" description="A correct solution, used to check the tests before the question is used">
        <fieldset disabled={readOnly} className="grid gap-3 p-4">
          <label className={`${label} max-w-xs`}>
            <span className={caption}>Language</span>
            <select className="input" value={draft.modelLanguage}
              onChange={e => set('modelLanguage', e.target.value as ModelLanguage)}>
              {LANGUAGES.map(l => <option key={l.value} value={l.value}>{l.label}</option>)}
            </select>
          </label>
          <label className={label}>
            <span className={caption}>Source</span>
            <textarea className="input min-h-56 font-mono text-[13px]" value={draft.modelSource}
              maxLength={200000} spellCheck={false} onChange={e => set('modelSource', e.target.value)} />
          </label>
        </fieldset>
      </Panel>

      {dirty && !readOnly && (
        <p className="text-xs text-amber-300">You have unsaved changes above.</p>
      )}

      <QuestionTests question={question} readOnly={readOnly} />
    </div>
  )
}
