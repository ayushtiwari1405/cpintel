import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import DOMPurify from 'dompurify'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { format } from 'date-fns'
import {
  AlertTriangle, ArrowLeft, CheckCircle2, Clock, ExternalLink, Loader2, LogIn, Play, Send,
} from 'lucide-react'
import { CodeEditor } from '@/components/editor/CodeEditor'
import { ErrorState, LoadingState, errorMessage } from '@/components/common/States'
import { useToast } from '@/components/common/Toaster'
import { useLabLeetCodeProblem, useMyLabs } from '@/hooks/useLabs'
import { labsApi } from '@/api/labsApi'
import {
  extensionTooOld, lcRelay, lcRun, lcSignIn, lcStatus, lcSubmit, monacoLanguage,
  type LcResult, type LcStatus,
} from '@/api/lcBrowser'
import type { LabLeetCodeProblem } from '@/types'

const LANG_KEY = 'cpintel.lc.lang'

function stored(key: string): string | null {
  try { return localStorage.getItem(key) } catch { return null }
}
function store(key: string, value: string) {
  try { localStorage.setItem(key, value) } catch { /* private window: not remembered */ }
}

/**
 * A lab's LeetCode problem, solved inside CPIntel: the statement, an editor with LeetCode's
 * starter code, Run on test input and Submit. Both go to LeetCode through the student's own
 * LeetCode sign-in (desktop app or CPIntel extension), so the solve lands on their LeetCode
 * account, where the lab check picks it up.
 */
export default function LabProblemPage() {
  const { problemId: raw } = useParams()
  const problemId = Number(raw)
  const { data: problem, isLoading, isError, error, refetch } = useLabLeetCodeProblem(problemId)

  if (!Number.isFinite(problemId)) {
    return <ErrorState title="Not found" error={new Error('That address does not name a problem.')} />
  }
  if (isError) return <ErrorState title="Could not open this problem" error={error} onRetry={() => refetch()} />
  if (isLoading || !problem) return <LoadingState label="Loading the problem…" />
  return <Workspace problem={problem} />
}

function Workspace({ problem }: { problem: LabLeetCodeProblem }) {
  const toast = useToast()
  const queryClient = useQueryClient()
  const languages = problem.snippets
  const [lang, setLang] = useState(() => {
    const remembered = stored(LANG_KEY)
    return languages.find(l => l.langSlug === remembered)?.langSlug
      ?? languages.find(l => l.langSlug === 'cpp')?.langSlug ?? languages[0]?.langSlug ?? 'cpp'
  })
  const draftKey = `cpintel.lc.draft.${problem.slug}.${lang}`
  const starter = languages.find(l => l.langSlug === lang)?.code ?? ''
  // The draft belongs to one problem and language; switching language shows that language's.
  const [draft, setDraft] = useState<{ key: string; code: string } | null>(null)
  const code = draft?.key === draftKey ? draft.code : (stored(draftKey) ?? starter)
  const setCode = (next: string) => {
    setDraft({ key: draftKey, code: next })
    store(draftKey, next)
  }

  const [input, setInput] = useState(problem.exampleTestcases.join('\n'))
  const [busy, setBusy] = useState<'run' | 'submit' | null>(null)
  const [result, setResult] = useState<{ kind: 'run' | 'submit'; r: LcResult } | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  const connection = useLeetCodeConnection()
  const ready = connection.status?.signedIn === true

  const solution = { slug: problem.slug, questionId: problem.questionId, lang, code }

  const run = async () => {
    if (!ready || busy) return
    setBusy('run'); setFailure(null)
    try {
      setResult({ kind: 'run', r: await lcRun(solution, input) })
    } catch (e) {
      setFailure(errorMessage(e, 'The run did not go through.'))
    } finally {
      setBusy(null)
    }
  }

  const submit = async () => {
    if (!ready || busy) return
    setBusy('submit'); setFailure(null)
    try {
      const r = await lcSubmit(solution)
      setResult({ kind: 'submit', r })
      if (r.status_msg === 'Accepted') {
        // Ask for this student's labs to be checked now rather than at the next schedule.
        try {
          const found = (await labsApi.check()).data
          queryClient.invalidateQueries({ queryKey: ['labs'] })
          toast.push('success', found.recorded > 0 ? 'Accepted, and counted for the lab.' : 'Accepted.')
        } catch {
          toast.push('success', 'Accepted. It will count for the lab within ten minutes.')
        }
      }
    } catch (e) {
      setFailure(errorMessage(e, 'The submission did not go through.'))
    } finally {
      setBusy(null)
    }
  }

  const html = useMemo(() => problem.contentHtml
    ? DOMPurify.sanitize(problem.contentHtml, { USE_PROFILES: { html: true } }) : null, [problem.contentHtml])

  return (
    <div className="space-y-4">
      <Link to="/labs" className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-300">
        <ArrowLeft size={12} /> {problem.labTitle}
      </Link>

      <header className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold text-gray-50">{problem.title}</h1>
          <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-gray-400">
            <span>LeetCode{problem.difficulty && <> · {problem.difficulty}</>}</span>
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
          Open on LeetCode <ExternalLink size={12} aria-hidden="true" />
        </a>
      </header>

      <ConnectionBar connection={connection} />

      <div className="grid gap-4 xl:grid-cols-2">
        <section aria-label="Statement" className="rounded-xl border border-gray-800 bg-gray-900 p-4 xl:max-h-[calc(100vh-12rem)] xl:overflow-y-auto">
          {html
            ? <div className="cf-statement text-sm leading-relaxed text-gray-300" dangerouslySetInnerHTML={{ __html: html }} />
            : (
              <p className="text-sm text-gray-400">
                {problem.paidOnly
                  ? 'This is a LeetCode Premium problem, so its statement is only shown on LeetCode.'
                  : 'LeetCode did not send a statement for this problem.'}{' '}
                <a href={problem.url} target="_blank" rel="noreferrer" className="text-accent-300 hover:underline">Read it there</a>.
              </p>
            )}
        </section>

        <section aria-label="Solution" className="flex min-w-0 flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <label className="sr-only" htmlFor="lc-lang">Language</label>
            <select id="lc-lang" value={lang}
              onChange={e => { setLang(e.target.value); store(LANG_KEY, e.target.value) }}
              className="rounded-lg border border-gray-800 bg-gray-900 px-2.5 py-1.5 text-sm text-gray-200">
              {languages.map(l => <option key={l.langSlug} value={l.langSlug}>{l.lang}</option>)}
            </select>
            <button type="button" className="btn-ghost btn-sm"
              onClick={() => { if (confirm('Replace your code with LeetCode\'s starter code?')) setCode(starter) }}>
              Reset
            </button>
            <div className="ml-auto flex gap-2">
              <button type="button" className="btn-secondary btn-sm" disabled={!ready || busy !== null} onClick={run}
                title={ready ? 'Run on the test input (Ctrl+Enter)' : 'Connect LeetCode first'}>
                {busy === 'run' ? <Loader2 size={13} className="animate-spin" aria-hidden="true" /> : <Play size={13} aria-hidden="true" />}
                Run
              </button>
              <button type="button" className="btn-primary btn-sm" disabled={!ready || busy !== null} onClick={submit}
                title={ready ? 'Submit to LeetCode (Ctrl+S)' : 'Connect LeetCode first'}>
                {busy === 'submit' ? <Loader2 size={13} className="animate-spin" aria-hidden="true" /> : <Send size={13} aria-hidden="true" />}
                Submit
              </button>
            </div>
          </div>

          <div className="h-[420px] overflow-hidden rounded-xl border border-gray-800">
            <CodeEditor value={code} onChange={setCode} language={monacoLanguage(lang)} onRun={run} onSubmit={submit} />
          </div>

          <div className="grid gap-3 lg:grid-cols-2">
            <label className="block">
              <span className="mb-1 block text-xs text-gray-400">Test input (one argument per line)</span>
              <textarea value={input} onChange={e => setInput(e.target.value)} rows={6} spellCheck={false}
                className="w-full rounded-lg border border-gray-800 bg-gray-950 px-3 py-2 font-mono text-xs text-gray-200" />
            </label>
            <div aria-live="polite" className="min-w-0">
              <span className="mb-1 block text-xs text-gray-400">Result</span>
              {busy && (
                <p className="flex items-center gap-2 text-sm text-gray-400">
                  <Loader2 size={14} className="animate-spin" /> {busy === 'run' ? 'Running on LeetCode…' : 'Judging on LeetCode…'}
                </p>
              )}
              {!busy && failure && <p role="alert" className="text-sm text-red-400">{failure}</p>}
              {!busy && !failure && result && <ResultView kind={result.kind} r={result.r} />}
              {!busy && !failure && !result && <p className="text-xs text-gray-600">Run or submit to see LeetCode's verdict.</p>}
            </div>
          </div>
        </section>
      </div>
    </div>
  )
}

interface Connection {
  relay: ReturnType<typeof lcRelay>
  status: LcStatus | undefined
  loading: boolean
  error: unknown
  signIn: () => void
  signingIn: boolean
  linked: { handle: string; verified: boolean } | null
}

function useLeetCodeConnection(): Connection {
  const relay = lcRelay()
  const queryClient = useQueryClient()
  const { data: labs } = useMyLabs()
  const status = useQuery({
    queryKey: ['leetcode', 'status', relay],
    queryFn: lcStatus,
    enabled: relay !== null,
    retry: false,
    staleTime: 60_000,
  })
  const [signingIn, setSigningIn] = useState(false)
  const toast = useToast()
  const signIn = () => {
    setSigningIn(true)
    lcSignIn()
      .then(s => queryClient.setQueryData(['leetcode', 'status', relay], s))
      .catch(e => toast.push('error', errorMessage(e, 'Could not sign in to LeetCode')))
      .finally(() => setSigningIn(false))
  }
  return {
    relay, status: status.data, loading: status.isLoading, error: status.error,
    signIn, signingIn, linked: labs?.leetcode ?? null,
  }
}

function ConnectionBar({ connection: c }: { connection: Connection }) {
  const box = 'flex flex-wrap items-center gap-x-3 gap-y-2 rounded-xl border p-3 text-sm'
  if (!c.relay) {
    return (
      <div className={`${box} border-amber-900/60 bg-amber-950/20 text-amber-200`}>
        <AlertTriangle size={16} className="shrink-0" aria-hidden="true" />
        <span className="min-w-0 flex-1">
          {extensionTooOld()
            ? 'Your CPIntel extension is too old for LeetCode. Install the new version to run and submit here.'
            : 'To run and submit here, use the CPIntel desktop app or install the CPIntel browser extension. You can still solve it on LeetCode; it counts either way.'}
        </span>
        <Link to="/extension" className="btn-secondary btn-sm">Get the extension</Link>
      </div>
    )
  }
  if (c.loading) {
    return <div className={`${box} border-gray-800 text-gray-400`}><Loader2 size={14} className="animate-spin" /> Checking your LeetCode sign-in…</div>
  }
  if (!c.status?.signedIn) {
    return (
      <div className={`${box} border-gray-800 bg-gray-900 text-gray-300`}>
        <span className="min-w-0 flex-1">
          {c.error ? errorMessage(c.error, 'Could not reach LeetCode.') + ' ' : ''}
          Sign in to LeetCode {c.relay === 'desktop' ? 'in the app' : 'in this browser'} to run and submit from here.
        </span>
        <button type="button" className="btn-primary btn-sm" onClick={c.signIn} disabled={c.signingIn}>
          {c.signingIn ? <Loader2 size={13} className="animate-spin" aria-hidden="true" /> : <LogIn size={13} aria-hidden="true" />}
          Sign in to LeetCode
        </button>
      </div>
    )
  }
  const signedAs = c.status.username ?? ''
  const mismatch = c.linked && c.linked.handle.toLowerCase() !== signedAs.toLowerCase()
  if (!c.linked || mismatch || !c.linked.verified) {
    return (
      <div className={`${box} border-amber-900/60 bg-amber-950/20 text-amber-200`}>
        <AlertTriangle size={16} className="shrink-0" aria-hidden="true" />
        <span className="min-w-0 flex-1">
          Signed in to LeetCode as <b className="font-mono">{signedAs}</b>.{' '}
          {!c.linked
            ? 'Link and verify this handle on the Platforms page, or your solves will not count.'
            : mismatch
              ? <>Your linked handle is <b className="font-mono">{c.linked.handle}</b>, so solves from this account will not count.</>
              : 'Verify this handle on the Platforms page, or your solves will not count.'}
        </span>
        <Link to="/platforms" className="btn-secondary btn-sm">Platforms</Link>
      </div>
    )
  }
  return (
    <div className={`${box} border-gray-800 bg-gray-900 text-gray-400`}>
      <CheckCircle2 size={14} className="text-green-400" aria-hidden="true" />
      Submitting to LeetCode as <span className="font-mono text-gray-200">{signedAs}</span>
      {c.relay === 'desktop' ? ' (desktop app)' : ' (this browser)'}
    </div>
  )
}

function ResultView({ kind, r }: { kind: 'run' | 'submit'; r: LcResult }) {
  const msg = r.status_msg ?? (r.state === 'FAILURE' ? 'Failed' : 'Done')
  const good = kind === 'submit' ? msg === 'Accepted' : r.run_success && r.correct_answer !== false && msg === 'Accepted'
  const tone = good ? 'text-green-300' : 'text-red-300'
  const pre = 'mt-1 max-h-40 overflow-auto whitespace-pre-wrap rounded-md border border-gray-800 bg-gray-950 p-2 font-mono text-xs text-gray-300'
  const error = r.full_compile_error || r.compile_error || r.full_runtime_error || r.runtime_error

  return (
    <div className="space-y-2 text-sm">
      <p className={`font-semibold ${tone}`}>
        {msg}
        {kind === 'submit' && r.total_testcases != null && (
          <span className="ml-2 font-normal text-gray-400">{r.total_correct ?? 0} / {r.total_testcases} tests</span>
        )}
      </p>
      {kind === 'submit' && msg === 'Accepted' && (
        <p className="text-xs text-gray-400">Runtime {r.status_runtime} · Memory {r.status_memory}</p>
      )}
      {error && <pre className={pre}>{error}</pre>}
      {!error && kind === 'run' && r.code_answer && (
        <div className="space-y-1">
          {r.code_answer.map((out, i) => {
            const expected = r.expected_code_answer?.[i]
            const same = expected === undefined || expected === out
            return (
              <div key={i} className="text-xs">
                <span className="text-gray-500">Case {i + 1}: </span>
                <span className={same ? 'text-gray-200' : 'text-red-300'}>{out}</span>
                {!same && <span className="text-gray-500"> (expected {expected})</span>}
              </div>
            )
          })}
        </div>
      )}
      {!error && kind === 'submit' && msg !== 'Accepted' && r.last_testcase && (
        <div className="text-xs">
          <span className="text-gray-500">Failing input</span>
          <pre className={pre}>{r.last_testcase}</pre>
          {r.expected_output !== undefined && <p className="mt-1 text-gray-400">Expected <span className="font-mono">{String(r.expected_output)}</span>, got <span className="font-mono">{String(r.code_output)}</span></p>}
        </div>
      )}
    </div>
  )
}
