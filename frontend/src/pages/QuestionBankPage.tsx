import { useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { formatDistanceToNow } from 'date-fns'
import { clsx } from 'clsx'
import { AlertTriangle, BookOpen, Download, FileArchive, Loader2, Plus } from 'lucide-react'
import { Dialog } from '@/components/common/Dialog'
import { PageHeader } from '@/components/common/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/common/States'
import { Pill } from '@/components/admin/AdminUi'
import { QuestionEditor } from '@/components/questions/QuestionEditor'
import { formatBytes, saveBlob } from '@/components/questions/QuestionTests'
import { questionBankApi } from '@/api/questionBankApi'
import { useToast } from '@/components/common/Toaster'
import { useBankClassrooms, useBankQuestions, useCreateQuestion, useImportQuestion } from '@/hooks/useQuestionBank'
import type { BankClassroom, QuestionImport, QuestionRequest } from '@/types'

const STATEMENT_TEMPLATE = `Describe the task here.

## Input

The first line contains an integer $n$ ($1 \\le n \\le 2 \\cdot 10^5$).

## Output

Print one integer.
`

/**
 * A classroom's question bank, for its admins and TAs: write questions and their tests here,
 * and an admin later publishes them to the judge as an examination.
 */
export default function QuestionBankPage() {
  const [params, setParams] = useSearchParams()
  const { data: rooms, isLoading, isError, error, refetch } = useBankClassrooms()

  const wanted = Number(params.get('classroom')) || null
  const room = rooms?.find(r => r.classroomId === wanted) ?? rooms?.[0] ?? null
  const questionId = Number(params.get('q')) || null

  const header = <PageHeader title="Question bank"
    description="Exam questions and their tests, written here instead of on the judge." />

  if (isLoading) return <>{header}<LoadingState label="Loading your classrooms…" /></>
  if (isError) return <>{header}<ErrorState title="Could not load your classrooms" error={error} onRetry={() => refetch()} /></>
  if (!room) {
    return <>{header}<EmptyState icon={<BookOpen size={20} />} title="No question banks for you">
      A classroom's question bank is open to its admins and its TAs.
    </EmptyState></>
  }

  const open = (id: number | null) => {
    const next = new URLSearchParams(params)
    next.set('classroom', String(room.classroomId))
    if (id == null) next.delete('q')
    else next.set('q', String(id))
    setParams(next)
  }

  if (questionId != null) {
    return <QuestionEditor questionId={questionId} readOnly={!room.active} onBack={() => open(null)} />
  }

  return (
    <div className="space-y-4">
      {header}
      {rooms!.length > 1 && (
        <div className="flex flex-wrap items-center gap-1" role="group" aria-label="Classroom">
          {rooms!.map(r => (
            <button key={r.classroomId} type="button"
              aria-pressed={r.classroomId === room.classroomId}
              onClick={() => setParams({ classroom: String(r.classroomId) })}
              className={clsx('rounded-md px-2.5 py-1 text-xs transition-colors',
                r.classroomId === room.classroomId
                  ? 'bg-accent-600/20 text-accent-300'
                  : 'text-gray-500 hover:bg-gray-800/60 hover:text-gray-300')}>
              {r.name}
            </button>
          ))}
        </div>
      )}
      <QuestionList room={room} onOpen={open} />
    </div>
  )
}

function QuestionList({ room, onOpen }: { room: BankClassroom; onOpen: (id: number) => void }) {
  const [archived, setArchived] = useState(false)
  const [creating, setCreating] = useState(false)
  const [imported, setImported] = useState<QuestionImport | null>(null)
  const importer = useImportQuestion(room.classroomId)
  const chooser = useRef<HTMLInputElement>(null)
  const toast = useToast()

  async function downloadTemplate() {
    try {
      saveBlob(await questionBankApi.packageTemplate(), 'question-package-template.zip')
    } catch {
      toast.push('error', 'Could not download the template')
    }
  }

  function importFile(file: File | undefined) {
    if (!file) return
    importer.mutate(file, {
      // Straight to the question unless the import has something to say first.
      onSuccess: r => (r.warnings.length > 0 ? setImported(r) : onOpen(r.question.questionId)),
    })
  }
  const { data: questions, isLoading, isError, error, refetch } = useBankQuestions(room.classroomId, archived)

  return (
    <section className="rounded-xl border border-gray-800 bg-gray-900">
      <header className="flex flex-wrap items-center justify-between gap-2 border-b border-gray-800 px-4 py-3">
        <div className="min-w-0">
          <h2 className="text-sm font-medium text-gray-100">{room.name}</h2>
          <p className="mt-0.5 text-xs text-gray-500">
            You are {room.role === 'ADMIN' ? 'an admin' : 'a TA'} here
            {!room.active && ' · archived classroom, read only'}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-1.5 text-xs text-gray-400">
            <input type="checkbox" checked={archived} onChange={e => setArchived(e.target.checked)} />
            Show archived
          </label>
          <button type="button" className="btn-ghost btn-sm" onClick={downloadTemplate}
            title="An example package with every file filled in, and a README on the format">
            <Download size={13} aria-hidden="true" /> Template
          </button>
          {room.active && (
            <>
              <input ref={chooser} type="file" accept=".zip,application/zip" className="hidden"
                aria-label="Choose a problem package"
                onChange={e => { importFile(e.target.files?.[0]); e.target.value = '' }} />
              <button type="button" className="btn-secondary btn-sm" disabled={importer.isPending}
                title="A problem package zip, as DOMjudge imports it"
                onClick={() => chooser.current?.click()}>
                {importer.isPending
                  ? <Loader2 size={13} className="animate-spin" />
                  : <FileArchive size={13} aria-hidden="true" />}
                Import package
              </button>
              <button type="button" className="btn-primary btn-sm" onClick={() => setCreating(true)}>
                <Plus size={13} aria-hidden="true" /> New question
              </button>
            </>
          )}
        </div>
      </header>

      {isLoading ? <LoadingState label="Loading questions…" className="py-8" />
        : isError ? <ErrorState title="Could not load the questions" error={error} onRetry={() => refetch()} />
        : !questions || questions.length === 0 ? (
          <EmptyState compact icon={<BookOpen size={18} />}
            title={archived ? 'No archived questions' : 'No questions yet'}>
            {archived ? 'Archived questions appear here.' : 'Write the first one with New question.'}
          </EmptyState>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                  <th className="px-4 py-2 font-medium">Question</th>
                  <th className="px-4 py-2 font-medium">State</th>
                  <th className="px-4 py-2 font-medium">Tests</th>
                  <th className="px-4 py-2 font-medium">Last change</th>
                </tr>
              </thead>
              <tbody>
                {questions.map(q => (
                  <tr key={q.questionId} className="border-b border-gray-800/60 last:border-0 hover:bg-gray-850/50">
                    <td className="px-4 py-2">
                      <button type="button" onClick={() => onOpen(q.questionId)}
                        className="text-left font-medium text-gray-100 hover:text-accent-300">
                        {q.title}
                      </button>
                    </td>
                    <td className="px-4 py-2">
                      {q.status === 'READY' ? <Pill tone="green">checked</Pill> : <Pill tone="amber">draft</Pill>}
                    </td>
                    <td className="px-4 py-2 text-xs text-gray-400 tabular-nums">
                      {q.samples} sample{q.samples === 1 ? '' : 's'} · {q.secretTests} secret · {formatBytes(q.testBytes)}
                    </td>
                    <td className="px-4 py-2 text-xs text-gray-500">
                      {formatDistanceToNow(new Date(q.updatedAt), { addSuffix: true })}
                      {q.updatedBy && <> by {q.updatedBy}</>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

      <Dialog open={imported != null} onClose={() => setImported(null)} size="md"
        title={imported ? `Imported "${imported.question.title}"` : ''}
        description="The question is a draft. The import changed or left out the following:">
        <ul className="space-y-1.5 text-sm text-gray-300">
          {imported?.warnings.map((w, i) => (
            <li key={i} className="flex gap-2">
              <AlertTriangle size={14} className="mt-0.5 shrink-0 text-amber-300" aria-hidden="true" />
              <span>{w}</span>
            </li>
          ))}
        </ul>
        <div className="mt-4 flex justify-end">
          <button type="button" className="btn-primary"
            onClick={() => { const id = imported!.question.questionId; setImported(null); onOpen(id) }}>
            Open the question
          </button>
        </div>
      </Dialog>

      <NewQuestion open={creating} classroomId={room.classroomId}
        onClose={() => setCreating(false)} onCreated={id => { setCreating(false); onOpen(id) }} />
    </section>
  )
}

function NewQuestion({ open, classroomId, onClose, onCreated }: {
  open: boolean
  classroomId: number
  onClose: () => void
  onCreated: (questionId: number) => void
}) {
  const [title, setTitle] = useState('')
  const create = useCreateQuestion(classroomId)

  function submit(e: React.FormEvent) {
    e.preventDefault()
    const body: QuestionRequest = {
      title: title.trim(), statement: STATEMENT_TEMPLATE, timeLimitMs: 1000, memoryLimitMb: 256,
      checker: 'EXACT', floatTolerance: null, modelLanguage: null, modelSource: null,
    }
    create.mutate(body, { onSuccess: q => { setTitle(''); onCreated(q.questionId) } })
  }

  return (
    <Dialog open={open} onClose={onClose} title="New question" size="sm"
      description="Give it a title; you fill in the rest on the next page.">
      <form onSubmit={submit} className="space-y-3">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-gray-500">Title</span>
          <input className="input" value={title} maxLength={200} autoFocus
            onChange={e => setTitle(e.target.value)} />
        </label>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={!title.trim() || create.isPending}>
            {create.isPending && <Loader2 size={14} className="animate-spin" />}
            Create
          </button>
        </div>
      </form>
    </Dialog>
  )
}
