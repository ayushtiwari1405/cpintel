import type { Trace } from './trace'

/**
 * Notes written for an example, anchored to code rather than to step numbers: "the 2nd time
 * line 6 is about to run". Step numbers change whenever the code or input does; this survives
 * both, and turns into ordinary per-step notes once the program has been traced.
 */
export interface LessonNote {
  line: number
  /** Which run of the line, from 1; the first by default. */
  nth?: number
  /** At the step where the function on this line returns, rather than where the line runs. */
  on?: 'line' | 'return'
  text: string
}

export function notesFromLesson(trace: Trace, lesson: LessonNote[]): Record<number, string> {
  const notes: Record<number, string> = {}
  for (const n of lesson) {
    const want = n.nth ?? 1
    let seen = 0
    const at = trace.steps.findIndex(s => {
      if (s.line !== n.line || s.event !== (n.on ?? 'line')) return false
      seen++
      return seen === want
    })
    if (at >= 0) notes[at] = notes[at] ? `${notes[at]}\n\n${n.text}` : n.text
  }
  return notes
}
