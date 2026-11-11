import { useState } from 'react'
import { Loader2, Play } from 'lucide-react'
import { Dialog } from '@/components/common/Dialog'
import { CodeEditor } from '@/components/editor/CodeEditor'
import { traceProgram } from '@/visualiser/engine'
import { EXAMPLES } from '@/visualiser/examples'
import type { Trace, VizLanguage } from '@/visualiser/trace'

export interface Comparison { trace: Trace; code: string; stdin: string; title: string }

/**
 * Sets up the second run for Compare: the same program on another input, another algorithm on
 * the same input, or an example (the one the current example suggests comes first).
 */
export function CompareDialog({ open, onClose, language, code, stdin, suggested, onReady }: {
  open: boolean
  onClose: () => void
  language: VizLanguage
  code: string
  stdin: string
  /** An example that pairs well with the one loaded (insertion sort for bubble sort). */
  suggested?: string
  onReady: (c: Comparison) => void
}) {
  const start = EXAMPLES.find(e => e.id === suggested)
  const [codeB, setCodeB] = useState(start?.code ?? code)
  const [stdinB, setStdinB] = useState(start?.stdin ?? stdin)
  const [title, setTitle] = useState(start?.title ?? 'Second run')
  const [running, setRunning] = useState(false)
  const [progress, setProgress] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const run = async () => {
    setRunning(true)
    setError(null)
    const trace = await traceProgram({ language, source: codeB, stdin: stdinB }, setProgress)
    setRunning(false)
    if (trace.steps.length === 0) {
      setError(trace.error ?? 'The second program produced no steps.')
      return
    }
    onReady({ trace, code: codeB, stdin: stdinB, title })
  }

  const same = EXAMPLES.filter(e => e.language === language)
  return (
    <Dialog open={open} onClose={onClose} size="lg" dismissible={!running}
      title="Compare with a second run"
      description="Both runs then step together, side by side. Use the same code with another input, or another algorithm."
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose} disabled={running}>Cancel</button>
          <button type="button" className="btn-primary" onClick={run} disabled={running}>
            {running ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} />}
            {running ? progress ?? 'Tracing…' : 'Visualise both'}
          </button>
        </>
      }>
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col">
            <span className="field-label">Start from</span>
            <select className="input py-1.5 text-sm" value="" onChange={e => {
              const id = e.target.value
              if (id === 'same') { setCodeB(code); setStdinB(stdin); setTitle('Same code, other input'); return }
              const ex = EXAMPLES.find(x => x.id === id)
              if (ex) { setCodeB(ex.code); setStdinB(ex.stdin); setTitle(ex.title) }
            }} aria-label="Start the second run from">
              <option value="" disabled>Choose…</option>
              <option value="same">This code (change the input below)</option>
              {same.map(e => (
                <option key={e.id} value={e.id}>{e.title}{e.id === suggested ? ' (suggested)' : ''}</option>
              ))}
            </select>
          </label>
          <label className="flex min-w-48 flex-1 flex-col">
            <span className="field-label">Name</span>
            <input className="input py-1.5 text-sm" value={title} onChange={e => setTitle(e.target.value)} />
          </label>
        </div>
        <div className="h-72 overflow-hidden rounded-md border border-gray-800">
          <CodeEditor value={codeB} onChange={setCodeB} language={language === 'python' ? 'python' : 'cpp'}
            readOnly={running} onRun={run} />
        </div>
        <label className="flex flex-col">
          <span className="field-label">Input (stdin)</span>
          <textarea className="input resize-y font-mono text-xs" rows={2} value={stdinB}
            onChange={e => setStdinB(e.target.value)} spellCheck={false} />
        </label>
        {error && (
          <pre className="whitespace-pre-wrap rounded-md border border-red-800/70 bg-red-950/40 p-2 font-mono text-xs text-red-200">{error}</pre>
        )}
      </div>
    </Dialog>
  )
}
