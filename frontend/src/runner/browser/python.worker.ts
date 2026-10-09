/// <reference lib="webworker" />
/**
 * Python 3 in the browser, with Pyodide (CPython compiled to WebAssembly).
 *
 * Pyodide comes from jsDelivr at a pinned version and is cached by the browser, so the CPIntel
 * server neither serves nor runs it. The interpreter is loaded once per worker; the page
 * terminates the worker when a test runs past the time limit, since a Python loop cannot be
 * interrupted from outside, and starts a new one for the next test.
 */

/** Also pinned in the site's Content-Security-Policy (nginx/templates); change both. */
const PYODIDE_VERSION = '314.0.7'
const PYODIDE_BASE = `https://cdn.jsdelivr.net/npm/pyodide@${PYODIDE_VERSION}/`

/**
 * The harness each test runs through, mirroring what the server runner does with CPython:
 * a parse step that reports a syntax error once, and a run with fresh globals, the test's input
 * as stdin (with .buffer, which fast-input idioms use) and capped stdout/stderr.
 */
const HARNESS = `
import io, linecache, sys, traceback

class _Capped(io.RawIOBase):
    def __init__(self, limit):
        self.limit, self.data, self.truncated = limit, bytearray(), False
    def writable(self):
        return True
    def write(self, b):
        room = self.limit - len(self.data)
        if len(b) > room:
            self.truncated = True
        if room > 0:
            self.data += bytes(b[:room])
        return len(b)

def _stream(limit):
    raw = _Capped(limit)
    return raw, io.TextIOWrapper(io.BufferedWriter(raw), encoding="utf-8", newline="\\n",
                                 line_buffering=False)

def __cpintel_check(src):
    try:
        compile(src, "main.py", "exec")
        return ""
    except SyntaxError:
        return traceback.format_exc(limit=0)

def __cpintel_run(src, data, limit):
    # There is no main.py on disk, so register the source for tracebacks to quote.
    linecache.cache["main.py"] = (len(src), None, src.splitlines(True), "main.py")
    out_raw, out = _stream(limit)
    err_raw, err = _stream(limit)
    saved = sys.stdin, sys.stdout, sys.stderr
    sys.stdin = io.TextIOWrapper(io.BufferedReader(io.BytesIO(data.encode("utf-8"))),
                                 encoding="utf-8")
    sys.stdout, sys.stderr = out, err
    code = 0
    try:
        exec(compile(src, "main.py", "exec"), {"__name__": "__main__", "__builtins__": __builtins__})
    except SystemExit as e:
        if e.code is None:
            code = 0
        elif isinstance(e.code, int):
            code = e.code
        else:
            print(e.code, file=sys.stderr)
            code = 1
    except BaseException as e:
        tb = e.__traceback__.tb_next if e.__traceback__ else None
        traceback.print_exception(type(e), e, tb)
        code = 1
    finally:
        for s in (out, err):
            try:
                s.flush()
            except Exception:
                pass
        sys.stdin, sys.stdout, sys.stderr = saved
    return (code,
            out_raw.data.decode("utf-8", "replace"),
            err_raw.data.decode("utf-8", "replace"),
            out_raw.truncated or err_raw.truncated)
`

interface PyProxy { toJs: () => unknown; destroy: () => void }
interface Pyodide {
  runPython: (code: string) => unknown
  globals: { get: (name: string) => (...args: unknown[]) => PyProxy }
}

let pyodide: Promise<Pyodide> | null = null

const load = () => {
  pyodide ??= (async () => {
    const mod = await import(/* @vite-ignore */ `${PYODIDE_BASE}pyodide.mjs`)
    const py: Pyodide = await mod.loadPyodide({ indexURL: PYODIDE_BASE })
    py.runPython(HARNESS)
    return py
  })()
  pyodide.catch(() => { pyodide = null })
  return pyodide
}

export type PythonRequest =
  | { kind: 'load' }
  | { kind: 'check'; source: string }
  | { kind: 'run'; source: string; input: string; outputLimit: number }

export type PythonMessage =
  | { kind: 'loaded' }
  | { kind: 'checked'; output: string; ms: number }
  | { kind: 'started' }
  | { kind: 'ran'; code: number; stdout: string; stderr: string; truncated: boolean; ms: number }
  | { kind: 'failed'; error: string }

const post = (m: PythonMessage) => self.postMessage(m)

self.onmessage = async (e: MessageEvent<PythonRequest>) => {
  let py: Pyodide
  try {
    py = await load()
  } catch {
    post({ kind: 'failed', error: 'Could not download Python. Check the internet connection '
      + 'and try again.' })
    return
  }

  const req = e.data
  try {
    if (req.kind === 'load') {
      post({ kind: 'loaded' })
    } else if (req.kind === 'check') {
      const started = performance.now()
      const output = py.globals.get('__cpintel_check')(req.source) as unknown as string
      post({ kind: 'checked', output, ms: Math.round(performance.now() - started) })
    } else {
      post({ kind: 'started' })
      const started = performance.now()
      const result = py.globals.get('__cpintel_run')(req.source, req.input, req.outputLimit)
      const [code, stdout, stderr, truncated] = result.toJs() as [number, string, string, boolean]
      result.destroy()
      post({ kind: 'ran', code, stdout, stderr, truncated,
        ms: Math.round(performance.now() - started) })
    }
  } catch (err: any) {
    // Python's own errors are caught inside the harness, so this is the interpreter itself
    // failing — most often a recursion deep enough to exhaust the JavaScript stack.
    const message = String(err?.message ?? err)
    post({ kind: 'failed', error: /call stack|too much recursion/i.test(message)
      ? 'RecursionError: the recursion went deeper than Python in the browser can go.'
      : message })
  }
}
