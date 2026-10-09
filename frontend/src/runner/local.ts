import type { RunRequest, RunResponse, RunnerRuntime } from '@/types'
import { browserEngine, type Progress } from './browser/engine'
import { COMPILE_TIME_LIMIT_MS, OUTPUT_LIMIT_BYTES, TIME_LIMIT_MS, judge } from './judge'

export type { Progress }

/**
 * Running code on the student's own machine, outside examinations.
 *
 * The server's runner is small and is kept for examinations, where every candidate has to be
 * judged by the same machine under the same limits. Everywhere else a Run costs the server
 * nothing:
 *
 *  - In the desktop app, with the compilers installed on the computer (g++, Python). Fast, and
 *    the same compiler family as the judge.
 *  - On the website, or for a language the computer has no compiler for, in this browser tab
 *    in WebAssembly: Clang for C++ and Pyodide for Python, downloaded once from a CDN.
 *
 * Runtime ids are the server runner's ("cpp", "python3"), so the language mapping and an
 * event's language restriction treat a local runtime exactly like a server one.
 */

const BROWSER_RUNTIMES: RunnerRuntime[] = [
  {
    id: 'cpp', displayName: 'C++ (Clang, in this browser)', editorLanguage: 'cpp',
    available: browserEngine.supported,
    unavailableReason: browserEngine.supported ? null : 'This browser cannot run WebAssembly.',
  },
  {
    id: 'python3', displayName: 'Python 3 (in this browser)', editorLanguage: 'python',
    available: browserEngine.supported,
    unavailableReason: browserEngine.supported ? null : 'This browser cannot run WebAssembly.',
  },
]

type Route = 'desktop' | 'browser'

/** Set when the student refuses to let the desktop app run code on their computer. */
let desktopDeclined = false

let routes: Promise<Map<string, { runtime: RunnerRuntime; route: Route }>> | null = null

/** For each language, where it runs: the computer's own toolchain when it has one. */
function resolveRoutes() {
  routes ??= (async () => {
    const map = new Map<string, { runtime: RunnerRuntime; route: Route }>()
    for (const r of BROWSER_RUNTIMES) map.set(r.id, { runtime: r, route: 'browser' })

    const desktop = window.cpintelDesktop?.runner
    if (desktop && !desktopDeclined) {
      try {
        for (const r of await desktop.languages()) {
          if (r.available) map.set(r.id, { runtime: r, route: 'desktop' })
        }
      } catch {
        // An older desktop build, or detection failed: the browser covers every language.
      }
    }
    return map
  })()
  return routes
}

export const localRunner = {
  async languages(): Promise<RunnerRuntime[]> {
    const map = await resolveRoutes()
    return [...map.values()].map(v => v.runtime)
      .sort((a, b) => a.displayName.localeCompare(b.displayName))
  },

  async run(request: RunRequest, onProgress: Progress): Promise<RunResponse> {
    const entry = (await resolveRoutes()).get(request.language)
    if (!entry) return judge(request.tests, error(`Unknown language: ${request.language}`))

    if (entry.route === 'desktop') {
      onProgress(request.language === 'cpp' ? 'Compiling…' : 'Running…')
      try {
        const raw = await window.cpintelDesktop!.runner!.run({
          language: request.language,
          source: request.source,
          inputs: request.tests.map(t => t.input),
          timeLimitMs: TIME_LIMIT_MS,
          compileTimeLimitMs: COMPILE_TIME_LIMIT_MS,
          outputLimitBytes: OUTPUT_LIMIT_BYTES,
        })
        if (!raw.declined) return judge(request.tests, raw)
        // The student said no to running code on their computer. Respect it for the rest of
        // the session and run in the browser instead, which needs no permission.
        desktopDeclined = true
        routes = null
      } finally {
        onProgress(null)
      }
    }

    if (!browserEngine.supported) {
      return judge(request.tests, error('This browser cannot run WebAssembly, so code cannot '
        + 'be run here. Submitting still works.'))
    }
    const raw = await browserEngine.run(request.language, request.source, request.tests,
      onProgress)
    return judge(request.tests, raw)
  },
}

const error = (message: string) =>
  ({ compiled: false, compileOutput: '', compileMs: 0, runs: [], error: message })
