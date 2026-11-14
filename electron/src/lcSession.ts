import { BrowserWindow, session } from 'electron'
import { buildLcRequest, readLcAnswer, type LcArgs, type LcOp } from './lcRequests'

/**
 * LeetCode, signed in inside the app, for lab problems solved in CPIntel.
 *
 * <p>Like Codeforces, the sign-in lives in a session partition of the app's own and never
 * leaves this computer: the page asks for a run or a submission through {@link callLeetCode},
 * which builds the request (see lcRequests.ts) and sends it with this session's cookies.
 */

const LC = 'https://leetcode.com'
const PARTITION = 'persist:leetcode'

let headersInstalled = false

function lcSession() {
  const sess = session.fromPartition(PARTITION)
  if (!headersInstalled) {
    // LeetCode's API expects its own Origin and Referer; requests from this partition are only
    // ever the app's own.
    sess.webRequest.onBeforeSendHeaders({ urls: [`${LC}/*`] }, (details, cb) => {
      cb({ requestHeaders: { ...details.requestHeaders, Origin: LC, Referer: `${LC}/` } })
    })
    headersInstalled = true
  }
  return sess
}

async function cookie(name: string): Promise<string | null> {
  const found = await lcSession().cookies.get({ url: LC, name })
  return found[0]?.value ?? null
}

export async function callLeetCode(op: LcOp, args: LcArgs = {}): Promise<unknown> {
  const request = buildLcRequest(op, args)
  const csrf = await cookie('csrftoken')
  const headers: Record<string, string> = { 'content-type': 'application/json' }
  if (csrf) headers['x-csrftoken'] = csrf
  const res = await lcSession().fetch(LC + request.path, {
    method: request.body ? 'POST' : 'GET',
    headers,
    body: request.body ? JSON.stringify(request.body) : undefined,
  })
  const text = await res.text()
  let json: unknown = null
  try { json = JSON.parse(text) } catch { /* an HTML error page */ }
  if (res.status === 429) throw new Error('LeetCode says you are submitting too often. Wait a moment and try again.')
  if (res.status === 401 || res.status === 403) throw new Error('LeetCode refused: sign in to LeetCode again.')
  if (!res.ok || json === null) throw new Error(`LeetCode answered ${res.status}.`)
  return readLcAnswer(op, json)
}

/**
 * Signs in to LeetCode in the app's own window. Resolves with who is signed in, at once when
 * the stored session is still good, or null if the window is closed first.
 */
export async function signInLeetCode(parent?: BrowserWindow): Promise<{ signedIn: boolean; username: string | null } | null> {
  const already = await callLeetCode('status').catch(() => null) as { signedIn: boolean; username: string | null } | null
  if (already?.signedIn) return already

  const win = new BrowserWindow({
    width: 1000, height: 780, parent, title: 'Sign in to LeetCode',
    backgroundColor: '#ffffff', autoHideMenuBar: true,
    webPreferences: { session: lcSession(), contextIsolation: true, nodeIntegration: false, sandbox: true },
  })
  // Google and GitHub sign-in open new windows; keep them in this session.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//i.test(url)) win.loadURL(url)
    return { action: 'deny' }
  })

  return new Promise(resolve => {
    let settled = false
    const timer = setInterval(async () => {
      if (settled || win.isDestroyed()) return
      if (!(await cookie('LEETCODE_SESSION'))) return
      const status = await callLeetCode('status').catch(() => null) as { signedIn: boolean; username: string | null } | null
      if (status?.signedIn) {
        settled = true
        clearInterval(timer)
        resolve(status)
        win.destroy()
      }
    }, 1000)
    win.on('closed', () => {
      clearInterval(timer)
      if (!settled) { settled = true; resolve(null) }
    })
    win.loadURL(`${LC}/accounts/login/`).catch(() => { /* shown in the window */ })
  })
}

export async function forgetLeetCode(): Promise<void> {
  await session.fromPartition(PARTITION).clearStorageData({ storages: ['cookies'] })
}
