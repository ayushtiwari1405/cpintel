import { clsx } from 'clsx'
import {
  AlertTriangle, CheckCircle2, Clock, FlaskConical, Loader2, Plus, Terminal, X, XCircle,
} from 'lucide-react'
import type { ReactNode } from 'react'
import { TabStrip } from './TabStrip'
import type { RunResponse, RunTestResult, RunVerdict, VerdictResponse } from '@/types'

/** One test the Run button will execute. Sample cases arrive with an expected output. */
export interface WorkCase {
  id: string
  input: string
  /** Null when there is nothing to compare against — a case typed to see what happens. */
  expected: string | null
  fromSample: boolean
}

let caseSeq = 0
export function newCase(input = '', expected: string | null = null, fromSample = false): WorkCase {
  return { id: `case-${++caseSeq}`, input, expected, fromSample }
}

const VERDICT_META: Record<RunVerdict, { label: string; tone: Tone }> = {
  OK:                  { label: 'Accepted',            tone: 'ok' },
  WRONG_ANSWER:        { label: 'Wrong answer',        tone: 'bad' },
  TIME_LIMIT_EXCEEDED: { label: 'Time limit exceeded', tone: 'warn' },
  RUNTIME_ERROR:       { label: 'Runtime error',       tone: 'bad' },
  NO_EXPECTED:         { label: 'Finished',            tone: 'plain' },
}

type Tone = 'ok' | 'bad' | 'warn' | 'plain'

const TONE_TEXT: Record<Tone, string> = {
  ok: 'text-green-400',
  bad: 'text-red-400',
  warn: 'text-amber-400',
  plain: 'text-gray-300',
}

const PLATFORM_VERDICTS: Record<string, string> = {
  OK: 'Accepted',
  WRONG_ANSWER: 'Wrong answer',
  TIME_LIMIT_EXCEEDED: 'Time limit exceeded',
  MEMORY_LIMIT_EXCEEDED: 'Memory limit exceeded',
  RUNTIME_ERROR: 'Runtime error',
  COMPILATION_ERROR: 'Compilation error',
  IDLENESS_LIMIT_EXCEEDED: 'Idleness limit exceeded',
  TESTING: 'Judging',
  SUBMITTED: 'Queued',
  SKIPPED: 'Skipped',
  CHALLENGED: 'Hacked',
}

interface Props {
  cases: WorkCase[]
  onCasesChange: (cases: WorkCase[]) => void
  activeCase: number
  onActiveCase: (index: number) => void
  tab: 'testcase' | 'result'
  onTab: (tab: 'testcase' | 'result') => void
  result: RunResponse | null
  running: boolean
  error: string | null
  /** What the platform said about the last submission, if anything. */
  verdict: VerdictResponse | null
  polling: boolean
  onShowSubmissionTests: () => void
  /** Run and Submit, supplied by the pane that owns them. */
  actions: ReactNode
  /** Anything worth saying about the local runner — off, missing, unsandboxed. */
  notes?: ReactNode
  disabled?: boolean
}

/**
 * The console under the editor: the tests you are about to run, and what happened when you did.
 *
 * Sample tests are seeded as editable cases rather than shown as a read-only list, because the
 * first thing anyone does with a failing sample is change one number in it. Cases you add have
 * no expected output and simply report what the program printed, which is the "run it on this
 * and let me look" case that used to need its own panel.
 *
 * What the platform said about a real submission lives in the header rather than in the Result
 * tab, so it stays visible while you edit tests — and so it is never confused with the local
 * run, which proves far less.
 */
export function ConsolePanel({
  cases, onCasesChange, activeCase, onActiveCase, tab, onTab,
  result, running, error, verdict, polling, onShowSubmissionTests, actions, notes, disabled,
}: Props) {
  const active = cases[activeCase]

  const update = (patch: Partial<WorkCase>) => {
    onCasesChange(cases.map((c, i) => (i === activeCase ? { ...c, ...patch } : c)))
  }

  const addCase = () => {
    onCasesChange([...cases, newCase()])
    onActiveCase(cases.length)
  }

  const removeCase = (index: number) => {
    const next = cases.filter((_, i) => i !== index)
    onCasesChange(next)
    if (activeCase >= next.length) onActiveCase(Math.max(0, next.length - 1))
  }

  return (
    <div className="flex flex-col min-h-0 flex-1 bg-gray-950">
      <TabStrip
        tabs={[
          { id: 'testcase', label: 'Testcase', icon: FlaskConical },
          {
            id: 'result',
            label: 'Result',
            icon: Terminal,
            badge: running
              ? <Loader2 size={11} className="animate-spin" />
              : result ? <ResultDot result={result} /> : null,
          },
        ]}
        active={tab}
        onChange={id => onTab(id as 'testcase' | 'result')}
        right={
          <>
            {verdict && (
              <PlatformVerdict
                verdict={verdict}
                polling={polling}
                onShowTests={onShowSubmissionTests}
              />
            )}
            {actions}
          </>
        }
      />

      <div className="flex-1 min-h-0 overflow-y-auto">
        {tab === 'testcase' ? (
          <div className="p-3 space-y-3">
            <div className="flex items-center gap-1.5 flex-wrap">
              {cases.map((c, i) => (
                <CaseChip
                  key={c.id}
                  label={`Case ${i + 1}`}
                  active={i === activeCase}
                  sample={c.fromSample}
                  onClick={() => onActiveCase(i)}
                  onRemove={cases.length > 1 ? () => removeCase(i) : undefined}
                />
              ))}
              <button
                onClick={addCase}
                disabled={disabled}
                title="Add a case of your own"
                className="flex items-center gap-1 px-2 py-1 rounded-md text-xs text-gray-500
                           border border-dashed border-gray-800 hover:border-gray-700
                           hover:text-gray-300 transition-colors disabled:opacity-40"
              >
                <Plus size={12} />
              </button>
            </div>

            {!active ? (
              <p className="text-xs text-gray-600">
                No test cases yet. Add one to run your code against it.
              </p>
            ) : (
              <>
                <Labelled label="Input">
                  <textarea
                    value={active.input}
                    onChange={e => update({ input: e.target.value })}
                    disabled={disabled}
                    spellCheck={false}
                    rows={4}
                    placeholder="The stdin your program reads"
                    className="w-full rounded-lg border border-gray-800 bg-gray-900 px-3 py-2
                               font-mono text-xs text-gray-200 placeholder-gray-700 resize-y
                               outline-none focus:border-indigo-600 disabled:opacity-50"
                  />
                </Labelled>

                <Labelled
                  label="Expected output"
                  hint={active.expected == null ? 'optional — leave empty to just see the output' : undefined}
                >
                  <textarea
                    value={active.expected ?? ''}
                    onChange={e => update({ expected: e.target.value || null })}
                    disabled={disabled}
                    spellCheck={false}
                    rows={3}
                    placeholder="Leave empty to print the output without judging it"
                    className="w-full rounded-lg border border-gray-800 bg-gray-900 px-3 py-2
                               font-mono text-xs text-gray-200 placeholder-gray-700 resize-y
                               outline-none focus:border-indigo-600 disabled:opacity-50"
                  />
                </Labelled>
              </>
            )}

            {notes && <div className="pt-1 space-y-1">{notes}</div>}
          </div>
        ) : (
          <ResultView
            result={result}
            running={running}
            error={error}
            cases={cases}
            activeCase={activeCase}
            onActiveCase={onActiveCase}
          />
        )}
      </div>
    </div>
  )
}

function ResultDot({ result }: { result: RunResponse }) {
  if (!result.compiled || result.error) {
    return <span className="w-1.5 h-1.5 rounded-full bg-red-400" />
  }
  const judged = result.results.filter(r => r.verdict !== 'NO_EXPECTED')
  if (judged.length === 0) return <span className="w-1.5 h-1.5 rounded-full bg-gray-500" />
  const allPassed = judged.every(r => r.verdict === 'OK')
  return (
    <span className={clsx('w-1.5 h-1.5 rounded-full', allPassed ? 'bg-green-400' : 'bg-red-400')} />
  )
}

function ResultView({ result, running, error, cases, activeCase, onActiveCase }: {
  result: RunResponse | null
  running: boolean
  error: string | null
  cases: WorkCase[]
  activeCase: number
  onActiveCase: (index: number) => void
}) {
  if (running) {
    return (
      <div className="flex items-center justify-center gap-2 py-12 text-sm text-gray-500">
        <Loader2 size={15} className="animate-spin" /> Compiling and running…
      </div>
    )
  }

  if (error) {
    return (
      <div className="p-3">
        <Banner tone="warn" icon={<AlertTriangle size={14} />}>{error}</Banner>
      </div>
    )
  }

  if (!result) {
    return (
      <p className="px-3 py-12 text-center text-xs text-gray-600">
        Run your code to see the output here.
      </p>
    )
  }

  if (result.error) {
    return (
      <div className="p-3">
        <Banner tone="warn" icon={<AlertTriangle size={14} />}>{result.error}</Banner>
      </div>
    )
  }

  if (!result.compiled) {
    return (
      <div className="p-3 space-y-2">
        <p className="flex items-center gap-2 text-sm font-medium text-red-400">
          <XCircle size={15} /> Compilation error
        </p>
        <Pre className="text-red-300/90 max-h-full">
          {result.compileOutput ?? 'The compiler said nothing useful.'}
        </Pre>
      </div>
    )
  }

  const judged = result.results.filter(r => r.verdict !== 'NO_EXPECTED')
  const passed = judged.filter(r => r.verdict === 'OK').length
  const allPassed = judged.length > 0 && passed === judged.length
  const shown = result.results[activeCase] ?? result.results[0]

  return (
    <div className="p-3 space-y-3">
      <div className="flex items-baseline gap-3 flex-wrap">
        <span className={clsx(
          'text-base font-semibold',
          judged.length === 0 ? 'text-gray-200' : allPassed ? 'text-green-400' : 'text-red-400'
        )}>
          {judged.length === 0
            ? 'Finished'
            : allPassed ? 'Accepted' : 'Wrong answer'}
        </span>
        {judged.length > 0 && (
          <span className="text-xs text-gray-500">{passed}/{judged.length} cases passed</span>
        )}
        <span className="text-xs text-gray-600">compiled in {result.compileMs} ms</span>
      </div>

      {allPassed && (
        // Worth saying plainly. Agreeing with the samples is a smoke test, not a verdict, and
        // a green banner is exactly the moment someone stops testing and submits.
        <p className="text-[11px] text-gray-600">
          Samples only — the judge tests far more than this.
        </p>
      )}

      {result.compileOutput && (
        <details className="group">
          <summary className="cursor-pointer text-[11px] text-amber-400/80 hover:text-amber-300">
            Compiler warnings
          </summary>
          <Pre className="mt-1 text-amber-200/80 max-h-40">{result.compileOutput}</Pre>
        </details>
      )}

      <div className="flex items-center gap-1.5 flex-wrap">
        {result.results.map((r, i) => (
          <CaseChip
            key={i}
            label={cases[i] ? `Case ${i + 1}` : r.label}
            active={i === activeCase}
            tone={r.verdict === 'OK' ? 'ok' : r.verdict === 'NO_EXPECTED' ? 'plain' : 'bad'}
            onClick={() => onActiveCase(i)}
          />
        ))}
      </div>

      {shown && <CaseResult result={shown} />}
    </div>
  )
}

function CaseResult({ result }: { result: RunTestResult }) {
  const meta = VERDICT_META[result.verdict] ?? VERDICT_META.NO_EXPECTED

  return (
    <div className="space-y-2.5">
      <div className="flex items-center gap-2 text-xs">
        <VerdictIcon verdict={result.verdict} />
        <span className={clsx('font-medium', TONE_TEXT[meta.tone])}>{meta.label}</span>
        <span className="ml-auto text-gray-600">{result.durationMs} ms</span>
      </div>

      {result.truncated && (
        <p className="text-[11px] text-gray-600">Output was truncated at the size limit.</p>
      )}

      <Labelled label="Input"><Pre>{result.input}</Pre></Labelled>
      <Labelled label="Your output">
        <Pre className={result.verdict === 'WRONG_ANSWER' ? 'text-red-300' : undefined}>
          {result.actual || '(nothing printed)'}
        </Pre>
      </Labelled>
      {result.expected != null && (
        <Labelled label="Expected"><Pre>{result.expected}</Pre></Labelled>
      )}
      {result.stderr && (
        <Labelled label="stderr"><Pre className="text-amber-200/80">{result.stderr}</Pre></Labelled>
      )}

      {result.exitCode != null && result.exitCode !== 0 && (
        <p className="text-[11px] text-gray-500">
          Exited with code {result.exitCode}
          {result.exitCode === 139 && ' — segmentation fault'}
          {result.exitCode === 136 && ' — floating point exception'}
          {result.exitCode === 134 && ' — aborted (a failed assertion, or an uncaught exception)'}
        </p>
      )}
    </div>
  )
}

function PlatformVerdict({ verdict, polling, onShowTests }: {
  verdict: VerdictResponse
  polling: boolean
  onShowTests: () => void
}) {
  const pending = !verdict.finished
  const accepted = verdict.verdict === 'OK'
  const failed = verdict.finished && !accepted

  return (
    <span className={clsx(
      'flex items-center gap-1.5 px-2 py-1 rounded-md border text-[11px] max-w-[22rem]',
      pending ? 'bg-gray-800 border-gray-700 text-gray-300'
        : accepted ? 'bg-green-950/60 border-green-900 text-green-300'
          : 'bg-red-950/60 border-red-900 text-red-300'
    )}>
      {pending
        ? <Loader2 size={11} className="animate-spin flex-shrink-0" />
        : accepted
          ? <CheckCircle2 size={11} className="flex-shrink-0" />
          : <XCircle size={11} className="flex-shrink-0" />}
      <span className="truncate font-medium">
        {PLATFORM_VERDICTS[verdict.verdict] ?? verdict.verdict.replace(/_/g, ' ')}
      </span>
      {verdict.passedTestCount != null && failed && (
        <span className="opacity-70 flex-shrink-0">on test {verdict.passedTestCount + 1}</span>
      )}
      {pending && polling && (
        <span className="opacity-60 flex-shrink-0">#{verdict.submissionId}</span>
      )}
      {failed && (
        <button
          onClick={onShowTests}
          title="Open the test that failed, without leaving the app"
          className="flex-shrink-0 underline underline-offset-2 hover:text-gray-100"
        >
          see it
        </button>
      )}
    </span>
  )
}

function CaseChip({ label, active, sample, tone, onClick, onRemove }: {
  label: string
  active: boolean
  sample?: boolean
  tone?: Tone
  onClick: () => void
  onRemove?: () => void
}) {
  return (
    <span className={clsx(
      'group flex items-center gap-1 rounded-md border text-xs transition-colors',
      active
        ? 'border-gray-700 bg-gray-800 text-gray-100'
        : 'border-transparent bg-gray-900 text-gray-500 hover:text-gray-300'
    )}>
      <button onClick={onClick} className="flex items-center gap-1.5 px-2 py-1">
        {tone && (
          <span className={clsx('w-1.5 h-1.5 rounded-full flex-shrink-0', {
            'bg-green-400': tone === 'ok',
            'bg-red-400': tone === 'bad',
            'bg-amber-400': tone === 'warn',
            'bg-gray-600': tone === 'plain',
          })} />
        )}
        {label}
        {sample && <span className="text-[10px] text-gray-600">sample</span>}
      </button>
      {onRemove && (
        <button
          onClick={onRemove}
          aria-label={`Remove ${label}`}
          className="pr-1.5 text-gray-600 hover:text-red-400 transition-colors"
        >
          <X size={11} />
        </button>
      )}
    </span>
  )
}

function VerdictIcon({ verdict }: { verdict: RunVerdict }) {
  const cls = 'flex-shrink-0'
  if (verdict === 'OK') return <CheckCircle2 size={13} className={clsx(cls, 'text-green-400')} />
  if (verdict === 'TIME_LIMIT_EXCEEDED') return <Clock size={13} className={clsx(cls, 'text-amber-400')} />
  if (verdict === 'NO_EXPECTED') return <Terminal size={13} className={clsx(cls, 'text-gray-400')} />
  return <XCircle size={13} className={clsx(cls, 'text-red-400')} />
}

function Labelled({ label, hint, children }: {
  label: string
  hint?: string
  children: ReactNode
}) {
  return (
    <div className="space-y-1">
      <span className="flex items-baseline gap-2 text-[11px] font-medium text-gray-500">
        {label}
        {hint && <span className="font-normal text-gray-600">{hint}</span>}
      </span>
      {children}
    </div>
  )
}

function Pre({ children, className }: { children: string; className?: string }) {
  return (
    <pre className={clsx(
      'rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 max-h-48 overflow-auto',
      'font-mono text-xs text-gray-300 whitespace-pre-wrap break-words',
      className
    )}>{children}</pre>
  )
}

function Banner({ children, tone, icon }: {
  children: ReactNode
  tone: 'warn' | 'bad'
  icon: ReactNode
}) {
  return (
    <div className={clsx(
      'flex items-start gap-2 rounded-lg border px-3 py-2 text-xs',
      tone === 'warn'
        ? 'border-amber-900 bg-amber-950/40 text-amber-300'
        : 'border-red-900 bg-red-950/40 text-red-300'
    )}>
      <span className="flex-shrink-0 mt-0.5">{icon}</span>
      <span>{children}</span>
    </div>
  )
}
