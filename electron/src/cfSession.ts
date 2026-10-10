import { BrowserWindow, session, type Cookie, type Session } from 'electron'

/**
 * Connecting Codeforces from inside the desktop app, without a helper process.
 *
 * <h2>Why this exists</h2>
 *
 * Codeforces publishes no login API, and its pages sit behind a browser check that an ordinary
 * HTTP client cannot pass. The web build works around both with a helper script that reads the
 * session out of whichever browser the user signed in with — the only thing a web page can do,
 * since JavaScript cannot read an HttpOnly cookie belonging to another origin.
 *
 * The desktop app is a browser. It can simply show Codeforces its own sign-in page, let the user
 * sign in there exactly as they would anywhere, and then read the cookies out of the session
 * that window used. Nothing is circumvented and nothing is copied out of Chrome or Firefox: the
 * check is passed the way it is meant to be, by a person in a real browser.
 *
 * <p>Two properties are kept from the helper design on purpose:
 *
 * <ul>
 *   <li>The cookie never reaches the renderer. Main posts it to the CPIntel backend itself and
 *       answers the page with a handle, so a bug in the UI cannot leak a live credential.</li>
 *   <li>The User-Agent travels with the cookies. The clearance that records having passed the
 *       check is bound to the User-Agent that earned it, so the backend has to replay the exact
 *       string this window sent — which is why it is read back off the window rather than
 *       assumed.</li>
 * </ul>
 *
 * <h2>Why the User-Agent is left alone</h2>
 *
 * <p>This window announces itself as Electron, and that is deliberate. Dressing it as plain
 * Chrome looks tidier and stops the check passing altogether — measured, twice, loading
 * {@code /enter} in a visible window:
 *
 * <pre>
 *   User-Agent overridden to Chrome/126   "Just a moment..." at 3s, 5s, 7s, 10s — never clears
 *   Electron's own default                challenge at 3s, through by 5s, real page
 * </pre>
 *
 * <p>The reason is visible from inside the page: an overridden {@code navigator.userAgent} says
 * Chrome while {@code navigator.userAgentData.brands} and the client-hint headers Chromium sends
 * still say what this really is. Cloudflare compares them, and a client lying about itself is
 * exactly what its check is looking for. Presented honestly, an Electron window is an ordinary
 * browser with a person in front of it and passes in about two seconds.
 *
 * <p>The session lives in its own persistent partition. It is deliberately not the app's own
 * session: Codeforces cookies have no business riding along with requests to the CPIntel
 * backend, and keeping them apart means signing out of one is not signing out of the other. It
 * persists so that reconnecting later is usually a single click with no password.
 */

const PARTITION = 'persist:codeforces'
const SIGN_IN_URL = 'https://codeforces.com/enter'

/** Third-party analytics. Mirrors CfSessionStore.isRelevant() and the helper — keep in step. */
const NOISE_EXACT = new Set(['_GA', '_GID', '_FBP', '_CLCK', '_CLSK'])
const NOISE_PREFIX = ['_GA_', '_GAT', '__UTM', '_HJ']

function isRelevant(name: string): boolean {
  const n = name.toUpperCase()
  return !NOISE_EXACT.has(n) && !NOISE_PREFIX.some(p => n.startsWith(p))
}

export interface ConnectResult {
  connected: boolean
  handle: string
}

export class CfConnectError extends Error {}

/** Every codeforces.com cookie the session holds, as one Cookie header. */
async function cookieHeader(sess: Session): Promise<{ header: string; names: string[] }> {
  const jar: Cookie[] = await sess.cookies.get({ domain: 'codeforces.com' })
  const kept = jar.filter(c => isRelevant(c.name))
  return {
    header: kept.map(c => `${c.name}=${c.value}`).join('; '),
    names: kept.map(c => c.name).sort(),
  }
}

/**
 * Hand the session to CPIntel, which is what decides whether it is any good.
 *
 * No verdict is reached here. The backend asks Codeforces who the session belongs to, refuses a
 * handle that is not the one the user claimed, and stores nothing until both hold — so guessing
 * locally could only ever disagree with the authority.
 */
async function pushToBackend(
  apiBase: string, token: string, header: string, userAgent: string, expected?: string,
): Promise<ConnectResult> {
  let res: Response
  try {
    res = await fetch(`${apiBase}/practice/cf-session`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ cookieHeader: header, userAgent, expectedHandle: expected }),
    })
  } catch (e: any) {
    throw new CfConnectError(`Could not reach CPIntel: ${e?.message ?? e}`)
  }

  const body: any = await res.json().catch(() => ({}))
  if (!res.ok) {
    throw new CfConnectError(body?.message || body?.error || `CPIntel answered ${res.status}`)
  }
  return { connected: true, handle: body?.data?.handle ?? expected ?? '' }
}

/**
 * Open Codeforces, wait for the user to sign in, then connect the session.
 *
 * <p>Signing in is detected by watching where the window ends up rather than by looking for a
 * particular cookie. Codeforces leaves {@code /enter} once the credentials are accepted, and
 * which cookies mark a signed-in session is its business and has changed before; the backend
 * verifies the session anyway, so a premature attempt costs one rejected request and the window
 * stays open for the user to finish. That is the failure this shape is chosen for — the
 * alternative, closing on a cookie that turns out to mean nothing, fails silently.
 *
 * <p>Resolves when the backend accepts the session. Rejects if the user closes the window.
 */
export function connectCodeforces(
  apiBase: string, token: string, expected?: string, parent?: BrowserWindow,
): Promise<ConnectResult> {
  if (!token) return Promise.reject(new CfConnectError('Not signed in to CPIntel.'))

  const sess = session.fromPartition(PARTITION)

  const win = new BrowserWindow({
    width: 1000,
    height: 760,
    parent,
    title: 'Sign in to Codeforces',
    backgroundColor: '#ffffff',
    autoHideMenuBar: true,
    webPreferences: {
      session: sess,
      // Nothing of ours runs in this window. It shows a third-party site and must have no
      // preload, no Node and no bridge to reach for.
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })

  // Whatever this window will actually send, read back rather than chosen. The backend replays
  // it verbatim, and the clearance is void under any other string.
  const userAgent = win.webContents.getUserAgent()

  return new Promise<ConnectResult>((resolve, reject) => {
    let settled = false
    let attempting = false

    const finish = (fn: () => void) => {
      if (settled) return
      settled = true
      fn()
      if (!win.isDestroyed()) win.destroy()
    }

    const tryConnect = async () => {
      if (settled || attempting) return
      // Still on the sign-in page, so there is nothing to take yet.
      if (win.isDestroyed() || win.webContents.getURL().includes('/enter')) return

      attempting = true
      try {
        const { header, names } = await cookieHeader(sess)
        // A jar with nothing in it is a page that has not set anything yet, not a failure.
        if (!header) return
        console.log('[cf] connecting session with cookies:', names.join(', '))
        const result = await pushToBackend(apiBase, token, header, userAgent, expected)
        finish(() => resolve(result))
      } catch (e: any) {
        // Leave the window open: the usual cause is that the user has not finished signing in,
        // and closing it here would take away the only way to finish.
        console.warn('[cf] session not accepted yet:', e?.message ?? e)
      } finally {
        attempting = false
      }
    }

    win.webContents.on('did-navigate', tryConnect)
    win.webContents.on('did-navigate-in-page', tryConnect)

    win.on('closed', () => {
      finish(() => reject(new CfConnectError('Sign-in window was closed.')))
    })

    win.loadURL(SIGN_IN_URL).catch((e: any) => {
      finish(() => reject(new CfConnectError(`Could not open Codeforces: ${e?.message ?? e}`)))
    })
  })
}

/**
 * A Codeforces window of the app's own, over the CPIntel window like any sign-in popup, that
 * closes itself once {@code done} says its job is finished. Resolves true then, false if the
 * user closes it first.
 *
 * <p>It shares the session {@link fetchCodeforces} uses, which is the point of it: a sign-in or
 * a passed browser check here is one the app's own requests carry. A system browser has a
 * cookie jar of its own, so signing in there is invisible to the app.
 */
function codeforcesWindow(
  url: string, title: string, parent: BrowserWindow | undefined,
  done: (win: BrowserWindow) => Promise<boolean>,
): Promise<boolean> {
  const win = new BrowserWindow({
    width: 1000,
    height: 760,
    parent,
    title,
    backgroundColor: '#ffffff',
    autoHideMenuBar: true,
    webPreferences: {
      session: session.fromPartition(PARTITION),
      // A third-party site: no preload, no Node, no bridge.
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })
  // Links Codeforces opens in a new tab (its Google sign-in among them) stay in a window of
  // this session too, rather than going to a browser whose sign-in the app cannot see.
  win.webContents.setWindowOpenHandler(({ url: next }) => {
    if (/^https:\/\//i.test(next)) win.loadURL(next)
    return { action: 'deny' }
  })

  return new Promise(resolve => {
    let settled = false
    const check = async () => {
      if (settled || win.isDestroyed()) return
      try {
        if (await done(win)) {
          settled = true
          resolve(true)
          win.destroy()
        }
      } catch {
        // A page mid-navigation cannot be asked anything; the next load asks again.
      }
    }
    win.webContents.on('did-finish-load', check)
    win.webContents.on('did-navigate-in-page', check)
    win.on('closed', () => {
      if (!settled) { settled = true; resolve(false) }
    })
    win.loadURL(url).catch(() => { /* reported by the page itself */ })
  })
}

/** Cloudflare's "checking your browser" page, from inside it. */
const IS_CHECK_PAGE = `document.title.includes('Just a moment')
  || !!document.querySelector('#challenge-form, #cf-challenge-running, [name="cf-turnstile-response"]')`

/** Codeforces' header has a logout link only for a signed-in user. */
const IS_SIGNED_IN = `!!document.querySelector('a[href*="/logout"]')`

/**
 * Sign in to Codeforces. Resolves true once the window shows a signed-in page — at once, with no
 * password, when the stored session is still good — and false if the user closes it.
 *
 * <p>Nothing is posted anywhere from here. The page then reads codeforces.com through
 * {@link fetchCodeforces} and hands that page to the server, as the website's extension does.
 * The server checking a copied cookie from its own address is what used to fail: Cloudflare ties
 * the cookie to the browser that earned it, so a sign-in that worked never connected.
 */
export function signInCodeforces(parent?: BrowserWindow): Promise<boolean> {
  return codeforcesWindow(SIGN_IN_URL, 'Sign in to Codeforces', parent, async win =>
    !win.webContents.getURL().includes('/enter')
      && await win.webContents.executeJavaScript(IS_SIGNED_IN) === true)
}

/** Shows Codeforces until its browser check has passed, then closes. */
export function openCodeforces(url: string, parent?: BrowserWindow): Promise<boolean> {
  if (new URL(url).origin !== 'https://codeforces.com') {
    return Promise.reject(new Error('Only https://codeforces.com can be opened.'))
  }
  return codeforcesWindow(url, 'Codeforces', parent, async win =>
    await win.webContents.executeJavaScript(IS_CHECK_PAGE) === false)
}

/** Forget the stored Codeforces sign-in, so the next connect starts from a clean window. */
/** A Codeforces request made for the page, as the extension makes them on the website. */
export interface CfFetchRequest { method?: 'GET' | 'POST'; url: string; form?: Record<string, string> }
export interface CfFetchResponse { status: number; url: string; body: string }

let headersInstalled = false

/**
 * Fetches a Codeforces page with this app's own Codeforces session.
 *
 * <p>A hosted CPIntel server cannot fetch Codeforces' website — Cloudflare ties its clearance to
 * the browser that earned it — so statements and submissions are fetched here instead and the
 * page handed back for the server to read. The sign-in window above earned the clearance in this
 * very session partition, which is why this works where a server replaying the cookies does not.
 * Only https://codeforces.com, GET or POST.
 */
export async function fetchCodeforces(request: CfFetchRequest): Promise<CfFetchResponse> {
  const url = new URL(request.url)
  if (url.origin !== 'https://codeforces.com') {
    throw new Error('Only https://codeforces.com can be fetched.')
  }
  const sess = session.fromPartition(PARTITION)

  if (!headersInstalled) {
    // Codeforces' forms expect to be posted from Codeforces. Requests from this session are
    // only ever this app's own, so they can all carry its Origin and Referer.
    sess.webRequest.onBeforeSendHeaders({ urls: ['https://codeforces.com/*'] }, (details, cb) => {
      cb({ requestHeaders: {
        ...details.requestHeaders,
        Origin: 'https://codeforces.com',
        Referer: 'https://codeforces.com/',
      } })
    })
    headersInstalled = true
  }

  let body: FormData | undefined
  if (request.method === 'POST') {
    body = new FormData()
    for (const [k, v] of Object.entries(request.form ?? {})) body.append(k, String(v))
  }
  const res = await sess.fetch(url.toString(), {
    method: request.method === 'POST' ? 'POST' : 'GET',
    body,
    redirect: 'follow',
  })
  return { status: res.status, url: res.url, body: await res.text() }
}

export async function forgetCodeforces(): Promise<void> {
  const sess = session.fromPartition(PARTITION)
  await sess.clearStorageData({ storages: ['cookies'] })
}
