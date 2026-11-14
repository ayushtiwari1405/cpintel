/**
 * LeetCode through the student's own sign-in: the desktop app's LeetCode window, or on the
 * website the CPIntel extension (0.4.0 and later). The server never sees the LeetCode session.
 *
 * <p>The page can only ask for four things, and the app or extension builds each request itself:
 * who is signed in, a run on test input, a submission, and a result. Results are polled here
 * until LeetCode has judged them.
 */

export type LcRelay = 'desktop' | 'extension'

export interface LcStatus { signedIn: boolean; username: string | null }

/** What LeetCode says about a run or submission once judged; only the fields used here. */
export interface LcResult {
  state: string
  status_msg?: string
  status_code?: number
  run_success?: boolean
  correct_answer?: boolean
  total_correct?: number | null
  total_testcases?: number | null
  status_runtime?: string
  status_memory?: string
  compile_error?: string
  full_compile_error?: string
  runtime_error?: string
  full_runtime_error?: string
  last_testcase?: string
  expected_output?: string
  code_output?: string | string[]
  code_answer?: string[]
  expected_code_answer?: string[]
  std_output_list?: string[]
}

export class LcError extends Error {}

export function lcRelay(): LcRelay | null {
  if (typeof window === 'undefined') return null
  if (window.cpintelDesktop?.lc) return 'desktop'
  if (document.documentElement.hasAttribute('data-cpintel-lc')) return 'extension'
  return null
}

/** Whether the website has an extension too old to know LeetCode. */
export function extensionTooOld(): boolean {
  return typeof document !== 'undefined'
    && document.documentElement.hasAttribute('data-cpintel-cf')
    && !document.documentElement.hasAttribute('data-cpintel-lc')
}

let nextId = 0

function viaExtension(message: Record<string, unknown>, timeoutMs: number): Promise<{ result?: unknown; error?: string }> {
  const id = `lc-${Date.now()}-${nextId++}`
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      window.removeEventListener('message', onMessage)
      reject(new LcError('The CPIntel extension did not answer.'))
    }, timeoutMs)
    function onMessage(event: MessageEvent) {
      const data = event.data
      if (event.source !== window || !data || data.cpintelCf !== 'response' || data.id !== id) return
      clearTimeout(timer)
      window.removeEventListener('message', onMessage)
      resolve(data)
    }
    window.addEventListener('message', onMessage)
    window.postMessage({ cpintelCf: 'request', id, ...message }, window.location.origin)
  })
}

async function call<T>(op: 'status' | 'run' | 'submit' | 'check', args?: Record<string, unknown>): Promise<T> {
  const relay = lcRelay()
  let answer: { result?: unknown; error?: string }
  if (relay === 'desktop') answer = await window.cpintelDesktop!.lc!.call(op, args)
  else if (relay === 'extension') answer = await viaExtension({ kind: 'lc-call', op, args }, 45_000)
  else throw new LcError('No connection to LeetCode on this page.')
  if (answer?.error) throw new LcError(answer.error)
  return answer.result as T
}

export function lcStatus(): Promise<LcStatus> {
  return call<LcStatus>('status')
}

/** Opens LeetCode's sign-in in a window of its own; resolves once signed in. */
export async function lcSignIn(): Promise<LcStatus> {
  const relay = lcRelay()
  let answer: { result?: unknown; error?: string }
  if (relay === 'desktop') answer = await window.cpintelDesktop!.lc!.signIn()
  else if (relay === 'extension') answer = await viaExtension({ kind: 'lc-signin' }, 6 * 60_000)
  else throw new LcError('No connection to LeetCode on this page.')
  if (answer?.error) throw new LcError(answer.error)
  return answer.result as LcStatus
}

export interface LcSolution { slug: string; questionId: string; lang: string; code: string }

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))

/** Polls a run or submission until LeetCode has judged it. */
async function judged(id: string, timeoutMs = 90_000): Promise<LcResult> {
  const deadline = Date.now() + timeoutMs
  let wait = 800
  while (Date.now() < deadline) {
    await sleep(wait)
    const r = await call<LcResult>('check', { id })
    if (r?.state === 'SUCCESS' || r?.state === 'FAILURE') return r
    wait = Math.min(wait + 400, 2500)
  }
  throw new LcError('LeetCode is taking too long to judge this. Check it on LeetCode.')
}

/** Runs the solution on the given input (one test case's lines after another). */
export async function lcRun(solution: LcSolution, input: string): Promise<LcResult> {
  const { id } = await call<{ id: string }>('run', { ...solution, input })
  return judged(id)
}

/** Submits the solution and waits for the verdict. */
export async function lcSubmit(solution: LcSolution): Promise<LcResult & { submissionId: string }> {
  const { id } = await call<{ id: string }>('submit', { ...solution })
  return { ...(await judged(id)), submissionId: id }
}

/** LeetCode's language slugs, as Monaco language ids. */
export function monacoLanguage(langSlug: string): string {
  const map: Record<string, string> = {
    cpp: 'cpp', c: 'c', java: 'java', python: 'python', python3: 'python', javascript: 'javascript',
    typescript: 'typescript', csharp: 'csharp', golang: 'go', kotlin: 'kotlin', swift: 'swift',
    rust: 'rust', ruby: 'ruby', php: 'php', scala: 'scala', dart: 'dart', racket: 'scheme',
    erlang: 'plaintext', elixir: 'elixir',
  }
  return map[langSlug] ?? 'plaintext'
}
