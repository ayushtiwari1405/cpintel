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
      <span className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full
        bg-indigo-600/20 text-xs font-medium text-indigo-300">{n}</span>
      <div className="min-w-0 space-y-2">
        <p className="text-sm font-medium text-gray-200">{title}</p>
        <div className="space-y-2 text-xs leading-relaxed text-gray-400">{children}</div>
      </div>
    </li>
  )
}

/**
 * Getting the CPIntel Codeforces Connector into this browser.
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
        <Puzzle size={22} className="text-indigo-400" /> CPIntel Codeforces Connector
      </h1>
      <p className="mt-1 text-sm text-gray-400">
        A small browser extension that lets CPIntel open Codeforces problems and submit your
        solutions through this browser, where your Codeforces sign-in lives.
      </p>
    </div>
  )

  if (isDesktop()) {
    return (
      <div className="max-w-2xl space-y-6">
        {header}
        <div className="card flex items-start gap-2 text-sm text-gray-300">
          <CheckCircle2 size={16} className="mt-0.5 flex-shrink-0 text-green-400" />
          <p>
            The desktop app talks to Codeforces itself and needs no extension. Connect your
            account from <Link to="/platforms" className="text-indigo-300 underline">Platforms</Link>.
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
            The extension is installed in this browser (version {installed}).
          </p>
          <p className="text-xs leading-relaxed text-gray-400">
            Sign in at codeforces.com in this browser if you have not, then connect your account.
          </p>
          <Link to="/platforms"
            className="btn-primary inline-flex items-center gap-1.5 px-4 py-1.5 text-xs">
            Connect Codeforces
          </Link>
          {installed !== EXTENSION_VERSION && (
            <p className="flex items-start gap-1.5 text-xs text-amber-300/90">
              <AlertTriangle size={12} className="mt-0.5 flex-shrink-0" />
              <span>
                This site now hands out version {EXTENSION_VERSION}.{' '}
                <button onClick={downloadExtension} className="underline hover:text-amber-200">
                  Download it
                </button>
                , replace the files in your {EXTENSION_FOLDER} folder, and press the reload
                arrow on the extension at {extensionsPage}.
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
          <AlertTriangle size={14} className="mt-0.5 flex-shrink-0" />
          <p>
            The extension works in Chrome, Edge and Brave. Open CPIntel in one of those to use
            Codeforces here — or use the CPIntel desktop app, which needs no extension.
          </p>
        </div>
      )}

      {STORE_URL && (
        <div className="card space-y-3">
          <p className="text-sm font-medium text-gray-200">Install it from the store</p>
          <p className="text-xs text-gray-400">
            One click, and it updates itself. Come back and reload this page afterwards.
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
              {downloaded ? 'Downloaded. ' : ''}Unzip <span className="font-mono text-gray-300">
              {EXTENSION_FOLDER}.zip</span> and keep the <span className="font-mono
              text-gray-300">{EXTENSION_FOLDER}</span> folder somewhere it will stay, such as
              your Documents — the browser runs the extension from that folder, so deleting it
              removes the extension.
            </p>
          </Step>

          <Step n={2} title="Open your browser's extensions page">
            <p>
              A website is not allowed to open that page for you. Paste this into the address
              bar of a new tab:
            </p>
            <div className="flex items-center gap-2">
              <code className="select-all rounded bg-black/40 px-2 py-1 font-mono text-gray-200">
                {extensionsPage}
              </code>
              <button onClick={copy}
                className="flex items-center gap-1 text-[11px] text-indigo-300 hover:underline">
                <Copy size={11} /> {copied ? 'Copied' : 'Copy'}
              </button>
            </div>
          </Step>

          <Step n={3} title="Turn on Developer mode, then Load unpacked">
            <p>
              Switch on <span className="text-gray-200">Developer mode</span>
              {browser === 'edge' ? ' (in the left-hand panel)' : ' (top right of that page)'}.
              Press <span className="text-gray-200">Load unpacked</span> and choose the{' '}
              <span className="font-mono text-gray-300">{EXTENSION_FOLDER}</span> folder — the one
              that has <span className="font-mono text-gray-300">manifest.json</span> directly
              inside it.
            </p>
          </Step>

          <Step n={4} title="Come back here and reload">
            <p>An extension only starts on pages opened after it was installed.</p>
            <button onClick={() => window.location.reload()}
              className="btn-secondary inline-flex items-center gap-1.5 px-4 py-1.5 text-xs">
              <RefreshCw size={12} /> I have installed it — reload
            </button>
          </Step>
        </ol>
      </div>

      <div className="flex items-start gap-2 text-xs leading-relaxed text-gray-500">
        <ShieldCheck size={14} className="mt-0.5 flex-shrink-0 text-gray-600" />
        <p>
          What it can do: fetch pages from codeforces.com when this CPIntel site asks, and
          nothing else. It runs on this site only, keeps nothing, and CPIntel never receives
          your Codeforces password or cookies. Your browser may remind you that it is running
          an extension in developer mode; that is expected for one installed this way.
        </p>
      </div>
    </div>
  )
}
