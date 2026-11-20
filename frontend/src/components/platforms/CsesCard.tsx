import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { BadgeCheck, ExternalLink, Link2, Loader2, ShieldAlert, Unlink } from 'lucide-react'
import { platformApi } from '@/api/platformApi'
import { errorMessage } from '@/components/common/States'
import { useToast } from '@/components/common/Toaster'
import { openExternal } from '@/utils/desktopBridge'
import type { PlatformAccount } from '@/types'

/**
 * CSES, connected through CSES's own API sign-in: CPIntel gets an address on cses.fi, the
 * student signs in there to approve it, and this card notices. No password or code passes
 * through CPIntel, and the account is proven by CSES itself, so it is verified at once.
 */
export function CsesCard({ account }: { account: PlatformAccount | undefined }) {
  const toast = useToast()
  const queryClient = useQueryClient()
  const [waitingUntil, setWaitingUntil] = useState<number | null>(null)
  const [url, setUrl] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current) }, [])

  const poll = (until: number) => {
    timer.current = setTimeout(async () => {
      if (Date.now() > until) {
        setWaitingUntil(null)
        toast.push('error', 'The CSES sign-in was not approved in time. Start again.')
        return
      }
      try {
        const r = (await platformApi.csesCheck()).data
        if (r.connected) {
          setWaitingUntil(null)
          setUrl(null)
          queryClient.invalidateQueries({ queryKey: ['platforms'] })
          queryClient.invalidateQueries({ queryKey: ['labs'] })
          toast.push('success', `CSES connected as ${r.account?.handle}`)
          return
        }
      } catch (e) {
        setWaitingUntil(null)
        toast.push('error', errorMessage(e, 'Could not connect CSES'))
        return
      }
      poll(until)
    }, 3000)
  }

  const connect = async () => {
    setBusy(true)
    try {
      const started = (await platformApi.csesStart()).data
      setUrl(started.authenticationUrl)
      openExternal(started.authenticationUrl)
      const until = new Date(started.expiresAt).getTime()
      setWaitingUntil(until)
      poll(until)
    } catch (e) {
      toast.push('error', errorMessage(e, 'Could not start connecting CSES'))
    } finally {
      setBusy(false)
    }
  }

  const disconnect = async () => {
    if (!confirm('Disconnect CSES? Lab solves on CSES stop being counted until you connect again.')) return
    setBusy(true)
    try {
      await platformApi.csesDisconnect()
      queryClient.invalidateQueries({ queryKey: ['platforms'] })
      queryClient.invalidateQueries({ queryKey: ['labs'] })
      toast.push('info', 'CSES disconnected')
    } catch (e) {
      toast.push('error', errorMessage(e, 'Could not disconnect CSES'))
    } finally {
      setBusy(false)
    }
  }

  const needsReconnect = account?.syncStatus === 'FAILED'

  return (
    <section aria-labelledby="platform-CSES" className={`rounded-xl border bg-gray-900 p-5 ${account ? 'border-gray-700' : 'border-gray-800'}`}>
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0 flex-1">
          <h2 id="platform-CSES" className="mb-1 text-base font-semibold text-gray-50">CSES</h2>
          <p className="text-sm text-gray-400">
            Used for labs.
          </p>
          {account && (
            <dl className="mt-4">
              <dt className="text-[11px] uppercase tracking-wide text-gray-500">Account</dt>
              <dd className="font-mono text-sm text-gray-100">{account.handle}</dd>
            </dl>
          )}
          {account && !needsReconnect && (
            <p className="mt-3 inline-flex items-center gap-1.5 text-xs text-green-300">
              <BadgeCheck size={14} aria-hidden="true" /> Connected through CSES. Lab solves on CSES count.
            </p>
          )}
          {needsReconnect && (
            <p className="mt-3 flex items-start gap-1.5 text-xs text-amber-200">
              <ShieldAlert size={14} className="mt-px shrink-0" aria-hidden="true" />
              CSES no longer accepts this connection (it may have been signed out on cses.fi). Connect again.
            </p>
          )}
          {waitingUntil && (
            <div role="status" className="mt-4 rounded-lg border border-gray-800 bg-gray-950 p-3 text-sm text-gray-300">
              <p className="flex items-center gap-2">
                <Loader2 size={14} className="animate-spin" aria-hidden="true" />
                Sign in on cses.fi to approve CPIntel. This page updates once you have.
              </p>
              {url && (
                <a href={url} target="_blank" rel="noreferrer"
                  className="mt-2 inline-flex items-center gap-1 text-xs text-accent-300 hover:underline">
                  Open the CSES sign-in again <ExternalLink size={11} aria-hidden="true" />
                </a>
              )}
            </div>
          )}
        </div>
        <div className="flex shrink-0 gap-2">
          {(!account || needsReconnect) && !waitingUntil && (
            <button type="button" className="btn-primary btn-sm" onClick={connect} disabled={busy}>
              {busy ? <Loader2 size={13} className="animate-spin" aria-hidden="true" /> : <Link2 size={13} aria-hidden="true" />}
              {account ? 'Reconnect CSES' : 'Connect CSES'}
            </button>
          )}
          {account && (
            <button type="button" className="btn btn-sm border border-red-900/60 text-red-300 hover:bg-red-950/50"
              onClick={disconnect} disabled={busy}>
              <Unlink size={13} aria-hidden="true" /> Disconnect
            </button>
          )}
        </div>
      </div>
    </section>
  )
}
