import type { Trace, VizLanguage } from './trace'
import type { PyTraceMessage, PyTraceRequest } from './tracers/python.worker'

/** A line for the status bar while something slow happens, or null when it is over. */
export type Progress = (message: string | null) => void

/** Steps recorded before a run is cut short; enough for any program worth stepping through. */
export const MAX_STEPS = 3000

/** Wall-clock budget for tracing, after the toolchain has loaded. Tracing is slow by nature. */
const TRACE_TIME_LIMIT_MS = 20_000

export interface VizRequest {
  language: VizLanguage
  source: string
  stdin: string
}

/**
 * Runs a program under a tracer, entirely in this browser tab, and returns its trace. Never
 * throws: a failure comes back as a trace with an error and no steps.
 */
export async function traceProgram(req: VizRequest, onProgress: Progress): Promise<Trace> {
  try {
    if (req.language === 'python') return await tracePython(req, onProgress)
    const { traceCpp } = await import('./tracers/cpp')
    return await traceCpp(req, onProgress)
  } catch (err: any) {
    return failure(req.language, String(err?.message ?? err))
  } finally {
    onProgress(null)
  }
}

export const failure = (language: VizLanguage, error: string): Trace =>
  ({ language, steps: [], stdout: '', error, truncated: false })

let python: Worker | null = null

function tracePython(req: VizRequest, onProgress: Progress): Promise<Trace> {
  if (!python) onProgress('Loading Python (the first time downloads about 10 MB)…')
  python ??= new Worker(new URL('./tracers/python.worker.ts', import.meta.url), { type: 'module' })
  const worker = python

  return new Promise(resolve => {
    let timer = 0
    const discard = () => {
      worker.terminate()
      if (python === worker) python = null
    }
    worker.onmessage = (e: MessageEvent<PyTraceMessage>) => {
      const m = e.data
      if (m.kind === 'loaded') {
        onProgress('Tracing…')
        timer = window.setTimeout(() => {
          // A Python loop cannot be interrupted from outside; the interpreter goes with it.
          discard()
          resolve(failure('python', `The program ran for more than ${TRACE_TIME_LIMIT_MS / 1000} s `
            + 'while being traced. Look for an infinite loop, or try a smaller input.'))
        }, TRACE_TIME_LIMIT_MS)
        return
      }
      clearTimeout(timer)
      if (m.kind === 'failed') {
        discard()
        resolve(failure('python', m.error))
        return
      }
      try {
        resolve(JSON.parse(m.json) as Trace)
      } catch {
        resolve(failure('python', 'The trace could not be read.'))
      }
    }
    worker.onerror = e => {
      clearTimeout(timer)
      discard()
      resolve(failure('python', e.message || 'Python stopped unexpectedly.'))
    }
    const request: PyTraceRequest = { source: req.source, stdin: req.stdin, maxSteps: MAX_STEPS }
    worker.postMessage(request)
  })
}
