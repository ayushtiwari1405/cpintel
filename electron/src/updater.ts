import { app, dialog, shell, type BrowserWindow } from 'electron'
import { spawn } from 'child_process'
import { createHash } from 'crypto'
import fs from 'fs'
import os from 'os'
import path from 'path'

/**
 * Installing a newer version when the student presses Update on the page's ribbon.
 *
 * Nothing happens on its own: the page shows the ribbon, the student chooses. The release comes
 * from the CPIntel server this app is built for (GET /api/v1/desktop/release), never from the
 * page, and the installer is checked against the SHA-256 the server reports before it is run.
 *
 * Because the app downloads it rather than a browser, the installer carries no "downloaded
 * from the internet" mark, so Windows SmartScreen and macOS Gatekeeper do not stop it the way
 * they stop a first install.
 *
 *  - Windows: runs the new installer and quits; it replaces this version and starts the new one.
 *  - Linux AppImage: replaces the AppImage file in place and restarts.
 *  - Linux .deb: opens the package in the system installer.
 *  - macOS: opens the disk image, or unpacks the .zip a server-side (Linux) build makes; the
 *    student drags CPIntel into Applications over this one.
 */

interface Asset {
  name: string
  platform: string
  arch: string
  format: string
  size: number
  sha256: string | null
  url: string
}

export interface UpdateProgress {
  phase: 'downloading' | 'verifying' | 'installing'
  done: number
  total: number
}

export type UpdateResult =
  | { ok: true; message: string }
  | { ok: false; error: string }

const PLATFORM: Record<string, string> = { win32: 'windows', darwin: 'mac', linux: 'linux' }

/** Which installer replaces this copy of the app. */
function pick(assets: Asset[]): Asset | undefined {
  const platform = PLATFORM[process.platform]
  const arch = process.arch === 'arm64' ? 'arm64' : 'x64'
  const formats = process.platform === 'win32' ? ['exe']
    : process.platform === 'darwin' ? ['dmg', 'zip']
      : process.env.APPIMAGE ? ['AppImage'] : ['deb']
  for (const format of formats) {
    const found = assets.find(a => a.platform === platform && a.arch === arch && a.format === format)
    if (found) return found
  }
  return undefined
}

async function download(url: string, to: string, onProgress: (p: UpdateProgress) => void)
  : Promise<string> {
  const res = await fetch(url, { redirect: 'follow' })
  if (!res.ok || !res.body) throw new Error(`the server answered ${res.status}`)
  const total = Number(res.headers.get('content-length') ?? 0)
  const hash = createHash('sha256')
  const out = fs.createWriteStream(to)
  let done = 0
  let reported = 0
  const reader = res.body.getReader()
  for (;;) {
    const { value, done: end } = await reader.read()
    if (end) break
    hash.update(value)
    if (!out.write(value)) await new Promise<void>(r => out.once('drain', () => r()))
    done += value.length
    // A progress message for every chunk would flood the page; one per megabyte is plenty.
    if (done - reported > 1_048_576) {
      reported = done
      onProgress({ phase: 'downloading', done, total })
    }
  }
  await new Promise<void>((resolve, reject) => out.end((e?: Error | null) => e ? reject(e) : resolve()))
  return hash.digest('hex')
}

export async function installUpdate(
  serverOrigin: string,
  window: BrowserWindow | null,
  onProgress: (p: UpdateProgress) => void,
): Promise<UpdateResult> {
  let release: { version: string | null; assets: Asset[] }
  try {
    const res = await fetch(`${serverOrigin}/api/v1/desktop/release`)
    release = ((await res.json()) as { data: typeof release }).data
  } catch (e: any) {
    return { ok: false, error: `Could not reach the CPIntel server: ${e?.message ?? e}` }
  }
  if (!release?.version) return { ok: false, error: 'No release is published yet.' }

  const asset = pick(release.assets ?? [])
  if (!asset) {
    return { ok: false, error: `Release ${release.version} has no installer for this computer. `
      + 'Download it from the CPIntel website instead.' }
  }
  if (!asset.sha256) {
    return { ok: false, error: 'The server did not say what the installer should look like, so '
      + 'it cannot be checked. Download it from the CPIntel website instead.' }
  }

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cpintel-update-'))
  const file = path.join(dir, asset.name)
  try {
    onProgress({ phase: 'downloading', done: 0, total: asset.size })
    // Always this server's own route; a release can only point at installers it lists.
    const sha256 = await download(new URL(asset.url, serverOrigin).toString(), file, onProgress)
    onProgress({ phase: 'verifying', done: asset.size, total: asset.size })
    if (sha256 !== asset.sha256.toLowerCase()) {
      fs.rmSync(dir, { recursive: true, force: true })
      return { ok: false, error: 'The downloaded installer is not the one the server published '
        + '(its checksum differs), so it was deleted and nothing was installed. Try again; if it '
        + 'happens again, tell your administrator.' }
    }
  } catch (e: any) {
    fs.rmSync(dir, { recursive: true, force: true })
    return { ok: false, error: `The download failed: ${e?.message ?? e}` }
  }

  onProgress({ phase: 'installing', done: asset.size, total: asset.size })
  return install(asset, file, release.version, window)
}

async function install(asset: Asset, file: string, version: string, window: BrowserWindow | null)
  : Promise<UpdateResult> {
  if (asset.format === 'exe') {
    // The installer closes this copy itself if it is still running; quitting first is tidier.
    spawn(file, [], { detached: true, stdio: 'ignore' }).unref()
    setTimeout(() => app.quit(), 500)
    return { ok: true, message: `Installing CPIntel ${version}. It will open again by itself.` }
  }

  if (asset.format === 'AppImage') {
    const current = process.env.APPIMAGE!
    const staged = `${current}.update`
    try {
      fs.copyFileSync(file, staged)
      fs.chmodSync(staged, 0o755)
      fs.renameSync(staged, current)
    } catch {
      // The folder the AppImage lives in is not writable: leave the new one in Downloads.
      fs.rmSync(staged, { force: true })
      const saved = saveToDownloads(file, asset.name)
      fs.chmodSync(saved, 0o755)
      shell.showItemInFolder(saved)
      return { ok: true, message: `CPIntel ${version} is in your Downloads folder. Close this `
        + 'app and start the new file instead of this one.' }
    }
    app.relaunch({ execPath: current })
    setTimeout(() => app.exit(0), 300)
    return { ok: true, message: `Updated to ${version}; restarting.` }
  }

  const saved = saveToDownloads(file, asset.name)
  if (asset.format === 'deb') {
    await shell.openPath(saved)
    return { ok: true, message: `CPIntel ${version} opened in your software installer. Press `
      + 'Install (it asks for your password, as any package does), then restart CPIntel.' }
  }

  // macOS: an app cannot replace its own bundle while running, so the disk image is opened (or
  // the zip unpacked into Downloads) and the student finishes it, the same way as the first
  // install.
  const zip = asset.format === 'zip'
  await shell.openPath(saved)
  const options = {
    type: 'info' as const,
    buttons: ['Quit CPIntel'],
    title: `Install CPIntel ${version}`,
    message: `Finish installing CPIntel ${version}`,
    detail: (zip
      ? 'The new CPIntel is being unpacked into your Downloads folder. Drag it from there onto '
        + 'the Applications folder and choose Replace.'
      : 'In the window that opened, drag CPIntel onto the Applications folder and choose '
        + 'Replace.')
      + ' Then open CPIntel again from Applications.\n\nCPIntel quits now so that it '
      + 'can be replaced.',
  }
  if (window) await dialog.showMessageBox(window, options)
  else await dialog.showMessageBox(options)
  setTimeout(() => app.quit(), 200)
  return { ok: true, message: 'Drag CPIntel into Applications to finish.' }
}

function saveToDownloads(file: string, name: string): string {
  const to = path.join(app.getPath('downloads'), name)
  fs.copyFileSync(file, to)
  return to
}
