import { useMemo, useRef } from 'react'
import { clsx } from 'clsx'
import type { Compound, Frame, Step, Val } from '@/visualiser/trace'
import { Arrows } from './Arrows'
import { CompoundView, ValueView } from './ValueView'

/**
 * The stack and the heap at one step, with arrows for every reference and pointer — the view a
 * student needs to see what `a = b` did to a list, or where `node->next` really points.
 *
 * Frames run outermost first, the executing one last and highlighted. Heap objects are laid out
 * in rows by following references, so a linked list reads left to right as a chain instead of
 * as a column of boxes with arrows crossing back and forth.
 */
export function MemoryView({ step, prev, showFunctions, zoom }: {
  step: Step
  prev?: Step
  showFunctions: boolean
  zoom: number
}) {
  const root = useRef<HTMLDivElement>(null)
  const rows = useMemo(() => heapRows(step.heap), [step.heap])
  const prevFrames = useMemo(() => new Map(prev?.frames.map(f => [f.id, f]) ?? []), [prev])

  return (
    <div ref={root} className="relative h-full overflow-auto">
      <div className="flex min-w-max items-start gap-16 p-4" style={{ zoom }}>
        <section aria-label="Call stack" className="flex w-max min-w-56 flex-col gap-3">
          <h3 className="text-[11px] font-medium uppercase tracking-wide text-gray-500">Frames</h3>
          {step.frames.map((frame, i) => (
            <FrameBox key={frame.id} frame={frame} prev={prevFrames.get(frame.id)}
              active={i === step.frames.length - 1} showFunctions={showFunctions}
              ret={i === step.frames.length - 1 && step.event === 'return' ? step.ret : undefined} />
          ))}
        </section>

        <section aria-label="Heap objects" className="flex flex-col gap-3">
          <h3 className="text-[11px] font-medium uppercase tracking-wide text-gray-500">
            {Object.keys(step.heap).length ? 'Objects' : ''}
          </h3>
          {rows.map((row, r) => (
            <div key={r} className="flex items-start gap-12">
              {row.map(id => {
                const was = prev?.heap[id]
                const fresh = prev != null && was == null
                const changed = !fresh && was != null && JSON.stringify(was) !== JSON.stringify(step.heap[id])
                return (
                  <div key={id} data-anchor={id}
                    className={clsx('rounded-md p-1.5 transition-colors',
                      fresh && 'viz-changed', changed && 'ring-1 ring-accent-400/40')}>
                    <CompoundView c={step.heap[id]} anchor={id} prev={was ?? undefined} />
                  </div>
                )
              })}
            </div>
          ))}
        </section>
      </div>
      <Arrows container={root} version={step} />
    </div>
  )
}

function FrameBox({ frame, prev, active, ret, showFunctions }: {
  frame: Frame
  prev?: Frame
  active: boolean
  ret?: Val
  showFunctions: boolean
}) {
  const before = new Map(prev?.vars.map(([n, v]) => [n, JSON.stringify(v)]) ?? [])
  return (
    <div className={clsx('rounded-lg border bg-gray-900',
      active ? 'border-accent-500/70 shadow-[0_0_0_1px_rgb(var(--c-accent-500)/0.25)]' : 'border-gray-800')}>
      <div className={clsx('flex items-center justify-between gap-3 border-b px-2.5 py-1.5',
        active ? 'border-accent-500/40' : 'border-gray-800')}>
        <span className="font-mono text-xs font-medium text-gray-100">{frame.name}</span>
        <span className="font-mono text-[10px] text-gray-500">line {frame.line}</span>
      </div>
      <table className="w-full text-xs">
        <tbody>
          {frame.vars.map(([name, v], index) => {
            if (!showFunctions && v.k === 'opaque' && (v.t === 'function' || v.t === 'class')) return null
            const changed = prev != null && before.get(name) !== JSON.stringify(v)
            const prevVal = prev?.vars.find(([n]) => n === name)?.[1]
            return (
              <tr key={name} data-anchor={`var:${frame.id}:${index}`}
                className={clsx(changed && 'viz-changed')}>
                <td className="whitespace-nowrap py-1 pl-2.5 pr-3 text-right align-top font-mono text-gray-400">{name}</td>
                <td className="py-1 pr-2.5"><ValueView v={v} anchor={`var:${frame.id}:${index}`}
                  prev={prevVal} changed={changed} /></td>
              </tr>
            )
          })}
          {ret && (
            <tr className="border-t border-gray-800">
              <td className="whitespace-nowrap py-1 pl-2.5 pr-3 text-right font-mono text-[11px] text-accent-300">return</td>
              <td className="py-1 pr-2.5"><ValueView v={ret} /></td>
            </tr>
          )}
          {frame.vars.length === 0 && !ret && (
            <tr><td className="px-2.5 py-1.5 text-[11px] text-gray-600">no variables yet</td></tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

/** Heap ids an object refers to, in field/element order. */
function children(c: Compound): string[] {
  const out: string[] = []
  const visit = (v: Val) => {
    if (v.k === 'ref') out.push(v.id)
    else if (v.k === 'ptr' && v.to && !v.to.startsWith('var:')) out.push(v.to)
    else if (v.k === 'seq') v.items.forEach(visit)
    else if (v.k === 'map') v.entries.forEach(([a, b]) => { visit(a); visit(b) })
    else if (v.k === 'obj') v.fields.forEach(([, x]) => visit(x))
  }
  if (c.k === 'seq') c.items.forEach(visit)
  else if (c.k === 'map') c.entries.forEach(([a, b]) => { visit(a); visit(b) })
  else if (c.k === 'obj') c.fields.forEach(([, x]) => visit(x))
  return out
}

/**
 * Groups heap objects into rows: each row starts at the first unplaced object and follows its
 * first unplaced reference, so chains (linked lists, a node and its next) share a row.
 */
export function heapRows(heap: Record<string, Compound>): string[][] {
  const placed = new Set<string>()
  const rows: string[][] = []
  for (const start of Object.keys(heap)) {
    if (placed.has(start)) continue
    const row: string[] = []
    let id: string | undefined = start
    while (id && !placed.has(id) && heap[id] && row.length < 8) {
      placed.add(id)
      row.push(id)
      id = children(heap[id]).find(c => !placed.has(c) && heap[c])
    }
    rows.push(row)
  }
  return rows
}
