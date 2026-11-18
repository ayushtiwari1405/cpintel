import type { Frame, Step, Trace, Val } from '../trace'

export const num = (n: number): Val => ({ k: 'num', v: String(n) })

export function step(frames: Frame[], extra: Partial<Step> = {}): Step {
  return { event: 'line', line: 1, frames, heap: {}, out: 0, ...extra }
}

export const frame = (id: number, name: string, vars: [string, Val][] = [], line = 1): Frame =>
  ({ id, name, line, vars })

export const trace = (steps: Step[]): Trace =>
  ({ language: 'python', steps, stdout: '', error: null, truncated: false })
