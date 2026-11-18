import type { Trace } from './trace'

/**
 * Jumps through a trace other than one step at a time. All are pure: given a trace and where
 * the viewer is, the step to go to (or the same one when there is nowhere to go).
 */

const depth = (t: Trace, i: number) => t.steps[i]?.frames.length ?? 0

/** The next step at this call's depth or shallower: runs a called function to completion. */
export function stepOver(t: Trace, i: number): number {
  const d = depth(t, i)
  for (let j = i + 1; j < t.steps.length; j++) {
    if (depth(t, j) <= d && t.steps[j].event !== 'call') return j
  }
  return t.steps.length - 1
}

/** The previous step at this call's depth or shallower. */
export function stepBackOver(t: Trace, i: number): number {
  const d = depth(t, i)
  for (let j = i - 1; j >= 0; j--) {
    if (depth(t, j) <= d && t.steps[j].event !== 'call') return j
  }
  return 0
}

/** The next step that is about to run one of these lines. */
export function nextBreakpoint(t: Trace, i: number, lines: Set<number>): number {
  for (let j = i + 1; j < t.steps.length; j++) {
    const s = t.steps[j]
    if (s.event === 'line' && lines.has(s.line)) return j
  }
  return t.steps.length - 1
}

export function prevBreakpoint(t: Trace, i: number, lines: Set<number>): number {
  for (let j = i - 1; j >= 0; j--) {
    const s = t.steps[j]
    if (s.event === 'line' && lines.has(s.line)) return j
  }
  return 0
}

/** The next time a line runs after step i, wrapping to its first run; null if it never runs. */
export function nextRunOfLine(t: Trace, i: number, line: number): number | null {
  const n = t.steps.length
  for (let k = 1; k <= n; k++) {
    const j = (i + k) % n
    if (t.steps[j].event === 'line' && t.steps[j].line === line) return j
  }
  return null
}

/** How many times each line ran — the heat strip beside the code. */
export function lineCounts(t: Trace): Map<number, number> {
  const counts = new Map<number, number>()
  for (const s of t.steps) {
    if (s.event === 'line') counts.set(s.line, (counts.get(s.line) ?? 0) + 1)
  }
  return counts
}
