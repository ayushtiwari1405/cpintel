import { useEffect, useState } from 'react'
import {
  AlertTriangle, Apple, CheckCircle2, Copy, Download, Loader2, Monitor, OctagonX, ShieldCheck,
  Terminal,
} from 'lucide-react'
import { clsx } from 'clsx'

import { useDesktopRelease } from '@/hooks/useDesktopRelease'
import type { DesktopAsset, DesktopRelease } from '@/api/desktopApi'
import { isDesktop } from '@/utils/desktopBridge'
import { copyText } from '@/utils/clipboard'

type Os = 'windows' | 'mac' | 'linux'

const OS_NAMES: Record<Os, string> = { windows: 'Windows', mac: 'macOS', linux: 'Linux' }

function detectOs(): Os {
  const agent = navigator.userAgent
  if (/Windows/i.test(agent)) return 'windows'
  if (/Macintosh|Mac OS X/i.test(agent)) return 'mac'
  return 'linux'
}

const mb = (bytes: number) => `${Math.round(bytes / 1048576)} MB`

function Step({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  return (
    <li className="flex gap-3">
      <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full
        bg-accent-600/20 text-xs font-medium text-accent-300">{n}</span>
      <div className="min-w-0 space-y-2">
        <p className="text-sm font-medium text-gray-200">{title}</p>
        <div className="space-y-2 text-xs leading-relaxed text-gray-400">{children}</div>
      </div>
    </li>
  )
}

/** A command to type, with a copy button. */
function Command({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <div className="flex items-center gap-2">
      <code className="min-w-0 select-all overflow-x-auto whitespace-nowrap rounded-sm bg-black/40
        px-2 py-1 font-mono text-[11px] text-gray-200">{text}</code>
      <button
        onClick={async () => {
          try {
            await copyText(text)
            setCopied(true)
            setTimeout(() => setCopied(false), 2000)
          } catch { /* it is on screen to be typed */ }
        }}
        className="flex shrink-0 items-center gap-1 text-[11px] text-accent-300 hover:underline"
      >
        <Copy size={11} /> {copied ? 'Copied' : 'Copy'}
      </button>
    </div>
  )
}

/** Calls out what is expected, so that it is not mistaken for something wrong. */
function Expected({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-2 rounded-lg border border-amber-900/60 bg-amber-950/20
      px-3 py-2 text-xs leading-relaxed text-amber-200">
      <AlertTriangle size={13} className="mt-0.5 shrink-0" />
      <div className="space-y-1">{children}</div>
    </div>
  )
}

function DownloadButton({ asset, label }: { asset: DesktopAsset; label: string }) {
  return (
    <a href={asset.url} download
      className="btn-primary inline-flex items-center gap-1.5 px-4 py-2 text-xs">
      <Download size={13} /> {label} <span className="opacity-70">· {mb(asset.size)}</span>
    </a>
  )
}

const find = (release: DesktopRelease, platform: Os, format: string, arch?: string) =>
  release.assets.find(a => a.platform === platform && a.format === format
    && (!arch || a.arch === arch))

function WindowsSteps({ release }: { release: DesktopRelease }) {
  const exe = find(release, 'windows', 'exe')
  if (!exe) return <NoInstaller os="windows" />
  return (
    <ol className="space-y-5">
      <Step n={1} title="Download the installer">
        <DownloadButton asset={exe} label="Download for Windows" />
        <Expected>
          <p>
            If the browser warns, choose <span className="text-amber-100">Keep</span>
            (Edge: <span className="text-amber-100">… → Keep → Keep anyway</span>).
          </p>
        </Expected>
      </Step>
      <Step n={2} title="Open it">
        <Expected>
          <p>
            On <em>"Windows protected your PC"</em> (Unknown publisher), click{' '}
            <span className="text-amber-100">More info → Run anyway</span>.
          </p>
        </Expected>
      </Step>
      <Step n={3} title="It installs and opens by itself">
        <p>
          No administrator password needed. Sign in with your CPIntel account.
        </p>
      </Step>
    </ol>
  )
}

function MacSteps({ release }: { release: DesktopRelease }) {
  // A disk image when the release was built on a Mac; a .zip when this server built it, since
  // only a Mac can make a disk image.
  const mac = (arch: string) => find(release, 'mac', 'dmg', arch) ?? find(release, 'mac', 'zip', arch)
  const arm = mac('arm64')
  const intel = mac('x64')
  if (!arm && !intel) return <NoInstaller os="mac" />
  const zipped = (arm ?? intel)!.format === 'zip'
  return (
    <ol className="space-y-5">
      <Step n={1} title="Download the one for your Mac's chip">
        <div className="flex flex-wrap gap-2">
          {arm && <DownloadButton asset={arm} label="Apple Silicon (M1, M2, M3, M4…)" />}
          {intel && <DownloadButton asset={intel} label="Intel" />}
        </div>
        <p>
          Not sure? Apple menu → <span className="text-gray-200">About This Mac</span>.
        </p>
      </Step>
      {zipped ? (
        <Step n={2} title="Open it and drag CPIntel into Applications">
          <p>
            Double-click the .zip, then drag CPIntel into Applications.
          </p>
        </Step>
      ) : (
        <Step n={2} title="Open it and drag CPIntel into Applications">
          <p>Then eject the CPIntel disk in Finder's sidebar.</p>
        </Step>
      )}
      <Step n={3} title="Open CPIntel from Applications — the first time takes one extra step">
        <Expected>
          <p>
            If macOS blocks it, open{' '}
            <span className="text-amber-100">System Settings → Privacy &amp; Security</span> and click
            <span className="text-amber-100"> Open Anyway</span>.
          </p>
          <p>
            If it says <em>"damaged"</em>, run this in Terminal:
          </p>
          <Command text="xattr -cr /Applications/CPIntel.app" />
        </Expected>
      </Step>
    </ol>
  )
}

function LinuxSteps({ release }: { release: DesktopRelease }) {
  const deb = find(release, 'linux', 'deb')
  const appImage = find(release, 'linux', 'AppImage')
  if (!deb && !appImage) return <NoInstaller os="linux" />
  return (
    <ol className="space-y-5">
      {deb && (
        <Step n={1} title="Ubuntu, Debian, Mint: the .deb package">
          <DownloadButton asset={deb} label="Download .deb" />
          <p>From the download folder:</p>
          <Command text={`sudo apt install ./${deb.name}`} />
        </Step>
      )}
      {appImage && (
        <Step n={deb ? 2 : 1} title="Any other distribution: the AppImage">
          <DownloadButton asset={appImage} label="Download AppImage" />
          <p>Make it runnable, then open it:</p>
          <Command text={`chmod +x ${appImage.name}`} />
          <Expected>
            <p>
              If <em>libfuse.so.2</em> is missing:
            </p>
            <Command text="sudo apt install libfuse2" />
          </Expected>
        </Step>
      )}
    </ol>
  )
}

function NoInstaller({ os }: { os: Os }) {
  return (
    <p className="text-xs text-gray-400">
      This release has no installer for {OS_NAMES[os]}. Ask your administrator, or keep using the
      website, which does everything except supervised examinations.
    </p>
  )
}

function Checksums({ release, os }: { release: DesktopRelease; os: Os }) {
  const assets = release.assets.filter(a => a.platform === os && a.sha256)
  if (assets.length === 0) return null
  const command = os === 'windows'
    ? 'Get-FileHash .\\FILE -Algorithm SHA256'
    : os === 'mac' ? 'shasum -a 256 FILE' : 'sha256sum FILE'
  return (
    <div className="card space-y-3">
      <p className="flex items-center gap-2 text-sm font-medium text-gray-200">
        <ShieldCheck size={15} className="text-green-400" /> Make sure it is the real file
      </p>
      <p className="text-xs leading-relaxed text-gray-400">
        Optional. Run this in the download folder (replace FILE):
      </p>
      <Command text={command} />
      <p className="text-xs text-gray-400">It must match:</p>
      <ul className="space-y-1.5">
        {assets.map(a => (
          <li key={a.name} className="text-[11px]">
            <span className="font-mono text-gray-300">{a.name}</span>
            <code className="mt-0.5 block select-all break-all rounded-sm bg-black/40 px-2 py-1
              font-mono text-gray-200">{a.sha256}</code>
          </li>
        ))}
      </ul>
    </div>
  )
}

function RedFlags() {
  return (
    <div className="card space-y-3 border-red-900/60">
      <p className="flex items-center gap-2 text-sm font-medium text-gray-200">
        <OctagonX size={15} className="text-red-400" /> Don't install it if
      </p>
      <ul className="list-disc space-y-1.5 pl-5 text-xs leading-relaxed text-gray-400">
        <li>It came from anywhere but this page.</li>
        <li>
          The name doesn't start with <span className="font-mono text-gray-300">CPIntel-</span> or
          isn't .exe, .dmg, .deb, .AppImage or (Mac) .zip.
        </li>
        <li>The fingerprint doesn't match.</li>
        <li>Windows asks for an administrator password or names a publisher.</li>
        <li>Your antivirus flags it.</li>
        <li>The app asks for anything beyond your CPIntel and Codeforces sign-ins.</li>
      </ul>
      <p className="text-xs leading-relaxed text-gray-300">
        Delete it and tell your instructor.
      </p>
    </div>
  )
}

/**
 * Where students get the desktop app, and the operating system's steps for an installer that
 * is not signed — spelled out, with the warnings they will meet described as expected, and a
 * plain list of what would not be expected and what to do then.
 *
 * Reachable signed in or not: a lab administrator installs it on machines before anyone signs
 * in. The installers come from the deployment's own GitHub releases, through this server.
 */
export default function DownloadPage() {
  const desktop = isDesktop()
  const { data: release, isLoading, isError } = useDesktopRelease(!desktop)
  const [os, setOs] = useState<Os>(detectOs)
  const [version, setVersion] = useState<string | null>(null)

  useEffect(() => {
    if (desktop) window.cpintelDesktop!.getVersion().then(setVersion).catch(() => {})
  }, [desktop])

  const header = (
    <div>
      <h1 className="flex items-center gap-2 text-2xl font-semibold text-gray-50">
        <Monitor size={22} className="text-accent-400" /> CPIntel desktop app
      </h1>
      <p className="mt-1 text-sm leading-relaxed text-gray-400">
        Required for supervised exams. Compilers included.
      </p>
    </div>
  )

  if (desktop) {
    return (
      <div className="max-w-2xl space-y-6">
        {header}
        <div className="card flex items-start gap-2 text-sm text-gray-300">
          <CheckCircle2 size={16} className="mt-0.5 shrink-0 text-green-400" />
          <p>
            You're on the desktop app{version ? ` (version ${version})` : ''}.
          </p>
        </div>
      </div>
    )
  }

  if (isLoading) {
    return (
      <div className="max-w-2xl space-y-6">
        {header}
        <p className="flex items-center gap-2 text-sm text-gray-500">
          <Loader2 size={14} className="animate-spin" /> Looking up the latest version…
        </p>
      </div>
    )
  }

  if (isError || !release?.configured || !release.version) {
    return (
      <div className="max-w-2xl space-y-6">
        {header}
        <div className="card text-sm leading-relaxed text-gray-300">
          {isError
            ? 'The download list could not be loaded. Try again in a minute.'
            : !release?.configured
              ? 'The desktop app is not set up on this server yet. Ask your administrator.'
              : 'No version of the desktop app has been published yet.'}
        </div>
      </div>
    )
  }

  return (
    <div className="max-w-2xl space-y-6">
      {header}

      <div className="card space-y-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="text-sm font-medium text-gray-200">
            Version {release.version}
            {release.publishedAt && (
              <span className="ml-2 text-xs font-normal text-gray-500">
                published {new Date(release.publishedAt).toLocaleDateString()}
              </span>
            )}
          </p>
          <div className="flex rounded-lg border border-gray-800 p-0.5">
            {(['windows', 'mac', 'linux'] as Os[]).map(o => (
              <button key={o} onClick={() => setOs(o)}
                className={clsx('flex items-center gap-1 rounded-md px-2.5 py-1 text-xs',
                  os === o ? 'bg-accent-600/20 text-accent-300' : 'text-gray-400 hover:text-gray-200')}>
                {o === 'mac' ? <Apple size={12} /> : o === 'linux' ? <Terminal size={12} /> : <Monitor size={12} />}
                {OS_NAMES[o]}
              </button>
            ))}
          </div>
        </div>
        {os === 'windows' && <WindowsSteps release={release} />}
        {os === 'mac' && <MacSteps release={release} />}
        {os === 'linux' && <LinuxSteps release={release} />}
      </div>

      <Checksums release={release} os={os} />
      <RedFlags />

      <div className="flex items-start gap-2 text-xs leading-relaxed text-gray-500">
        <ShieldCheck size={14} className="mt-0.5 shrink-0 text-gray-600" />
        <p>
          During an exam the app records when you leave its window. It never updates without asking.
        </p>
      </div>
    </div>
  )
}
