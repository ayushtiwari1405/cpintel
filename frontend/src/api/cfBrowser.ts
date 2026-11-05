import { apiClient } from './client'
import { whileCfWindowOpen } from '@/utils/cfWindowExcuse'
import type { ApiResponse, CfSessionStatus, LanguageOption, ProblemDetail } from '@/types'

/**
 * Codeforces through the user's own browser.
 *
 * <p>A hosted CPIntel server cannot fetch Codeforces' website: Cloudflare ties the clearance a
 * browser earns to that browser, so the server only ever gets the challenge page. The user's
 * browser can. So statements, compiler lists and submissions are fetched here — by the desktop
 * app's own Codeforces session, or on the website by the CPIntel browser extension — and the
 * pages handed to the server, which reads them exactly as it reads its own fetches
 * (/api/v1/cf-browser). With neither available the server fetches as before, which works in
 * development where it shares a machine with the browser.
 */

const CF = 'https://codeforces.com'

export type CfRelay = 'desktop' | 'extension'

interface CfPage { status: number; url: string; body: string }
interface CfRequest { method?: 'GET' | 'POST'; url: string; form?: Record<string, string> }

/** Which browser-side route is available on this page, if any. */
export function cfRelay(): CfRelay | null {
  if (typeof window === 'undefined') return null
  if (window.cpintelDesktop?.cf?.fetch) return 'desktop'
  if (document.documentElement.hasAttribute('data-cpintel-cf')) return 'extension'
  return null
}

/** Codeforces' "checking your browser" page, which the user has to pass once, in a tab. */
export function isBrowserCheck(body: string): boolean {
  return !body.includes('problem-statement') && (body.includes('Just a moment')
    || body.includes('cf_chl_opt') || body.includes('__cf_chl_')
    || body.includes('Your browser is being checked'))
}

export class CfBrowserError extends Error {
  constructor(message: string, readonly browserCheck = false) { super(message) }
}

let nextId = 0

/** Talks to the extension's content script through window messages. */
function viaExtension(message: { kind: 'fetch' | 'open' | 'verify' | 'signin'; request?: CfRequest; url?: string },
                      timeoutMs = 45_000): Promise<any> {
  const id = `cf-${Date.now()}-${nextId++}`
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      window.removeEventListener('message', onMessage)
      reject(new CfBrowserError('The CPIntel extension did not answer.'))
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

/** One request to codeforces.com, made by this browser. Throws on the browser check. */
export async function cfFetch(request: CfRequest): Promise<CfPage> {
  const relay = cfRelay()
  let answer: any
  if (relay === 'desktop') answer = await window.cpintelDesktop!.cf.fetch!(request)
  else if (relay === 'extension') answer = await viaExtension({ kind: 'fetch', request })
  else throw new CfBrowserError('No browser connection to Codeforces on this page.')

  if (answer?.error) throw new CfBrowserError(answer.error)
  const page = answer as CfPage
  if (isBrowserCheck(page.body)) {
    throw new CfBrowserError(
      'Codeforces wants to check this browser first. Open codeforces.com, let the check '
        + 'finish, then try again.', true)
  }
  return page
}

/**
 * Opens codeforces.com where a person can pass its browser check: in the desktop app, its own
 * Codeforces window, which closes itself once the check is through and resolves then. A system
 * browser would not do there — its cookies are not the app's.
 */
export async function openCodeforces(): Promise<void> {
  if (cfRelay() === 'extension') {
    viaExtension({ kind: 'open' }).catch(() => window.open(CF, '_blank', 'noopener'))
  } else if (window.cpintelDesktop?.cf?.open) {
    await window.cpintelDesktop.cf.open(`${CF}/`)
  } else if (window.cpintelDesktop) {
    window.cpintelDesktop.openExternal(CF)
  } else {
    window.open(CF, '_blank', 'noopener')
  }
}

/** A contest as CPIntel stores it: a number, or `<group>~<id>` for a private group's contest. */
export type CfContestId = number | string

/** Its number, its group (null for a public contest), and its path on codeforces.com. */
export function cfContest(ref: CfContestId) {
  const text = String(ref).trim()
  const m = /^([A-Za-z0-9]{1,40})~(\d+)$/.exec(text)
  const id = Number(m ? m[2] : text)
  const group = m ? m[1] : null
  return { id, group, path: group ? `/group/${group}/contest/${id}` : `/contest/${id}` }
}

const problemUrl = (ref: CfContestId, index: string, contest: boolean) => contest
  ? `${CF}${cfContest(ref).path}/problem/${index.toUpperCase()}`
  : `${CF}/problemset/problem/${cfContest(ref).id}/${index.toUpperCase()}`

const submitUrl = (ref: CfContestId | undefined, contest: boolean) => contest && ref !== undefined
  ? `${CF}${cfContest(ref).path}/submit`
  : `${CF}/problemset/submit`

// ── operations ─────────────────────────────────────────────────────────────

/** Links the handle signed in on this browser. CPIntel keeps the handle, not the cookies. */
export async function browserConnect(): Promise<CfSessionStatus> {
  let page = await cfFetch({ url: `${CF}/` })
  if (!isSignedIn(page.body) && canSignInHere()) {
    // Signed out in this browser: sign in in a small Codeforces window, then carry on, so a
    // candidate can connect from the examination page without leaving it.
    const answer = await whileCfWindowOpen(() => viaExtension({ kind: 'signin' }, 4 * 60_000))
    if (answer?.error) throw new CfBrowserError(answer.error)
    page = await cfFetch({ url: `${CF}/` })
  }
  const res = await apiClient.post<ApiResponse<CfSessionStatus>>('/cf-browser/connect',
    { html: page.body })
  return res.data.data
}

export async function browserStatement(contestRef: CfContestId, index: string,
                                       contest: boolean): Promise<ProblemDetail> {
  const page = await cfFetch({ url: problemUrl(contestRef, index, contest) })
  const { id, group } = cfContest(contestRef)
  const res = await apiClient.post<ApiResponse<ProblemDetail>>('/cf-browser/statement',
    { contestId: id, group, index, contest, html: page.body })
  return res.data.data
}

/** The compilers on the submit page; empty when this browser is signed out. */
export async function browserLanguages(contestId: CfContestId | undefined,
                                       contest: boolean): Promise<LanguageOption[]> {
  const page = await cfFetch({ url: submitUrl(contestId, contest) })
  const res = await apiClient.post<ApiResponse<LanguageOption[]>>('/cf-browser/languages',
    { html: page.body })
  return res.data.data
}

/**
 * Submits through this browser: read the form off the submit page, have the server archive the
 * code and fill the form in, post it from here, and let the server read the new submission's id
 * off Codeforces' answer. Verdicts are then polled from the public API as before.
 */
export async function browserSubmit(body: {
  contestId: CfContestId; index: string; contest: boolean; languageId: string; source: string
}): Promise<number> {
  const page = await cfFetch({ url: submitUrl(body.contestId, body.contest) })
  const { id, group } = cfContest(body.contestId)
  const prepared = (await apiClient.post<ApiResponse<{
    archiveId: string; url: string; fields: Record<string, string>
  }>>('/cf-browser/submit/prepare', { ...body, contestId: id, group, html: page.body })).data.data

  const submitPage = submitUrl(body.contestId, body.contest)
  let answer: CfPage
  if (antiBotSeen) {
    answer = await postVerified(submitPage, prepared)
  } else {
    answer = await cfFetch({ method: 'POST', url: prepared.url, form: prepared.fields })
    if (isAntiBotRefusal(answer.body)) {
      antiBotSeen = true
      answer = await postVerified(submitPage, prepared)
    }
  }
  const done = await apiClient.post<ApiResponse<{ submissionId: number }>>(
    '/cf-browser/submit/complete',
    { archiveId: prepared.archiveId, contest: body.contest, html: answer.body })
  return done.data.data.submissionId
}

// ── Codeforces' anti-bot check on submitting ─────────────────────────────────

/**
 * Set once Codeforces has asked this page for its anti-bot check, so later submissions pass it
 * first instead of being refused once more.
 */
let antiBotSeen = false

/** Codeforces' answer to a submission that came without the anti-bot check's token. */
export function isAntiBotRefusal(body: string): boolean {
  return /anti-bot verification/i.test(body)
}

/**
 * The fields the server filled in for this submission. Everything else is taken from the submit
 * page as the real browser rendered it: the check's token, and whatever else its scripts add.
 */
const SUBMISSION_FIELDS = new Set(['csrf_token', 'action', 'submittedProblemIndex',
  'submittedProblemCode', 'programTypeId', 'source', 'tabSize', 'sourceFile'])

export function withVerifiedFields(prepared: Record<string, string>,
                                   live: Record<string, string>): Record<string, string> {
  const out = { ...prepared }
  for (const [name, value] of Object.entries(live)) {
    if (!SUBMISSION_FIELDS.has(name)) out[name] = value
  }
  return out
}

/** The submit page in a real window until the check has passed: that page's form fields. */
async function verifiedFields(submitPage: string): Promise<Record<string, string>> {
  let answer: any
  const relay = cfRelay()
  if (relay === 'desktop') {
    const verify = window.cpintelDesktop!.cf.verify
    if (!verify) {
      throw new CfBrowserError('Codeforces wants an anti-bot check that this version of the '
        + 'desktop app cannot show. Update the app, then submit again.')
    }
    answer = await whileCfWindowOpen(() => verify(submitPage))
  } else if (relay === 'extension') {
    const version = document.documentElement.getAttribute('data-cpintel-cf') ?? '0'
    if (olderThan(version, '0.2.0')) {
      throw new CfBrowserError('Codeforces wants an anti-bot check that this version of the '
        + 'CPIntel extension cannot show. Install the new one from the Extension page, then submit again.')
    }
    answer = await whileCfWindowOpen(() => viaExtension({ kind: 'verify', url: submitPage }, 4 * 60_000))
  } else {
    throw new CfBrowserError('No browser connection to Codeforces on this page.')
  }
  if (answer?.error) throw new CfBrowserError(answer.error)
  return answer.fields as Record<string, string>
}

async function postVerified(submitPage: string,
                            prepared: { url: string; fields: Record<string, string> }): Promise<CfPage> {
  const live = await verifiedFields(submitPage)
  return cfFetch({ method: 'POST', url: prepared.url, form: withVerifiedFields(prepared.fields, live) })
}

/** Whether dotted version a is older than b. */
export function olderThan(a: string, b: string): boolean {
  const x = a.split('.').map(n => parseInt(n, 10) || 0)
  const y = b.split('.').map(n => parseInt(n, 10) || 0)
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) < (y[i] ?? 0)
  }
  return false
}

/** Whether a Codeforces page shows a signed-in account: only then is there a logout link. */
export function isSignedIn(body: string): boolean {
  return /href="[^"]*\/logout/.test(body)
}

/** The extension can show a Codeforces sign-in window from version 0.3.0. */
function canSignInHere(): boolean {
  if (cfRelay() !== 'extension') return false
  return !olderThan(document.documentElement.getAttribute('data-cpintel-cf') ?? '0', '0.3.0')
}
