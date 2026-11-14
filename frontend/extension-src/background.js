// Fetches Codeforces pages for CPIntel, from this browser.
//
// Why a browser has to do it: Codeforces sits behind Cloudflare, which ties the clearance a
// browser earns to that browser. A server replaying the same cookies from its own address gets
// the challenge page, so CPIntel cannot fetch statements or submit on anyone's behalf from the
// server. This browser can: it is signed in, it has passed the check, and requests from here
// carry both.
//
// What it will do, and nothing else: GET or POST a URL on https://codeforces.com and hand back
// the status, final URL and body. The CPIntel server reads the pages and says what to post; this
// file knows nothing about Codeforces' pages. Only CPIntel's own pages can ask (see content.js
// and the matches in manifest.json).

const CF = 'https://codeforces.com'

// Requests from this service worker carry chrome-extension:// as their origin. Codeforces'
// forms expect to be posted from Codeforces, so those — and only those: tabId -1 is "not from
// any tab" — are sent with Codeforces' own Origin and Referer. Browsing codeforces.com in a tab
// is untouched.
async function installHeaderRule() {
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [1],
    addRules: [{
      id: 1,
      priority: 1,
      action: {
        type: 'modifyHeaders',
        requestHeaders: [
          { header: 'origin', operation: 'set', value: CF },
          { header: 'referer', operation: 'set', value: CF + '/' },
        ],
      },
      condition: {
        urlFilter: '|https://codeforces.com/',
        tabIds: [chrome.tabs.TAB_ID_NONE],
        resourceTypes: ['xmlhttprequest', 'other'],
      },
    }],
  })
}

// ---------------------------------------------------------------- LeetCode
//
// LeetCode's own site API, called with this browser's LeetCode sign-in. The page never names a
// URL or writes a request: it asks for one of four things (who is signed in, run, submit, read
// a result) and this builds the request, so nothing else on LeetCode can be done through it.

const LC = 'https://leetcode.com'

async function installLeetCodeRule() {
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [2],
    addRules: [{
      id: 2,
      priority: 1,
      action: {
        type: 'modifyHeaders',
        requestHeaders: [
          { header: 'origin', operation: 'set', value: LC },
          { header: 'referer', operation: 'set', value: LC + '/' },
        ],
      },
      condition: {
        urlFilter: '|https://leetcode.com/',
        tabIds: [chrome.tabs.TAB_ID_NONE],
        resourceTypes: ['xmlhttprequest', 'other'],
      },
    }],
  })
}

const SLUG = /^[a-z0-9-]{1,120}$/
const LANG = /^[a-z0-9]{1,20}$/
const QUESTION = /^\d{1,8}$/
const RESULT_ID = /^[A-Za-z0-9_.-]{1,80}$/
const MAX_CODE = 100 * 1024
const MAX_INPUT = 20 * 1024

function need(ok, what) { if (!ok) throw new Error(`Invalid ${what}.`) }

async function lcRequest(path, body) {
  await installLeetCodeRule()
  const csrf = await chrome.cookies.get({ url: LC, name: 'csrftoken' })
  const headers = { 'content-type': 'application/json' }
  if (csrf?.value) headers['x-csrftoken'] = csrf.value
  const res = await fetch(LC + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'include',
    cache: 'no-store',
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* an HTML error page */ }
  if (res.status === 429) throw new Error('LeetCode says you are submitting too often. Wait a moment and try again.')
  if (res.status === 401 || res.status === 403) throw new Error('LeetCode refused: sign in to LeetCode again.')
  if (!res.ok || json === null) throw new Error(`LeetCode answered ${res.status}.`)
  return json
}

async function lcCall(op, args = {}) {
  if (op === 'status') {
    const r = await lcRequest('/graphql/', { query: '{ userStatus { isSignedIn username } }' })
    const s = r?.data?.userStatus
    return { signedIn: !!s?.isSignedIn, username: s?.isSignedIn ? s.username : null }
  }
  if (op === 'run' || op === 'submit') {
    need(SLUG.test(args.slug ?? ''), 'problem')
    need(QUESTION.test(String(args.questionId ?? '')), 'question id')
    need(LANG.test(args.lang ?? ''), 'language')
    need(typeof args.code === 'string' && args.code.length <= MAX_CODE, 'code')
    const body = { lang: args.lang, question_id: String(args.questionId), typed_code: args.code }
    if (op === 'run') {
      need(typeof args.input === 'string' && args.input.length <= MAX_INPUT, 'input')
      const r = await lcRequest(`/problems/${args.slug}/interpret_solution/`, { ...body, data_input: args.input })
      if (!r.interpret_id) throw new Error(r.error || 'LeetCode did not start the run.')
      return { id: r.interpret_id }
    }
    const r = await lcRequest(`/problems/${args.slug}/submit/`, body)
    if (!r.submission_id) throw new Error(r.error || 'LeetCode did not take the submission.')
    return { id: String(r.submission_id) }
  }
  if (op === 'check') {
    need(RESULT_ID.test(String(args.id ?? '')), 'result id')
    return lcRequest(`/submissions/detail/${args.id}/check/`)
  }
  throw new Error('Unknown LeetCode request.')
}

async function lcSignedIn() {
  const c = await chrome.cookies.get({ url: LC, name: 'LEETCODE_SESSION' })
  return !!c?.value
}

/** Opens LeetCode's sign-in in a small window; resolves once a session cookie exists. */
async function lcSignIn() {
  if (await lcSignedIn()) {
    const s = await lcCall('status').catch(() => null)
    if (s?.signedIn) return s
  }
  const win = await chrome.windows.create({ url: LC + '/accounts/login/', type: 'popup', width: 900, height: 760, focused: true })
  const deadline = Date.now() + 5 * 60 * 1000
  try {
    while (Date.now() < deadline) {
      await new Promise(r => setTimeout(r, 1000))
      const alive = await chrome.windows.get(win.id).catch(() => null)
      if (!alive) throw new Error('The LeetCode sign-in window was closed.')
      if (await lcSignedIn()) {
        const s = await lcCall('status').catch(() => null)
        if (s?.signedIn) return s
      }
    }
    throw new Error('LeetCode sign-in did not finish in time.')
  } finally {
    chrome.windows.remove(win.id).catch(() => {})
  }
}

chrome.runtime.onInstalled.addListener(installLeetCodeRule)
chrome.runtime.onStartup.addListener(installLeetCodeRule)
chrome.runtime.onInstalled.addListener(installHeaderRule)
chrome.runtime.onStartup.addListener(installHeaderRule)

async function cfFetch(request) {
  const url = new URL(request.url)
  if (url.origin !== CF) throw new Error('Only https://codeforces.com can be fetched.')
  const method = request.method === 'POST' ? 'POST' : 'GET'

  let body
  if (method === 'POST') {
    body = new FormData()
    for (const [k, v] of Object.entries(request.form ?? {})) body.append(k, String(v))
  }

  await installHeaderRule()
  const res = await fetch(url.toString(), {
    method, body, credentials: 'include', redirect: 'follow', cache: 'no-store',
  })
  return { status: res.status, url: res.url, body: await res.text() }
}

// Run inside a Codeforces submit page: once Cloudflare's anti-bot widget has put its token on
// the page, the submit form's fields with that token among them; null until then.
function readVerifiedForm() {
  const named = /turnstile|captcha/i
  const fields = [...document.querySelectorAll('input[name], textarea[name]')]
  const token = fields.find(el => named.test(el.name) && el.value)
  if (!token) {
    // The window is small and the widget sits below the form: keep it in sight, for the times
    // it wants a click.
    const widget = document.querySelector('.cf-turnstile, iframe[src*="challenges.cloudflare.com"]')
      || fields.find(el => named.test(el.name))?.parentElement
    if (widget && !window.__cpintelScrolled) {
      widget.scrollIntoView({ block: 'center' })
      window.__cpintelScrolled = true
    }
    return null
  }
  const form = token.form || document.querySelector('form.submit-form')
    || [...document.forms].find(f => f.querySelector('[name=source], [name=sourceFile]'))
  const out = {}
  if (form) for (const [k, v] of new FormData(form)) if (typeof v === 'string') out[k] = v
  for (const el of fields) if (named.test(el.name) && el.value) out[el.name] = el.value
  return out
}

const SUBMIT_PAGE = /^\/((group\/[A-Za-z0-9]{1,40}\/)?(contest|gym)\/\d+\/submit|problemset\/submit)\/?$/
const VERIFY_TIMEOUT_MS = 3 * 60 * 1000
// The anti-bot check needs only its widget on screen: a small window, not a page to read.
const VERIFY_WINDOW = { width: 480, height: 360 }

// Where a window opened by CPIntel may be: the home page, sign-in, Cloudflare's check, and
// submit pages.
function allowedHere(raw) {
  let url
  try { url = new URL(raw) } catch { return false }
  if (url.origin !== CF) return false
  const path = url.pathname.replace(/\/+$/, '') || '/'
  return path === '/' || path === '/enter' || path.startsWith('/cdn-cgi/') || SUBMIT_PAGE.test(path)
}

// A small Codeforces window that stays until probe(), run inside its page, returns something;
// then it closes and that is the answer. Closing it first, or waiting too long, is an error.
async function cfWindow(url, probe, closedMessage, size = { width: 900, height: 720 }) {
  const win = await chrome.windows.create({ url, type: 'popup', ...size, focused: true })
  const tabId = win.tabs[0].id
  const deadline = Date.now() + VERIFY_TIMEOUT_MS
  try {
    while (Date.now() < deadline) {
      await new Promise(r => setTimeout(r, 500))
      // CPIntel excuses the time spent here, so the window stays on Codeforces' sign-in, check
      // and submit pages; anywhere else (a URL hidden from this extension is off Codeforces)
      // closes it.
      const tab = await chrome.tabs.get(tabId).catch(() => null)
      if (!tab) throw new Error(closedMessage)
      const at = tab.pendingUrl || tab.url
      if (at && !allowedHere(at)) throw new Error('The Codeforces window was closed: it left the sign-in.')
      if (!at && tab.status === 'complete') throw new Error('The Codeforces window was closed: it left Codeforces.')
      try {
        const [result] = await chrome.scripting.executeScript({ target: { tabId }, func: probe })
        if (result?.result) return result.result
      } catch {
        // Closed by the person, or a page still loading: tell the two apart.
        try { await chrome.tabs.get(tabId) } catch { throw new Error(closedMessage) }
      }
    }
    throw new Error('Codeforces did not finish in time.')
  } finally {
    chrome.windows.remove(win.id).catch(() => {})
  }
}

// Codeforces' anti-bot check is made by a widget running in a real page, so a fetched copy of
// the submit page never has its token. This opens the page in a small window, waits for the
// token (usually it fills in by itself; sometimes it wants a click), reads the form and closes.
async function cfVerify(rawUrl) {
  const url = new URL(rawUrl)
  if (url.origin !== CF || !SUBMIT_PAGE.test(url.pathname)) {
    throw new Error('Only a Codeforces submit page can be verified.')
  }
  return cfWindow(url.toString(), readVerifiedForm, 'The Codeforces check window was closed.',
    VERIFY_WINDOW)
}

// Run inside a Codeforces page: whether it shows a signed-in account.
function signedIn() {
  return !location.pathname.startsWith('/enter') && !!document.querySelector('a[href*="/logout"]')
}

// Sign in to Codeforces in this browser without leaving CPIntel's page: a small window on the
// sign-in page that closes as soon as an account is signed in. Nothing is read but that fact.
async function cfSignIn() {
  await cfWindow(CF + '/enter', signedIn, 'The Codeforces sign-in window was closed.')
  return true
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  // Only this extension's own content script, which only runs on CPIntel's pages.
  if (sender.id !== chrome.runtime.id || !message) return false

  if (message.type === 'cf-open') {
    // For the browser check: a real tab on codeforces.com is how a person passes it.
    chrome.tabs.create({ url: CF + '/' })
    sendResponse({ ok: true })
    return false
  }
  if (message.type === 'cf-signin') {
    cfSignIn().then(() => sendResponse({ ok: true }), e => sendResponse({ error: String(e?.message ?? e) }))
    return true
  }
  if (message.type === 'cf-verify') {
    cfVerify(message.url).then(fields => sendResponse({ fields }),
      e => sendResponse({ error: String(e?.message ?? e) }))
    return true
  }
  if (message.type === 'lc-signin') {
    lcSignIn().then(result => sendResponse({ result }), e => sendResponse({ error: String(e?.message ?? e) }))
    return true
  }
  if (message.type === 'lc-call') {
    lcCall(message.op, message.args).then(result => sendResponse({ result }),
      e => sendResponse({ error: String(e?.message ?? e) }))
    return true
  }
  if (message.type === 'cf-fetch') {
    cfFetch(message.request).then(sendResponse, e => sendResponse({ error: String(e?.message ?? e) }))
    return true   // answered asynchronously
  }
  return false
})
