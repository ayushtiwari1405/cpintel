import {
  app, BrowserWindow, ipcMain, shell,
  Menu, Tray, nativeImage, protocol
} from 'electron'
import path from 'path'
import { spawn, ChildProcess } from 'child_process'
import Store from 'electron-store'

const store = new Store()

let mainWindow:   BrowserWindow | null = null
let tray:         Tray | null = null
let backendProc:  ChildProcess | null = null

const IS_DEV   = process.env.NODE_ENV === 'development'
const DEV_URL  = 'http://localhost:5173'
const PROD_DIR = path.join(process.resourcesPath, 'frontend')

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
      SPRING_DATASOURCE_URL: 'jdbc:oracle:thin:@localhost:1521/XEPDB1',
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

  // Open external links in browser
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })

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

// ── IPC Handlers ──────────────────────────────────────────────

ipcMain.handle('app:version', () => app.getVersion())
ipcMain.handle('app:platform', () => process.platform)

ipcMain.handle('store:get', (_e, key: string) => store.get(key))
ipcMain.handle('store:set', (_e, key: string, value: unknown) => {
  store.set(key, value)
})

ipcMain.handle('shell:openExternal', (_e, url: string) => {
  shell.openExternal(url)
})
