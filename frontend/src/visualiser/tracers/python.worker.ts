/// <reference lib="webworker" />
/**
 * Traces a Python program for the visualiser, in Pyodide.
 *
 * Separate from the Run worker on purpose: tracing installs sys.settrace and swaps stdio, and
 * a Run should never inherit any of that. The interpreter itself comes from the same pinned
 * CDN path, so the browser's cache serves it to both.
 */
import { PYODIDE_BASE } from '@/runner/browser/toolchains'
import TRACER from './python_tracer.py?raw'

interface Pyodide {
  runPython: (code: string) => unknown
  globals: { get: (name: string) => (...args: unknown[]) => unknown }
}

let pyodide: Promise<Pyodide> | null = null

const load = () => {
  pyodide ??= (async () => {
    const mod = await import(/* @vite-ignore */ `${PYODIDE_BASE}pyodide.mjs`)
    const py: Pyodide = await mod.loadPyodide({ indexURL: PYODIDE_BASE })
    py.runPython(TRACER)
    return py
  })()
  pyodide.catch(() => { pyodide = null })
  return pyodide
}

export interface PyTraceRequest { source: string; stdin: string; maxSteps: number }
export type PyTraceMessage =
  | { kind: 'loaded' }
  | { kind: 'traced'; json: string }
  | { kind: 'failed'; error: string }

const post = (m: PyTraceMessage) => self.postMessage(m)

self.onmessage = async (e: MessageEvent<PyTraceRequest>) => {
  let py: Pyodide
  try {
    py = await load()
  } catch {
    post({ kind: 'failed', error: 'Could not download Python. Check the internet connection '
      + 'and try again.' })
    return
  }
  post({ kind: 'loaded' })
  try {
    const json = py.globals.get('__cpviz_trace')(e.data.source, e.data.stdin, e.data.maxSteps)
    post({ kind: 'traced', json: json as string })
  } catch (err: any) {
    const message = String(err?.message ?? err)
    post({ kind: 'failed', error: /call stack|too much recursion/i.test(message)
      ? 'RecursionError: the recursion went deeper than Python in the browser can go.'
      : message })
  }
}
