import type { RunResponse, RunTestCase, RunTestResult } from '@/types'

/**
 * Limits for a run on the student's own machine. The same numbers the server runner uses
 * (cpintel.runner.* in application.yml), so a solution that passes here is not cut off there.
 */
export const TIME_LIMIT_MS = 5000
export const COMPILE_TIME_LIMIT_MS = 60_000
export const OUTPUT_LIMIT_BYTES = 65_536
/** The server runner's memory cap (cpintel.runner.memory-limit-mb), and so the judge's. */
export const MEMORY_LIMIT_MB = 512

/** What happened when one test was run, before it is judged. */
export interface RawExec {
  stdout: string
  stderr: string
  /** Null when the program never got to exit — it timed out or the engine crashed. */
  exitCode: number | null
  timedOut: boolean
  durationMs: number
  truncated: boolean
}

/** A build and its runs, as an engine reports them. */
export interface RawRun {
  compiled: boolean
  compileOutput: string
  compileMs: number
  runs: RawExec[]
  /**
   * The compiled program's static size (code, data, global arrays) in KB, when the engine could
   * read it. Runs here have no memory cap, so this is how a too-big global array is caught.
   */
  staticKb?: number | null
  /** Set when nothing could be attempted at all. */
  error?: string
}

/**
 * Whether the program's static size alone is at nine-tenths of the judge's memory limit.
 *
 * Under the server's cap such a program cannot even be loaded, and the judge refuses it the
 * same way; here, with no cap, it may well run and pass. Every test is then a memory limit, so
 * the student finds out before submitting. The same threshold as the server's RunEngine.
 */
export function imageTooBig(staticKb: number | null | undefined): boolean {
  return staticKb != null && staticKb >= MEMORY_LIMIT_MB * 1024 * 0.9
}

/**
 * Compare output the way the server's RunEngine.matches does: ignore trailing whitespace on
 * each line and trailing blank lines, and nothing else. Not token-based, because collapsing all
 * whitespace would pass an answer whose line structure is wrong.
 */
export function matches(actual: string, expected: string): boolean {
  const a = normalize(actual)
  const b = normalize(expected)
  return a.length === b.length && a.every((line, i) => line === b[i])
}

function normalize(text: string): string[] {
  const lines = text.replace(/\r\n/g, '\n').split('\n').map(l => l.trimEnd())
  while (lines.length > 0 && lines[lines.length - 1] === '') lines.pop()
  return lines
}

/** Turns an engine's raw report into the same response the server runner returns. */
export function judge(tests: RunTestCase[], raw: RawRun): RunResponse {
  if (raw.error) {
    return { compiled: false, compileOutput: null, compileMs: 0, results: [], error: raw.error }
  }
  if (!raw.compiled) {
    return {
      compiled: false,
      compileOutput: raw.compileOutput.trim() ? raw.compileOutput : 'Compilation failed.',
      compileMs: raw.compileMs,
      results: [],
      error: null,
    }
  }

  const tooBig = imageTooBig(raw.staticKb)
  const tooBigNote = tooBig
    ? `\nThe program's code and global data alone take ${Math.round(raw.staticKb! / 1024)} MB, `
      + `over the ${MEMORY_LIMIT_MB} MB the judge allows. It ran here because this computer `
      + 'sets no limit, but the judge cannot even start it. Make the global arrays smaller.'
    : ''

  const results: RunTestResult[] = raw.runs.map((exec, i) => {
    const test = tests[i]
    const verdict: RunTestResult['verdict'] =
      tooBig ? 'MEMORY_LIMIT_EXCEEDED'
        : exec.timedOut ? 'TIME_LIMIT_EXCEEDED'
        : exec.exitCode !== 0 ? (outOfMemory(exec.stderr) ? 'MEMORY_LIMIT_EXCEEDED' : 'RUNTIME_ERROR')
          : test.expected == null ? 'NO_EXPECTED'
            : matches(exec.stdout, test.expected) ? 'OK' : 'WRONG_ANSWER'
    return {
      label: test.label || `Test ${i + 1}`,
      verdict,
      input: test.input,
      expected: test.expected,
      actual: exec.stdout,
      stderr: exec.stderr + tooBigNote,
      durationMs: exec.durationMs,
      exitCode: exec.exitCode === 0 ? null : exec.exitCode,
      truncated: exec.truncated,
    }
  })

  // Warnings are worth showing on success; a clean build says nothing.
  return {
    compiled: true,
    compileOutput: raw.compileOutput.trim() ? raw.compileOutput : null,
    compileMs: raw.compileMs,
    results,
    error: null,
  }
}

/** Collects a stream up to the output cap, and remembers whether it had to stop. */
export class CappedText {
  private chunks: Uint8Array[] = []
  private size = 0
  truncated = false

  constructor(private readonly limit = OUTPUT_LIMIT_BYTES) {}

  push(bytes: Uint8Array) {
    const room = this.limit - this.size
    if (room <= 0) {
      if (bytes.length > 0) this.truncated = true
      return
    }
    const kept = bytes.length > room ? bytes.slice(0, room) : bytes.slice()
    if (kept.length < bytes.length) this.truncated = true
    this.chunks.push(kept)
    this.size += kept.length
  }

  text(): string {
    const all = new Uint8Array(this.size)
    let at = 0
    for (const c of this.chunks) { all.set(c, at); at += c.length }
    return new TextDecoder().decode(all)
  }
}

/**
 * Whether a failed run died for want of memory, as far as its own error output says: C++ throws
 * std::bad_alloc and Python raises MemoryError, and both say so. A C program that carries on with
 * a null malloc just crashes and stays a runtime error. The same rule as RunEngine.outOfMemory.
 */
export function outOfMemory(stderr: string | null | undefined): boolean {
  if (!stderr) return false
  return stderr.includes('std::bad_alloc') || stderr.includes('MemoryError')
    || stderr.includes('Cannot allocate memory') || stderr.includes('out of memory')
}
