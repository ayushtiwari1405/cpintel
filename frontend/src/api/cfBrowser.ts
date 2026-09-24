import { apiClient } from './client'
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
function viaExtension(message: { kind: 'fetch' | 'open'; request?: CfRequest }): Promise<any> {
  const id = `cf-${Date.now()}-${nextId++}`
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      window.removeEventListener('message', onMessage)
      reject(new CfBrowserError('The CPIntel extension did not answer.'))
    }, 45_000)
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

/** Opens codeforces.com in a tab, where a person can pass its browser check. */
export function openCodeforces() {
  if (cfRelay() === 'extension') {
    viaExtension({ kind: 'open' }).catch(() => window.open(CF, '_blank', 'noopener'))
  } else if (window.cpintelDesktop) {
    window.cpintelDesktop.openExternal(CF)
  } else {
    window.open(CF, '_blank', 'noopener')
  }
}

const problemUrl = (contestId: number, index: string, contest: boolean) => contest
  ? `${CF}/contest/${contestId}/problem/${index.toUpperCase()}`
  : `${CF}/problemset/problem/${contestId}/${index.toUpperCase()}`

const submitUrl = (contestId: number | undefined, contest: boolean) => contest
  ? `${CF}/contest/${contestId}/submit`
  : `${CF}/problemset/submit`

// ── operations ─────────────────────────────────────────────────────────────

/** Links the handle signed in on this browser. CPIntel keeps the handle, not the cookies. */
export async function browserConnect(): Promise<CfSessionStatus> {
  const page = await cfFetch({ url: `${CF}/` })
  const res = await apiClient.post<ApiResponse<CfSessionStatus>>('/cf-browser/connect',
    { html: page.body })
  return res.data.data
}

export async function browserStatement(contestId: number, index: string,
                                       contest: boolean): Promise<ProblemDetail> {
  const page = await cfFetch({ url: problemUrl(contestId, index, contest) })
  const res = await apiClient.post<ApiResponse<ProblemDetail>>('/cf-browser/statement',
    { contestId, index, contest, html: page.body })
  return res.data.data
}

/** The compilers on the submit page; empty when this browser is signed out. */
export async function browserLanguages(contestId: number | undefined,
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
  contestId: number; index: string; contest: boolean; languageId: string; source: string
}): Promise<number> {
  const page = await cfFetch({ url: submitUrl(body.contestId, body.contest) })
  const prepared = (await apiClient.post<ApiResponse<{
    archiveId: string; url: string; fields: Record<string, string>
  }>>('/cf-browser/submit/prepare', { ...body, html: page.body })).data.data

  const answer = await cfFetch({ method: 'POST', url: prepared.url, form: prepared.fields })
  const done = await apiClient.post<ApiResponse<{ submissionId: number }>>(
    '/cf-browser/submit/complete',
    { archiveId: prepared.archiveId, contest: body.contest, html: answer.body })
  return done.data.data.submissionId
}
