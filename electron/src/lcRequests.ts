/**
 * The LeetCode requests the page may ask for, built here from checked arguments.
 *
 * <p>The page never names a URL: it asks for who is signed in, a run on test input, a
 * submission, or a result, and this decides the path and body. Anything else on LeetCode (the
 * profile, settings, other people's code) cannot be reached through the app's LeetCode session.
 * The browser extension builds the same requests the same way.
 */

export type LcOp = 'status' | 'run' | 'submit' | 'check'

export interface LcArgs {
  slug?: string
  questionId?: string | number
  lang?: string
  code?: string
  input?: string
  id?: string
}

export interface LcRequest { path: string; body?: Record<string, unknown> }

const SLUG = /^[a-z0-9-]{1,120}$/
const LANG = /^[a-z0-9]{1,20}$/
const QUESTION = /^\d{1,8}$/
const RESULT_ID = /^[A-Za-z0-9_.-]{1,80}$/
const MAX_CODE = 100 * 1024
const MAX_INPUT = 20 * 1024

function need(ok: boolean, what: string): void {
  if (!ok) throw new Error(`Invalid ${what}.`)
}

export function buildLcRequest(op: LcOp, args: LcArgs = {}): LcRequest {
  if (op === 'status') {
    return { path: '/graphql/', body: { query: '{ userStatus { isSignedIn username } }' } }
  }
  if (op === 'run' || op === 'submit') {
    need(SLUG.test(args.slug ?? ''), 'problem')
    need(QUESTION.test(String(args.questionId ?? '')), 'question id')
    need(LANG.test(args.lang ?? ''), 'language')
    need(typeof args.code === 'string' && args.code.length <= MAX_CODE, 'code')
    const body = { lang: args.lang, question_id: String(args.questionId), typed_code: args.code }
    if (op === 'run') {
      need(typeof args.input === 'string' && args.input.length <= MAX_INPUT, 'input')
      return { path: `/problems/${args.slug}/interpret_solution/`, body: { ...body, data_input: args.input } }
    }
    return { path: `/problems/${args.slug}/submit/`, body }
  }
  if (op === 'check') {
    need(RESULT_ID.test(String(args.id ?? '')), 'result id')
    return { path: `/submissions/detail/${args.id}/check/` }
  }
  throw new Error('Unknown LeetCode request.')
}

/** What the page gets back from a run, submit or status request. */
export function readLcAnswer(op: LcOp, json: any): unknown {
  if (op === 'status') {
    const s = json?.data?.userStatus
    return { signedIn: !!s?.isSignedIn, username: s?.isSignedIn ? s.username : null }
  }
  if (op === 'run') {
    if (!json?.interpret_id) throw new Error(json?.error || 'LeetCode did not start the run.')
    return { id: String(json.interpret_id) }
  }
  if (op === 'submit') {
    if (!json?.submission_id) throw new Error(json?.error || 'LeetCode did not take the submission.')
    return { id: String(json.submission_id) }
  }
  return json
}
