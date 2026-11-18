import type { Compound, Frame, Step, Val } from './trace'

/**
 * Deciding how to draw a variable as a data structure, from its shape and (as teachers name
 * things the same way) its name. Pure, so it is tested without a browser.
 */

export type ViewKind =
  | 'array' | 'grid' | 'graph' | 'tree' | 'list' | 'heap' | 'stack' | 'queue' | 'table' | 'raw'

export const VIEW_LABELS: Record<ViewKind, string> = {
  array: 'Array', grid: '2D grid', graph: 'Graph', tree: 'Binary tree', list: 'Linked list',
  heap: 'Heap (as tree)', stack: 'Stack', queue: 'Queue', table: 'Table', raw: 'Raw',
}

type Heap = Record<string, Compound>

/** The compound a value is or refers to, and its heap id when it has one. */
export function deref(v: Val | undefined, heap: Heap): { c: Compound; id?: string } | null {
  if (!v) return null
  if (v.k === 'ref') return heap[v.id] ? { c: heap[v.id], id: v.id } : null
  if (v.k === 'ptr') return v.to && heap[v.to] ? { c: heap[v.to], id: v.to } : null
  if (v.k === 'seq' || v.k === 'map' || v.k === 'obj') return { c: v }
  return null
}

export const isPrim = (v: Val | undefined) =>
  !!v && (v.k === 'num' || v.k === 'bool' || v.k === 'char' || v.k === 'str' || v.k === 'none')

export const num = (v: Val | undefined): number | null => {
  if (!v) return null
  if (v.k === 'num') { const n = Number(v.v); return Number.isFinite(n) ? n : null }
  if (v.k === 'bool') return v.v === 'True' || v.v === 'true' ? 1 : 0
  return null
}

const GRAPH_NAMES = /^(adj|graph|g|edges?|nbrs?|neighbou?rs?|al|tree|children)$/i
const HEAP_NAMES = /^(heap|pq|minheap|maxheap|h)$/i

/** The node a pointer field leads to when it is the same kind of object (next, left, right). */
export function selfLinks(c: Compound, heap: Heap): [string, string | null][] {
  if (c.k !== 'obj') return []
  const out: [string, string | null][] = []
  for (const [name, v] of c.fields) {
    if (v.k === 'none' || (v.k === 'ptr' && v.to == null)) {
      // A null link counts when the name says it is one.
      if (/^(next|prev|left|right|l|r|child|lc|rc|parent|nxt)$/i.test(name)) out.push([name, null])
      continue
    }
    const target = deref(v, heap)
    if (target?.id && target.c.k === 'obj' && target.c.t === c.t) out.push([name, target.id])
  }
  return out
}

export function detectView(name: string, v: Val, heap: Heap): ViewKind {
  const r = deref(v, heap)
  if (!r) return 'raw'
  const c = r.c
  if (c.k === 'map') {
    const values = c.entries.map(([, x]) => deref(x, heap)?.c)
    if (GRAPH_NAMES.test(name) && values.length > 0 && values.every(x => x?.k === 'seq')) return 'graph'
    return 'table'
  }
  if (c.k === 'obj') {
    const links = selfLinks(c, heap)
    if (links.length === 1 || links.some(([n]) => /^(next|nxt|prev)$/i.test(n))) return 'list'
    if (links.length >= 2) return 'tree'
    return 'raw'
  }
  if (c.k !== 'seq') return 'raw'
  if (c.t === 'stack') return 'stack'
  if (c.t === 'queue' || c.t === 'deque') return 'queue'
  if (c.t === 'priority_queue' || (HEAP_NAMES.test(name) && c.items.every(isPrim))) return 'heap'
  const rows = c.items.map(x => deref(x, heap)?.c)
  if (c.items.length > 0 && rows.every(x => x?.k === 'seq')) {
    if (GRAPH_NAMES.test(name)) return 'graph'
    return 'grid'
  }
  if (c.items.every(isPrim)) return 'array'
  return 'raw'
}

/** Variables worth a data-structure card: anything with parts, in the executing frame and globals. */
export function structureVars(step: Step): { frame: Frame; name: string; v: Val }[] {
  const frames = step.frames
  if (frames.length === 0) return []
  const pick = frames.length > 1 ? [frames[frames.length - 1], frames[0]] : [frames[0]]
  const out: { frame: Frame; name: string; v: Val }[] = []
  for (const frame of pick) {
    for (const [name, v] of frame.vars) {
      if (deref(v, step.heap)) out.push({ frame, name, v })
    }
  }
  return out
}

// Not x, y or n: those are far more often a value being looked for, or the length itself.
const INDEX_NAMES = /^([ijkpqlruv]|lo|hi|mid|low|high|left|right|start|end|idx|pos|ptr|cur|top|head|tail|front|back|[ijk]\d|l\d|r\d)$/i
const GRID_NAMES = /^(x|y|row|col|c)$/i

/** Integer variables that read as indices into a sequence of this length. */
export function indexMarkers(step: Step, length: number, exclude: string, grid = false): [string, number][] {
  const out: [string, number][] = []
  const seen = new Set<string>()
  const frames = step.frames.length > 1 ? [step.frames[step.frames.length - 1], step.frames[0]] : step.frames
  for (const frame of frames) {
    for (const [name, v] of frame.vars) {
      if (name === exclude || seen.has(name)) continue
      if (!INDEX_NAMES.test(name) && !(grid && GRID_NAMES.test(name))) continue
      const n = num(v)
      if (v.k === 'num' && n != null && Number.isInteger(n) && n >= 0 && n <= length) {
        out.push([name, n])
        seen.add(name)
      }
    }
  }
  return out
}

/** Per-node arrays beside a graph: visited flags, distances, parents, colours. */
export function nodeAnnotations(step: Step, n: number, exclude: string): [string, Val[]][] {
  const out: [string, Val[]][] = []
  const frames = step.frames.length > 1 ? [step.frames[step.frames.length - 1], step.frames[0]] : step.frames
  for (const frame of frames) {
    for (const [name, v] of frame.vars) {
      if (name === exclude || out.some(([x]) => x === name)) continue
      if (!/^(vis|visited|seen|used|done|col|color|colour|dist|d|level|lvl|depth|par|parent|p|low|tin|tout|comp|indeg)$/i.test(name)) continue
      const c = deref(v, step.heap)?.c
      if (c?.k === 'seq' && c.items.length >= n && c.items.every(isPrim)) out.push([name, c.items])
    }
  }
  return out
}

/** Edges of an adjacency structure: list of lists, or a map from node to list. */
export function adjacency(c: Compound, heap: Heap): { nodes: string[]; edges: [string, string, string?][] } {
  const nodes: string[] = []
  const edges: [string, string, string?][] = []
  const label = (v: Val) => (v.k === 'num' || v.k === 'str' || v.k === 'char' ? v.v : '?')
  const rows: [string, Val][] = c.k === 'seq'
    ? c.items.map((x, i) => [String(i), x])
    : c.k === 'map' ? c.entries.map(([k, x]) => [label(k), x]) : []
  for (const [from] of rows) nodes.push(from)
  for (const [from, x] of rows) {
    const list = deref(x, heap)?.c
    if (list?.k !== 'seq') continue
    for (const item of list.items) {
      const pair = deref(item, heap)?.c
      if (pair?.k === 'seq' && pair.items.length >= 1) {
        // (to, weight) pairs, as weighted graphs are usually stored.
        const to = label(pair.items[0])
        edges.push([from, to, pair.items[1] ? label(pair.items[1]) : undefined])
        if (!nodes.includes(to)) nodes.push(to)
      } else if (isPrim(item)) {
        const to = label(item)
        edges.push([from, to])
        if (!nodes.includes(to)) nodes.push(to)
      }
    }
  }
  return { nodes, edges }
}

/** The integer variables in scope at a step: the running frame's, then globals. */
export function intScope(step: Step): Map<string, number> {
  const scope = new Map<string, number>()
  const frames = step.frames.length > 1 ? [step.frames[step.frames.length - 1], step.frames[0]] : step.frames
  for (const frame of frames) {
    for (const [name, v] of frame.vars) {
      const n = num(v)
      if (v.k === 'num' && n != null && Number.isInteger(n) && !scope.has(name)) scope.set(name, n)
    }
  }
  return scope
}

/**
 * Evaluates a small integer expression — names, numbers, + - * / // % and parentheses — or
 * returns null for anything else (a call, a field, a variable not in scope).
 */
export function evalIndex(expr: string, scope: Map<string, number>): number | null {
  const tokens = expr.match(/\s*(\/\/|[A-Za-z_]\w*|\d+|[-+*/%()])/g)?.map(t => t.trim())
  if (!tokens || tokens.join('') !== expr.replace(/\s+/g, '')) return null
  let i = 0
  const peek = () => tokens[i]
  const atom = (): number | null => {
    const t = tokens[i++]
    if (t === undefined) return null
    if (t === '(') { const v = sum(); if (tokens[i++] !== ')') return null; return v }
    if (t === '-') { const v = atom(); return v == null ? null : -v }
    if (/^\d+$/.test(t)) return Number(t)
    if (/^[A-Za-z_]\w*$/.test(t)) return scope.get(t) ?? null
    return null
  }
  const product = (): number | null => {
    let v = atom()
    while (v != null && (peek() === '*' || peek() === '/' || peek() === '//' || peek() === '%')) {
      const op = tokens[i++]
      const r = atom()
      if (r == null || (op !== '*' && r === 0)) return null
      v = op === '*' ? v * r : op === '%' ? v % r : Math.trunc(v / r)
    }
    return v
  }
  const sum = (): number | null => {
    let v = product()
    while (v != null && (peek() === '+' || peek() === '-')) {
      const op = tokens[i++]
      const r = product()
      if (r == null) return null
      v = op === '+' ? v + r : v - r
    }
    return v
  }
  const v = sum()
  return i === tokens.length ? v : null
}

/**
 * The cells of `name` the line about to run reads or writes: `a[j]` and `a[j + 1]` in a bubble
 * sort's comparison, `dp[i - 1][j]` in a DP. One index for a 1D array, two for a grid.
 */
export function accessed(lineText: string | undefined, name: string, step: Step): number[][] {
  if (!lineText) return []
  const scope = intScope(step)
  const out: number[][] = []
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const re = new RegExp(`(?<![\\w.])${escaped}((?:\\s*\\[[^\\[\\]]+\\])+)`, 'g')
  for (const m of lineText.matchAll(re)) {
    const parts = [...m[1].matchAll(/\[([^[\]]+)\]/g)].map(x => evalIndex(x[1], scope))
    if (parts.length && parts.every(p => p != null)) out.push(parts as number[])
  }
  return out
}
