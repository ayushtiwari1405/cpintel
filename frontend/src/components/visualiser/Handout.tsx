import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { Printer, X } from 'lucide-react'
import { MemoryView } from './MemoryView'
import { StructuresView } from './StructuresView'
import type { Trace } from '@/visualiser/trace'
import { structureVars } from '@/visualiser/structures'
import { LANGUAGE_LABELS } from '@/visualiser/examples'

/**
 * A printable handout of a visualisation: the code, then each chosen step with its line, the
 * teacher's note, the output so far and a drawing of memory — the data-structure view where
 * the step has one, the stack and heap otherwise. Shown first as a preview; printing (or "Save
 * as PDF" in the print dialog) prints only this, in the light theme, which is what paper wants.
 */
export function Handout({ trace, code, steps, notes, onClose }: {
  trace: Trace
  code: string
  steps: number[]
  notes: Record<number, string>
  onClose: () => void
}) {
  useEffect(() => {
    // Dark surfaces waste toner and print badly: light for the print, back afterwards.
    const root = document.documentElement
    let wasDark = false
    const before = () => { wasDark = root.classList.contains('dark'); root.classList.remove('dark') }
    const after = () => { if (wasDark) root.classList.add('dark') }
    window.addEventListener('beforeprint', before)
    window.addEventListener('afterprint', after)
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('beforeprint', before)
      window.removeEventListener('afterprint', after)
      window.removeEventListener('keydown', onKey)
    }
  }, [onClose])

  const lines = code.split('\n')
  return createPortal(
    <div className="viz-handout fixed inset-0 z-50 overflow-auto bg-gray-950" role="dialog" aria-modal="true"
      aria-label="Handout preview">
      <div className="sticky top-0 z-10 flex items-center gap-2 border-b border-gray-800 bg-gray-900 px-4 py-2 print:hidden">
        <p className="flex-1 text-sm text-gray-300">
          Handout preview: {steps.length} step{steps.length === 1 ? '' : 's'}
          {Object.keys(notes).length ? ' (the steps with notes)' : ''}
        </p>
        <button type="button" className="btn-primary btn-sm" onClick={() => window.print()}>
          <Printer size={13} /> Print / Save as PDF
        </button>
        <button type="button" className="btn-ghost btn-sm" onClick={onClose} aria-label="Close preview">
          <X size={14} />
        </button>
      </div>

      <article className="mx-auto max-w-4xl px-6 py-6 text-gray-100">
        <header className="mb-4">
          <h1 className="text-xl font-semibold">Code visualisation</h1>
          <p className="text-sm text-gray-400">
            {LANGUAGE_LABELS[trace.language]} · {trace.steps.length} steps · {new Date().toLocaleDateString()}
          </p>
        </header>

        <pre className="mb-6 overflow-hidden whitespace-pre-wrap rounded-md border border-gray-800 bg-gray-900 p-3 font-mono text-xs leading-5">
          {lines.map((l, i) => (
            <div key={i}><span className="mr-3 inline-block w-6 select-none text-right text-gray-500">{i + 1}</span>{l}</div>
          ))}
        </pre>

        {steps.map(i => {
          const s = trace.steps[i]
          if (!s) return null
          const structured = structureVars(s).length > 0
          return (
            <section key={i} className="mb-6 break-inside-avoid rounded-lg border border-gray-800">
              <header className="flex flex-wrap items-baseline gap-x-3 border-b border-gray-800 px-3 py-2">
                <h2 className="text-sm font-semibold">Step {i + 1}</h2>
                <span className="font-mono text-xs text-gray-400">
                  {s.event === 'line' ? `line ${s.line}` : s.event === 'call' ? `call at line ${s.line}` : `${s.event} at line ${s.line}`}
                </span>
                <code className="min-w-0 flex-1 truncate font-mono text-xs text-gray-300">{lines[s.line - 1]?.trim()}</code>
              </header>
              {notes[i] && <p className="whitespace-pre-wrap border-b border-gray-800 px-3 py-2 text-sm">{notes[i]}</p>}
              <div className="px-1">
                {structured
                  ? <StructuresView step={s} prev={trace.steps[i - 1]} zoom={0.9}
                      lineText={s.event === 'line' ? lines[s.line - 1] : undefined} />
                  : <MemoryView step={s} prev={trace.steps[i - 1]} showFunctions={false} zoom={0.9} />}
              </div>
              {trace.stdout.slice(0, s.out) && (
                <p className="border-t border-gray-800 px-3 py-1.5 font-mono text-xs text-gray-400">
                  Output so far: {trace.stdout.slice(0, s.out)}
                </p>
              )}
            </section>
          )
        })}
      </article>
    </div>,
    document.body,
  )
}
