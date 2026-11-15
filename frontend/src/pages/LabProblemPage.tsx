import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import DOMPurify from 'dompurify'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { clsx } from 'clsx'
import { AlertTriangle, Gauge, Loader2, LogIn, Puzzle, RotateCcw } from 'lucide-react'

import { LabMetaChips, LabWorkspace, WorkspaceNotice } from '@/components/labs/LabWorkspace'
import { Section, StatementHeader } from '@/components/practice/StatementView'
import { SolvePane } from '@/components/workspace/SolvePane'
import { ErrorState, LoadingState, errorMessage } from '@/components/common/States'
import { useToast } from '@/components/common/Toaster'
import { useLabLeetCodeProblem, useMyLabs } from '@/hooks/useLabs'
import { labsApi } from '@/api/labsApi'
import {
  extensionTooOld, lcRelay, lcRun, lcSignIn, lcStatus, lcSubmit, monacoLanguage,
  type LcResult, type LcStatus,
} from '@/api/lcBrowser'
import { openExternal } from '@/utils/desktopBridge'
import type { LabLeetCodeProblem, RunResponse, RunTestCase, VerdictResponse } from '@/types'

const LANG_KEY = 'cpintel.lc.lang'

function stored(key: string): string | null {
  try { return localStorage.getItem(key) } catch { return null }
}
function store(key: string, value: string) {
  try { localStorage.setItem(key, value) } catch { /* private window: not remembered */ }
}

/**
 * A lab's LeetCode problem, solved inside CPIntel: the statement, an editor with LeetCode's
 * starter code, Run on test cases and Submit. Both go to LeetCode through the student's own
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

const LC_VERDICTS: Record<string, string> = {
  'Accepted': 'OK',
  'Wrong Answer': 'WRONG_ANSWER',
  'Time Limit Exceeded': 'TIME_LIMIT_EXCEEDED',
  'Memory Limit Exceeded': 'MEMORY_LIMIT_EXCEEDED',
  'Runtime Error': 'RUNTIME_ERROR',
  'Compile Error': 'COMPILATION_ERROR',
}

/** LeetCode's verdict on a submission, in the shape the console's verdict chip reads. */
export function lcChipVerdict(r: LcResult, submissionId: string): VerdictResponse {
  const msg = r.status_msg ?? (r.state === 'FAILURE' ? 'Failed' : 'Done')
  const verdict = LC_VERDICTS[msg] ?? msg
  return {
    submissionId,
    verdict,
    passedTestCount: verdict !== 'OK' && r.total_correct != null ? r.total_correct : undefined,
    finished: true,
  }
}

/**
 * LeetCode's answer to a Run, in the shape the console's Result tab reads — one result per
 * case, compared with LeetCode's own expected answers.
 */
export function lcRunResponse(r: LcResult, tests: RunTestCase[]): RunResponse {
  const compileError = r.full_compile_error || r.compile_error
  if (compileError || r.status_msg === 'Compile Error') {
    return { compiled: false, compileOutput: compileError || 'Compile error', compileMs: 0, results: [], error: null }
  }
  const answers = r.code_answer ?? []
  const runtimeError = r.full_runtime_error || r.runtime_error
  if (!runtimeError && answers.length === 0 && r.run_success === false) {
    return { compiled: true, compileOutput: null, compileMs: 0, results: [],
      error: `LeetCode: ${r.status_msg ?? 'the run failed'}.` }
  }
  const ms = Number.parseInt(r.status_runtime ?? '', 10) || 0
  const results: RunResponse['results'] = []
  tests.forEach((t, i) => {
    if (i < answers.length) {
      const expected = r.expected_code_answer?.[i]
      results.push({
        label: t.label, input: t.input, actual: answers[i], expected: expected ?? null,
        verdict: expected === undefined ? 'NO_EXPECTED' : expected === answers[i] ? 'OK' : 'WRONG_ANSWER',
        stderr: r.std_output_list?.[i] ?? '', durationMs: ms, exitCode: null, truncated: false,
      })
    } else if (runtimeError && i === answers.length) {
      // The case that failed; the ones after it never ran.
      results.push({
        label: t.label, input: t.input, actual: '', expected: r.expected_code_answer?.[i] ?? null,
        verdict: 'RUNTIME_ERROR', stderr: runtimeError, durationMs: ms, exitCode: null, truncated: false,
      })
    }
  })
  return { compiled: true, compileOutput: null, compileMs: 0, results, error: null }
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

  const [running, setRunning] = useState(false)
  const [runResult, setRunResult] = useState<RunResponse | null>(null)
  const [runError, setRunError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [submitted, setSubmitted] = useState<{ r: LcResult; id: string } | null>(null)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const connection = useLeetCodeConnection()
  const ready = connection.status?.signedIn === true
  const blocked = !connection.relay
    ? 'Use the CPIntel desktop app or extension to run and submit here'
    : !ready ? 'Sign in to LeetCode first' : null

  const solution = { slug: problem.slug, questionId: problem.questionId, lang, code }

  const run = async (tests: RunTestCase[]) => {
    if (!ready || running) return
    setRunning(true); setRunError(null)
    try {
      // LeetCode takes every case's arguments one after another, one argument per line.
      const input = tests.map(t => t.input.replace(/\n+$/, '')).join('\n')
      setRunResult(lcRunResponse(await lcRun(solution, input), tests))
    } catch (e) {
      setRunError(errorMessage(e, 'The run did not go through.'))
    } finally {
      setRunning(false)
    }
  }

  const submit = async () => {
    if (!ready || submitting) return
    setSubmitting(true); setSubmitError(null); setSubmitted(null)
    try {
      const r = await lcSubmit(solution)
      setSubmitted({ r, id: r.submissionId })
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
      setSubmitError(errorMessage(e, 'The submission did not go through.'))
    } finally {
      setSubmitting(false)
    }
  }

  const html = useMemo(() => problem.contentHtml
    ? DOMPurify.sanitize(problem.contentHtml, { USE_PROFILES: { html: true } }) : null, [problem.contentHtml])

  const samples = useMemo(
    () => problem.exampleTestcases.map(input => ({ input, output: '' })), [problem.exampleTestcases])

  const verdict: VerdictResponse | null = submitting
    ? { submissionId: '', verdict: 'TESTING', finished: false }
    : submitted ? lcChipVerdict(submitted.r, submitted.id) : null

  const statement = (
    <article className="mx-auto max-w-3xl">
      <StatementHeader
        badge={problem.questionId}
        kicker={<><Link to="/labs" className="hover:text-gray-300">{problem.labTitle}</Link> · LeetCode</>}
        title={problem.title}
        externalLabel="LeetCode"
        onOpenExternal={() => openExternal(problem.url, true)}
        chips={
          <>
            {problem.difficulty && <DifficultyChip difficulty={problem.difficulty} />}
            <LabMetaChips marks={problem.marks} dueAt={problem.dueAt} status={problem.status} />
          </>
        }
      />
      {html
        // LeetCode writes a plain $ as a dollar sign, so no maths typesetting here.
        ? <Section html={html} math={false} />
        : (
          <p className="text-sm text-gray-400">
            {problem.paidOnly
              ? 'This is a LeetCode Premium problem, so its statement is only shown on LeetCode.'
              : 'LeetCode did not send a statement for this problem.'}{' '}
            <a href={problem.url} target="_blank" rel="noreferrer" className="text-accent-300 hover:underline">Read it there</a>.
          </p>
        )}
      <div className="h-8" />
    </article>
  )

  return (
    <LabWorkspace
      labId={problem.labId}
      problemId={problem.problemId}
      notice={<ConnectionNotice connection={connection} />}
      statement={statement}
      storageKey="cpintel.lab.split"
      editor={
        <SolvePane
          languages={languages.map(l => ({ id: l.langSlug, label: l.lang }))}
          languageId={lang}
          onLanguageChange={id => { setLang(id); store(LANG_KEY, id) }}
          source={code}
          onSourceChange={setCode}
          editorLanguage={monacoLanguage(lang)}
          toolbar={
            <button
              type="button"
              onClick={() => { if (confirm('Replace your code with LeetCode\'s starter code?')) setCode(starter) }}
              title="Start again from LeetCode's starter code"
              aria-label="Reset to the starter code"
              className="rounded-md border border-gray-800 p-1.5 text-gray-400 transition-colors
                         hover:bg-gray-800 hover:text-gray-200"
            >
              <RotateCcw size={13} />
            </button>
          }
          samples={samples}
          problemKey={`lc-${problem.problemId}`}
          inputPlaceholder="One argument per line, as on LeetCode"
          showExpected={false}
          stderrLabel="Printed"
          onRun={tests => { void run(tests) }}
          running={running}
          runResult={runResult}
          runError={runError}
          runBlocked={blocked}
          runTitle="Run on LeetCode against these cases  (Ctrl+Enter)"
          onSubmit={() => { void submit() }}
          submitting={submitting}
          submitBlocked={blocked}
          submitTitle={`Submit to LeetCode as ${connection.status?.username ?? ''}  (Ctrl+S)`}
          verdict={verdict}
          polling={false}
          verdictDetail={submitError
            ? <p role="alert" className="text-sm text-red-400">{submitError}</p>
            : submitted ? <SubmitDetail r={submitted.r} /> : undefined}
          storageKey="cpintel.lab"
        />
      }
    />
  )
}

/** LeetCode's own colours for its three difficulties. */
function DifficultyChip({ difficulty }: { difficulty: string }) {
  const tone = difficulty === 'Easy' ? 'text-teal-400 border-teal-900'
    : difficulty === 'Medium' ? 'text-amber-400 border-amber-900'
      : difficulty === 'Hard' ? 'text-red-400 border-red-900' : 'text-gray-400 border-gray-700'
  return (
    <span className={clsx('flex items-center gap-1 rounded-full border px-2.5 py-1 font-medium', tone)}>
      <Gauge size={11} /> {difficulty}
    </span>
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

/** What stands between the student and Run / Submit, if anything. Nothing once all is well. */
function ConnectionNotice({ connection: c }: { connection: Connection }) {
  if (!c.relay) {
    return (
      <WorkspaceNotice icon={<Puzzle size={14} />}
        action={<Link to="/extension" className="btn-secondary btn-sm">Get the extension</Link>}>
        {extensionTooOld()
          ? 'Your CPIntel extension is too old for LeetCode. Install the new version to run and submit here.'
          : 'To run and submit here, use the CPIntel desktop app or install the CPIntel browser extension. You can still solve it on LeetCode; it counts either way.'}
      </WorkspaceNotice>
    )
  }
  if (c.loading) {
    return (
      <WorkspaceNotice icon={<Loader2 size={14} className="animate-spin" />}>
        <span className="text-gray-400">Checking your LeetCode sign-in…</span>
      </WorkspaceNotice>
    )
  }
  if (!c.status?.signedIn) {
    return (
      <WorkspaceNotice icon={<LogIn size={14} />}
        action={
          <button type="button" className="btn-primary btn-sm" onClick={c.signIn} disabled={c.signingIn}>
            {c.signingIn ? <Loader2 size={13} className="animate-spin" aria-hidden="true" /> : <LogIn size={13} aria-hidden="true" />}
            Sign in to LeetCode
          </button>
        }>
        {c.error ? errorMessage(c.error, 'Could not reach LeetCode.') + ' ' : ''}
        Sign in to LeetCode {c.relay === 'desktop' ? 'in the app' : 'in this browser'} to run and submit from here.
      </WorkspaceNotice>
    )
  }
  const signedAs = c.status.username ?? ''
  const mismatch = c.linked && c.linked.handle.toLowerCase() !== signedAs.toLowerCase()
  if (!c.linked || mismatch || !c.linked.verified) {
    return (
      <WorkspaceNotice icon={<AlertTriangle size={14} />} tone="warn"
        action={<Link to="/platforms" className="btn-secondary btn-sm">Platforms</Link>}>
        Signed in to LeetCode as <b className="font-mono">{signedAs}</b>.{' '}
        {!c.linked
          ? 'Link and verify this handle on the Platforms page, or your solves will not count.'
          : mismatch
            ? <>Your linked handle is <b className="font-mono">{c.linked.handle}</b>, so solves from this account will not count.</>
            : 'Verify this handle on the Platforms page, or your solves will not count.'}
      </WorkspaceNotice>
    )
  }
  return null
}

/** What LeetCode said about the submission, under the verdict chip. */
function SubmitDetail({ r }: { r: LcResult }) {
  const msg = r.status_msg ?? (r.state === 'FAILURE' ? 'Failed' : 'Done')
  const accepted = msg === 'Accepted'
  const pre = 'mt-1 max-h-40 overflow-auto whitespace-pre-wrap rounded-md border border-gray-800 bg-gray-900 p-2 font-mono text-xs text-gray-300'
  const error = r.full_compile_error || r.compile_error || r.full_runtime_error || r.runtime_error
  return (
    <div className="space-y-2 rounded-lg border border-gray-800 bg-gray-900/40 p-3 text-sm">
      <p className={accepted ? 'font-semibold text-green-400' : 'font-semibold text-red-400'}>
        LeetCode: {msg}
        {r.total_testcases != null && (
          <span className="ml-2 text-xs font-normal text-gray-400">{r.total_correct ?? 0} / {r.total_testcases} tests</span>
        )}
      </p>
      {accepted && (r.status_runtime || r.status_memory) && (
        <p className="text-xs text-gray-400">Runtime {r.status_runtime} · Memory {r.status_memory}</p>
      )}
      {error && <pre className={pre}>{error}</pre>}
      {!error && !accepted && r.last_testcase && (
        <div className="text-xs">
          <span className="text-gray-500">Failing input</span>
          <pre className={pre}>{r.last_testcase}</pre>
          {r.expected_output !== undefined && (
            <p className="mt-1 text-gray-400">
              Expected <span className="font-mono">{String(r.expected_output)}</span>, got{' '}
              <span className="font-mono">{String(r.code_output)}</span>
            </p>
          )}
        </div>
      )}
    </div>
  )
}
