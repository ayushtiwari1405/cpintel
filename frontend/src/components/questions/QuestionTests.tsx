import { useRef, useState } from 'react'
import { ArrowDown, ArrowUp, Download, Loader2, Trash2, Upload } from 'lucide-react'
import { questionBankApi } from '@/api/questionBankApi'
import { useToast } from '@/components/common/Toaster'
import { Panel, Pill } from '@/components/admin/AdminUi'
import { useQuestionActions } from '@/hooks/useQuestionBank'
import type { QuestionDetail, QuestionTestInfo } from '@/types'

/** Requests stay under the server's 8 MB request limit, with room for the form fields. */
const REQUEST_BUDGET = 7 * 1024 * 1024

export function formatBytes(n: number) {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(n < 10 * 1024 ? 1 : 0)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

function stemOf(name: string) {
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(0, dot) : name
}

/**
 * Splits chosen files into uploads that each fit one request, keeping an input with its
 * answer. Files over the per-file limit are reported instead of sent.
 */
export function batches(files: File[], maxFileBytes: number): { batches: File[][]; tooBig: File[] } {
  const tooBig = files.filter(f => f.size > maxFileBytes)
  const byStem = new Map<string, File[]>()
  for (const f of files) {
    if (f.size > maxFileBytes) continue
    const key = stemOf(f.name)
    byStem.set(key, [...(byStem.get(key) ?? []), f])
  }
  const out: File[][] = []
  let current: File[] = []
  let size = 0
  for (const group of byStem.values()) {
    const groupSize = group.reduce((s, f) => s + f.size, 0)
    if (current.length > 0 && size + groupSize > REQUEST_BUDGET) {
      out.push(current)
      current = []
      size = 0
    }
    current.push(...group)
    size += groupSize
  }
  if (current.length > 0) out.push(current)
  return { batches: out, tooBig }
}

/** A question's samples and secret tests: upload, order, download, remove. */
export function QuestionTests({ question, readOnly }: { question: QuestionDetail; readOnly: boolean }) {
  const samples = question.tests.filter(t => t.sample)
  const secret = question.tests.filter(t => !t.sample)
  const total = question.tests.reduce((s, t) => s + t.inputBytes + (t.answerBytes ?? 0), 0)
  const missing = question.tests.filter(t => t.answerBytes == null).length
  return (
    <div className="space-y-4">
      <p className="text-xs text-gray-500">
        Upload each test as <code className="kbd">name.in</code> with its answer
        as <code className="kbd">name.ans</code> (or <code className="kbd">.out</code>); several at
        once is fine, and they are added in the order of their names. {question.tests.length} of
        {' '}{question.limits.maxTests} tests, {formatBytes(total)} of
        {' '}{formatBytes(question.limits.maxQuestionBytes)}. Each file at most
        {' '}{formatBytes(question.limits.maxFileBytes)}; samples at most
        {' '}{formatBytes(question.limits.maxSampleBytes)}.
        {missing > 0 && <> <span className="text-amber-300">{missing} without an answer.</span></>}
      </p>
      <TestGroup question={question} sample tests={samples} readOnly={readOnly}
        title="Samples" description="Shown to candidates in the statement" />
      <TestGroup question={question} sample={false} tests={secret} readOnly={readOnly}
        title="Secret tests" description="Only the judge sees these" />
    </div>
  )
}

function TestGroup({ question, sample, tests, readOnly, title, description }: {
  question: QuestionDetail
  sample: boolean
  tests: QuestionTestInfo[]
  readOnly: boolean
  title: string
  description: string
}) {
  const { addTests, deleteTest, reorder } = useQuestionActions(question.questionId)
  const toast = useToast()
  const input = useRef<HTMLInputElement>(null)
  const [progress, setProgress] = useState<string | null>(null)
  const [confirming, setConfirming] = useState<number | null>(null)
  const busy = progress != null || deleteTest.isPending || reorder.isPending
  const noun = sample ? 'sample' : 'secret test'

  async function upload(chosen: FileList | null) {
    if (!chosen || chosen.length === 0) return
    const { batches: parts, tooBig } = batches(Array.from(chosen), question.limits.maxFileBytes)
    if (tooBig.length > 0) {
      toast.push('error', `${tooBig.map(f => f.name).join(', ')} ${tooBig.length === 1 ? 'is' : 'are'} `
        + `over ${formatBytes(question.limits.maxFileBytes)} and ${tooBig.length === 1 ? 'was' : 'were'} not uploaded.`)
    }
    let version = question.version
    try {
      for (let i = 0; i < parts.length; i++) {
        setProgress(parts.length > 1 ? `Uploading ${i + 1} of ${parts.length}…` : 'Uploading…')
        const updated = await addTests.mutateAsync({ files: parts[i], sample, version })
        version = updated.version
      }
    } catch {
      // The hook has already said why.
    } finally {
      setProgress(null)
      if (input.current) input.current.value = ''
    }
  }

  function move(index: number, by: -1 | 1) {
    const ids = tests.map(t => t.testId)
    const [id] = ids.splice(index, 1)
    ids.splice(index + by, 0, id)
    reorder.mutate({ sample, testIds: ids, version: question.version })
  }

  async function save(t: QuestionTestInfo, part: 'input' | 'answer') {
    try {
      const blob = await questionBankApi.download(question.questionId, t.testId, part)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `${sample ? 'sample' : 'secret'}-${t.ordinal}.${part === 'input' ? 'in' : 'ans'}`
      link.click()
      URL.revokeObjectURL(url)
    } catch {
      toast.push('error', 'Could not download that file')
    }
  }

  return (
    <Panel title={`${title} (${tests.length})`} description={description}
      actions={!readOnly && (
        <>
          {progress && <span className="flex items-center gap-1 text-xs text-gray-400">
            <Loader2 size={12} className="animate-spin" /> {progress}
          </span>}
          <input ref={input} type="file" multiple accept=".in,.ans,.out" className="hidden"
            aria-label={`Choose ${title.toLowerCase()} to upload`}
            onChange={e => upload(e.target.files)} />
          <button type="button" className="btn-secondary btn-sm" disabled={busy}
            onClick={() => input.current?.click()}>
            <Upload size={13} aria-hidden="true" /> Add {sample ? 'samples' : 'tests'}
          </button>
        </>
      )}>
      {tests.length === 0 ? (
        <p className="px-4 py-3 text-xs text-gray-600">
          {sample ? 'No samples yet.' : 'No secret tests yet.'}
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="px-4 py-2 font-medium">#</th>
                <th className="px-4 py-2 font-medium">Uploaded as</th>
                <th className="px-4 py-2 font-medium">Input</th>
                <th className="px-4 py-2 font-medium">Answer</th>
                <th className="px-4 py-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {tests.map((t, i) => (
                <tr key={t.testId} className="border-b border-gray-800/60 last:border-0">
                  <td className="px-4 py-2 font-mono text-xs text-gray-400 tabular-nums">{t.ordinal}</td>
                  <td className="max-w-[16rem] truncate px-4 py-2 font-mono text-xs text-gray-300"
                    title={t.name}>{t.name}</td>
                  <td className="px-4 py-2 text-xs text-gray-400 tabular-nums">
                    <button type="button" className="inline-flex items-center gap-1 hover:text-gray-200"
                      onClick={() => save(t, 'input')} aria-label={`Download input of ${noun} ${t.ordinal}`}>
                      {formatBytes(t.inputBytes)} <Download size={11} aria-hidden="true" />
                    </button>
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-400 tabular-nums">
                    {t.answerBytes == null ? <Pill tone="amber">missing</Pill> : (
                      <button type="button" className="inline-flex items-center gap-1 hover:text-gray-200"
                        onClick={() => save(t, 'answer')} aria-label={`Download answer of ${noun} ${t.ordinal}`}>
                        {formatBytes(t.answerBytes)} <Download size={11} aria-hidden="true" />
                      </button>
                    )}
                  </td>
                  <td className="px-4 py-2">
                    {!readOnly && (confirming === t.testId ? (
                      <span className="flex items-center justify-end gap-1 text-xs">
                        <span className="text-gray-400">Remove?</span>
                        <button type="button" className="btn-danger btn-sm" disabled={busy}
                          onClick={() => { setConfirming(null); deleteTest.mutate({ testId: t.testId, version: question.version }) }}>
                          Remove
                        </button>
                        <button type="button" className="btn-ghost btn-sm" onClick={() => setConfirming(null)}>
                          Keep
                        </button>
                      </span>
                    ) : (
                      <span className="flex items-center justify-end gap-0.5">
                        <button type="button" className="btn-ghost btn-sm" disabled={busy || i === 0}
                          onClick={() => move(i, -1)} aria-label={`Move ${noun} ${t.ordinal} up`}>
                          <ArrowUp size={13} aria-hidden="true" />
                        </button>
                        <button type="button" className="btn-ghost btn-sm" disabled={busy || i === tests.length - 1}
                          onClick={() => move(i, 1)} aria-label={`Move ${noun} ${t.ordinal} down`}>
                          <ArrowDown size={13} aria-hidden="true" />
                        </button>
                        <button type="button" className="btn-ghost btn-sm" disabled={busy}
                          onClick={() => setConfirming(t.testId)} aria-label={`Remove ${noun} ${t.ordinal}`}>
                          <Trash2 size={13} aria-hidden="true" />
                        </button>
                      </span>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  )
}
