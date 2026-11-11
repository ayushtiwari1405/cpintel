import { useMemo, useState } from 'react'
import { clsx } from 'clsx'
import { Boxes } from 'lucide-react'
import { EmptyState } from '@/components/common/States'
import type { Compound, Step, Val } from '@/visualiser/trace'
import { brief } from '@/visualiser/trace'
import {
  VIEW_LABELS, accessed, adjacency, deref, detectView, indexMarkers, isPrim, nodeAnnotations, selfLinks,
  structureVars, type ViewKind,
} from '@/visualiser/structures'
import { layoutGraph } from '@/visualiser/graphLayout'
import { CompoundView, Prim } from './ValueView'

type Heap = Record<string, Compound>

/**
 * Every variable with parts, drawn as the data structure it is — an array with its index
 * variables under it, a DP table, a graph with its visited marks, a tree, a linked list.
 * The view is guessed from the shape and the name and can be overridden per variable.
 */
export function StructuresView({ step, prev, zoom, lineText }: {
  step: Step
  prev?: Step
  zoom: number
  /** The source of the line about to run, for the cells it reads (a[j] and a[j + 1]). */
  lineText?: string
}) {
  const vars = structureVars(step)
  const [overrides, setOverrides] = useState<Record<string, ViewKind>>({})

  if (vars.length === 0) {
    return (
      <EmptyState icon={<Boxes size={18} />} title="No data structures in scope"
        className="h-full justify-center">
        Lists, arrays, maps, grids, graphs, trees and linked lists in the running function (and
        globals) appear here as soon as they exist.
      </EmptyState>
    )
  }

  const prevFind = (frameId: number, name: string) =>
    prev?.frames.find(f => f.id === frameId)?.vars.find(([n]) => n === name)?.[1]

  return (
    <div className="h-full overflow-auto">
      <div className="flex flex-col gap-4 p-4" style={{ zoom }}>
        {vars.map(({ frame, name, v }) => {
          const key = `${frame.name}:${name}`
          const auto = detectView(name, v, step.heap)
          const kind = overrides[key] ?? auto
          return (
            <section key={`${frame.id}:${name}`} className="rounded-lg border border-gray-800 bg-gray-900">
              <header className="flex items-center gap-2 border-b border-gray-800 px-3 py-1.5">
                <span className="font-mono text-sm text-gray-100">{name}</span>
                {frame.name !== step.frames[step.frames.length - 1]?.name || step.frames.length === 1
                  ? <span className="text-[10px] text-gray-500">in {frame.name}</span> : null}
                <label className="ml-auto flex items-center gap-1.5 text-[11px] text-gray-500">
                  <span>View as</span>
                  <select value={kind}
                    onChange={e => setOverrides(o => ({ ...o, [key]: e.target.value as ViewKind }))}
                    className="rounded-md border border-gray-700 bg-gray-950 px-1.5 py-0.5 text-[11px] text-gray-300">
                    {(Object.keys(VIEW_LABELS) as ViewKind[]).map(k => (
                      <option key={k} value={k}>{VIEW_LABELS[k]}{k === auto ? ' (auto)' : ''}</option>
                    ))}
                  </select>
                </label>
              </header>
              <div className="overflow-x-auto p-3">
                <Structure kind={kind} name={name} v={v} step={step} prevV={prevFind(frame.id, name)}
                  prevHeap={prev?.heap} lineText={lineText} />
              </div>
            </section>
          )
        })}
      </div>
    </div>
  )
}

function Structure({ kind, name, v, step, prevV, prevHeap, lineText }: {
  kind: ViewKind; name: string; v: Val; step: Step; prevV?: Val; prevHeap?: Heap; lineText?: string
}) {
  const heap = step.heap
  const r = deref(v, heap)
  const before = prevV && prevHeap ? deref(prevV, prevHeap)?.c : undefined
  if (!r) return <span className="text-xs text-gray-500">{brief(v, heap)}</span>
  const c = r.c
  switch (kind) {
    case 'array': return c.k === 'seq'
      ? <ArrayStrip c={c} prev={before} step={step} name={name} lineText={lineText} /> : <Mismatch />
    case 'grid': return c.k === 'seq'
      ? <Grid c={c} prev={before} heap={heap} prevHeap={prevHeap} step={step} name={name} lineText={lineText} />
      : <Mismatch />
    case 'graph': return <Graph c={c} heap={heap} step={step} name={name} />
    case 'tree': return r.id ? <BinaryTree root={r.id} heap={heap} /> : <Mismatch />
    case 'list': return r.id ? <LinkedList head={r.id} heap={heap} /> : <Mismatch />
    case 'heap': return c.k === 'seq' ? <HeapTree c={c} /> : <Mismatch />
    case 'stack': return c.k === 'seq' ? <StackView c={c} /> : <Mismatch />
    case 'queue': return c.k === 'seq' ? <QueueView c={c} /> : <Mismatch />
    case 'table': return <CompoundView c={c} prev={before} />
    default: return <CompoundView c={c} prev={before} />
  }
}

const Mismatch = () => (
  <p className="text-xs text-gray-500">This variable does not have that shape. Pick another view.</p>
)

const cellText = (v: Val, heap?: Heap) => (isPrim(v)
  ? <Prim v={v as Extract<Val, { k: 'num' | 'str' | 'bool' | 'none' | 'char' }>} />
  : <span className="font-mono text-[11px] text-gray-400">{brief(v, heap)}</span>)

function ArrayStrip({ c, prev, step, name, lineText }: {
  c: Extract<Compound, { k: 'seq' }>; prev?: Compound; step: Step; name: string; lineText?: string
}) {
  const markers = indexMarkers(step, c.items.length, name)
  const reads = new Set(accessed(lineText, name, step).filter(a => a.length === 1).map(a => a[0]))
  const prevItems = prev?.k === 'seq' ? prev.items : null
  const numeric = c.items.map(x => (x.k === 'num' ? Number(x.v) : NaN))
  const bars = numeric.length > 1 && numeric.every(Number.isFinite)
  const max = bars ? Math.max(...numeric.map(Math.abs), 1) : 1
  const [showBars, setShowBars] = useState(false)
  return (
    <div className="flex flex-col gap-1">
      {bars && (
        <label className="mb-1 flex items-center gap-1.5 self-start text-[11px] text-gray-500">
          <input type="checkbox" checked={showBars} onChange={e => setShowBars(e.target.checked)} />
          Bars (for sorting)
        </label>
      )}
      <div className="flex items-end">
        {c.items.map((x, i) => {
          const changed = prevItems != null && JSON.stringify(prevItems[i]) !== JSON.stringify(x)
          const here = markers.filter(([, at]) => at === i).map(([n]) => n)
          return (
            <div key={i} className="flex flex-col items-center">
              {showBars && bars && (
                <div className="flex h-24 w-9 items-end justify-center px-1">
                  <div className={clsx('w-full rounded-t-sm transition-[height] duration-200',
                    reads.has(i) ? 'bg-sky-400' : changed ? 'bg-amber-400' : here.length ? 'bg-accent-400' : 'bg-gray-600')}
                    style={{ height: `${Math.max(4, (Math.abs(numeric[i]) / max) * 96)}px` }} />
                </div>
              )}
              <span className="font-mono text-[9px] text-gray-500">{i}</span>
              <div className={clsx('-ml-px flex h-9 min-w-9 items-center justify-center border px-1.5 text-xs',
                here.length ? 'border-accent-400 bg-accent-400/10' : 'border-gray-700 bg-gray-950',
                reads.has(i) && 'relative z-10 ring-2 ring-sky-400',
                changed && 'viz-changed')}
                aria-label={`${name}[${i}]${reads.has(i) ? ', used on this line' : ''}${here.length ? `, ${here.join(' and ')} here` : ''}`}>
                {cellText(x)}
              </div>
              <div className="flex h-8 flex-col items-center pt-0.5">
                {here.length > 0 && (
                  <>
                    <span className="text-[10px] leading-3 text-accent-300">▲</span>
                    <span className="font-mono text-[10px] leading-3 text-accent-300">{here.join(',')}</span>
                  </>
                )}
              </div>
            </div>
          )
        })}
        {c.more > 0 && <span className="self-center px-2 font-mono text-[11px] text-gray-500">+{c.more}</span>}
        {/* An index one past the end (an end pointer, or a loop that just finished). */}
        {markers.some(([, at]) => at === c.items.length) && (
          <div className="flex flex-col items-center opacity-70">
            <span className="font-mono text-[9px] text-gray-600">{c.items.length}</span>
            <div className="h-9 w-9 border border-dashed border-gray-700" />
            <span className="font-mono text-[10px] text-accent-300">
              ▲ {markers.filter(([, at]) => at === c.items.length).map(([n]) => n).join(',')}
            </span>
          </div>
        )}
      </div>
      {c.items.length === 0 && <span className="text-xs text-gray-500">empty</span>}
      {reads.size > 0 && (
        <p className="mt-1 flex items-center gap-1.5 text-[11px] text-gray-500">
          <span className="inline-block h-2.5 w-2.5 rounded-sm ring-2 ring-sky-400" aria-hidden="true" />
          used by the line about to run
        </p>
      )}
    </div>
  )
}

function Grid({ c, prev, heap, prevHeap, step, name, lineText }: {
  c: Extract<Compound, { k: 'seq' }>; prev?: Compound; heap: Heap; prevHeap?: Heap; step: Step
  name: string; lineText?: string
}) {
  const reads = new Set(accessed(lineText, name, step).filter(a => a.length === 2).map(([a, b]) => `${a},${b}`))
  const rows = c.items.map(x => deref(x, heap)?.c).map(x => (x?.k === 'seq' ? x.items : []))
  const prevRows = prev?.k === 'seq'
    ? prev.items.map(x => (prevHeap ? deref(x, prevHeap)?.c : x)).map(x => (x?.k === 'seq' ? x.items : []))
    : null
  const cols = Math.max(0, ...rows.map(r => r.length))
  const markers = indexMarkers(step, Math.max(rows.length, cols), '', true)
  const get = (...names: string[]) => markers.find(([n]) => names.includes(n))?.[1]
  const ri = get('i', 'r', 'row', 'x')
  const ci = get('j', 'c', 'col', 'y')
  return (
    <table className="border-collapse font-mono text-xs">
      <thead>
        <tr>
          <th />
          {Array.from({ length: cols }, (_, j) => (
            <th key={j} className={clsx('px-1 text-[9px] font-normal', j === ci ? 'text-accent-300' : 'text-gray-500')}>{j}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, i) => (
          <tr key={i}>
            <th className={clsx('pr-1.5 text-right text-[9px] font-normal', i === ri ? 'text-accent-300' : 'text-gray-500')}>{i}</th>
            {Array.from({ length: cols }, (_, j) => {
              const x = row[j]
              const changed = prevRows != null && JSON.stringify(prevRows[i]?.[j]) !== JSON.stringify(x)
              const focus = i === ri && j === ci
              return (
                <td key={j} className={clsx('h-8 min-w-8 border px-1.5 text-center',
                  focus ? 'border-accent-400 bg-accent-400/15' : (i === ri || j === ci) ? 'border-gray-700 bg-gray-850' : 'border-gray-700',
                  reads.has(`${i},${j}`) && 'relative ring-2 ring-inset ring-sky-400',
                  changed && 'viz-changed')}>
                  {x ? cellText(x, heap) : ''}
                </td>
              )
            })}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Graph({ c, heap, step, name }: { c: Compound; heap: Heap; step: Step; name: string }) {
  const { nodes, edges } = useMemo(() => adjacency(c, heap), [c, heap])
  const notes = nodeAnnotations(step, nodes.length, name)
  const markers = indexMarkers(step, nodes.length, name)
  const n = nodes.length
  if (n === 0) return <span className="text-xs text-gray-500">No nodes yet.</span>
  const { pos, size } = layoutGraph(nodes, edges)
  const key = (a: string, b: string) => `${a}->${b}`
  const all = new Set(edges.map(([a, b]) => key(a, b)))
  const undirected = edges.every(([a, b]) => all.has(key(b, a)))
  const visitedNote = notes.find(([nm]) => /^(vis|visited|seen|used|done)$/i.test(nm))
  const drawn = new Set<string>()
  return (
    <div className="flex flex-wrap items-start gap-4">
      <svg width={size} height={size} role="img"
        aria-label={`Graph ${name}: ${n} nodes. Edges ${edges.slice(0, 40).map(([a, b]) => `${a} to ${b}`).join(', ')}`
          + `${visitedNote ? `. Visited: ${nodes.filter((id, i) => isTruthy(visitedNote[1][Number(id)] ?? visitedNote[1][i])).join(', ') || 'none'}` : ''}`}>
        <defs>
          <marker id="viz-edge" viewBox="0 0 8 8" refX="17" refY="4" markerWidth="6" markerHeight="6" orient="auto">
            <path d="M0,0 L8,4 L0,8 z" className="fill-gray-500" />
          </marker>
        </defs>
        {edges.map(([a, b, w], i) => {
          if (undirected && drawn.has(key(b, a))) return null
          drawn.add(key(a, b))
          const p = pos.get(a)!; const q = pos.get(b)
          if (!q) return null
          return (
            <g key={i}>
              <line x1={p.x} y1={p.y} x2={q.x} y2={q.y} strokeWidth={1.25} className="stroke-gray-600"
                markerEnd={undirected ? undefined : 'url(#viz-edge)'} />
              {w != null && (
                <text x={(p.x + q.x) / 2} y={(p.y + q.y) / 2 - 3} textAnchor="middle"
                  className="fill-amber-300 font-mono text-[10px]">{w}</text>
              )}
            </g>
          )
        })}
        {nodes.map((id, i) => {
          const p = pos.get(id)!
          const visited = visitedNote && isTruthy(visitedNote[1][Number(id)] ?? visitedNote[1][i])
          const here = markers.filter(([, at]) => String(at) === id).map(([nm]) => nm)
          return (
            <g key={id}>
              <circle cx={p.x} cy={p.y} r={14} strokeWidth={here.length ? 2.5 : 1.5}
                className={clsx(visited ? 'fill-accent-600/80' : 'fill-gray-900',
                  here.length ? 'stroke-amber-300' : 'stroke-accent-400')} />
              <text x={p.x} y={p.y + 4} textAnchor="middle"
                className={clsx('font-mono text-[11px]', visited ? 'fill-white' : 'fill-gray-100')}>{id}</text>
              {here.length > 0 && (
                <text x={p.x + 17} y={p.y - 12} textAnchor="start" className="fill-amber-300 font-mono text-[10px] font-medium">
                  {here.join(',')}
                </text>
              )}
            </g>
          )
        })}
      </svg>
      {notes.length > 0 && (
        <table className="border-collapse font-mono text-[11px]">
          <thead>
            <tr><th className="px-1.5 text-left font-normal text-gray-500">node</th>
              {notes.map(([nm]) => <th key={nm} className="px-1.5 text-left font-normal text-gray-400">{nm}</th>)}</tr>
          </thead>
          <tbody>
            {nodes.map((id, i) => (
              <tr key={id}>
                <td className="border border-gray-800 px-1.5 text-gray-400">{id}</td>
                {notes.map(([nm, items]) => (
                  <td key={nm} className="border border-gray-800 px-1.5">{items[i] ? cellText(items[i]) : ''}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

const isTruthy = (v: Val | undefined) =>
  !!v && ((v.k === 'bool' && (v.v === 'True' || v.v === 'true')) || (v.k === 'num' && Number(v.v) !== 0))

/** The field that names a node: val, value, key, data — or the first plain field. */
function nodeLabel(c: Compound, heap: Heap): string {
  if (c.k !== 'obj') return brief(c, heap)
  const f = c.fields.find(([n]) => /^(val|value|key|data|v|x|item)$/i.test(n))
    ?? c.fields.find(([, v]) => isPrim(v))
  return f ? brief(f[1]) : c.t
}

function LinkedList({ head, heap }: { head: string; heap: Heap }) {
  const chain: string[] = []
  const seen = new Set<string>()
  let id: string | null = head
  let cycleTo: string | null = null
  while (id && heap[id] && chain.length < 40) {
    if (seen.has(id)) { cycleTo = id; break }
    seen.add(id); chain.push(id)
    const links: [string, string | null][] = selfLinks(heap[id], heap)
    const next: [string, string | null] | undefined = links.find(([n]) => /^(next|nxt)$/i.test(n)) ?? links[0]
    id = next?.[1] ?? null
  }
  return (
    <div className="flex flex-wrap items-center gap-1">
      {chain.map((nid, i) => (
        <div key={nid} className="flex items-center gap-1">
          <div className="flex items-center overflow-hidden rounded-md border border-accent-500/60">
            <span className="bg-gray-950 px-2.5 py-1.5 font-mono text-xs text-gray-100">{nodeLabel(heap[nid], heap)}</span>
            <span className="border-l border-accent-500/40 bg-gray-850 px-1.5 py-1.5 text-[10px] text-gray-500">{i === 0 ? 'head' : '•'}</span>
          </div>
          <span className="text-accent-400" aria-hidden="true">→</span>
        </div>
      ))}
      <span className="font-mono text-xs text-gray-500">{cycleTo ? `↺ back to ${nodeLabel(heap[cycleTo], heap)} (cycle)` : 'null'}</span>
    </div>
  )
}

interface TNode { id: string | null; label: string; kids: TNode[] }

function BinaryTree({ root, heap }: { root: string; heap: Heap }) {
  const seen = new Set<string>()
  const build = (id: string | null, depth: number): TNode => {
    if (!id || !heap[id] || seen.has(id) || depth > 12) return { id: null, label: '', kids: [] }
    seen.add(id)
    const links = selfLinks(heap[id], heap)
    const left = links.find(([n]) => /^(left|l|lc|lo)$/i.test(n)) ?? links[0]
    const right = links.find(([n]) => /^(right|r|rc|hi)$/i.test(n)) ?? links[1]
    const kids = [build(left?.[1] ?? null, depth + 1), build(right?.[1] ?? null, depth + 1)]
    return { id, label: nodeLabel(heap[id], heap), kids: kids.every(k => !k.id) ? [] : kids }
  }
  return <TreeSvg tree={build(root, 0)} label="Binary tree" />
}

/** An array heap (priority queue, heapq list) drawn as the tree it encodes. */
function HeapTree({ c }: { c: Extract<Compound, { k: 'seq' }> }) {
  const build = (i: number): TNode => (i >= c.items.length ? { id: null, label: '', kids: [] }
    : {
      id: String(i), label: brief(c.items[i]),
      kids: 2 * i + 1 < c.items.length ? [build(2 * i + 1), build(2 * i + 2)] : [],
    })
  if (c.items.length === 0) return <span className="text-xs text-gray-500">empty</span>
  return (
    <div className="flex flex-col gap-3">
      <TreeSvg tree={build(0)} label="Heap" />
      <CompoundView c={c} />
    </div>
  )
}

function TreeSvg({ tree, label }: { tree: TNode; label: string }) {
  const W = 44; const H = 52
  const placed: { n: TNode; x: number; y: number }[] = []
  const edges: [number, number, number, number][] = []
  let col = 0
  // A missing child still takes half a column, so a lone left child leans left.
  const visit = (n: TNode, d: number): { x: number; real: boolean } => {
    if (!n.id) { const x = col * W; col += 0.5; return { x, real: false } }
    const kids = n.kids.map(k => visit(k, d + 1))
    const real = kids.filter(k => k.real).map(k => k.x)
    let x: number
    if (real.length === 0) { x = col * W; col++ } else x = (kids[0].x + kids[kids.length - 1].x) / 2
    const y = d * H
    placed.push({ n, x, y })
    for (const kx of real) edges.push([x, y, kx, y + H])
    return { x, real: true }
  }
  visit(tree, 0)
  const depth = Math.max(0, ...placed.map(p => p.y))
  const describe = (t: TNode): string => (!t.id ? 'empty'
    : t.kids.length ? `${t.label} (${t.kids.map(describe).join(', ')})` : t.label)
  return (
    <svg width={Math.max(W, col * W) + 40} height={depth + 60} role="img"
      aria-label={`${label}: ${describe(tree)}`}>
      <g transform="translate(20,20)">
        {edges.map(([x1, y1, x2, y2], i) => (
          <line key={i} x1={x1} y1={y1} x2={x2} y2={y2} className="stroke-gray-600" strokeWidth={1.25} />
        ))}
        {placed.map(({ n, x, y }) => (
          <g key={n.id}>
            <circle cx={x} cy={y} r={15} className="fill-gray-900 stroke-accent-400" strokeWidth={1.5} />
            <text x={x} y={y + 4} textAnchor="middle" className="fill-gray-100 font-mono text-[10px]">
              {n.label.length > 5 ? `${n.label.slice(0, 4)}…` : n.label}
            </text>
          </g>
        ))}
      </g>
    </svg>
  )
}

function StackView({ c }: { c: Extract<Compound, { k: 'seq' }> }) {
  const items = [...c.items].reverse()
  return (
    <div className="inline-flex flex-col">
      {items.length === 0 && <span className="text-xs text-gray-500">empty</span>}
      {items.map((x, i) => (
        <div key={i} className={clsx('-mt-px flex min-w-24 items-center justify-between gap-3 border px-2.5 py-1.5 text-xs',
          i === 0 ? 'border-accent-400 bg-accent-400/10' : 'border-gray-700 bg-gray-950')}>
          {cellText(x)}
          {i === 0 && <span className="text-[10px] text-accent-300">top</span>}
        </div>
      ))}
    </div>
  )
}

function QueueView({ c }: { c: Extract<Compound, { k: 'seq' }> }) {
  return (
    <div className="flex items-center gap-2">
      <span className="text-[10px] text-accent-300">front →</span>
      <div className="flex">
        {c.items.length === 0 && <span className="text-xs text-gray-500">empty</span>}
        {c.items.map((x, i) => (
          <div key={i} className={clsx('-ml-px flex h-9 min-w-9 items-center justify-center border px-1.5 text-xs',
            i === 0 ? 'border-accent-400 bg-accent-400/10' : 'border-gray-700 bg-gray-950')}>
            {cellText(x)}
          </div>
        ))}
      </div>
      <span className="text-[10px] text-gray-500">← back</span>
    </div>
  )
}
