import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { clsx } from 'clsx'
import {
  Boxes, Columns2, Cpu, Eye, EyeOff, GitFork, GraduationCap, Loader2, Maximize2, Minimize2, Pencil,
  Play, Printer, Share2, StickyNote, Terminal, ZoomIn, ZoomOut,
} from 'lucide-react'

import { CodeEditor } from '@/components/editor/CodeEditor'
import { monaco } from '@/components/editor/monacoSetup'
import { SplitPane } from '@/components/workspace/SplitPane'
import { TabStrip } from '@/components/workspace/TabStrip'
import { useToast } from '@/components/common/Toaster'
import { EmptyState } from '@/components/common/States'
import { MemoryView } from '@/components/visualiser/MemoryView'
import { StructuresView } from '@/components/visualiser/StructuresView'
import { RecursionView } from '@/components/visualiser/RecursionView'
import { StepControls } from '@/components/visualiser/StepControls'
import { PredictPanel, type Score } from '@/components/visualiser/PredictPanel'
import { Handout } from '@/components/visualiser/Handout'
import { CompareDialog, type Comparison } from '@/components/visualiser/CompareDialog'
import { questionsFor, type Question } from '@/visualiser/predict'
import { handoutSteps } from '@/visualiser/handout'
import { notesFromLesson, type LessonNote } from '@/visualiser/lessons'
import { useMediaQuery, WIDE } from '@/hooks/useMediaQuery'
import { MAX_STEPS, traceProgram } from '@/visualiser/engine'
import { EXAMPLES, LANGUAGE_LABELS, STARTER } from '@/visualiser/examples'
import {
  lineCounts, nextBreakpoint, nextRunOfLine, prevBreakpoint, stepBackOver, stepOver,
} from '@/visualiser/navigate'
import { MAX_LINK_CHARS, decodeShare, encodeShare } from '@/visualiser/share'
import { brief, buildCallTree, type CallNode, type Trace, type VizLanguage } from '@/visualiser/trace'

type ViewTab = 'memory' | 'structures' | 'recursion' | 'output'
type Editor = monaco.editor.IStandaloneCodeEditor

const DRAFT_KEY = (l: VizLanguage) => `cpintel-viz-draft-${l}`
const readDraft = (l: VizLanguage) => {
  try { return localStorage.getItem(DRAFT_KEY(l)) ?? STARTER[l] } catch { return STARTER[l] }
}
const writeDraft = (l: VizLanguage, code: string) => {
  try { localStorage.setItem(DRAFT_KEY(l), code) } catch { /* private mode: drafts are a convenience */ }
}

/**
 * The code visualiser: write or paste a program, run it under a tracer in this browser, and
 * step through it — forwards, backwards, by call, by breakpoint — watching the stack, the heap,
 * the data structures and the recursion tree change. Teachers can pin notes to steps, present it
 * full screen and share the whole thing as a link.
 */
export default function VisualisePage() {
  const wide = useMediaQuery(WIDE)
  const toast = useToast(s => s.push)
  const rootRef = useRef<HTMLDivElement>(null)

  const [language, setLanguage] = useState<VizLanguage>('python')
  const [code, setCode] = useState(() => readDraft('python'))
  const [stdin, setStdin] = useState('')
  const [trace, setTrace] = useState<Trace | null>(null)
  const [step, setStep] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [speed, setSpeed] = useState(1)
  const [progress, setProgress] = useState<string | null>(null)
  const [running, setRunning] = useState(false)
  const [tab, setTab] = useState<ViewTab>('memory')
  const [notes, setNotes] = useState<Record<number, string>>({})
  const [editingNote, setEditingNote] = useState(false)
  const [breakpoints, setBreakpoints] = useState<Set<number>>(new Set())
  const [zoom, setZoom] = useState(1)
  const [showFunctions, setShowFunctions] = useState(false)
  const [presenting, setPresenting] = useState(false)
  const [editor, setEditor] = useState<Editor | null>(null)
  const [fromBreakpoint, setFromBreakpoint] = useState(false)
  // Teaching: predict mode, a second run to compare with, the handout, an example's lesson.
  const [predict, setPredict] = useState(false)
  const [quiz, setQuiz] = useState<{ from: number; questions: Question[] } | null>(null)
  const [score, setScore] = useState<Score>({ asked: 0, right: 0 })
  const [compare, setCompare] = useState<Comparison | null>(null)
  const [compareOpen, setCompareOpen] = useState(false)
  const [handout, setHandout] = useState(false)
  const [lesson, setLesson] = useState<{ id: string; code: string; notes: LessonNote[] } | null>(null)

  const steps = useMemo(() => trace?.steps ?? [], [trace])
  // In Compare both runs share one step number; the shorter one waits at its end.
  const total = Math.max(steps.length, compare?.trace.steps.length ?? 0)
  const stepA = Math.min(step, steps.length - 1)
  const current = steps[stepA]
  const prev = stepA > 0 ? steps[stepA - 1] : undefined
  const tree = useMemo(() => (trace ? buildCallTree(trace) : null), [trace])
  const treeB = useMemo(() => (compare ? buildCallTree(compare.trace) : null), [compare])
  const counts = useMemo(() => (trace ? lineCounts(trace) : new Map<number, number>()), [trace])
  const goto = useCallback((i: number) => {
    setStep(Math.max(0, Math.min(i, Math.max(trace?.steps.length ?? 1, compare?.trace.steps.length ?? 0) - 1)))
  }, [trace, compare])

  // ── running ─────────────────────────────────────────────────────────────

  const run = useCallback(async (opts?: {
    language?: VizLanguage; code?: string; stdin?: string; step?: number; startLines?: number[]
  }) => {
    const lang = opts?.language ?? language
    const src = opts?.code ?? code
    const startLines = opts?.startLines ?? (fromBreakpoint ? [...breakpoints] : [])
    setRunning(true)
    setPlaying(false)
    const result = await traceProgram({ language: lang, source: src, stdin: opts?.stdin ?? stdin, startLines },
      setProgress)
    if (startLines.length && result.steps.length === 0 && !result.error) {
      result.error = 'No breakpoint line ran, so nothing was recorded. Untick "Start at the first '
        + 'breakpoint", or put a breakpoint on a line that runs.'
    }
    setRunning(false)
    setTrace(result)
    setQuiz(null)
    setScore({ asked: 0, right: 0 })
    // An example's lesson notes land on their steps — unless the code was changed since.
    if (lesson && lesson.code === src && !opts?.step) setNotes(notesFromLesson(result, lesson.notes))
    setStep(Math.max(0, Math.min(opts?.step ?? 0, result.steps.length - 1)))
    if (result.steps.length === 0) setTab('output')
  }, [language, code, stdin, fromBreakpoint, breakpoints, lesson])

  const edit = () => {
    setTrace(null); setPlaying(false); setEditingNote(false); setQuiz(null); setCompare(null); setHandout(false)
  }

  const switchLanguage = (l: VizLanguage) => {
    if (l === language) return
    writeDraft(language, code)
    setLanguage(l)
    setCode(readDraft(l))
    setStdin('')
    edit()
    setNotes({})
    setBreakpoints(new Set())
  }

  const loadExample = (id: string) => {
    const ex = EXAMPLES.find(e => e.id === id)
    if (!ex) return
    writeDraft(language, code)
    setLanguage(ex.language)
    setCode(ex.code)
    setStdin(ex.stdin)
    setNotes({})
    setBreakpoints(new Set())
    setLesson(ex.lesson ? { id: ex.id, code: ex.code, notes: ex.lesson } : null)
    edit()
  }

  useEffect(() => { writeDraft(language, code) }, [language, code])

  // A shared link: restore everything in it and run it once.
  useEffect(() => {
    if (!window.location.hash) return
    let cancelled = false
    decodeShare(window.location.hash).then(shared => {
      if (cancelled) return
      if (!shared) { toast('error', 'That visualisation link is damaged or incomplete.'); return }
      setLanguage(shared.language)
      setCode(shared.code)
      setStdin(shared.stdin)
      setNotes(shared.notes)
      setBreakpoints(new Set(shared.breakpoints))
      setFromBreakpoint(shared.fromBreakpoint)
      setPredict(shared.predict)
      run({ language: shared.language, code: shared.code, stdin: shared.stdin, step: shared.step,
        startLines: shared.fromBreakpoint ? shared.breakpoints : [] })
    })
    return () => { cancelled = true }
    // Once, on arrival.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ── playing ─────────────────────────────────────────────────────────────

  useEffect(() => {
    if (!playing || !trace) return
    if (step >= total - 1) { setPlaying(false); return }
    const id = window.setTimeout(() => {
      const next = step + 1
      setStep(next)
      // Playing stops at a breakpoint, as a debugger's Continue does.
      const s = trace.steps[next]
      if (breakpoints.size && s?.event === 'line' && breakpoints.has(s.line)) setPlaying(false)
    }, 650 / speed)
    return () => window.clearTimeout(id)
  }, [playing, step, speed, trace, breakpoints, total])

  /** One step forward; in predict mode, a quiz first when the step changes something. */
  const forward = useCallback(() => {
    if (!trace) return
    if (predict && !compare && step < steps.length - 1) {
      const questions = questionsFor(steps[step], steps[step + 1])
      if (questions.length) { setPlaying(false); setQuiz({ from: step, questions }); return }
    }
    goto(step + 1)
  }, [trace, predict, compare, step, steps, goto])

  // Moving anywhere else abandons a quiz that is open.
  useEffect(() => { if (quiz && quiz.from !== step) setQuiz(null) }, [quiz, step])

  const jumpNext = useCallback(() => {
    if (!trace) return
    goto(breakpoints.size ? nextBreakpoint(trace, step, breakpoints) : stepOver(trace, step))
  }, [trace, step, breakpoints, goto])
  const jumpBack = useCallback(() => {
    if (!trace) return
    goto(breakpoints.size ? prevBreakpoint(trace, step, breakpoints) : stepBackOver(trace, step))
  }, [trace, step, breakpoints, goto])

  // Arrow keys, Space, Home and End drive the trace — except while typing somewhere.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!trace || editingNote) return
      const t = e.target as HTMLElement | null
      if (t?.closest('input, textarea, select, [contenteditable], .monaco-editor')) return
      if (e.key === 'ArrowRight') { e.preventDefault(); if (e.shiftKey) jumpNext(); else if (!quiz) forward() }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); if (e.shiftKey) jumpBack(); else goto(step - 1) }
      else if (e.key === ' ') { e.preventDefault(); setPlaying(p => !p) }
      else if (e.key === 'Home') { e.preventDefault(); goto(0) }
      else if (e.key === 'End') { e.preventDefault(); goto(total - 1) }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [trace, step, total, editingNote, goto, jumpNext, jumpBack, forward, quiz])

  // ── the editor: current line, breakpoints, run counts ─────────────────────

  const live = useRef({ trace, step, goto })
  live.current = { trace, step, goto }

  const onEditor = useCallback((ed: Editor) => {
    ed.updateOptions({ glyphMargin: true })
    ed.onMouseDown(e => {
      const line = e.target.position?.lineNumber
      if (!line) return
      const T = monaco.editor.MouseTargetType
      if (e.target.type === T.GUTTER_GLYPH_MARGIN || e.target.type === T.GUTTER_LINE_NUMBERS) {
        setBreakpoints(b => {
          const next = new Set(b)
          if (next.has(line)) next.delete(line); else next.add(line)
          return next
        })
      } else if (e.target.type === T.CONTENT_TEXT && live.current.trace) {
        // Clicking a line while stepping jumps to the next time it runs.
        const j = nextRunOfLine(live.current.trace, live.current.step, line)
        if (j != null) live.current.goto(j)
      }
    })
    setEditor(ed)
  }, [])

  const decorations = useRef<monaco.editor.IEditorDecorationsCollection | null>(null)
  useEffect(() => {
    if (!editor) return
    decorations.current ??= editor.createDecorationsCollection()
    const list: monaco.editor.IModelDeltaDecoration[] = []
    for (const line of breakpoints) {
      list.push({ range: new monaco.Range(line, 1, line, 1),
        options: { glyphMarginClassName: 'viz-bp', glyphMarginHoverMessage: { value: 'Breakpoint' } } })
    }
    if (trace) {
      const model = editor.getModel()
      for (const [line, n] of counts) {
        if (!model || line > model.getLineCount()) continue
        const end = model.getLineMaxColumn(line)
        list.push({ range: new monaco.Range(line, end, line, end),
          // showIfCollapsed: the range is empty (the end of the line), which Monaco
          // otherwise does not draw.
          options: { showIfCollapsed: true, after: { content: `  ×${n}`, inlineClassName: 'viz-count' } } })
      }
      if (prev && prev.line !== current?.line) {
        list.push({ range: new monaco.Range(prev.line, 1, prev.line, 1),
          options: { isWholeLine: true, className: 'viz-line-prev' } })
      }
      if (current) {
        list.push({ range: new monaco.Range(current.line, 1, current.line, 1),
          options: { isWholeLine: true,
            className: current.event === 'exception' ? 'viz-line-error' : 'viz-line-current',
            glyphMarginClassName: breakpoints.has(current.line) ? 'viz-bp viz-arrow' : 'viz-arrow' } })
        editor.revealLineInCenterIfOutsideViewport(current.line)
      }
      if (trace.errorLine && step === steps.length - 1) {
        list.push({ range: new monaco.Range(trace.errorLine, 1, trace.errorLine, 1),
          options: { isWholeLine: true, className: 'viz-line-error' } })
      }
    }
    decorations.current.set(list)
  }, [editor, trace, step, current, prev, breakpoints, counts, steps.length])

  useEffect(() => {
    editor?.updateOptions({ fontSize: presenting ? 17 : 13 })
  }, [editor, presenting])

  // ── teaching: present and share ──────────────────────────────────────────

  useEffect(() => {
    const onChange = () => {
      const on = document.fullscreenElement === rootRef.current
      setPresenting(on)
      setZoom(on ? 1.3 : 1)
    }
    document.addEventListener('fullscreenchange', onChange)
    return () => document.removeEventListener('fullscreenchange', onChange)
  }, [])

  const togglePresent = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen()
      else await rootRef.current?.requestFullscreen()
    } catch {
      toast('error', 'This browser would not go full screen.')
    }
  }

  const share = async () => {
    try {
      const encoded = await encodeShare({
        language, code, stdin, notes, breakpoints: [...breakpoints], step, fromBreakpoint, predict,
      })
      const url = `${window.location.origin}/visualise#${encoded}`
      if (url.length > MAX_LINK_CHARS) {
        toast('error', 'This program is too long to share as a link.')
        return
      }
      await navigator.clipboard.writeText(url)
      toast('success', 'Link copied. It opens at this step, with your notes and breakpoints.')
    } catch {
      toast('error', 'Could not copy the link.')
    }
  }

  // ── layout ─────────────────────────────────────────────────────────────

  const languageSelect = (
    <select value={language} onChange={e => switchLanguage(e.target.value as VizLanguage)}
      aria-label="Language" disabled={running}
      className="rounded-md border border-gray-700 bg-gray-950 px-2 py-1 text-xs text-gray-200">
      {(Object.keys(LANGUAGE_LABELS) as VizLanguage[]).map(l =>
        <option key={l} value={l}>{LANGUAGE_LABELS[l]}</option>)}
    </select>
  )

  const editorPane = (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col bg-gray-950">
      <TabStrip tabs={[{ id: 'code', label: trace ? 'Code (stepping)' : 'Code', icon: Cpu }]}
        active="code" onChange={() => {}}
        right={
          <>
            {!trace && (
              <select value="" onChange={e => loadExample(e.target.value)} aria-label="Load an example"
                className="max-w-36 rounded-md border border-gray-700 bg-gray-950 px-2 py-1 text-xs text-gray-300">
                <option value="" disabled>Examples…</option>
                {[...new Set(EXAMPLES.map(e => e.topic))].map(topic => (
                  <optgroup key={topic} label={topic}>
                    {EXAMPLES.filter(e => e.topic === topic).map(e =>
                      <option key={e.id} value={e.id}>{e.title} ({LANGUAGE_LABELS[e.language]}){e.lesson ? ' · notes' : ''}</option>)}
                  </optgroup>
                ))}
              </select>
            )}
            {languageSelect}
            {trace ? (
              <button type="button" onClick={edit} className="btn-secondary btn-sm">
                <Pencil size={13} /> Edit
              </button>
            ) : (
              <button type="button" onClick={() => run()} disabled={running} className="btn-primary btn-sm">
                {running ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
                Visualise
              </button>
            )}
          </>
        } />
      <div className="relative min-h-0 flex-1">
        <CodeEditor value={code} onChange={setCode} language={language === 'python' ? 'python' : 'cpp'}
          readOnly={!!trace || running} onRun={() => { if (!trace) run() }} onEditor={onEditor} />
      </div>
      <div className="shrink-0 border-t border-gray-800 bg-gray-900 px-3 py-2">
        <label className="field-label" htmlFor="viz-stdin">Input (stdin)</label>
        <textarea id="viz-stdin" value={stdin} onChange={e => setStdin(e.target.value)} rows={2}
          readOnly={!!trace} spellCheck={false}
          placeholder="What the program reads, if anything"
          className="input resize-y font-mono text-xs" />
        <label className={clsx('mt-1.5 flex items-center gap-1.5 text-[11px]',
          breakpoints.size ? 'text-gray-400' : 'text-gray-600')}>
          <input type="checkbox" checked={fromBreakpoint && breakpoints.size > 0} disabled={!breakpoints.size || !!trace}
            onChange={e => setFromBreakpoint(e.target.checked)} />
          <span title={`Skips the set-up before it, so a long run fits in the ${MAX_STEPS} recorded steps`}>
            Start recording at the first breakpoint
          </span>
        </label>
        <p className="mt-1.5 text-[11px] text-gray-500">
          {trace
            ? 'Click a line to jump to its next run. Click the gutter to set a breakpoint.'
            : <>Runs in this browser. Click the gutter for breakpoints. <span className="kbd">Ctrl</span>+<span className="kbd">Enter</span> visualises.</>}
        </p>
      </div>
    </div>
  )

  const output = trace ? trace.stdout.slice(0, current ? current.out : trace.stdout.length) : ''
  const atEnd = !!trace && step >= steps.length - 1
  const example = lesson ? EXAMPLES.find(e => e.id === lesson.id) : undefined

  /** One run's view for the selected tab, at step i (Compare draws two of these). */
  const view = (t: Trace, i: number, src: string, callTree: CallNode | null, onStep: (j: number) => void) => {
    const at = Math.min(i, t.steps.length - 1)
    const s = t.steps[at]
    const before = at > 0 ? t.steps[at - 1] : undefined
    if (tab === 'output' || !s) {
      const shown = t.stdout.slice(0, s ? s.out : t.stdout.length)
      return (
        <div className="h-full overflow-auto p-4">
          {t.error && (at >= t.steps.length - 1) && (
            <pre className="mb-3 whitespace-pre-wrap rounded-md border border-red-800/70 bg-red-950/40 p-3 font-mono text-xs text-red-200">
              {t.error}{t.errorLine ? ` (line ${t.errorLine})` : ''}
            </pre>
          )}
          <h3 className="mb-1 text-[11px] font-medium uppercase tracking-wide text-gray-500">Output so far</h3>
          <pre className="min-h-16 whitespace-pre-wrap rounded-md border border-gray-800 bg-gray-900 p-3 font-mono text-xs text-gray-200"
            style={{ zoom }}>
            {shown || <span className="text-gray-600">nothing printed yet</span>}
          </pre>
        </div>
      )
    }
    if (tab === 'memory') return <MemoryView step={s} prev={before} showFunctions={showFunctions} zoom={zoom} />
    if (tab === 'structures') {
      return <StructuresView step={s} prev={before} zoom={zoom}
        lineText={s.event === 'line' ? src.split('\n')[s.line - 1] : undefined} />
    }
    return <RecursionView tree={callTree} trace={t} step={at} onStep={onStep} zoom={zoom} />
  }

  const viewPane = (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col bg-gray-950">
      <TabStrip
        tabs={[
          { id: 'memory', label: 'Memory', icon: Cpu },
          { id: 'structures', label: 'Structures', icon: Boxes },
          { id: 'recursion', label: 'Calls', icon: GitFork },
          { id: 'output', label: 'Output', icon: Terminal,
            badge: trace?.error ? <span className="ml-0.5 h-1.5 w-1.5 rounded-full bg-red-400" /> : undefined },
        ]}
        active={tab} onChange={id => setTab(id as ViewTab)}
        right={
          <>
            <button type="button" className="btn-ghost btn-sm" onClick={() => setZoom(z => Math.max(0.6, z - 0.1))}
              aria-label="Zoom out" title="Zoom out"><ZoomOut size={14} /></button>
            <button type="button" className="btn-ghost btn-sm" onClick={() => setZoom(z => Math.min(2, z + 0.1))}
              aria-label="Zoom in" title="Zoom in"><ZoomIn size={14} /></button>
            {tab === 'memory' && language === 'python' && (
              <button type="button" className="btn-ghost btn-sm" onClick={() => setShowFunctions(s => !s)}
                aria-pressed={showFunctions} title={showFunctions ? 'Hide functions and classes' : 'Show functions and classes'}>
                {showFunctions ? <Eye size={14} /> : <EyeOff size={14} />}
              </button>
            )}
            <button type="button" className={clsx('btn-ghost btn-sm', editingNote && 'text-accent-300')}
              onClick={() => setEditingNote(e => !e)} disabled={!trace}
              aria-pressed={editingNote} title="Add a note to this step"><StickyNote size={14} /></button>
            <button type="button" className={clsx('btn-ghost btn-sm', predict && 'bg-sky-950/60 text-sky-300')}
              onClick={() => { setPredict(p => !p); setQuiz(null) }} aria-pressed={predict}
              title={predict ? 'Predict mode is on: stepping forward asks first' : 'Predict mode: guess each step before it runs'}>
              <GraduationCap size={14} />
            </button>
            <button type="button" className={clsx('btn-ghost btn-sm', compare && 'bg-accent-400/10 text-accent-300')}
              onClick={() => (compare ? setCompare(null) : setCompareOpen(true))} disabled={!trace || steps.length === 0}
              aria-pressed={!!compare} title={compare ? 'Stop comparing' : 'Compare with a second run, side by side'}>
              <Columns2 size={14} />
            </button>
            <button type="button" className="btn-ghost btn-sm" onClick={() => setHandout(true)}
              disabled={!trace || steps.length === 0} title="Handout: print or save key steps as PDF">
              <Printer size={14} />
            </button>
            <button type="button" className="btn-ghost btn-sm" onClick={share}
              title="Copy a link to this visualisation"><Share2 size={14} /></button>
            <button type="button" className="btn-ghost btn-sm" onClick={togglePresent}
              title={presenting ? 'Leave presentation' : 'Present full screen'}>
              {presenting ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
            </button>
          </>
        } />

      {trace && steps.length > 0 && (
        <StepControls step={step} count={total} current={current} playing={playing} speed={speed}
          onStep={i => (i === step + 1 ? forward() : goto(i))} onPlay={setPlaying} onSpeed={setSpeed}
          onBreakBack={jumpBack} onBreakNext={jumpNext} hasBreakpoints={breakpoints.size > 0} />
      )}

      {quiz && current && (
        <PredictPanel key={quiz.from} line={current.line} questions={quiz.questions} score={score}
          onScored={(right, asked) => setScore(sc => ({ asked: sc.asked + asked, right: sc.right + right }))}
          onContinue={() => { setQuiz(null); goto(quiz.from + 1) }}
          onSkip={() => { setQuiz(null); goto(quiz.from + 1) }} />
      )}

      {predict && !quiz && trace && score.asked > 0 && (
        <p className="shrink-0 border-b border-sky-900/50 bg-sky-950/20 px-3 py-1 text-[11px] text-sky-300">
          Predict mode · {score.right} of {score.asked} right so far
        </p>
      )}

      {trace && !quiz && (editingNote || notes[step]) && (
        <NoteBar text={notes[step] ?? ''} editing={editingNote}
          onChange={text => setNotes(n => {
            const next = { ...n }
            if (text.trim()) next[step] = text; else delete next[step]
            return next
          })}
          onDone={() => setEditingNote(false)}
          noteCount={Object.keys(notes).length}
          onNextNote={() => {
            const later = Object.keys(notes).map(Number).filter(s => s > step).sort((a, b) => a - b)
            if (later.length) goto(later[0])
          }} />
      )}

      {trace?.truncated && atEnd && (
        <p className="shrink-0 border-b border-amber-800/60 bg-amber-950/40 px-3 py-1.5 text-xs text-amber-200">
          Stopped after {steps.length} steps. Visualise a smaller input, or a shorter loop, to see the rest.
        </p>
      )}

      <div className="min-h-0 flex-1">
        {running ? (
          <div role="status" className="flex h-full items-center justify-center gap-2 text-sm text-gray-400">
            <Loader2 size={16} className="animate-spin text-accent-400" /> {progress ?? 'Starting…'}
          </div>
        ) : !trace ? (
          <EmptyState icon={<Play size={18} />} title="Write some code, then Visualise"
            className="h-full justify-center">
            Step through it line by line, forwards and back, and watch variables, objects, pointers,
            data structures and recursive calls change. Pick an example to start.
          </EmptyState>
        ) : compare ? (
          <div className="flex h-full flex-col">
            <CompareSummary a={trace} b={compare.trace} bTitle={compare.title} />
            <div className="grid min-h-0 flex-1 grid-cols-1 divide-gray-800 max-md:divide-y md:grid-cols-2 md:divide-x">
              {[
                { label: example?.title ?? 'This code', t: trace, src: code, ct: tree },
                { label: compare.title, t: compare.trace, src: compare.code, ct: treeB },
              ].map(({ label, t, src, ct }, k) => {
                const at = Math.min(step, t.steps.length - 1)
                const s = t.steps[at]
                return (
                  <section key={k} className="flex min-h-0 flex-col" aria-label={`Run ${k === 0 ? 'A' : 'B'}: ${label}`}>
                    <header className="flex shrink-0 items-center gap-2 border-b border-gray-800 bg-gray-900/60 px-3 py-1.5">
                      <span className="rounded-sm bg-gray-800 px-1.5 font-mono text-[11px] text-gray-300">{k === 0 ? 'A' : 'B'}</span>
                      <span className="truncate text-xs font-medium text-gray-200">{label}</span>
                      <code className="min-w-0 flex-1 truncate font-mono text-[11px] text-gray-500">
                        {s ? `${s.line}: ${src.split('\n')[s.line - 1]?.trim() ?? ''}` : ''}
                      </code>
                      <span className={clsx('shrink-0 font-mono text-[11px]', step >= t.steps.length - 1 ? 'text-green-300' : 'text-gray-400')}>
                        {step >= t.steps.length - 1 ? `finished in ${t.steps.length}` : `${at + 1}/${t.steps.length}`}
                      </span>
                    </header>
                    <div className="min-h-0 flex-1">{view(t, step, src, ct, goto)}</div>
                  </section>
                )
              })}
            </div>
          </div>
        ) : (
          view(trace, step, code, tree, goto)
        )}
      </div>

      {trace && tab !== 'output' && steps.length > 0 && !compare && (
        <div className="max-h-24 shrink-0 overflow-auto border-t border-gray-800 bg-gray-900 px-3 py-1.5">
          <span className="mr-2 text-[10px] uppercase tracking-wide text-gray-500">Output</span>
          <span className="whitespace-pre-wrap font-mono text-xs text-gray-200">{output}</span>
          {trace.error && atEnd && <span className="ml-2 font-mono text-xs text-red-300">{trace.error}</span>}
        </div>
      )}
    </div>
  )

  return (
    <div ref={rootRef} className="flex min-h-0 flex-1 flex-col bg-gray-950">
      {/* What changed, for screen readers: the step, the line, and the variables that moved. */}
      <div className="sr-only" aria-live="polite" aria-atomic="true" data-testid="viz-announcer">
        {trace && current ? announce(step, steps.length, current, prev) : ''}
      </div>
      <SplitPane className="min-h-0 flex-1" direction={wide ? 'horizontal' : 'vertical'} storageKey={wide ? 'cpintel-viz-split' : 'cpintel-viz-split-v'}
        initial={wide ? 40 : 45} min={20} max={75} first={editorPane} second={viewPane} />
      {compareOpen && (
        <CompareDialog open onClose={() => setCompareOpen(false)} language={language} code={code} stdin={stdin}
          suggested={example?.compareWith}
          onReady={c => { setCompare(c); setCompareOpen(false); setQuiz(null); setStep(0) }} />
      )}
      {handout && trace && (
        <Handout trace={trace} code={code} notes={notes}
          steps={handoutSteps(trace, notes, breakpoints)} onClose={() => setHandout(false)} />
      )}
    </div>
  )
}

/** How the two runs differ in work: steps taken and the busiest line. */
function CompareSummary({ a, b, bTitle }: { a: Trace; b: Trace; bTitle: string }) {
  const busiest = (t: Trace) => {
    let best: [number, number] = [0, 0]
    for (const [line, n] of lineCounts(t)) if (n > best[1]) best = [line, n]
    return best
  }
  const [la, na] = busiest(a)
  const [lb, nb] = busiest(b)
  const diff = b.steps.length - a.steps.length
  return (
    <p className="shrink-0 border-b border-gray-800 bg-gray-900 px-3 py-1.5 text-xs text-gray-400">
      A took <span className="font-mono text-gray-200">{a.steps.length}</span> steps
      (busiest: line {la}, ×{na}); B, {bTitle}, took <span className="font-mono text-gray-200">{b.steps.length}</span>
      {' '}(busiest: line {lb}, ×{nb}).{' '}
      {diff === 0 ? 'The same amount of work.'
        : <>B did <span className="font-mono text-gray-200">{Math.abs(Math.round((diff / a.steps.length) * 100))}%</span> {diff < 0 ? 'fewer' : 'more'} steps.</>}
    </p>
  )
}

function announce(i: number, n: number, s: Trace['steps'][number], before?: Trace['steps'][number]): string {
  const top = s.frames[s.frames.length - 1]
  const was = new Map(before?.frames.find(f => f.id === top?.id)?.vars.map(([k, v]) => [k, JSON.stringify(v)]) ?? [])
  const changed = (top?.vars ?? []).filter(([k, v]) => was.get(k) !== JSON.stringify(v)).slice(0, 3)
    .map(([k, v]) => `${k} is ${brief(v, s.heap)}`)
  const what = s.event === 'call' ? `call ${top?.name}` : s.event === 'return' ? `${top?.name} returns ${s.ret ? brief(s.ret, s.heap) : ''}`
    : s.event === 'exception' ? s.error ?? 'exception' : `line ${s.line}`
  return `Step ${i + 1} of ${n}, ${what}.${changed.length ? ` ${changed.join(', ')}.` : ''}`
}

function NoteBar({ text, editing, onChange, onDone, noteCount, onNextNote }: {
  text: string
  editing: boolean
  onChange: (t: string) => void
  onDone: () => void
  noteCount: number
  onNextNote: () => void
}) {
  return (
    <div className="shrink-0 border-b border-amber-800/50 bg-amber-950/30 px-3 py-2">
      {editing ? (
        <div className="flex items-start gap-2">
          <textarea autoFocus value={text} onChange={e => onChange(e.target.value)} rows={2}
            placeholder="A note for this step: what to notice, a question to ask the class…"
            onKeyDown={e => { if (e.key === 'Escape' || (e.key === 'Enter' && (e.ctrlKey || e.metaKey))) onDone() }}
            className="input flex-1 resize-y text-sm" aria-label="Note for this step" />
          <button type="button" onClick={onDone} className="btn-secondary btn-sm">Done</button>
        </div>
      ) : (
        <div className="flex items-start gap-2">
          <StickyNote size={14} className="mt-0.5 shrink-0 text-amber-300" aria-hidden="true" />
          <p className="flex-1 whitespace-pre-wrap text-sm text-amber-100">{text}</p>
          {noteCount > 1 && (
            <button type="button" onClick={onNextNote} className="btn-ghost btn-sm text-amber-200">Next note</button>
          )}
        </div>
      )}
    </div>
  )
}
