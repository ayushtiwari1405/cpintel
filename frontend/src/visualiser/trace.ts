/**
 * The execution trace the visualiser shows: what a tracer (Python's or the instrumented C++
 * runtime's) records, one full snapshot per step.
 *
 * Both tracers produce exactly this shape, so every view works for both languages. A step is
 * self-contained — its frames and every heap object they reach — so any step can be drawn on its
 * own, which is what makes stepping backwards and scrubbing free.
 */

/** 'c' is traced through the C++ tracer, compiled as C++ with C's malloc semantics. */
export type VizLanguage = 'python' | 'cpp' | 'c'

/** A value as a variable, field or element holds it. */
export type Val =
  | { k: 'num' | 'str' | 'bool' | 'none' | 'char'; v: string; t?: string }
  /** A reference to a heap object (a Python object, or what a C++ pointer points at). */
  | { k: 'ref'; id: string }
  /** A C++ pointer: its address, and the object it points into when that is known. */
  | { k: 'ptr'; t: string; addr: string; to: string | null; idx?: number }
  /** A function, class, module, uninitialised value or anything else shown as text. */
  | { k: 'opaque'; t: string; v: string }
  | Compound

/** Something with parts. A heap object is always one; a C++ local can be one inline. */
export type Compound =
  | { k: 'seq'; t: string; items: Val[]; more: number; addr?: string }
  | { k: 'map'; t: string; entries: [Val, Val][]; more: number; addr?: string }
  | { k: 'obj'; t: string; fields: [string, Val][]; addr?: string }
  | { k: 'opaque'; t: string; v: string; addr?: string }

export interface Frame {
  /** Unique for the whole run: two calls of the same function have different ids. */
  id: number
  name: string
  line: number
  vars: [string, Val][]
}

export type StepEvent = 'line' | 'call' | 'return' | 'exception'

export interface Step {
  event: StepEvent
  /** The line about to run (line), being entered (call) or being left (return). */
  line: number
  /** Outermost first; the last one is executing. */
  frames: Frame[]
  heap: Record<string, Compound>
  /** How many characters of the program's output existed at this step. */
  out: number
  /** What the returning function gives back, on a return step. */
  ret?: Val
  error?: string
}

export interface Trace {
  language: VizLanguage
  steps: Step[]
  stdout: string
  /** Why the program stopped early or could not start, if it did. */
  error: string | null
  errorLine?: number | null
  /** The step limit was reached before the program finished. */
  truncated: boolean
}

// ── Derived: the tree of calls, for the recursion view ─────────────────────

export interface CallNode {
  frameId: number
  name: string
  /** The parameters as they were on entry. */
  args: [string, Val][]
  ret?: Val
  /** First and last steps this call was on the stack. */
  enter: number
  exit: number
  children: CallNode[]
  parent: CallNode | null
}

/**
 * Rebuilds the call tree from the stacks of consecutive steps: a frame id appearing is a
 * call, one disappearing is a return. The root is the program's outermost frame.
 */
export function buildCallTree(trace: Trace): CallNode | null {
  const steps = trace.steps
  if (steps.length === 0 || steps[0].frames.length === 0) return null
  const nodes = new Map<number, CallNode>()
  let root: CallNode | null = null

  steps.forEach((step, i) => {
    let parent: CallNode | null = null
    for (const frame of step.frames) {
      let node = nodes.get(frame.id)
      if (!node) {
        node = {
          frameId: frame.id, name: frame.name, args: frame.vars, enter: i, exit: i,
          children: [], parent,
        }
        nodes.set(frame.id, node)
        if (parent) parent.children.push(node)
        else root ??= node
      }
      node.exit = i
      parent = node
    }
    if (step.event === 'return' && step.ret && step.frames.length > 0) {
      const top = nodes.get(step.frames[step.frames.length - 1].id)
      if (top) top.ret = step.ret
    }
  })
  return root
}

/** The calls on the stack at a step, innermost last. */
export function activeFrameIds(step: Step | undefined): Set<number> {
  return new Set(step?.frames.map(f => f.id) ?? [])
}

/** Short text for a value, for tooltips, recursion-tree labels and compact cells. */
export function brief(v: Val | undefined, heap?: Record<string, Compound>, depth = 0): string {
  if (!v) return ''
  switch (v.k) {
    case 'num': case 'bool': case 'none': return v.v
    case 'char': return `'${v.v}'`
    case 'str': return JSON.stringify(v.v)
    case 'opaque': return v.v
    case 'ptr': return v.to == null ? (v.addr === '0x0' ? 'nullptr' : v.addr) : `→${v.addr}`
    case 'ref': {
      const target = heap?.[v.id]
      if (!target || depth > 1) return `→${v.id}`
      return brief(target, heap, depth + 1)
    }
    case 'seq': {
      const open = v.t === 'tuple' ? '(' : v.t.includes('set') ? '{' : '['
      const close = v.t === 'tuple' ? ')' : v.t.includes('set') ? '}' : ']'
      const shown = v.items.slice(0, 8).map(x => brief(x, heap, depth + 1))
      const rest = v.items.length - shown.length + v.more
      return `${open}${shown.join(', ')}${rest > 0 ? `, …${rest}` : ''}${close}`
    }
    case 'map': {
      const shown = v.entries.slice(0, 5)
        .map(([a, b]) => `${brief(a, heap, depth + 1)}: ${brief(b, heap, depth + 1)}`)
      const rest = v.entries.length - shown.length + v.more
      return `{${shown.join(', ')}${rest > 0 ? `, …${rest}` : ''}}`
    }
    case 'obj':
      return `${v.t}(${v.fields.slice(0, 4).map(([n, x]) => `${n}=${brief(x, heap, depth + 1)}`)
        .join(', ')})`
  }
}
