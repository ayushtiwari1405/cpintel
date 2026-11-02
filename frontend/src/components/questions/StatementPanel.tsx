import { useEffect, useRef, useState } from 'react'
import { AlertTriangle, FileText, Loader2, RefreshCw, Upload } from 'lucide-react'
import { questionBankApi } from '@/api/questionBankApi'
import { Panel } from '@/components/admin/AdminUi'
import { formatBytes } from '@/components/questions/QuestionTests'
import { useQuestionActions } from '@/hooks/useQuestionBank'
import type { QuestionDetail, StatementPreviewRequest } from '@/types'

/** Pieces of a statement a setter writes again and again, put in where the cursor is. */
const SNIPPETS: { label: string; text: string; block: boolean }[] = [
  { label: 'Input', text: '## Input\n\nThe first line contains an integer $n$ ($1 \\le n \\le 2 \\cdot 10^5$).\n', block: true },
  { label: 'Output', text: '## Output\n\nPrint one integer.\n', block: true },
  { label: 'Constraints', text: '## Constraints\n\n- $1 \\le n \\le 2 \\cdot 10^5$\n- $1 \\le a_i \\le 10^9$\n', block: true },
  { label: 'Notes', text: '## Notes\n\nIn the first example, …\n', block: true },
  { label: 'Inline maths', text: '$x$', block: false },
  { label: 'Displayed maths', text: '$$\n\\sum_{i=1}^{n} a_i\n$$\n', block: true },
  { label: 'Table', text: '| $n$ | answer |\n|-----|--------|\n| 1 | 2 |\n', block: true },
]

/** The statement as a PDF, held as an object URL for an iframe and released when replaced. */
function usePdfUrl() {
  const [url, setUrl] = useState<string | null>(null)
  const current = useRef<string | null>(null)
  const show = (blob: Blob | null) => {
    if (current.current) URL.revokeObjectURL(current.current)
    current.current = blob && typeof URL.createObjectURL === 'function' ? URL.createObjectURL(blob) : null
    setUrl(current.current)
  }
  useEffect(() => () => { if (current.current) URL.revokeObjectURL(current.current) }, [])
  return [url, show] as const
}

/**
 * Renders the editor's statement as candidates will see it, a moment after typing stops. The
 * question's saved samples are included, so a new sample shows once it is uploaded.
 */
function usePreview(questionId: number, request: StatementPreviewRequest | null, samplesKey: number) {
  const [url, show] = usePdfUrl()
  const [problems, setProblems] = useState<string[]>([])
  const [state, setState] = useState<'idle' | 'rendering' | 'failed'>('idle')
  const [error, setError] = useState<string | null>(null)
  const [nonce, setNonce] = useState(0)
  const key = request ? JSON.stringify(request) : null

  useEffect(() => {
    if (key == null) return
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      setState('rendering')
      questionBankApi.preview(questionId, JSON.parse(key) as StatementPreviewRequest, controller.signal)
        .then(r => { show(r.pdf); setProblems(r.problems); setState('idle'); setError(null) })
        .catch(async (err: any) => {
          if (controller.signal.aborted) return
          let message = 'Could not render the preview.'
          const data = err?.response?.data
          if (data instanceof Blob) {
            try { message = JSON.parse(await data.text())?.message ?? message } catch { /* keep it */ }
          }
          setError(message)
          setState('failed')
        })
    }, 900)
    return () => { window.clearTimeout(timer); controller.abort() }
    // `show` is stable in effect; re-render only when the text, limits or samples change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [questionId, key, samplesKey, nonce])

  return { url, problems, state, error, refresh: () => setNonce(n => n + 1) }
}

export function StatementPanel({ question, statement, onStatementChange, preview, readOnly }: {
  question: QuestionDetail
  statement: string
  onStatementChange: (value: string) => void
  /** The editor's title and limits as typed; null while they are not valid. */
  preview: StatementPreviewRequest | null
  readOnly: boolean
}) {
  return question.statementKind === 'PDF'
    ? <UploadedStatement question={question} readOnly={readOnly} />
    : <WrittenStatement question={question} statement={statement} onStatementChange={onStatementChange}
        preview={preview} readOnly={readOnly} />
}

function WrittenStatement({ question, statement, onStatementChange, preview, readOnly }: {
  question: QuestionDetail
  statement: string
  onStatementChange: (value: string) => void
  preview: StatementPreviewRequest | null
  readOnly: boolean
}) {
  const area = useRef<HTMLTextAreaElement>(null)
  const { url, problems, state, error, refresh } = usePreview(question.questionId, preview, question.version)

  function insert(snippet: (typeof SNIPPETS)[number]) {
    const el = area.current
    const at = el ? el.selectionStart : statement.length
    const end = el ? el.selectionEnd : statement.length
    const before = statement.slice(0, at)
    const lead = snippet.block && before.length > 0 && !before.endsWith('\n\n')
      ? (before.endsWith('\n') ? '\n' : '\n\n') : ''
    const next = before + lead + snippet.text + statement.slice(end)
    onStatementChange(next)
    const caret = at + lead.length + snippet.text.length
    requestAnimationFrame(() => { el?.focus(); el?.setSelectionRange(caret, caret) })
  }

  return (
    <Panel title="Statement"
      description="Markdown, with $…$ for maths. The preview is the PDF candidates will read."
      actions={!readOnly && <PdfUpload question={question} label="Use my own PDF instead" />}>
      <div className="grid gap-4 p-4 xl:grid-cols-2">
        <div className="flex min-w-0 flex-col gap-2">
          {!readOnly && (
            <div className="flex flex-wrap gap-1" role="toolbar" aria-label="Insert into the statement">
              {SNIPPETS.map(s => (
                <button key={s.label} type="button" className="btn-ghost btn-sm" onClick={() => insert(s)}>
                  {s.label}
                </button>
              ))}
            </div>
          )}
          <textarea ref={area} aria-label="Statement" readOnly={readOnly}
            className="input min-h-[28rem] flex-1 font-mono text-[13px] leading-relaxed"
            value={statement} maxLength={100000} spellCheck
            onChange={e => onStatementChange(e.target.value)} />
        </div>
        <div className="flex min-w-0 flex-col gap-2">
          <div className="flex items-center justify-between gap-2 text-xs text-gray-500">
            <span aria-live="polite">
              {state === 'rendering' ? (
                <span className="inline-flex items-center gap-1"><Loader2 size={12} className="animate-spin" /> Rendering…</span>
              ) : preview == null ? 'Fix the title and limits to see the preview.' : 'Preview'}
            </span>
            <button type="button" className="btn-ghost btn-sm" onClick={refresh} disabled={preview == null}>
              <RefreshCw size={12} aria-hidden="true" /> Refresh
            </button>
          </div>
          {problems.length > 0 && (
            <div role="status" className="rounded-lg border border-amber-800/70 bg-amber-950/40 px-3 py-2 text-xs text-amber-200">
              <p className="flex items-center gap-1 font-medium">
                <AlertTriangle size={12} aria-hidden="true" />
                {problems.length === 1 ? 'One formula' : `${problems.length} formulas`} could not be drawn; they show in red.
              </p>
              <ul className="mt-1 list-disc space-y-0.5 pl-5">
                {problems.slice(0, 5).map((p, i) => <li key={i} className="break-words font-mono">{p}</li>)}
              </ul>
            </div>
          )}
          {state === 'failed' && error && <p className="text-xs text-red-300">{error}</p>}
          {url ? (
            <iframe src={url} title="Statement preview"
              className="min-h-[28rem] w-full flex-1 rounded-lg border border-gray-800 bg-gray-950" />
          ) : (
            <div className="flex min-h-[28rem] flex-1 items-center justify-center rounded-lg border border-dashed border-gray-800 text-xs text-gray-600">
              The preview appears here.
            </div>
          )}
        </div>
      </div>
    </Panel>
  )
}

function UploadedStatement({ question, readOnly }: { question: QuestionDetail; readOnly: boolean }) {
  const { removePdf } = useQuestionActions(question.questionId)
  const [url, show] = usePdfUrl()
  const [confirming, setConfirming] = useState(false)
  const info = question.statementPdf

  useEffect(() => {
    let live = true
    questionBankApi.statement(question.questionId)
      .then(r => { if (live) show(r.pdf) })
      .catch(() => { if (live) show(null) })
    return () => { live = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [question.questionId, question.version])

  return (
    <Panel title="Statement" description="Your own PDF is what candidates read. The written statement is kept but not used."
      actions={!readOnly && (
        confirming ? (
          <span className="flex items-center gap-1 text-xs">
            <span className="text-gray-400">Remove the PDF?</span>
            <button type="button" className="btn-danger btn-sm" disabled={removePdf.isPending}
              onClick={() => { setConfirming(false); removePdf.mutate(question.version) }}>
              Remove
            </button>
            <button type="button" className="btn-ghost btn-sm" onClick={() => setConfirming(false)}>Keep</button>
          </span>
        ) : (
          <>
            <PdfUpload question={question} label="Replace PDF" />
            <button type="button" className="btn-secondary btn-sm" onClick={() => setConfirming(true)}>
              Use the written statement
            </button>
          </>
        )
      )}>
      <div className="space-y-2 p-4">
        {info && (
          <p className="flex flex-wrap items-center gap-x-2 text-xs text-gray-400">
            <FileText size={13} aria-hidden="true" />
            <span className="font-mono text-gray-300">{info.fileName}</span>
            <span>{info.pages} page{info.pages === 1 ? '' : 's'} · {formatBytes(info.sizeBytes)}</span>
            {info.uploadedBy && <span>uploaded by {info.uploadedBy}</span>}
          </p>
        )}
        {url ? (
          <iframe src={url} title="Uploaded statement"
            className="h-[70vh] w-full rounded-lg border border-gray-800 bg-gray-950" />
        ) : (
          <p className="text-xs text-gray-600">Loading the PDF…</p>
        )}
      </div>
    </Panel>
  )
}

function PdfUpload({ question, label }: { question: QuestionDetail; label: string }) {
  const { uploadPdf } = useQuestionActions(question.questionId)
  const input = useRef<HTMLInputElement>(null)
  return (
    <>
      <input ref={input} type="file" accept="application/pdf,.pdf" className="hidden"
        aria-label="Choose a statement PDF"
        onChange={e => {
          const file = e.target.files?.[0]
          if (file) uploadPdf.mutate({ file, version: question.version })
          e.target.value = ''
        }} />
      <button type="button" className="btn-secondary btn-sm" disabled={uploadPdf.isPending}
        onClick={() => input.current?.click()}>
        {uploadPdf.isPending ? <Loader2 size={13} className="animate-spin" /> : <Upload size={13} aria-hidden="true" />}
        {label}
      </button>
    </>
  )
}
