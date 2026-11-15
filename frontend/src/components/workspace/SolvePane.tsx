import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Code2, Loader2, Play, Send } from 'lucide-react'

import { CodeEditor } from '@/components/editor/CodeEditor'
import { SplitPane } from './SplitPane'
import { TabStrip } from './TabStrip'
import { ConsolePanel, newCase, type WorkCase } from './ConsolePanel'
import type { ProblemSample, RunResponse, RunTestCase, VerdictResponse } from '@/types'

export interface SolveLanguage {
  id: string
  label: string
}

interface Props {
  languages: SolveLanguage[]
  languageId: string
  onLanguageChange: (id: string) => void
  source: string
  onSourceChange: (value: string) => void
  /** Monaco's name for the language being written. */
  editorLanguage: string
  /** Controls beside the language picker that belong to this judge, such as a reset. */
  toolbar?: ReactNode

  /** What the cases are seeded from. */
  samples: ProblemSample[]
  /** Changing it reseeds the cases, so one problem's output is never read as another's. */
  problemKey: string
  inputPlaceholder?: string
  showExpected?: boolean
  stderrLabel?: string

  onRun: (tests: RunTestCase[]) => void
  running: boolean
  progress?: string | null
  runResult: RunResponse | null
  runError: string | null
  /** Why Run is unavailable, or null when it can run. */
  runBlocked: string | null
  runTitle: string

  onSubmit: () => void
  submitting: boolean
  /** Why Submit is unavailable, or null when it can submit. */
  submitBlocked: string | null
  submitTitle: string
  verdict: VerdictResponse | null
  polling: boolean
  /** More about the last submission: the failing input, the compiler's message. */
  verdictDetail?: ReactNode

  notes?: ReactNode
  storageKey: string
}

/**
 * The right half of a lab workspace: the same editor-over-console layout as Practice, with the
 * same tab strips, the same Testcase / Result console and Run and Submit in the same place.
 *
 * Practice's own pane is built around Codeforces — its language list, its local runner, its
 * submission history — so the lab workspaces use this one, made from the same parts, and each
 * page supplies how Run and Submit reach its judge.
 */
export function SolvePane({
  languages, languageId, onLanguageChange, source, onSourceChange, editorLanguage, toolbar,
  samples, problemKey, inputPlaceholder, showExpected, stderrLabel,
  onRun, running, progress, runResult, runError, runBlocked, runTitle,
  onSubmit, submitting, submitBlocked, submitTitle, verdict, polling, verdictDetail,
  notes, storageKey,
}: Props) {
  const [cases, setCases] = useState<WorkCase[]>([newCase()])
  const [activeCase, setActiveCase] = useState(0)
  const [consoleTab, setConsoleTab] = useState<'testcase' | 'result'>('testcase')

  // Keyed on the text, not the array, so a refetch does not throw away an edited case.
  const sampleKey = useMemo(
    () => samples.map(s => `${s.input}\u0000${s.output}`).join('\u0001'), [samples])
  useEffect(() => {
    setCases(samples.length > 0
      ? samples.map(s => newCase(s.input, showExpected === false ? null : s.output, true))
      : [newCase()])
    setActiveCase(0)
    setConsoleTab('testcase')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [problemKey, sampleKey])

  const canRun = runBlocked === null && !running && !submitting && cases.length > 0
    && !!source.trim()
  const canSend = submitBlocked === null && !submitting && !running && !!source.trim()

  const run = () => {
    if (!canRun) return
    setConsoleTab('result')
    onRun(cases.map((c, i) => ({ input: c.input, expected: c.expected, label: `Case ${i + 1}` })))
  }
  const submit = () => {
    if (!canSend) return
    // The submission's detail lands in the Result tab.
    setConsoleTab('result')
    onSubmit()
  }

  const editorSection = (
    <div className="flex flex-col flex-1 min-w-0 min-h-0 bg-gray-950">
      <TabStrip
        tabs={[{ id: 'code', label: 'Code', icon: Code2 }]}
        active="code"
        onChange={() => {}}
        right={
          <>
            {toolbar}
            <select
              value={languageId}
              onChange={e => onLanguageChange(e.target.value)}
              aria-label="Language"
              title="The language you write in: Run and Submit both use it"
              className="min-w-0 max-w-52 shrink rounded-md border border-gray-800
                         bg-gray-900 px-2 py-1 text-xs text-gray-300 outline-hidden
                         focus:border-accent-600"
            >
              {languages.length === 0 && <option value="">No languages loaded</option>}
              {languages.map(l => <option key={l.id} value={l.id}>{l.label}</option>)}
            </select>
          </>
        }
      />
      <div className="relative flex-1 min-h-0">
        <CodeEditor
          value={source}
          onChange={onSourceChange}
          language={editorLanguage}
          onRun={run}
          onSubmit={submit}
        />
      </div>
    </div>
  )

  const actions = (
    <>
      <button
        onClick={run}
        disabled={!canRun}
        title={runBlocked ?? (source.trim() ? runTitle : 'Write some code first')}
        className="flex items-center gap-1.5 rounded-md border border-gray-700 bg-gray-800
                   px-2.5 py-1.5 text-xs font-medium text-gray-200 transition-colors
                   hover:bg-gray-700 disabled:opacity-40 disabled:cursor-not-allowed
                   disabled:hover:bg-gray-800"
      >
        {running ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
        Run
      </button>
      <button
        onClick={submit}
        disabled={!canSend}
        title={submitBlocked ?? (source.trim() ? submitTitle : 'Write some code first')}
        className="flex items-center gap-1.5 rounded-md bg-green-600 px-3 py-1.5 text-xs
                   font-medium text-white transition-colors hover:bg-green-500
                   disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-green-600"
      >
        {submitting ? <Loader2 size={13} className="animate-spin" /> : <Send size={13} />}
        Submit
      </button>
    </>
  )

  return (
    <div className="flex flex-col flex-1 min-w-0 min-h-0">
      <SplitPane
        direction="vertical"
        storageKey={`${storageKey}.console`}
        initial={62}
        min={25}
        max={88}
        className="flex-1"
        first={editorSection}
        second={
          <ConsolePanel
            cases={cases}
            onCasesChange={setCases}
            activeCase={activeCase}
            onActiveCase={setActiveCase}
            tab={consoleTab}
            onTab={setConsoleTab}
            result={runResult}
            progress={progress}
            running={running}
            error={runError}
            verdict={verdict}
            polling={polling}
            verdictDetail={verdictDetail}
            inputPlaceholder={inputPlaceholder}
            showExpected={showExpected}
            stderrLabel={stderrLabel}
            actions={actions}
            notes={notes}
          />
        }
      />
    </div>
  )
}
