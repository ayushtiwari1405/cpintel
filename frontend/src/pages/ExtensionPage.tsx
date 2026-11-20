import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  AlertTriangle, CheckCircle2, Copy, Download, ExternalLink, Puzzle, RefreshCw, ShieldCheck,
} from 'lucide-react'

import { isDesktop } from '@/utils/desktopBridge'
import { copyText } from '@/utils/clipboard'
import {
  downloadExtension, EXTENSION_FOLDER, EXTENSION_VERSION, installedExtensionVersion,
} from '@/utils/cfExtension'

/** A Chrome Web Store listing, where a deployment has one. Installing from it is one click. */
const STORE_URL = import.meta.env.VITE_CF_EXTENSION_URL as string | undefined

type Browser = 'chrome' | 'edge' | 'brave' | 'other'

/** Which Chromium browser this is, for the address of its extensions page. */
function detectBrowser(): Browser {
  const agent = navigator.userAgent
  if ('brave' in navigator) return 'brave'
  if (/Edg\//.test(agent)) return 'edge'
  if (/Firefox\/|FxiOS/.test(agent)) return 'other'
  if (/Chrome\/|Chromium\//.test(agent)) return 'chrome'
  return 'other'
}

const EXTENSIONS_PAGE: Record<Browser, string> = {
  chrome: 'chrome://extensions',
  edge: 'edge://extensions',
  brave: 'brave://extensions',
  other: 'chrome://extensions',
}

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

/**
 * Getting the CPIntel Connector into this browser.
 *
 * <p>Where somebody lands when they try to connect Codeforces on the website and have no
 * extension. The download is made on this page, for this site (see `cfExtension.ts`), so it
 * works on any deployment without a store listing — at the price of the browser's own steps for
 * an extension that did not come from its store, which are spelled out rather than left to be
 * guessed. Where there is a store listing, that is offered first.
 */
export default function ExtensionPage() {
  const installed = installedExtensionVersion()
  const browser = detectBrowser()
  const extensionsPage = EXTENSIONS_PAGE[browser]
  const [copied, setCopied] = useState(false)
  const [downloaded, setDownloaded] = useState(false)

  const copy = async () => {
    try {
      await copyText(extensionsPage)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // The address is on screen to be typed; a refused clipboard loses nothing.
    }
  }

  const header = (
    <div>
      <h1 className="flex items-center gap-2 text-2xl font-semibold text-gray-50">
        <Puzzle size={22} className="text-accent-400" /> CPIntel Connector
      </h1>
      <p className="mt-1 text-sm text-gray-400">
        Run and submit Codeforces and LeetCode solutions with this browser's sign-ins.
      </p>
    </div>
  )

  if (isDesktop()) {
    return (
      <div className="max-w-2xl space-y-6">
        {header}
        <div className="card flex items-start gap-2 text-sm text-gray-300">
          <CheckCircle2 size={16} className="mt-0.5 shrink-0 text-green-400" />
          <p>
            Not needed in the desktop app. Connect from{' '}
            <Link to="/platforms" className="text-accent-300 underline">Platforms</Link>.
          </p>
        </div>
      </div>
    )
  }

  if (installed) {
    return (
      <div className="max-w-2xl space-y-6">
        {header}
        <div className="card space-y-3">
          <p className="flex items-center gap-2 text-sm text-gray-200">
            <CheckCircle2 size={16} className="text-green-400" />
            Installed (version {installed}).
          </p>
          <p className="text-xs leading-relaxed text-gray-400">
            Sign in at codeforces.com, then connect.
          </p>
          <Link to="/platforms"
            className="btn-primary inline-flex items-center gap-1.5 px-4 py-1.5 text-xs">
            Connect Codeforces
          </Link>
          {installed !== EXTENSION_VERSION && (
            <p className="flex items-start gap-1.5 text-xs text-amber-300/90">
              <AlertTriangle size={12} className="mt-0.5 shrink-0" />
              <span>
                Version {EXTENSION_VERSION} is out.{' '}
                <button onClick={downloadExtension} className="underline hover:text-amber-200">
                  Download it
                </button>
                , replace your {EXTENSION_FOLDER} folder and reload it at {extensionsPage}.
              </span>
            </p>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className="max-w-2xl space-y-6">
      {header}

      {browser === 'other' && (
        <div className="flex items-start gap-2 rounded-lg border border-amber-900/60
          bg-amber-950/20 px-4 py-3 text-xs leading-relaxed text-amber-200">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" />
          <p>
            Works in Chrome, Edge and Brave — or use the desktop app.
          </p>
        </div>
      )}

      {STORE_URL && (
        <div className="card space-y-3">
          <p className="text-sm font-medium text-gray-200">Install it from the store</p>
          <p className="text-xs text-gray-400">
            Reload this page after installing.
          </p>
          <a href={STORE_URL} target="_blank" rel="noreferrer"
            className="btn-primary inline-flex items-center gap-1.5 px-4 py-1.5 text-xs">
            <ExternalLink size={12} /> Get the extension
          </a>
        </div>
      )}

      <div className="card space-y-5">
        <p className="text-sm font-medium text-gray-200">
          {STORE_URL ? 'Or install it yourself' : 'Install it in four steps'}
        </p>
        <ol className="space-y-5">
          <Step n={1} title="Download it and unzip it">
            <button
              onClick={() => { downloadExtension(); setDownloaded(true) }}
              className="btn-primary inline-flex items-center gap-1.5 px-4 py-1.5 text-xs"
            >
              <Download size={12} /> Download the extension
            </button>
            <p>
              {downloaded ? 'Downloaded. ' : ''}Unzip it somewhere permanent — deleting the folder removes it.
            </p>
          </Step>

          <Step n={2} title="Open your browser's extensions page">
            <p>
              Paste into a new tab:
            </p>
            <div className="flex items-center gap-2">
              <code className="select-all rounded-sm bg-black/40 px-2 py-1 font-mono text-gray-200">
                {extensionsPage}
              </code>
              <button onClick={copy}
                className="flex items-center gap-1 text-[11px] text-accent-300 hover:underline">
                <Copy size={11} /> {copied ? 'Copied' : 'Copy'}
              </button>
            </div>
          </Step>

          <Step n={3} title="Turn on Developer mode, then Load unpacked">
            <p>
              Choose the <span className="font-mono text-gray-300">{EXTENSION_FOLDER}</span> folder
              (the one with <span className="font-mono text-gray-300">manifest.json</span>).
            </p>
          </Step>

          <Step n={4} title="Come back here and reload">
            <button onClick={() => window.location.reload()}
              className="btn-secondary inline-flex items-center gap-1.5 px-4 py-1.5 text-xs">
              <RefreshCw size={12} /> I have installed it — reload
            </button>
          </Step>
        </ol>
      </div>

      <div className="flex items-start gap-2 text-xs leading-relaxed text-gray-500">
        <ShieldCheck size={14} className="mt-0.5 shrink-0 text-gray-600" />
        <p>
          Never sends your password or cookies to CPIntel.
        </p>
      </div>
    </div>
  )
}
