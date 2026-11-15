import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import DOMPurify from 'dompurify'
import { useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Clock, Cpu, Link2 } from 'lucide-react'

import { LabMetaChips, LabWorkspace, WorkspaceNotice } from '@/components/labs/LabWorkspace'
import { Meta, Section, StatementHeader } from '@/components/practice/StatementView'
import { SolvePane } from '@/components/workspace/SolvePane'
import { ErrorState, LoadingState, errorMessage } from '@/components/common/States'
import { useToast } from '@/components/common/Toaster'
import { starterFor } from '@/components/editor/languages'
import { useLabCsesProblem, useMyLabs } from '@/hooks/useLabs'
import { useRunCode, useRunnerLanguages } from '@/hooks/useRunner'
import { labsApi } from '@/api/labsApi'
import { openExternal } from '@/utils/desktopBridge'
import type { CsesVerdict, LabCsesProblem, RunTestCase, VerdictResponse } from '@/types'

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

/** CSES's verdict in the shape the console's verdict chip reads. */
export function csesChipVerdict(v: CsesVerdict): VerdictResponse {
  const code = (v.result ?? v.status ?? '').toUpperCase().replace(/[\s-]+/g, '_')
  const failed = v.tests.find(t => t.verdict.toUpperCase() !== 'ACCEPTED')
  return {
    submissionId: String(v.submissionId),
    verdict: v.pending ? 'TESTING'
      : code === 'ACCEPTED' ? 'OK'
        : code === 'COMPILE_ERROR' ? 'COMPILATION_ERROR'
          : code || 'Done',
    // The chip says "on test n+1" from this.
    passedTestCount: !v.pending && failed ? failed.number - 1 : undefined,
    finished: !v.pending,
  }
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

  const draftKey = `cpintel.cses.draft.${problem.taskId}.${runtime?.id ?? lang}`
  const [draft, setDraft] = useState<{ key: string; code: string } | null>(null)
  const code = draft?.key === draftKey ? draft.code : (stored(draftKey) ?? starterFor(editorLanguage))
  const setCode = (next: string) => {
    setDraft({ key: draftKey, code: next })
    store(draftKey, next)
  }

  const run = useRunCode()
  const [submitting, setSubmitting] = useState(false)
  const [verdict, setVerdict] = useState<CsesVerdict | null>(null)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const ready = account?.verified === true

  const doRun = (tests: RunTestCase[]) => {
    if (!runtime) return
    run.mutate({ language: runtime.id, source: code, tests })
  }

  const doSubmit = async () => {
    if (!ready || !runtime || submitting) return
    setSubmitting(true); setSubmitError(null); setVerdict(null)
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
      setSubmitError(errorMessage(e, 'The submission did not go through.'))
    } finally {
      setSubmitting(false)
    }
  }

  const html = useMemo(() => DOMPurify.sanitize(problem.contentHtml, { USE_PROFILES: { html: true } }),
    [problem.contentHtml])

  const notice = ready ? null : (
    <WorkspaceNotice
      icon={account ? <AlertTriangle size={14} /> : <Link2 size={14} />}
      tone={account ? 'warn' : 'plain'}
      action={<Link to="/platforms" className="btn-secondary btn-sm">Platforms</Link>}
    >
      {account
        ? 'CSES no longer accepts your connection. Reconnect CSES to submit from here.'
        : 'Connect your CSES account to submit from here. You can still run on the examples.'}
    </WorkspaceNotice>
  )

  const statement = (
    <article className="mx-auto max-w-3xl">
      <StatementHeader
        badge={problem.taskId}
        kicker={<><Link to="/labs" className="hover:text-gray-300">{problem.labTitle}</Link> · CSES</>}
        title={problem.title}
        externalLabel="CSES"
        onOpenExternal={() => openExternal(problem.url, true)}
        chips={<LabChips problem={problem} />}
      />
      <Section html={html}
        className="[&_h1]:mt-6 [&_h1]:mb-2 [&_h1]:text-[11px] [&_h1]:font-semibold [&_h1]:uppercase [&_h1]:tracking-wider [&_h1]:text-gray-500" />
      <div className="h-8" />
    </article>
  )

  return (
    <LabWorkspace
      labId={problem.labId}
      problemId={problem.problemId}
      notice={notice}
      statement={statement}
      storageKey="cpintel.lab.split"
      editor={
        <SolvePane
          languages={languages.map(l => ({ id: l.id, label: l.displayName }))}
          languageId={runtime?.id ?? lang}
          onLanguageChange={id => { setLang(id); store(LANG_KEY, id) }}
          source={code}
          onSourceChange={setCode}
          editorLanguage={editorLanguage}
          samples={problem.examples}
          problemKey={`cses-${problem.problemId}`}
          onRun={doRun}
          running={run.isPending}
          progress={run.progress}
          runResult={run.data ?? null}
          runError={run.isError ? errorMessage(run.error, 'The run did not go through.') : null}
          runBlocked={runtime ? null : 'No local toolchain is available to run code'}
          runTitle="Run against these cases  (Ctrl+Enter)"
          onSubmit={doSubmit}
          submitting={submitting}
          submitBlocked={!ready ? 'Connect CSES first' : !runtime ? 'Pick a language' : null}
          submitTitle={`Submit to CSES as ${account?.handle ?? ''}  (Ctrl+S)`}
          verdict={verdict ? csesChipVerdict(verdict) : null}
          polling={!!verdict?.pending}
          verdictDetail={submitError
            ? <p role="alert" className="text-sm text-red-400">{submitError}</p>
            : verdict ? <CsesVerdictDetail v={verdict} /> : undefined}
          storageKey="cpintel.lab"
        />
      }
    />
  )
}

/** Marks, deadline and whether it is already solved, as chips like Practice's time and memory. */
function LabChips({ problem }: { problem: LabCsesProblem }) {
  return (
    <>
      {problem.timeLimit && <Meta icon={<Clock size={12} />} label="Time" value={problem.timeLimit} />}
      {problem.memoryLimit && <Meta icon={<Cpu size={12} />} label="Memory" value={problem.memoryLimit} />}
      <LabMetaChips marks={problem.marks} dueAt={problem.dueAt} status={problem.status} />
    </>
  )
}

function CsesVerdictDetail({ v }: { v: CsesVerdict }) {
  const pre = 'mt-1 max-h-40 overflow-auto whitespace-pre-wrap rounded-md border border-gray-800 bg-gray-900 p-2 font-mono text-xs text-gray-300'
  if (v.pending) {
    return (
      <p className="text-xs text-gray-400">
        Judging on CSES{v.totalTests ? ` — ${v.finishedTests ?? 0} of ${v.totalTests} tests` : '…'}
      </p>
    )
  }
  const accepted = v.result === 'ACCEPTED'
  const failed = v.tests.find(t => t.verdict !== 'ACCEPTED')
  const passed = v.tests.filter(t => t.verdict === 'ACCEPTED').length
  return (
    <div className="space-y-1 rounded-lg border border-gray-800 bg-gray-900/40 p-3 text-sm">
      <p className={accepted ? 'font-semibold text-green-400' : 'font-semibold text-red-400'}>
        CSES: {(v.result ?? v.status ?? 'Done').replace(/_/g, ' ').toLowerCase().replace(/^./, c => c.toUpperCase())}
        {v.tests.length > 0 && (
          <span className="ml-2 text-xs font-normal text-gray-400">{passed} / {v.tests.length} tests</span>
        )}
      </p>
      {!accepted && failed && (
        <p className="text-xs text-gray-400">First failing test: #{failed.number} ({failed.verdict.toLowerCase()})</p>
      )}
      {v.compiler && <pre className={pre}>{v.compiler}</pre>}
    </div>
  )
}
