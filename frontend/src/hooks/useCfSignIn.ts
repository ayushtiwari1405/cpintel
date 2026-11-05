import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'

import { browserConnect, cfRelay } from '@/api/cfBrowser'
import { useToast } from '@/components/common/Toaster'
import { apiBase } from '@/api/cfHelper'
import { useAuthStore } from '@/store/authStore'
import { desktopCf, isDesktop } from '@/utils/desktopBridge'
import { whileCfWindowOpen } from '@/utils/cfWindowExcuse'

/**
 * Signs in to Codeforces and connects it, through whatever this page has: the desktop app's own
 * Codeforces window, or the extension's sign-in popup. Either way the page stays where it is, so
 * it works from an examination. `available` is false on a website without the extension, where
 * the connect card's other routes are the way.
 */
export function useCfSignIn() {
  const qc = useQueryClient()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<{ message: string; check: boolean } | null>(null)

  const signIn = async () => {
    setBusy(true)
    setError(null)
    try {
      const desktopSignIn = window.cpintelDesktop?.cf?.signIn
      if (isDesktop() && !desktopSignIn) {
        // Desktop builds from before the app read Codeforces itself.
        const token = useAuthStore.getState().accessToken ?? ''
        const handle = await desktopCf.connect(apiBase(), token)
        qc.invalidateQueries({ queryKey: ['practice'] })
        toast.push('success', handle ? `Connected as ${handle}` : 'Codeforces connected')
        return
      }
      let status
      try {
        // The extension opens its own sign-in popup when this browser is signed out.
        status = await browserConnect()
      } catch (e) {
        if (!desktopSignIn) throw e
        // The app's own sign-in window, then read codeforces.com as the app again.
        const res = await whileCfWindowOpen(() => desktopSignIn())
        if (!res.ok) {
          const failed = new Error(res.error || 'Could not sign in to Codeforces.')
          ;(failed as Error & { cause?: unknown }).cause = e
          throw failed
        }
        status = await browserConnect()
      }
      qc.setQueryData(['practice', 'cf-session'], status)
      qc.invalidateQueries({ queryKey: ['practice'] })
      toast.push('success', `Connected as ${status.handle}`)
    } catch (e: any) {
      setError({
        message: e?.response?.data?.message ?? e?.message ?? 'Could not connect',
        check: !!e?.browserCheck,
      })
    } finally {
      setBusy(false)
    }
  }

  return { signIn, busy, error, available: isDesktop() || cfRelay() === 'extension' }
}
