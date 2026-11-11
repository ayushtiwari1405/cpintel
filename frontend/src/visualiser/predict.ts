import { brief, type Compound, type Frame, type Step, type Val } from './trace'

/**
 * Predict mode: before a step runs, ask what it will change.
 *
 * The questions are the values that actually differ between this step and the next one — a
 * variable, a list element, an object's field, a return value — limited to things a student can
 * type (numbers, strings, booleans, None). The answer is checked leniently: `5`, `5.0`, `'a'`,
 * `"a"`, `True` and `true` are all accepted where they mean the same thing.
 */

export interface Question {
  /** What is asked about: `x`, `a[2]`, `node.next`, `fib(3) returns`. */
  label: string
  answer: Val
  /** The value before this step, shown as a hint ("was 3"). */
  before?: Val
}

const MAX_QUESTIONS = 4
const isPrim = (v: Val | undefined): v is Extract<Val, { k: 'num' | 'str' | 'bool' | 'none' | 'char' }> =>
  !!v && (v.k === 'num' || v.k === 'str' || v.k === 'bool' || v.k === 'none' || v.k === 'char')

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)

/** A variable's parts: the compound it holds or refers to. */
function parts(v: Val | undefined, heap: Record<string, Compound>): Compound | null {
  if (!v) return null
  if (v.k === 'ref') return heap[v.id] ?? null
  if (v.k === 'ptr') return v.to ? heap[v.to] ?? null : null
  if (v.k === 'seq' || v.k === 'map' || v.k === 'obj') return v
  return null
}

function diffValue(label: string, was: Val | undefined, now: Val, beforeHeap: Record<string, Compound>,
  afterHeap: Record<string, Compound>, out: Question[]) {
  if (isPrim(now)) {
    if (!same(was, now)) out.push({ label, answer: now, before: isPrim(was) ? was : undefined })
    return
  }
  const a = parts(was, beforeHeap)
  const b = parts(now, afterHeap)
  if (!b) return
  if (b.k === 'seq') {
    const old = a?.k === 'seq' ? a.items : []
    b.items.forEach((item, i) => {
      if (isPrim(item) && !same(old[i], item)) out.push({ label: `${label}[${i}]`, answer: item, before: isPrim(old[i]) ? old[i] : undefined })
    })
  } else if (b.k === 'obj') {
    const old = new Map(a?.k === 'obj' ? a.fields : [])
    for (const [name, v] of b.fields) {
      if (isPrim(v) && !same(old.get(name), v)) out.push({ label: `${label}.${name}`, answer: v, before: isPrim(old.get(name)) ? old.get(name) : undefined })
    }
  } else if (b.k === 'map') {
    const old = new Map(a?.k === 'map' ? a.entries.map(([k, v]) => [JSON.stringify(k), v]) : [])
    for (const [k, v] of b.entries) {
      const was = old.get(JSON.stringify(k))
      if (isPrim(k) && isPrim(v) && !same(was, v)) {
        out.push({ label: `${label}[${brief(k)}]`, answer: v, before: isPrim(was) ? was : undefined })
      }
    }
  }
}

/** What running from `before` to `after` changes that is worth asking about. */
export function questionsFor(before: Step | undefined, after: Step | undefined): Question[] {
  if (!before || !after) return []
  const out: Question[] = []
  if (after.event === 'return' && isPrim(after.ret)) {
    const fn = after.frames[after.frames.length - 1]
    out.push({ label: `${fn?.name ?? 'function'}() returns`, answer: after.ret })
  }
  // The running frame and the globals: what the line about to run can touch by name.
  const top = before.frames[before.frames.length - 1]
  const wanted = new Set([top?.id, before.frames[0]?.id])
  const frames = after.frames.filter(f => wanted.has(f.id))
  const was = (f: Frame, name: string) => before.frames.find(x => x.id === f.id)?.vars.find(([n]) => n === name)?.[1]
  for (const f of frames) {
    for (const [name, v] of f.vars) {
      if (v.k === 'opaque') continue
      diffValue(name, was(f, name), v, before.heap, after.heap, out)
    }
  }
  // One question per label, the first few.
  const seen = new Set<string>()
  return out.filter(q => !seen.has(q.label) && seen.add(q.label)).slice(0, MAX_QUESTIONS)
}

const unquote = (s: string) => s.replace(/^(['"])(.*)\1$/s, '$2')

/** Whether a typed guess means the same as the answer. */
export function isRight(guess: string, answer: Val): boolean {
  const g = guess.trim()
  if (!g) return false
  switch (answer.k) {
    case 'num': {
      const a = Number(answer.v)
      const n = Number(g)
      return Number.isFinite(n) && Math.abs(n - a) <= 1e-9 * Math.max(1, Math.abs(a))
    }
    case 'bool': return g.toLowerCase() === answer.v.toLowerCase()
    case 'none': return /^(none|null|nullptr|null_ptr|nil)$/i.test(g)
    case 'str': return unquote(g) === answer.v || g === answer.v
    case 'char': return unquote(g) === answer.v
    default: return false
  }
}
