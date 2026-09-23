import { useEffect, useState } from 'react'
import {
  Link2, Loader2, ShieldCheck, ShieldAlert, LogOut, ExternalLink, ChevronDown, ChevronUp,
  Terminal,
} from 'lucide-react'
import { clsx } from 'clsx'
import { useQueryClient } from '@tanstack/react-query'
import { formatDistanceToNowStrict } from 'date-fns'
import { useCfSession, useConnectCfSession, useDisconnectCfSession } from '@/hooks/usePractice'
import { useToast } from '@/components/common/Toaster'
import { cfHelper, HELPER_COMMAND, apiBase } from '@/api/cfHelper'
import { useAuthStore } from '@/store/authStore'
import { desktopCf, isDesktop, openExternal } from '@/utils/desktopBridge'

/**
 * @param variant `card` stands on its own; `inline` drops the panel of its own and sits inside
 *   one that is already there — the Codeforces row on Platforms, where a second bordered box
 *   inside the first reads as a rendering mistake rather than as a section.
 */
export function CfSessionCard({ variant = 'card' }: { variant?: 'card' | 'inline' } = {}) {
  const { data: session, isLoading } = useCfSession()
  const [open, setOpen] = useState(false)

  const shell = variant === 'inline' ? 'mt-4 pt-4 border-t border-gray-800' : 'card'

  if (isLoading) {
    return (
      <div className={clsx(shell, 'flex items-center gap-2 text-xs text-gray-500')}>
        <Loader2 size={13} className="animate-spin" /> Checking Codeforces session…
      </div>
    )
  }

  if (session && !session.submitEnabled) {
    return (
      <div className={clsx(shell, 'flex gap-2 text-xs text-gray-400')}>
        <ShieldAlert size={14} className="flex-shrink-0 mt-0.5 text-gray-500" />
        <p>
          Submitting is turned off on this deployment. You can still read problems here and
          submit on Codeforces directly.
        </p>
      </div>
    )
  }

  if (session?.connected) {
    return (
      <ConnectedRow
        handle={session.handle}
        expiresAt={session.expiresAt}
        className={shell}
      />
    )
  }

  return (
    <div className={clsx(shell, 'space-y-3')}>
      <button
        onClick={() => setOpen(v => !v)}
        className="w-full flex items-center justify-between gap-2 text-left"
      >
        <span className="flex items-center gap-2 text-sm text-gray-200">
          <Link2 size={14} className="text-indigo-400" />
          Connect your Codeforces session to submit
        </span>
        {open ? <ChevronUp size={14} className="text-gray-500" />
          : <ChevronDown size={14} className="text-gray-500" />}
      </button>

      {open && (isDesktop() ? <DesktopConnectForm /> : <ConnectForm />)}
    </div>
  )
}

function ConnectedRow(
  { handle, expiresAt, className }:
  { handle?: string; expiresAt?: string; className?: string },
) {
  const disconnect = useDisconnectCfSession()

  return (
    <div className={clsx(className, 'flex items-center justify-between gap-3')}>
      <div className="flex items-center gap-2 min-w-0">
        <ShieldCheck size={15} className="text-green-400 flex-shrink-0" />
        <div className="min-w-0">
          <p className="text-sm text-gray-200 truncate">
            Submitting as <span className="font-medium text-gray-50">{handle}</span>
          </p>
          {expiresAt && (
            <p className="text-[11px] text-gray-500">
              Session stored until {formatDistanceToNowStrict(new Date(expiresAt),
                { addSuffix: true })} — reconnect if submits start failing
            </p>
          )}
        </div>
      </div>
      <button
        onClick={() => disconnect.mutate()}
        className="flex items-center gap-1.5 text-xs text-gray-500 hover:text-red-400
                   transition-colors flex-shrink-0"
      >
        <LogOut size={13} /> Disconnect
      </button>
    </div>
  )
}

/**
 * The desktop route: sign in to Codeforces in a window CPIntel owns.
 *
 * <p>There is no helper to start and nothing to paste, because neither was ever the point. Both
 * exist to get at a cookie a web page is not allowed to read, and this app is a browser — it can
 * show Codeforces its own sign-in page and then read the session that window created. The user
 * signs in to Codeforces exactly as they would anywhere; the password goes to Codeforces and is
 * never seen here.
 *
 * <p>No handle is asked for either. The backend verifies the session against Codeforces and
 * reports whose it is, so asking the user to type a name only creates a way to get it wrong.
 */
function DesktopConnectForm() {
  const qc = useQueryClient()
  const toast = useToast()

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const connect = async () => {
    setBusy(true)
    setError(null)
    try {
      const token = useAuthStore.getState().accessToken ?? ''
      const handle = await desktopCf.connect(apiBase(), token)
      qc.invalidateQueries({ queryKey: ['practice', 'cf-session'] })
      qc.invalidateQueries({ queryKey: ['practice', 'languages'] })
      toast.push('success', handle ? `Connected as ${handle}` : 'Codeforces connected')
    } catch (e: any) {
      setError(e?.message ?? 'Could not connect that account')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-3">
      <p className="text-xs text-gray-400">
        Opens Codeforces in a CPIntel window. Sign in there as you normally would — your
        password goes to Codeforces and never passes through CPIntel.
      </p>

      {error && (
        <p className="flex items-start gap-1.5 text-[11px] text-red-300">
          <ShieldAlert size={12} className="flex-shrink-0 mt-0.5" /> {error}
        </p>
      )}

      <button
        onClick={connect}
        disabled={busy}
        className="btn-primary py-1.5 text-xs w-full flex items-center justify-center gap-1.5"
      >
        {busy ? <Loader2 size={12} className="animate-spin" /> : <ExternalLink size={12} />}
        {busy ? 'Waiting for sign-in…' : 'Sign in to Codeforces'}
      </button>

      <div className="flex items-start gap-2 text-[11px] text-amber-300/80">
        <ShieldAlert size={12} className="flex-shrink-0 mt-0.5" />
        <p>
          The session that window creates is a key to your account while it lives. It is sent
          straight to the CPIntel server — it never reaches this page — and stored encrypted,
          never shown back to you. Revoke it any time from Codeforces → Settings → Sessions, or
          with Disconnect here.
        </p>
      </div>
    </div>
  )
}

function ConnectForm() {
  const qc = useQueryClient()
  const toast = useToast()

  const [handle, setHandle] = useState('')
  const [opened, setOpened] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [helper, setHelper] = useState<'checking' | 'up' | 'down'>('checking')
  const [manual, setManual] = useState(false)

  // Poll the helper so the card notices it being started without needing a refresh.
  useEffect(() => {
    let stop = false
    const check = async () => {
      try {
        await cfHelper.health()
        if (!stop) setHelper('up')
      } catch {
        if (!stop) setHelper('down')
      }
    }
    check()
    const id = setInterval(check, 4000)
    return () => { stop = true; clearInterval(id) }
  }, [])

  const signIn = () => {
    setOpened(true)
    openExternal('https://codeforces.com/enter')
  }

  const connect = async () => {
    setBusy(true)
    setError(null)
    try {
      const res = await cfHelper.connect(handle.trim())
      qc.invalidateQueries({ queryKey: ['practice', 'cf-session'] })
      qc.invalidateQueries({ queryKey: ['practice', 'languages'] })
      toast.push('success', `Connected as ${res.handle}`)
    } catch (e: any) {
      setError(e?.message ?? 'Could not connect that account')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-3">
      <p className="text-xs text-gray-400">
        Codeforces has no login or submission API, so CPIntel reuses a session you create
        yourself in your own browser. Nothing here ever asks for your password.
      </p>

      {/* 1 — who */}
      <label className="block space-y-1">
        <span className="text-[11px] text-gray-500">Your Codeforces handle</span>
        <input
          value={handle}
          onChange={e => setHandle(e.target.value)}
          spellCheck={false}
          placeholder="tourist"
          className="input py-1.5 text-xs font-mono"
        />
      </label>

      {/* 2 — sign in there */}
      <button
        onClick={signIn}
        disabled={!handle.trim()}
        className="btn-secondary py-1.5 text-xs w-full flex items-center justify-center gap-1.5"
      >
        <ExternalLink size={12} />
        {opened ? 'Reopen Codeforces sign-in' : 'Open Codeforces and sign in'}
      </button>
      <p className="text-[11px] text-gray-600 -mt-1">
        Tick <span className="text-gray-400">Remember me</span> there — without it the session
        dies the moment you close the browser.
      </p>

      {/* 3 — the helper */}
      <div className={clsx(
        'rounded-lg border px-3 py-2 text-[11px] space-y-1',
        helper === 'up' ? 'border-green-900 bg-green-950/30 text-green-300'
          : 'border-gray-800 bg-gray-950 text-gray-400'
      )}>
        <span className="flex items-center gap-1.5 font-medium">
          {helper === 'checking' && <Loader2 size={11} className="animate-spin" />}
          {helper === 'up' ? <ShieldCheck size={11} /> : <Terminal size={11} />}
          {helper === 'up' ? 'Local helper running'
            : helper === 'checking' ? 'Looking for the local helper…'
              : 'Start the local helper'}
        </span>
        {helper !== 'up' && (
          <>
            <p>
              Your browser cannot read a Codeforces session on its own — the cookie is
              HttpOnly and CORS blocks the request. Run this once, in the project root:
            </p>
            <code className="block bg-black/40 rounded px-2 py-1 font-mono text-gray-300
                             select-all">
              {HELPER_COMMAND}
            </code>
          </>
        )}
      </div>

      {error && (
        <p className="flex items-start gap-1.5 text-[11px] text-red-300">
          <ShieldAlert size={12} className="flex-shrink-0 mt-0.5" /> {error}
        </p>
      )}

      <button
        onClick={connect}
        disabled={!handle.trim() || helper !== 'up' || busy}
        className="btn-primary py-1.5 text-xs w-full flex items-center justify-center gap-1.5"
      >
        {busy && <Loader2 size={12} className="animate-spin" />}
        Connect {handle.trim() || 'account'}
      </button>

      <div className="flex items-start gap-2 text-[11px] text-amber-300/80">
        <ShieldAlert size={12} className="flex-shrink-0 mt-0.5" />
        <p>
          A session cookie is a key to your account while it lives. The helper hands it
          straight to the CPIntel server — it never passes through this page — and it is
          stored encrypted, never shown back to you. Revoke it any time from Codeforces →
          Settings → Sessions, or with Disconnect here.
        </p>
      </div>

      <button
        onClick={() => setManual(v => !v)}
        className="text-[11px] text-gray-600 hover:text-gray-400 transition-colors"
      >
        {manual ? 'Hide' : 'Paste a Cookie header manually instead'}
      </button>
      {manual && <ManualPaste />}
    </div>
  )
}

/** The original DevTools route, kept as a fallback when the helper cannot run. */
function ManualPaste() {
  const [cookie, setCookie] = useState('')
  const connect = useConnectCfSession()

  return (
    <div className="space-y-2 pt-1">
      <p className="text-[11px] text-gray-500">
        DevTools (F12) → Network → reload → click the first
        <span className="font-mono text-gray-400"> codeforces.com</span> request → copy the
        whole <span className="font-mono text-gray-400">Cookie</span> request header.
      </p>
      <textarea
        value={cookie}
        onChange={e => setCookie(e.target.value)}
        rows={3}
        spellCheck={false}
        placeholder="JSESSIONID=…; X-User-Sha1=…; 39ce7=…"
        className="input font-mono text-[11px] resize-none"
      />
      <button
        onClick={() => connect.mutate(cookie)}
        disabled={!cookie.trim() || connect.isPending}
        className="btn-secondary py-1.5 text-xs w-full flex items-center justify-center gap-1.5"
      >
        {connect.isPending && <Loader2 size={12} className="animate-spin" />}
        Connect pasted session
      </button>
    </div>
  )
}
