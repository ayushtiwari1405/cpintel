import type { Trace } from './trace'

/** Steps on one handout; past this it stops being a handout. */
export const MAX_HANDOUT_STEPS = 16

/**
 * Which steps a printed handout shows: the ones the teacher annotated, if any; otherwise the
 * first few runs of each breakpoint line; otherwise a few steps spread evenly through the run.
 */
export function handoutSteps(trace: Trace, notes: Record<number, string>, breakpoints: Set<number>): number[] {
  const n = trace.steps.length
  if (n === 0) return []
  const noted = Object.keys(notes).map(Number).filter(i => i >= 0 && i < n).sort((a, b) => a - b)
  if (noted.length) return noted.slice(0, MAX_HANDOUT_STEPS)

  if (breakpoints.size) {
    const runs = new Map<number, number>()
    const hits: number[] = []
    trace.steps.forEach((s, i) => {
      if (s.event !== 'line' || !breakpoints.has(s.line)) return
      const k = (runs.get(s.line) ?? 0) + 1
      runs.set(s.line, k)
      if (k <= 3) hits.push(i)
    })
    if (hits.length) return hits.slice(0, MAX_HANDOUT_STEPS)
  }

  const count = Math.min(8, n)
  const picked = new Set<number>()
  for (let k = 0; k < count; k++) picked.add(Math.round((k * (n - 1)) / Math.max(1, count - 1)))
  return [...picked].sort((a, b) => a - b)
}
