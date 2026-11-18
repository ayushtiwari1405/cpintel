import { compile } from '@/runner/browser/engine'
import { MAX_STEPS, failure, type Progress, type VizRequest } from '../engine'
import type { Trace } from '../trace'
import type { CppRunMessage, CppRunRequest } from './cppRun.worker'
import { instrument } from './cpp/instrument'
import RUNTIME from './cpp/cpviz.h?raw'
import treeSitterWasm from '@vscode/tree-sitter-wasm/wasm/tree-sitter.wasm?url'
import cppGrammarWasm from '@vscode/tree-sitter-wasm/wasm/tree-sitter-cpp.wasm?url'

/** Wall-clock budget for the traced run itself; compiling has its own. */
const RUN_TIME_LIMIT_MS = 20_000

type TS = typeof import('@vscode/tree-sitter-wasm')
type Parser = InstanceType<TS['Parser']>

let parser: Promise<Parser> | null = null

/** The C++ parser, loaded once; both wasm files are served by this site, not a CDN. */
function loadParser(): Promise<Parser> {
  parser ??= (async () => {
    const mod = await import('@vscode/tree-sitter-wasm')
    const ts = ((mod as unknown as { default?: TS }).default ?? mod) as TS
    await ts.Parser.init({ locateFile: () => treeSitterWasm })
    const language = await ts.Language.load(cppGrammarWasm)
    const p = new ts.Parser()
    p.setLanguage(language)
    return p
  })()
  parser.catch(() => { parser = null })
  return parser
}

/**
 * In WebAssembly, address 0 is ordinary memory and nothing checks an index, so a null pointer
 * write or a[10] on a 5-element array would quietly "work" — the opposite of what a student
 * should see. These turn them into a trap on the offending line, with no runtime library:
 * Clang's sanitizers in trap mode for raw pointers and arrays, libc++'s hardening for
 * vector/string/array indexing.
 */
const traceBuild = (req: VizRequest) => ({
  files: { 'cpviz.h': RUNTIME },
  defines: [
    `CPVIZ_MAX_STEPS=${MAX_STEPS}`, '_LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_FAST',
    ...(req.language === 'c' ? ['CPVIZ_C'] : []),
    ...(req.startLines?.length ? [`CPVIZ_START_LINES=${req.startLines.join(',')}`] : []),
  ],
  flags: ['-fsanitize=null,bounds,vla-bound,return', '-fsanitize-trap=all'],
})

/**
 * Traces a C++ program: instrument it, compile it in the browser's Clang, run it, collect the
 * steps. When the instrumented program does not compile, the original is compiled to tell a
 * mistake in the student's code (shown as theirs) from one in the instrumentation (retried with
 * the cautious rewrite, then reported as the visualiser's).
 */
export async function traceCpp(req: VizRequest, onProgress: Progress): Promise<Trace> {
  onProgress('Preparing C++…')
  let p: Parser
  try {
    p = await loadParser()
  } catch {
    return failure('cpp', 'Could not load the C++ parser. Check the internet connection and try again.')
  }
  const tree = p.parse(req.source)
  if (!tree) return failure('cpp', 'Could not read this program.')
  try {
    return await build(req, tree.rootNode, onProgress)
  } finally {
    tree.delete()
  }
}

async function build(req: VizRequest, root: Parameters<typeof instrument>[1], onProgress: Progress)
  : Promise<Trace> {
  const full = instrument(req.source, root)

  const build = traceBuild(req)
  let built = await compile('cpp', full, onProgress, build)
  const lang = req.language
  if (built.kind === 'failed') return failure(lang, built.error)
  if (built.kind !== 'done') return failure(lang, 'The compiler did not answer.')

  if (!built.ok) {
    onProgress('Checking the program…')
    // The student's program, compiled as they wrote it (C as C), says whose mistake it is.
    const plain = await compile(lang === 'c' ? 'c' : 'cpp', req.source, onProgress)
    if (plain.kind === 'done' && !plain.ok) {
      return { ...failure(lang, `Compilation failed:\n${plain.output}`), errorLine: firstErrorLine(plain.output) }
    }
    onProgress('Compiling (cautious mode)…')
    const careful = instrument(req.source, root, { safe: true })
    const retry = await compile('cpp', careful, onProgress, build)
    if (retry.kind !== 'done' || !retry.ok) {
      return failure(lang, 'This program compiles, but the visualiser could not trace it — it '
        + 'uses something the tracer does not support yet.'
        + (lang === 'c' ? ' C is traced as C++, so C-only code (a void* assigned to another '
          + 'pointer type without a cast, say) needs a cast here.' : '') + '\n\n'
        + (retry.kind === 'done' ? retry.output : built.output).slice(0, 4000))
    }
    built = retry
  }
  if (!built.ok) return failure(lang, 'The compiler did not answer.')

  onProgress('Tracing…')
  const result = await run(built.module, req.stdin)
  if (!result) {
    return failure(lang, `The program ran for more than ${RUN_TIME_LIMIT_MS / 1000} s while being `
      + 'traced. Look for an infinite loop, or try a smaller input.')
  }
  const last = result.steps[result.steps.length - 1]
  // In the traced build, "aborted" is almost always one of the checks TRACE_BUILD turns on.
  const error = result.error?.startsWith('Runtime error: the program aborted')
    ? 'Runtime error: the program stopped here. Look for a null or dangling pointer, an index '
      + 'outside an array, vector or string, or a failed assert.'
    : result.error
  return {
    language: lang,
    steps: result.steps,
    stdout: result.stdout,
    error,
    errorLine: result.error && last ? last.line : null,
    truncated: result.truncated,
  }
}

function firstErrorLine(output: string): number | null {
  const m = output.match(/main\.(?:c|cpp):(\d+):\d+: error/)
  return m ? Number(m[1]) : null
}

function run(module: WebAssembly.Module, stdin: string) {
  const worker = new Worker(new URL('./cppRun.worker.ts', import.meta.url), { type: 'module' })
  return new Promise<Extract<CppRunMessage, { kind: 'done' }>['result'] | null>(resolve => {
    let timer = 0
    const finish = (r: Extract<CppRunMessage, { kind: 'done' }>['result'] | null) => {
      clearTimeout(timer)
      worker.terminate()
      resolve(r)
    }
    worker.onmessage = (e: MessageEvent<CppRunMessage>) => {
      if (e.data.kind === 'started') timer = window.setTimeout(() => finish(null), RUN_TIME_LIMIT_MS)
      else finish(e.data.result)
    }
    worker.onerror = e => finish({ steps: [], stdout: '', truncated: false,
      error: e.message || 'The program could not be started.' })
    const request: CppRunRequest = { module, stdin }
    worker.postMessage(request)
  })
}
