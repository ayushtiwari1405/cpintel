import {
  app, BrowserWindow, ipcMain, shell, dialog,
  Menu, Tray, nativeImage, nativeTheme, type IpcMainInvokeEvent,
} from 'electron'
import fs from 'fs'
import path from 'path'
import Store from 'electron-store'
import { Lockdown, disposeOnQuit, type LockdownPolicy, type LockdownState } from './lockdown'
import { connectCodeforces, fetchCodeforces, forgetCodeforces } from './cfSession'

const store = new Store()

let mainWindow:   BrowserWindow | null = null
let tray:         Tray | null = null

const IS_DEV = !app.isPackaged
const DEV_URL  = 'http://localhost:5173'

/**
 * The CPIntel server this installation talks to.
 *
 * <p>The desktop app is a window onto the central server, not a copy of it: the page, the API
 * and the examination all come from there, exactly as they do in a browser, with the lockdown
 * layered on top. A packaged build reads the address stamped in at build time
 * (scripts/stamp-server.mjs) and nothing else — an installer that could be repointed by a
 * setting could be repointed at a server the candidate runs themselves. It used to load a
 * bundled copy of the frontend from disk and spawn a local backend beside it, which needed
 * Postgres, Mongo and Redis on every lab machine and was never packaged, so the release build
 * did not work at all.
 */
function serverUrl(): string | null {
  if (IS_DEV) return process.env.CPINTEL_DEV_URL ?? DEV_URL
  try {
    const stamped = JSON.parse(fs.readFileSync(path.join(__dirname, 'server.json'), 'utf8'))
    return typeof stamped.url === 'string' ? stamped.url : null
  } catch {
    return null
  }
}

const APP_URL = serverUrl()
const APP_ORIGIN = APP_URL ? new URL(APP_URL).origin : null

/** Whether a URL is a page of the CPIntel server — the only origin this window may show. */
function isAppUrl(url: string): boolean {
  try {
    return APP_ORIGIN !== null && new URL(url).origin === APP_ORIGIN
  } catch {
    return false
  }
}

/**
 * Whether an IPC call came from the CPIntel server's own page.
 *
 * <p>The preload hands the page the lockdown, the Codeforces sign-in and a key-value store. With
 * the page now loaded over the network, anything else that ended up in this window — a page
 * reached by a redirect, a frame — must not get them. Checked on every handler, not once.
 */
function trusted(event: IpcMainInvokeEvent): boolean {
  const frame = event.senderFrame
  return !!frame && frame === event.sender.mainFrame && isAppUrl(frame.url)
}

/** Registers an IPC handler that answers only the CPIntel page. */
function handle(channel: string, fn: (event: IpcMainInvokeEvent, ...args: any[]) => unknown) {
  ipcMain.handle(channel, (event, ...args) => {
    if (!trusted(event)) throw new Error(`${channel}: refused for ${event.senderFrame?.url}`)
    return fn(event, ...args)
  })
}

/**
 * Contest lockdown. Owns every hook it installs, so engaging and releasing are symmetric and
 * a crash cannot leave the desktop in kiosk mode — see lockdown.ts for what it can and, more
 * importantly, cannot actually prevent.
 */
const lockdown = new Lockdown(isAppUrl, (state: LockdownState) => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('lockdown:changed', state)
  }
})

// ── Window ────────────────────────────────────────────────────

function createWindow(): void {
  const bounds = store.get('windowBounds', { width: 1280, height: 800 }) as any

  mainWindow = new BrowserWindow({
    width:  bounds.width,
    height: bounds.height,
    x: bounds.x,
    y: bounds.y,
    minWidth:  900,
    minHeight: 600,
    title: 'CPIntel',
    // Painted before the page loads; matches the page background the renderer will pick
    // when the user has no saved theme (it follows the OS).
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#030712' : '#f4f5f7',
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'default',
    webPreferences: {
      preload:          path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration:  false,
      webSecurity:      !IS_DEV,
    },
  })

  mainWindow.loadURL(APP_URL!)
  if (IS_DEV) mainWindow.webContents.openDevTools()

  // The server may be down, or the lab network not up yet. A blank window reads as a broken
  // install; this says what is wrong and keeps trying, so the app is ready the moment the
  // server is.
  mainWindow.webContents.on('did-fail-load', (_e, code, description, url, isMainFrame) => {
    if (!isMainFrame || code === -3 /* aborted by a newer navigation */) return
    if (!isAppUrl(url)) return
    mainWindow?.loadURL(offlinePage(description))
  })

  // This window shows the CPIntel server and nothing else. A link elsewhere opens in the
  // system browser (when no examination forbids it); the lockdown counts the attempt when one
  // is engaged.
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (isAppUrl(url) || url.startsWith('data:')) return
    event.preventDefault()
    if (!lockdown.isEngaged()) shell.openExternal(url)
  })
  mainWindow.webContents.on('will-redirect', (event, url) => {
    if (!isAppUrl(url)) event.preventDefault()
  })

  // Save window bounds on resize/move
  const saveBounds = () => {
    if (mainWindow) store.set('windowBounds', mainWindow.getBounds())
  }
  mainWindow.on('resize', saveBounds)
  mainWindow.on('move',   saveBounds)

  // Open external links in browser — but never while a contest is locked down, where a new
  // window is the simplest way straight out of everything the lock is doing. Only http(s):
  // shell.openExternal will launch whatever handles a scheme, and a page should not get to
  // pick an arbitrary program to start.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (lockdown.isEngaged()) {
      lockdown.countBlocked()
      return { action: 'deny' }
    }
    if (/^https?:\/\//i.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })

  lockdown.attach(mainWindow)

  mainWindow.on('closed', () => { mainWindow = null })
}

/** Shown when the server cannot be reached; retries on its own every five seconds. */
function offlinePage(reason: string): string {
  const dark = nativeTheme.shouldUseDarkColors
  const html = `<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="refresh" content="5;url=${APP_URL}">
<title>CPIntel</title>
<style>body{font:14px system-ui,sans-serif;display:flex;align-items:center;justify-content:center;
height:100vh;margin:0;background:${dark ? '#030712' : '#f4f5f7'};color:${dark ? '#d1d5db' : '#374151'}}
div{max-width:420px;text-align:center}h1{font-size:18px;margin:0 0 8px}p{margin:4px 0;opacity:.8}
code{font-size:12px;opacity:.6}</style></head><body><div>
<h1>Cannot reach the CPIntel server</h1>
<p>${APP_ORIGIN}</p>
<p>Check the network connection. This page tries again every five seconds.</p>
<p><code>${reason.replace(/[<>&]/g, '')}</code></p></div></body></html>`
  return 'data:text/html;charset=utf-8,' + encodeURIComponent(html)
}

function createTray(): void {
  const icon = nativeImage.createEmpty()
  tray = new Tray(icon)
  tray.setToolTip('CPIntel')
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Show CPIntel', click: () => mainWindow?.show() },
    { type: 'separator' },
    { label: 'Quit',         click: () => app.quit() },
  ]))
  tray.on('click', () => mainWindow?.show())
}

// ── App lifecycle ─────────────────────────────────────────────

app.whenReady().then(() => {
  if (!APP_URL) {
    // A release build made without CPINTEL_SERVER_URL. Refusing to start beats opening a
    // window with nothing in it.
    dialog.showErrorBox('CPIntel',
      'This installation does not know which CPIntel server to use. It was built without '
      + 'CPINTEL_SERVER_URL — ask whoever provided it for a correct build.')
    app.quit()
    return
  }
  createWindow()
  if (process.platform !== 'linux') createTray()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

disposeOnQuit(lockdown)

// ── IPC Handlers ──────────────────────────────────────────────

handle('app:version', () => app.getVersion())
handle('app:platform', () => process.platform)

handle('store:get', (_e, key: string) => store.get(key))
handle('store:set', (_e, key: string, value: unknown) => {
  store.set(key, value)
})

handle('shell:openExternal', (_e, url: string, allowedInLockdown = false) => {
  // The renderer marks the few links that stay open during a contest — the problem on the
  // judge's own site, which a contestant is entitled to read. Everything else is refused.
  // An examination that did not ask for external applications to be refused does not get them
  // refused: the restriction belongs to the paper, not to the installation.
  if (lockdown.blocksExternalApps() && !allowedInLockdown) {
    lockdown.countBlocked()
    return
  }
  if (/^https?:\/\//i.test(url)) shell.openExternal(url)
})

// ── Codeforces ────────────────────────────────────────────────

/**
 * Sign in to Codeforces in a window of our own, and hand the session to the backend.
 *
 * <p>The cookie is deliberately not returned to the renderer — main posts it and the page learns
 * only the handle, which is the same property the helper process has in the web build.
 *
 * <p>Refused while a contest is locked down. A full Codeforces window is a browser, and handing
 * a contestant one mid-round is the shortest way around everything the lock is doing; the moment
 * to connect is before the round starts. The lock counts it as a blocked navigation so the
 * refusal shows up in the round's record rather than only in this reply.
 */
handle('cf:connect', async (_e, apiBase: string, token: string, handle?: string) => {
  if (lockdown.isEngaged()) {
    lockdown.countBlocked()
    return {
      connected: false,
      error: 'Codeforces cannot be connected during a locked-down contest. '
        + 'Connect before the round starts.',
    }
  }
  // The session goes to the CPIntel server and nowhere else. The page says where the API is,
  // and a page that named some other host would be asking for a live Codeforces login to be
  // posted to it.
  if (!isAppUrl(apiBase)) {
    return { connected: false, error: 'Refused: the API address is not this CPIntel server.' }
  }
  try {
    return await connectCodeforces(apiBase, token, handle, mainWindow ?? undefined)
  } catch (e: any) {
    // A closed window and a refused session are both ordinary outcomes, not crashes, and the
    // renderer has to tell the user which one happened.
    return { connected: false, error: e?.message ?? String(e) }
  }
})

handle('cf:forget', () => forgetCodeforces())

// Allowed during a lockdown: a Codeforces round is sat through these requests. The URL is
// checked inside fetchCodeforces — codeforces.com only.
handle('cf:fetch', async (_e, request) => {
  try {
    return await fetchCodeforces(request)
  } catch (e: any) {
    return { error: e?.message ?? String(e) }
  }
})

// ── Lockdown ──────────────────────────────────────────────────

handle('lockdown:engage', (_e, reason: string, policy?: Partial<LockdownPolicy>) =>
  lockdown.engage(reason, policy))
handle('lockdown:release', () => lockdown.release())
handle('lockdown:state', () => lockdown.state())
