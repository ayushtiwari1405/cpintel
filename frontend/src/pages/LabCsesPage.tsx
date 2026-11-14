import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import DOMPurify from 'dompurify'
import { useQueryClient } from '@tanstack/react-query'
import { format } from 'date-fns'
import {
  AlertTriangle, ArrowLeft, CheckCircle2, Clock, ExternalLink, Loader2, Play, Send,
} from 'lucide-react'
import { CodeEditor } from '@/components/editor/CodeEditor'
import { starterFor } from '@/components/editor/languages'
import { ErrorState, LoadingState, errorMessage } from '@/components/common/States'
import { useToast } from '@/components/common/Toaster'
import { useLabCsesProblem, useMyLabs } from '@/hooks/useLabs'
import { useRunCode, useRunnerLanguages } from '@/hooks/useRunner'
import { labsApi } from '@/api/labsApi'
import { typesetWhenReady } from '@/utils/mathjax'
import type { CsesVerdict, LabCsesProblem, RunResponse } from '@/types'

/** The runner languages CSES takes from here; the server maps them onto CSES's names. */
const CSES_LANGUAGES = ['cpp', 'c', 'java', 'python3']
const LANG_KEY = 'cpintel.cses.lang'

function stored(key: string): string | null {
  try { return localStorage.getItem(key) } catch { return null }
}
function store(key: string, value: string) {
  try { localStorage.setItem(key, value) } catch { /* private window: not remembered */ }
}

/**
 * A lab's CSES task, solved inside CPIntel: the statement, Run on the examples with the same
 * local runner Practice uses (CSES tasks read standard input), and Submit to CSES with the
 * student's connected CSES account. An accepted submission counts for the lab at once.
 */
export default function LabCsesPage() {
  const { problemId: raw } = useParams()
  const problemId = Number(raw)
  const { data: problem, isLoading, isError, error, refetch } = useLabCsesProblem(problemId)

  if (!Number.isFinite(problemId)) {
    return <ErrorState title="Not found" error={new Error('That address does not name a problem.')} />
  }
  if (isError) return <ErrorState title="Could not open this task" error={error} onRetry={() => refetch()} />
  if (isLoading || !problem) return <LoadingState label="Loading the task…" />
  return <Workspace problem={problem} />
}

function Workspace({ problem }: { problem: LabCsesProblem }) {
  const toast = useToast()
  const queryClient = useQueryClient()
  const { data: labs } = useMyLabs()
  const account = labs?.cses ?? null
  const { data: runtimes } = useRunnerLanguages()
  const languages = (runtimes ?? []).filter(r => CSES_LANGUAGES.includes(r.id))
  const [lang, setLang] = useState(() => stored(LANG_KEY) ?? 'cpp')
  const runtime = languages.find(l => l.id === lang) ?? languages[0]
  const editorLanguage = runtime?.editorLanguage ?? 'cpp'

  const draftKey = `cpintel.cses.draft.${problem.taskId}.${lang}`
  const [draft, setDraft] = useState<{ key: string; code: string } | null>(null)
  const code = draft?.key === draftKey ? draft.code : (stored(draftKey) ?? starterFor(editorLanguage))
  const setCode = (next: string) => {
    setDraft({ key: draftKey, code: next })
    store(draftKey, next)
  }

  const run = useRunCode()
  const [runResult, setRunResult] = useState<RunResponse | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [verdict, setVerdict] = useState<CsesVerdict | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  const ready = account?.verified === true

  const doRun = () => {
    if (!runtime || run.isPending) return
    setVerdict(null); setFailure(null)
    run.mutate({
      language: runtime.id,
      source: code,
      tests: problem.examples.map((e, i) => ({ input: e.input, expected: e.output, label: `Example ${i + 1}` })),
    }, {
      onSuccess: setRunResult,
      onError: e => setFailure(errorMessage(e, 'The run did not go through.')),
    })
  }

  const doSubmit = async () => {
    if (!ready || !runtime || submitting) return
    setSubmitting(true); setFailure(null); setRunResult(null)
    try {
      let v = (await labsApi.csesSubmit(problem.problemId, runtime.id, code)).data
      setVerdict(v)
      const deadline = Date.now() + 120_000
      while (v.pending && Date.now() < deadline) {
        await new Promise(r => setTimeout(r, 1500))
        v = (await labsApi.csesVerdict(problem.problemId, v.submissionId)).data
        setVerdict(v)
      }
      if (!v.pending && v.result === 'ACCEPTED') {
        queryClient.invalidateQueries({ queryKey: ['labs'] })
        toast.push('success', 'Accepted, and counted for the lab.')
      }
    } catch (e) {
      setFailure(errorMessage(e, 'The submission did not go through.'))
    } finally {
      setSubmitting(false)
    }
  }

  const html = useMemo(() => DOMPurify.sanitize(problem.contentHtml, { USE_PROFILES: { html: true } }),
    [problem.contentHtml])
  // Written by the effect, not dangerouslySetInnerHTML: React re-applies that on every
  // re-render, which would put the raw $$$ back over MathJax's output (see StatementView).
  const statementRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = statementRef.current
    if (!el) return
    let cancelled = false
    el.innerHTML = html
    typesetWhenReady(el, () => cancelled)
    return () => { cancelled = true }
  }, [html])

  return (
    <div className="space-y-4">
      <Link to="/labs" className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-300">
        <ArrowLeft size={12} /> {problem.labTitle}
      </Link>

      <header className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold text-gray-50">{problem.title}</h1>
          <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-gray-400">
            <span>CSES {problem.taskId}</span>
            {problem.timeLimit && <span>Time {problem.timeLimit}</span>}
            {problem.memoryLimit && <span>Memory {problem.memoryLimit}</span>}
            <span>{Number(problem.marks)} {Number(problem.marks) === 1 ? 'mark' : 'marks'}</span>
            <span>Due {format(new Date(problem.dueAt), 'EEE d MMM, HH:mm')}</span>
            {problem.status === 'SOLVED' && (
              <span className="inline-flex items-center gap-1 text-green-300"><CheckCircle2 size={12} /> Solved</span>
            )}
            {problem.status === 'LATE' && (
              <span className="inline-flex items-center gap-1 text-amber-300"><Clock size={12} /> Solved late</span>
            )}
          </p>
        </div>
        <a href={problem.url} target="_blank" rel="noreferrer" className="btn-ghost btn-sm shrink-0">
          Open on CSES <ExternalLink size={12} aria-hidden="true" />
        </a>
      </header>

      {!ready ? (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2 rounded-xl border border-amber-900/60 bg-amber-950/20 p-3 text-sm text-amber-200">
          <AlertTriangle size={16} className="shrink-0" aria-hidden="true" />
          <span className="min-w-0 flex-1">
            {account
              ? 'CSES no longer accepts your connection. Reconnect CSES to submit from here.'
              : 'Connect your CSES account to submit from here. You can still run on the examples.'}
          </span>
          <Link to="/platforms" className="btn-secondary btn-sm">Platforms</Link>
        </div>
      ) : (
        <div className="flex items-center gap-2 rounded-xl border border-gray-800 bg-gray-900 p-3 text-sm text-gray-400">
          <CheckCircle2 size={14} className="text-green-400" aria-hidden="true" />
          Submitting to CSES as <span className="font-mono text-gray-200">{account?.handle}</span>
        </div>
      )}

      <div className="grid gap-4 xl:grid-cols-2">
        <section aria-label="Statement" className="rounded-xl border border-gray-800 bg-gray-900 p-4 xl:max-h-[calc(100vh-12rem)] xl:overflow-y-auto">
          <div ref={statementRef} className="cf-statement text-sm leading-relaxed text-gray-300 [&_h1]:mt-4 [&_h1]:text-sm [&_h1]:font-semibold [&_h1]:text-gray-100" />
        </section>

        <section aria-label="Solution" className="flex min-w-0 flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <label className="sr-only" htmlFor="cses-lang">Language</label>
            <select id="cses-lang" value={runtime?.id ?? lang}
              onChange={e => { setLang(e.target.value); store(LANG_KEY, e.target.value) }}
              className="rounded-lg border border-gray-800 bg-gray-900 px-2.5 py-1.5 text-sm text-gray-200">
              {languages.map(l => <option key={l.id} value={l.id}>{l.displayName}</option>)}
            </select>
            <div className="ml-auto flex gap-2">
              <button type="button" className="btn-secondary btn-sm" disabled={!runtime || run.isPending || submitting}
                onClick={doRun} title="Run on the examples (Ctrl+Enter)">
                {run.isPending ? <Loader2 size={13} className="animate-spin" aria-hidden="true" /> : <Play size={13} aria-hidden="true" />}
                Run
              </button>
              <button type="button" className="btn-primary btn-sm" disabled={!ready || !runtime || submitting}
                onClick={doSubmit} title={ready ? 'Submit to CSES (Ctrl+S)' : 'Connect CSES first'}>
                {submitting ? <Loader2 size={13} className="animate-spin" aria-hidden="true" /> : <Send size={13} aria-hidden="true" />}
                Submit
              </button>
            </div>
          </div>

          <div className="h-[420px] overflow-hidden rounded-xl border border-gray-800">
            <CodeEditor value={code} onChange={setCode} language={editorLanguage} onRun={doRun} onSubmit={doSubmit} />
          </div>

          <div aria-live="polite" className="min-w-0 space-y-2">
            {run.isPending && (
              <p className="flex items-center gap-2 text-sm text-gray-400">
                <Loader2 size={14} className="animate-spin" /> {run.progress ?? 'Running on the examples…'}
              </p>
            )}
            {failure && <p role="alert" className="text-sm text-red-400">{failure}</p>}
            {runResult && !run.isPending && <RunView r={runResult} />}
            {verdict && <VerdictView v={verdict} />}
            {!runResult && !verdict && !failure && !run.isPending && (
              <p className="text-xs text-gray-600">Run checks your code on the examples here. Submit sends it to CSES.</p>
            )}
          </div>
        </section>
      </div>
    </div>
  )
}

function RunView({ r }: { r: RunResponse }) {
  const pre = 'mt-1 max-h-40 overflow-auto whitespace-pre-wrap rounded-md border border-gray-800 bg-gray-950 p-2 font-mono text-xs text-gray-300'
  if (r.error) return <p className="text-sm text-red-400">{r.error}</p>
  if (!r.compiled) {
    return <div><p className="text-sm font-semibold text-red-300">Compilation error</p><pre className={pre}>{r.compileOutput}</pre></div>
  }
  return (
    <ul className="space-y-1 text-sm">
      {r.results.map(t => (
        <li key={t.label}>
          <span className={t.verdict === 'OK' ? 'text-green-300' : 'text-red-300'}>
            {t.label}: {t.verdict === 'OK' ? 'passed' : t.verdict.replace(/_/g, ' ').toLowerCase()}
          </span>
          {t.verdict !== 'OK' && (
            <pre className={pre}>{`Got:\n${t.actual}${t.expected != null ? `\nExpected:\n${t.expected}` : ''}${t.stderr ? `\n${t.stderr}` : ''}`}</pre>
          )}
        </li>
      ))}
    </ul>
  )
}

function VerdictView({ v }: { v: CsesVerdict }) {
  const pre = 'mt-1 max-h-40 overflow-auto whitespace-pre-wrap rounded-md border border-gray-800 bg-gray-950 p-2 font-mono text-xs text-gray-300'
  if (v.pending) {
    return (
      <p className="flex items-center gap-2 text-sm text-gray-400">
        <Loader2 size={14} className="animate-spin" /> Judging on CSES
        {v.totalTests ? ` (${v.finishedTests ?? 0} / ${v.totalTests} tests)` : '…'}
      </p>
    )
  }
  const accepted = v.result === 'ACCEPTED'
  const failed = v.tests.find(t => t.verdict !== 'ACCEPTED')
  return (
    <div className="text-sm">
      <p className={`font-semibold ${accepted ? 'text-green-300' : 'text-red-300'}`}>
        {(v.result ?? v.status ?? 'Done').replace(/_/g, ' ').toLowerCase().replace(/^./, c => c.toUpperCase())}
        {v.tests.length > 0 && (
          <span className="ml-2 font-normal text-gray-400">
            {v.tests.filter(t => t.verdict === 'ACCEPTED').length} / {v.tests.length} tests
          </span>
        )}
      </p>
      {!accepted && failed && (
        <p className="mt-1 text-xs text-gray-400">First failing test: #{failed.number} ({failed.verdict.toLowerCase()})</p>
      )}
      {v.compiler && <pre className={pre}>{v.compiler}</pre>}
    </div>
  )
}
