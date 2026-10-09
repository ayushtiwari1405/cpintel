import type { RunTestCase } from '@/types'
import {
  COMPILE_TIME_LIMIT_MS, OUTPUT_LIMIT_BYTES, TIME_LIMIT_MS, type RawExec, type RawRun,
} from '../judge'
import type { CompileMessage } from './cppCompile.worker'
import type { WasiRunMessage } from './wasiRun.worker'
import type { PythonMessage, PythonRequest } from './python.worker'

/** A line for the console while something slow happens, or null when it is over. */
export type Progress = (message: string | null) => void

/**
 * Runs code inside this browser tab, in WebAssembly — for the website, where there is no
 * compiler to call. Every program runs in a worker, so a runaway one costs a worker, not the
 * page, and the time limit is enforced by terminating it.
 */
export const browserEngine = {
  supported: typeof WebAssembly === 'object' && typeof Worker === 'function',

  async run(language: string, source: string, tests: RunTestCase[], onProgress: Progress)
    : Promise<RawRun> {
    if (language === 'cpp') return runCpp(source, tests, onProgress)
    if (language === 'python3') return runPython(source, tests, onProgress)
    return failed(`${language} cannot be run in the browser.`)
  },
}

const failed = (error: string): RawRun =>
  ({ compiled: false, compileOutput: '', compileMs: 0, runs: [], error })

const timedOut = (): RawExec => ({
  stdout: '', stderr: '', exitCode: null, timedOut: true, durationMs: TIME_LIMIT_MS,
  truncated: false,
})

const crashed = (message: string): RawExec => ({
  stdout: '', stderr: message, exitCode: 1, timedOut: false, durationMs: 0, truncated: false,
})

const mb = (bytes: number) => (bytes / 1024 / 1024).toFixed(0)

// ── C++ ───────────────────────────────────────────────────────────────────

let compiler: Worker | null = null

function compile(source: string, onProgress: Progress): Promise<CompileMessage> {
  compiler ??= new Worker(new URL('./cppCompile.worker.ts', import.meta.url), { type: 'module' })
  const worker = compiler

  return new Promise(resolve => {
    // Restarted on every download progress event, so a slow first download is not mistaken
    // for a compile that hangs.
    let timer = 0
    const arm = () => {
      clearTimeout(timer)
      timer = window.setTimeout(() => {
        worker.terminate()
        compiler = null
        resolve({ kind: 'done', ok: false, ms: COMPILE_TIME_LIMIT_MS,
          output: `Compilation timed out after ${COMPILE_TIME_LIMIT_MS / 1000} s.` })
      }, COMPILE_TIME_LIMIT_MS)
    }
    arm()

    worker.onmessage = (e: MessageEvent<CompileMessage>) => {
      const m = e.data
      if (m.kind === 'progress') {
        arm()
        onProgress(m.done < m.total
          ? `Downloading the C++ compiler, once: ${mb(m.done)} of ${mb(m.total)} MB…`
          : 'Compiling…')
        return
      }
      clearTimeout(timer)
      resolve(m)
    }
    worker.onerror = e => {
      clearTimeout(timer)
      worker.terminate()
      compiler = null
      resolve({ kind: 'failed', error: `The C++ compiler stopped: ${e.message || 'unknown error'}` })
    }
    onProgress('Compiling…')
    worker.postMessage({ source })
  })
}

function runWasm(module: WebAssembly.Module, input: string): Promise<RawExec> {
  const worker = new Worker(new URL('./wasiRun.worker.ts', import.meta.url), { type: 'module' })
  return new Promise(resolve => {
    let timer = 0
    const finish = (exec: RawExec) => {
      clearTimeout(timer)
      worker.terminate()
      resolve(exec)
    }
    worker.onmessage = (e: MessageEvent<WasiRunMessage>) => {
      if (e.data.kind === 'started') {
        // Timed from when the program starts, not from when the worker was created, so the
        // worker's own start-up is not charged to the solution.
        timer = window.setTimeout(() => finish(timedOut()), TIME_LIMIT_MS)
      } else {
        finish(e.data.exec)
      }
    }
    worker.onerror = e => finish(crashed(e.message || 'The program could not be started.'))
    worker.postMessage({ module, input })
  })
}

async function runCpp(source: string, tests: RunTestCase[], onProgress: Progress)
  : Promise<RawRun> {
  try {
    const built = await compile(source, onProgress)
    if (built.kind === 'failed') return failed(built.error)
    if (built.kind !== 'done') return failed('The C++ compiler did not answer.')
    if (!built.ok) return { compiled: false, compileOutput: built.output, compileMs: built.ms, runs: [] }

    const runs: RawExec[] = []
    for (let i = 0; i < tests.length; i++) {
      onProgress(tests.length > 1 ? `Running test ${i + 1} of ${tests.length}…` : 'Running…')
      runs.push(await runWasm(built.module, tests[i].input))
    }
    return { compiled: true, compileOutput: built.output, compileMs: built.ms, runs }
  } finally {
    onProgress(null)
  }
}

// ── Python ────────────────────────────────────────────────────────────────

let python: Worker | null = null

/** Sends one request to the Python worker and waits for its answer. */
function ask(request: PythonRequest, timeoutMs?: number)
  : Promise<PythonMessage | { kind: 'timeout' }> {
  python ??= new Worker(new URL('./python.worker.ts', import.meta.url), { type: 'module' })
  const worker = python

  return new Promise(resolve => {
    let timer = 0
    const discard = () => {
      worker.terminate()
      if (python === worker) python = null
    }
    worker.onmessage = (e: MessageEvent<PythonMessage>) => {
      const m = e.data
      if (m.kind === 'started') {
        if (timeoutMs) {
          timer = window.setTimeout(() => { discard(); resolve({ kind: 'timeout' }) }, timeoutMs)
        }
        return
      }
      clearTimeout(timer)
      // An interpreter that failed outside the harness may be in any state; start afresh.
      if (m.kind === 'failed') discard()
      resolve(m)
    }
    worker.onerror = e => {
      clearTimeout(timer)
      discard()
      resolve({ kind: 'failed', error: e.message || 'Python stopped unexpectedly.' })
    }
    worker.postMessage(request)
  })
}

async function runPython(source: string, tests: RunTestCase[], onProgress: Progress)
  : Promise<RawRun> {
  try {
    if (!python) onProgress('Loading Python (the first run downloads about 10 MB)…')
    const loaded = await ask({ kind: 'load' })
    if (loaded.kind === 'failed') return failed(loaded.error)

    // A parse first, as the server runner does, so a syntax error is reported once rather than
    // as the same traceback on every test.
    const checked = await ask({ kind: 'check', source })
    if (checked.kind === 'failed') return failed(checked.error)
    if (checked.kind === 'checked' && checked.output) {
      return { compiled: false, compileOutput: checked.output, compileMs: checked.ms, runs: [] }
    }
    const compileMs = checked.kind === 'checked' ? checked.ms : 0

    const runs: RawExec[] = []
    for (let i = 0; i < tests.length; i++) {
      if (!python) onProgress('Restarting Python…')
      else onProgress(tests.length > 1 ? `Running test ${i + 1} of ${tests.length}…` : 'Running…')
      const answer = await ask(
        { kind: 'run', source, input: tests[i].input, outputLimit: OUTPUT_LIMIT_BYTES },
        TIME_LIMIT_MS)
      if (answer.kind === 'timeout') runs.push(timedOut())
      else if (answer.kind === 'failed') runs.push(crashed(answer.error))
      else if (answer.kind === 'ran') {
        runs.push({
          stdout: answer.stdout, stderr: answer.stderr, exitCode: answer.code,
          timedOut: false, durationMs: answer.ms, truncated: answer.truncated,
        })
      }
    }
    return { compiled: true, compileOutput: '', compileMs, runs }
  } finally {
    onProgress(null)
  }
}
