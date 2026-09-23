import {
  app, BrowserWindow, ipcMain, shell,
  Menu, Tray, nativeImage, protocol
} from 'electron'
import path from 'path'
import { spawn, ChildProcess } from 'child_process'
import Store from 'electron-store'
import { Lockdown, disposeOnQuit, type LockdownPolicy, type LockdownState } from './lockdown'
import { connectCodeforces, forgetCodeforces } from './cfSession'

const store = new Store()

let mainWindow:   BrowserWindow | null = null
let tray:         Tray | null = null
let backendProc:  ChildProcess | null = null

const IS_DEV = !app.isPackaged
const DEV_URL  = 'http://localhost:5173'
const PROD_DIR = path.join(process.resourcesPath, 'frontend')

/**
 * Contest lockdown. Owns every hook it installs, so engaging and releasing are symmetric and
 * a crash cannot leave the desktop in kiosk mode — see lockdown.ts for what it can and, more
 * importantly, cannot actually prevent.
 */
const lockdown = new Lockdown(IS_DEV, (state: LockdownState) => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('lockdown:changed', state)
  }
})

// ── Backend lifecycle ─────────────────────────────────────────

function startBackend(): void {
  if (IS_DEV) return // Dev mode: backend started separately

  const javaPath = 'java'
  const jarPath  = path.join(process.resourcesPath, 'backend', 'app.jar')

  backendProc = spawn(javaPath, [
    '-XX:+UseContainerSupport',
    '-XX:MaxRAMPercentage=50.0',
    '-Dspring.profiles.active=desktop',
    '-jar', jarPath
  ], {
    env: {
      ...process.env,
      SPRING_DATASOURCE_URL:
        process.env.SPRING_DATASOURCE_URL ??
        `jdbc:postgresql://localhost:${process.env.POSTGRES_PORT ?? '5432'}/cpintel`,
    }
  })

  backendProc.stdout?.on('data', (d) => console.log('[backend]', d.toString().trim()))
  backendProc.stderr?.on('data', (d) => console.error('[backend]', d.toString().trim()))
  backendProc.on('exit', (code) => console.log('[backend] exited with code', code))
}

function stopBackend(): void {
  if (backendProc) {
    backendProc.kill('SIGTERM')
    backendProc = null
  }
}

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
    backgroundColor: '#030712',
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'default',
    webPreferences: {
      preload:          path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration:  false,
      webSecurity:      !IS_DEV,
    },
  })

  if (IS_DEV) {
    mainWindow.loadURL(DEV_URL)
    mainWindow.webContents.openDevTools()
  } else {
    mainWindow.loadFile(path.join(PROD_DIR, 'index.html'))
  }

  // Save window bounds on resize/move
  const saveBounds = () => {
    if (mainWindow) store.set('windowBounds', mainWindow.getBounds())
  }
  mainWindow.on('resize', saveBounds)
  mainWindow.on('move',   saveBounds)

  // Open external links in browser — but never while a contest is locked down, where a new
  // window is the simplest way straight out of everything the lock is doing.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (lockdown.isEngaged()) {
      lockdown.countBlocked()
      return { action: 'deny' }
    }
    shell.openExternal(url)
    return { action: 'deny' }
  })

  lockdown.attach(mainWindow)

  mainWindow.on('closed', () => { mainWindow = null })
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
  startBackend()
  createWindow()
  if (process.platform !== 'linux') createTray()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    stopBackend()
    app.quit()
  }
})

app.on('before-quit', stopBackend)
disposeOnQuit(lockdown)

// ── IPC Handlers ──────────────────────────────────────────────

ipcMain.handle('app:version', () => app.getVersion())
ipcMain.handle('app:platform', () => process.platform)

ipcMain.handle('store:get', (_e, key: string) => store.get(key))
ipcMain.handle('store:set', (_e, key: string, value: unknown) => {
  store.set(key, value)
})

ipcMain.handle('shell:openExternal', (_e, url: string, allowedInLockdown = false) => {
  // The renderer marks the few links that stay open during a contest — the problem on the
  // judge's own site, which a contestant is entitled to read. Everything else is refused.
  // An examination that did not ask for external applications to be refused does not get them
  // refused: the restriction belongs to the paper, not to the installation.
  if (lockdown.blocksExternalApps() && !allowedInLockdown) {
    lockdown.countBlocked()
    return
  }
  shell.openExternal(url)
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
ipcMain.handle('cf:connect', async (_e, apiBase: string, token: string, handle?: string) => {
  if (lockdown.isEngaged()) {
    lockdown.countBlocked()
    return {
      connected: false,
      error: 'Codeforces cannot be connected during a locked-down contest. '
        + 'Connect before the round starts.',
    }
  }
  try {
    return await connectCodeforces(apiBase, token, handle, mainWindow ?? undefined)
  } catch (e: any) {
    // A closed window and a refused session are both ordinary outcomes, not crashes, and the
    // renderer has to tell the user which one happened.
    return { connected: false, error: e?.message ?? String(e) }
  }
})

ipcMain.handle('cf:forget', () => forgetCodeforces())

// ── Lockdown ──────────────────────────────────────────────────

ipcMain.handle('lockdown:engage', (_e, reason: string, policy?: Partial<LockdownPolicy>) =>
  lockdown.engage(reason, policy))
ipcMain.handle('lockdown:release', () => lockdown.release())
ipcMain.handle('lockdown:state', () => lockdown.state())
