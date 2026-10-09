import { useEffect, useState } from 'react'
import { AlertTriangle, Download, Loader2, X } from 'lucide-react'
import { clsx } from 'clsx'

import { useDesktopRelease } from '@/hooks/useDesktopRelease'
import { useAuthStore } from '@/store/authStore'
import { useExamMode } from '@/store/examModeStore'
import { isDesktop, openExternal } from '@/utils/desktopBridge'
import { compareVersions } from '@/utils/version'

const DISMISSED_KEY = 'cpintel.update.dismissed'

function dismissedVersion(): string | null {
  try { return localStorage.getItem(DISMISSED_KEY) } catch { return null }
}

/**
 * "A new version is available", across the top of the desktop app.
 *
 * Nothing updates by itself. Closing the ribbon hides it until the next version comes out;
 * pressing Update downloads that version's installer, checks it and runs it (electron/src/
 * updater.ts). Below the deployment's minimum version the ribbon cannot be closed, since that
 * build is about to stop working with this server.
 *
 * The desktop app loads this page from the server, so even an installation from before the
 * updater existed shows the ribbon; there, Update opens the download page in the browser.
 * Never shown during an examination, where it would be one more thing on screen and installing
 * would mean quitting the paper.
 */
export function UpdateRibbon() {
  const { data: release } = useDesktopRelease(isDesktop())
  const examLive = useExamMode(s => s.live)
  const examSession = useAuthStore(s => s.mode === 'EXAM')
  const [current, setCurrent] = useState<string | null>(null)
  const [dismissed, setDismissed] = useState(dismissedVersion)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!isDesktop()) return
    window.cpintelDesktop!.getVersion().then(setCurrent).catch(() => {})
  }, [])

  useEffect(() => {
    const update = window.cpintelDesktop?.update
    if (!update) return
    return update.onProgress(p => {
      if (p.phase === 'downloading') {
        setBusy(p.total
          ? `Downloading… ${Math.floor((p.done / p.total) * 100)}%`
          : 'Downloading…')
      } else {
        setBusy(p.phase === 'verifying' ? 'Checking the download…' : 'Installing…')
      }
    })
  }, [])

  if (!isDesktop() || !current || !release?.version) return null
  if (examLive || examSession) return null
  if (compareVersions(release.version, current) <= 0) return null

  const required = compareVersions(current, release.minimumVersion) < 0
  if (!required && dismissed === release.version) return null

  const dismiss = () => {
    try { localStorage.setItem(DISMISSED_KEY, release.version!) } catch { /* session only */ }
    setDismissed(release.version)
  }

  const update = async () => {
    setError(null)
    const updater = window.cpintelDesktop?.update
    if (!updater) {
      // A build from before the updater: the download page has the installer and the steps.
      openExternal(`${window.location.origin}/download`)
      return
    }
    setBusy('Starting…')
    const result = await updater.install()
    if (result.ok) {
      setBusy(result.message)
    } else {
      setBusy(null)
      setError(result.error)
    }
  }

  return (
    <div role="status" className={clsx(
      'fixed inset-x-0 top-0 z-50 flex items-center justify-center gap-3 px-4 py-2 text-xs',
      'shadow-lg',
      required ? 'bg-red-700 text-white' : 'bg-indigo-600 text-white',
    )}>
      {required && <AlertTriangle size={14} className="flex-shrink-0" />}
      <span className="min-w-0 truncate">
        {error
          ? error
          : required
            ? `This version of CPIntel (${current}) is no longer supported. Update to ${release.version} to keep using it.`
            : `CPIntel ${release.version} is available — you have ${current}.`}
      </span>

      {busy ? (
        <span className="flex flex-shrink-0 items-center gap-1.5 font-medium">
          <Loader2 size={13} className="animate-spin" /> {busy}
        </span>
      ) : (
        <button
          onClick={update}
          className="flex flex-shrink-0 items-center gap-1.5 rounded-md bg-white/15 px-2.5 py-1
                     font-medium hover:bg-white/25"
        >
          <Download size={12} /> {error ? 'Try again' : 'Update'}
        </button>
      )}

      {!required && !busy && (
        <button onClick={dismiss} aria-label="Hide until the next version" title="Hide until the next version"
          className="flex-shrink-0 rounded p-1 hover:bg-white/15">
          <X size={13} />
        </button>
      )}
    </div>
  )
}
