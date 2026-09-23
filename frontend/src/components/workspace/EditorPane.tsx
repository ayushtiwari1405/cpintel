import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Code2, History, Loader2, Play, Send, ShieldAlert, Upload, X, XCircle,
} from 'lucide-react'
import { clsx } from 'clsx'

import { CodeEditor } from '@/components/editor/CodeEditor'
import { SubmissionHistory } from '@/components/editor/SubmissionHistory'
import { detectLanguage } from '@/components/editor/languages'
import { useRunCode, useRunnerLanguages, useRunnerStatus } from '@/hooks/useRunner'
import { SplitPane } from './SplitPane'
import { TabStrip } from './TabStrip'
import { ConsolePanel, newCase, type WorkCase } from './ConsolePanel'
import type {
  ContestRef, LanguageOption, ProblemSample, RunTestCase, VerdictResponse,
} from '@/types'

/** Codeforces rejects sources larger than 64 KB, so stop them here rather than at submit. */
const MAX_SOURCE_BYTES = 64 * 1024

/**
 * Extension -> ordered label patterns. The first language in the dropdown whose label matches
 * wins, so newer compilers are listed before older ones. Nothing is selected if no pattern
 * matches; the user's current choice is left alone rather than guessed at.
 */
const EXT_HINTS: Record<string, RegExp[]> = {
  cpp:   [/G\+\+23/i, /G\+\+20/i, /G\+\+17/i, /G\+\+/i, /clang\+\+/i],
  cc:    [/G\+\+23/i, /G\+\+20/i, /G\+\+17/i, /G\+\+/i],
  cxx:   [/G\+\+23/i, /G\+\+20/i, /G\+\+17/i, /G\+\+/i],
  hpp:   [/G\+\+23/i, /G\+\+20/i, /G\+\+17/i, /G\+\+/i],
  c:     [/GCC C\d/i, /GNU GCC/i],
  py:    [/^Python 3/i, /Python 3/i, /PyPy 3/i],
  java:  [/Java 21/i, /Java \d/i],
  kt:    [/Kotlin 2/i, /Kotlin/i],
  rs:    [/Rust.*2024/i, /Rust/i],
  go:    [/\bGo \d/i],
  js:    [/Node\.js/i, /JavaScript/i],
  mjs:   [/Node\.js/i, /JavaScript/i],
  cs:    [/C# 13/i, /C# \d/i],
  rb:    [/Ruby/i],
  // Anchored: an unanchored /Scala/ also matches "Pa(scal)ABC.NET", which sorts first.
  scala: [/\bScala\b/i],
  hs:    [/Haskell/i],
  pas:   [/Free Pascal/i, /PascalABC/i, /Pascal/i],
  php:   [/PHP/i],
  pl:    [/Perl/i],
  ml:    [/OCaml/i],
  d:     [/DMD/i],
}

const ACCEPT = [
  '.cpp', '.cc', '.cxx', '.hpp', '.c', '.h', '.py', '.java', '.kt', '.rs', '.go',
  '.js', '.mjs', '.cs', '.rb', '.scala', '.hs', '.pas', '.php', '.pl', '.ml', '.d',
  '.txt', 'text/*',
].join(',')

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

/** Returns the id of the best-matching language, or null to leave the selection untouched. */
function pickLanguageFor(fileName: string, languages: LanguageOption[]): string | null {
  const patterns = EXT_HINTS[extensionOf(fileName)]
  if (!patterns) return null
  for (const pattern of patterns) {
    const hit = languages.find(l => pattern.test(l.label))
    if (hit) return hit.id
  }
  return null
}

function formatBytes(n: number): string {
  return n < 1024 ? `${n} B` : `${(n / 1024).toFixed(1)} KB`
}

interface Props {
  languages: LanguageOption[]
  languageId: string
  onLanguageChange: (id: string) => void
  source: string
  onSourceChange: (value: string) => void
  onSubmit: () => void
  submitting: boolean
  polling: boolean
  verdict: VerdictResponse | null
  canSubmit: boolean
  problemSelected: boolean
  /** Sample tests from the statement — what the test cases are seeded from. */
  samples: ProblemSample[]
  /**
   * Identifies the open problem, e.g. "2258A". Changing it reseeds the test cases and clears
   * the results, so a previous problem's output cannot be read as this one's.
   */
  problemKey?: string
  /**
   * Identifies the problem to the code archive. Passed separately from `problemKey`, which is
   * an opaque change-detection token — the history panel needs the two parts to query with.
   */
  contest?: ContestRef
  problemIndex?: string
  /** Where the divider between editor and console is remembered. */
  storageKey?: string
  /** Label on the submit button — names the judge it goes to. */
  submitLabel?: string
}

/**
 * The right half of the workspace: write the code, run it, send it.
 *
 * Laid out the way every judge lays it out — editor above, console below, one draggable
 * divider between them — because that is the shape people already know how to use. Run and
 * Submit sit in the console's header rather than under the editor so they stay put as the
 * panes are resized, and so they are next to the output they produce.
 */
export function EditorPane({
  languages, languageId, onLanguageChange, source, onSourceChange,
  onSubmit, submitting, polling, verdict, canSubmit, problemSelected, samples, problemKey,
  contest, problemIndex, storageKey, submitLabel = 'Submit',
}: Props) {
  const fileInput = useRef<HTMLInputElement>(null)
  /** Explicit 'Run with' choice, overriding whatever the platform's label implies. */
  const [runtimeOverride, setRuntimeOverride] = useState<string | null>(null)
  const [fileName, setFileName] = useState<string | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)
  const [pickedLanguage, setPickedLanguage] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  /** Set when History is opened from the verdict chip, to land on that submission's tests. */
  const [focus, setFocus] = useState<{ id: number; tab: 'code' | 'tests' } | null>(null)

  const [cases, setCases] = useState<WorkCase[]>([newCase()])
  const [activeCase, setActiveCase] = useState(0)
  const [consoleTab, setConsoleTab] = useState<'testcase' | 'result'>('testcase')

  const run = useRunCode()
  const { data: runnerStatus } = useRunnerStatus()

  /*
   * The runtimes offered here are scoped to the event, when there is one.
   *
   * An examination restricted to C++ and Python must restrict Run as well as Submit. Doing it
   * only at Submit would leave the rule holding in the place a candidate touches once and not
   * in the place they touch every two minutes — and `contest` is already threaded in for the
   * history panel, so it costs nothing to ask with it. On Practice both are undefined and the
   * server answers with everything it can build, which is what Practice has always been.
   */
  const runScope = useMemo(
    () => (contest ? { platform: contest.platform, contestId: contest.id } : {}),
    [contest])
  const { data: runtimes } = useRunnerLanguages(runScope)

  /**
   * Reseed the cases from the statement's samples.
   *
   * Keyed on the sample text rather than on the array, which is rebuilt on every refetch.
   * Keying on identity would throw away an edited case each time the statement was refreshed —
   * on Compete, that is once a second.
   */
  const sampleKey = useMemo(
    () => samples.map(s => `${s.input}\u0000${s.output}`).join('\u0001'), [samples])

  const runRef = useRef(run)
  runRef.current = run

  useEffect(() => {
    setCases(samples.length > 0
      ? samples.map(s => newCase(s.input, s.output, true))
      : [newCase()])
    setActiveCase(0)
    setConsoleTab('testcase')
    runRef.current.reset()
    // Deliberately not depending on `samples`: sampleKey is its content, and the array is a
    // new object on every refetch.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [problemKey, sampleKey])

  const availableRuntimes = useMemo(
    () => (runtimes ?? []).filter(r => r.available), [runtimes])

  // What the platform's dropdown implies, if it has told us anything yet.
  const selectedLabel = languages.find(l => l.id === languageId)?.label
  const cfDetected = useMemo(() => detectLanguage(selectedLabel), [selectedLabel])

  /**
   * Which local runtime Run uses.
   *
   * Deliberately independent of the Codeforces language list. That list comes from
   * /api/practice/languages, which scrapes codeforces.com whenever a session is connected —
   * so with no internet it stalls until the request times out, and anything derived from it
   * stalls with it. Compiling is a purely local capability and must not wait on a remote
   * site to become available. When Codeforces has told us nothing, fall back to the first
   * runtime the backend reports it can actually run.
   */
  const runtimeId =
    runtimeOverride
    ?? cfDetected.runner
    ?? (languages.length === 0 ? availableRuntimes[0]?.id ?? null : null)

  const runtime = availableRuntimes.find(r => r.id === runtimeId) ?? null

  // Syntax mode follows what you are writing: the platform language when it maps to
  // something, otherwise whatever the runtime is. Writing Java that cannot be run locally
  // should still be highlighted as Java.
  const monacoLanguage =
    cfDetected.monaco !== 'plaintext' ? cfDetected.monaco
      : runtime?.editorLanguage ?? 'plaintext'

  const runnerOn = runnerStatus?.enabled !== false
  const canRun = runnerOn && !!runtime && !!source.trim() && !run.isPending && cases.length > 0

  /** Why Run is unavailable, when it is. Null when it can run. */
  const runBlockedReason = (): string | null => {
    if (!problemSelected) return 'Pick a problem first'
    if (!runnerOn) return 'Running locally is turned off on this deployment'
    if (!runtime) {
      if (availableRuntimes.length === 0) return 'No local toolchain is available to run code'
      return selectedLabel
        ? `${selectedLabel} cannot be run locally yet — you can still submit it`
        : 'Pick a language to run with'
    }
    if (!source.trim()) return 'Write some code first'
    if (cases.length === 0) return 'Add a test case first'
    return null
  }

  const doRun = () => {
    if (!runtime || cases.length === 0) return
    const tests: RunTestCase[] = cases.map((c, i) => ({
      input: c.input,
      expected: c.expected,
      label: `Case ${i + 1}`,
    }))
    setConsoleTab('result')
    run.mutate({ language: runtime.id, source, tests, ...runScope })
  }

  const loadFile = async (file: File) => {
    setFileError(null)
    setPickedLanguage(null)

    if (file.size > MAX_SOURCE_BYTES) {
      setFileError(`${file.name} is ${formatBytes(file.size)} — Codeforces caps source at 64 KB.`)
      return
    }

    let text: string
    try {
      text = await file.text()
    } catch {
      setFileError(`Could not read ${file.name}.`)
      return
    }

    // A NUL byte means this is a compiled binary or an image, not a source file.
    if (text.includes('\u0000')) {
      setFileError(`${file.name} doesn't look like source code.`)
      return
    }
    if (!text.trim()) {
      setFileError(`${file.name} is empty.`)
      return
    }

    onSourceChange(text)
    setFileName(file.name)

    const match = pickLanguageFor(file.name, languages)
    if (match && match !== languageId) {
      onLanguageChange(match)
      setPickedLanguage(languages.find(l => l.id === match)?.label ?? null)
    }
  }

  // Typing makes the filename a lie, so drop the badge as soon as the box is edited by hand.
  const handleTyping = (value: string) => {
    if (fileName) {
      setFileName(null)
      setPickedLanguage(null)
    }
    onSourceChange(value)
  }

  const canSend = problemSelected && canSubmit && !submitting && !!source.trim()

  const notes = (
    <>
      {fileError && (
        <p className="flex items-start gap-1.5 text-[11px] text-red-300">
          <XCircle size={12} className="flex-shrink-0 mt-0.5" /> {fileError}
        </p>
      )}
      {problemSelected && runnerOn && !runtime && selectedLabel && availableRuntimes.length > 0 && (
        <p className="text-[11px] text-gray-600">
          {selectedLabel} can be submitted but not run here — pick a toolchain under “Run with”,
          or submit it as-is.
        </p>
      )}
      {runnerStatus && !runnerStatus.enabled && (
        <p className="text-[11px] text-gray-600">
          Running locally is turned off on this deployment. Submitting still works.
        </p>
      )}
      {runnerStatus?.enabled && availableRuntimes.length === 0 && runtimes && (
        <p className="text-[11px] text-gray-600">
          No local toolchain was found, so code cannot be run here.
          {runtimes[0]?.unavailableReason ? ` ${runtimes[0].unavailableReason}` : ''}
        </p>
      )}
      {runnerStatus?.enabled && runnerStatus.isolated === false && (
        <p className="flex items-start gap-1.5 text-[11px] text-amber-300/70">
          <ShieldAlert size={12} className="flex-shrink-0 mt-0.5" />
          Code runs with time and memory limits but without filesystem isolation — bubblewrap
          was not available.
        </p>
      )}
    </>
  )

  const editorSection = (
    <div className="flex flex-col flex-1 min-w-0 min-h-0 bg-gray-950">
      <TabStrip
        tabs={[{ id: 'code', label: 'Code', icon: Code2 }]}
        active="code"
        onChange={() => {}}
        right={
          <>
            {fileName && (
              <span className="hidden lg:flex items-center gap-1 max-w-[10rem] px-2 py-1
                rounded-md bg-gray-800 text-[11px] text-gray-400">
                <span className="truncate font-mono">{fileName}</span>
                <button
                  onClick={() => { setFileName(null); setPickedLanguage(null); onSourceChange('') }}
                  title="Clear the editor"
                  className="text-gray-600 hover:text-gray-200 flex-shrink-0"
                >
                  <X size={11} />
                </button>
              </span>
            )}

            {availableRuntimes.length > 0 && (
              <label className="hidden min-w-0 items-center gap-1 text-[11px] text-gray-600
                lg:flex">
                <span className="flex-shrink-0">Run with</span>
                <select
                  value={runtime?.id ?? ''}
                  onChange={e => setRuntimeOverride(e.target.value || null)}
                  title="Which local toolchain compiles and runs the code. Independent of the
                         submit language, which only affects what gets sent to the judge."
                  className="min-w-0 flex-shrink rounded-md border border-gray-800 bg-gray-900
                             px-1.5 py-1 text-[11px] text-gray-400 outline-none
                             focus:border-indigo-600"
                >
                  {!runtime && <option value="">—</option>}
                  {availableRuntimes.map(r => (
                    <option key={r.id} value={r.id}>{r.displayName}</option>
                  ))}
                </select>
              </label>
            )}

            <select
              value={languageId}
              onChange={e => onLanguageChange(e.target.value)}
              title="The language your submission is sent as"
              className="min-w-0 max-w-[13rem] flex-shrink rounded-md border border-gray-800
                         bg-gray-900 px-2 py-1 text-xs text-gray-300 outline-none
                         focus:border-indigo-600"
            >
              {languages.length === 0 && <option value="">No languages loaded</option>}
              {languages.map(l => (
                <option key={l.id} value={l.id}>{l.label}</option>
              ))}
            </select>

            <input
              ref={fileInput}
              type="file"
              accept={ACCEPT}
              className="hidden"
              onChange={e => {
                const file = e.target.files?.[0]
                if (file) void loadFile(file)
                e.target.value = ''   // let the same file be picked again
              }}
            />
            <IconButton
              icon={<Upload size={13} />}
              label="Load a solution from a file"
              disabled={!problemSelected}
              onClick={() => fileInput.current?.click()}
            />
            <IconButton
              icon={<History size={13} />}
              label="Read code you submitted before"
              onClick={() => { setFocus(null); setHistoryOpen(true) }}
            />
          </>
        }
      />

      {/* Drop target wraps the editor rather than living on it: Monaco owns its own DOM
          and swallows drag events, so the handlers have to sit on an element outside it. */}
      <div
        className={clsx('relative flex-1 min-h-0', dragging && 'ring-2 ring-indigo-500 ring-inset')}
        onDragOver={e => {
          if (!problemSelected) return
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={e => {
          if (!problemSelected) return
          e.preventDefault()
          setDragging(false)
          const file = e.dataTransfer.files?.[0]
          if (file) void loadFile(file)
        }}
      >
        <CodeEditor
          value={source}
          onChange={handleTyping}
          language={monacoLanguage}
          readOnly={!problemSelected}
          onRun={() => { if (canRun) doRun() }}
          onSubmit={() => { if (canSend) onSubmit() }}
        />

        {!problemSelected && (
          <div className="absolute inset-0 flex items-center justify-center
                          bg-gray-950/60 pointer-events-none">
            <span className="text-sm text-gray-500">Pick a problem to start writing</span>
          </div>
        )}
        {dragging && (
          <div className="absolute inset-0 flex items-center justify-center
                          bg-gray-950/80 pointer-events-none">
            <span className="flex items-center gap-2 text-sm text-indigo-300">
              <Upload size={16} /> Drop to load
            </span>
          </div>
        )}
      </div>

      {pickedLanguage && (
        <p className="px-3 py-1 text-[11px] text-gray-600 border-t border-gray-800">
          Language set to {pickedLanguage} from the file you loaded.
        </p>
      )}
    </div>
  )

  const actions = (
    <>
      <button
        onClick={doRun}
        disabled={!canRun}
        title={runBlockedReason() ?? 'Run against these cases  (Ctrl+Enter)'}
        className="flex items-center gap-1.5 rounded-md border border-gray-700 bg-gray-800
                   px-2.5 py-1.5 text-xs font-medium text-gray-200 transition-colors
                   hover:bg-gray-700 disabled:opacity-40 disabled:cursor-not-allowed
                   disabled:hover:bg-gray-800"
      >
        {run.isPending
          ? <Loader2 size={13} className="animate-spin" />
          : <Play size={13} />}
        Run
      </button>

      <button
        onClick={onSubmit}
        disabled={!canSend}
        title={canSend ? 'Send this to the judge  (Ctrl+S)' : undefined}
        className="flex items-center gap-1.5 rounded-md bg-green-600 px-3 py-1.5 text-xs
                   font-medium text-white transition-colors hover:bg-green-500
                   disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-green-600"
      >
        {submitting
          ? <Loader2 size={13} className="animate-spin" />
          : <Send size={13} />}
        {submitLabel}
      </button>
    </>
  )

  return (
    <div className="flex flex-col flex-1 min-w-0 min-h-0">
      <SplitPane
        direction="vertical"
        storageKey={storageKey ? `${storageKey}.console` : undefined}
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
            result={run.data ?? null}
            running={run.isPending}
            error={run.isError
              ? ((run.error as any)?.response?.data?.message ?? 'Could not run the code.')
              : null}
            verdict={verdict}
            polling={polling}
            onShowSubmissionTests={() => {
              if (!verdict) return
              // The history panel matches on the judge's numeric submission id; both
              // judges number their submissions, they just report them as text.
              setFocus({ id: Number(verdict.submissionId), tab: 'tests' })
              setHistoryOpen(true)
            }}
            actions={actions}
            notes={notes}
            disabled={!problemSelected}
          />
        }
      />

      <SubmissionHistory
        open={historyOpen}
        onClose={() => { setHistoryOpen(false); setFocus(null) }}
        platform={contest?.platform ?? 'CODEFORCES'}
        contestId={contest?.id}
        problemIndex={problemIndex}
        currentSource={source}
        focusExternalId={focus?.id ?? null}
        focusTab={focus?.tab}
        onLoad={(loaded, loadedLanguageId) => {
          // The filename badge would be a lie about where this code came from.
          setFileName(null)
          setPickedLanguage(null)
          onSourceChange(loaded)
          // Only if the platform still offers that compiler — Codeforces retires them, and
          // selecting a dead id would fail at submit time rather than here.
          if (loadedLanguageId && languages.some(l => l.id === loadedLanguageId)) {
            onLanguageChange(loadedLanguageId)
          }
        }}
      />
    </div>
  )
}

function IconButton({ icon, label, onClick, disabled }: {
  icon: React.ReactNode
  label: string
  onClick: () => void
  disabled?: boolean
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={label}
      aria-label={label}
      className="rounded-md border border-gray-800 p-1.5 text-gray-400 transition-colors
                 hover:bg-gray-800 hover:text-gray-200 disabled:opacity-40
                 disabled:cursor-not-allowed disabled:hover:bg-transparent"
    >
      {icon}
    </button>
  )
}
