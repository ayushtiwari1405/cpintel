import { useEffect, useRef, useState } from 'react'
import { isDesktop, type LockdownState } from '@/utils/desktopBridge'

/** Matches AWAY_WARN_MS in the desktop build, so both say the same number. */
export const AWAY_WARN_MS = 10_000
const AWAY_REPEAT_MS = 30_000
const TICK_MS = 1_000

/**
 * The same away-time monitoring, for the browser build.
 *
 * On the desktop the main process owns this: it sees the window lose focus even while the page
 * is hidden, and its accounting cannot be reached from the page. In a browser there is no main
 * process, so this does the equivalent with the Page Visibility API — which is also the more
 * literal reading of leaving a contest, since in a browser the thing someone switches is a tab.
 *
 * Reaching someone who has switched away is the hard part in a browser, and the answer is
 * mostly *not* notifications. The tab title is rewritten while they are away, which needs no
 * permission, cannot be refused, and appears in the tab strip of the very window they switched
 * to. A notification is shown as well, but only when permission has already been granted —
 * asking for it from a timer has no user activation behind it, and Firefox and Safari reject
 * that outright. Permission is instead requested the next time they actually click something.
 *
 * Weaker than the desktop version, and the product says so rather than hiding it: a background
 * tab can be throttled or frozen, and the page can simply be closed. That is why a contest sat
 * in a browser also reports LOCKDOWN_UNAVAILABLE — an admin sees the weaker kind of monitoring
 * rather than a suspiciously clean record.
 */
export function useAwayMonitor(active: boolean): LockdownState | null {
  const [state, setState] = useState<LockdownState | null>(null)

  const awaySince = useRef<number | null>(null)
  const completedMs = useRef(0)
  const focusLosses = useRef(0)
  const longAbsences = useRef(0)
  const warnings = useRef(0)
  const nextWarningAt = useRef<number | null>(null)
  const countedLong = useRef(false)
  const originalTitle = useRef<string>('')

  useEffect(() => {
    if (!active || isDesktop()) return

    originalTitle.current = document.title

    const hiddenNow = () =>
      document.visibilityState === 'hidden' || !document.hasFocus()

    const reset = () => {
      const away = hiddenNow()
      awaySince.current = away ? Date.now() : null
      completedMs.current = 0
      focusLosses.current = 0
      longAbsences.current = 0
      warnings.current = 0
      nextWarningAt.current = away ? Date.now() + AWAY_WARN_MS : null
      countedLong.current = false
    }
    reset()

    const publish = () => {
      const now = Date.now()
      const current = awaySince.current === null ? 0 : now - awaySince.current
      setState({
        engaged: true,
        reason: 'Contest',
        since: null,
        away: awaySince.current !== null,
        focusLosses: focusLosses.current,
        longAbsences: longAbsences.current,
        awayMs: completedMs.current + current,
        currentAwayMs: current,
        warnings: warnings.current,
        clipboardWipes: 0,
        blocked: 0,
        warnAfterMs: AWAY_WARN_MS,
      })
    }

    /**
     * Permission is asked for on a real click, never from the timer.
     *
     * Firefox and Safari require user activation for requestPermission and reject a call
     * without it, so asking at the moment a warning is due would fail on exactly the browsers
     * where it was asked. Once granted it persists, so the next contest gets the notification.
     */
    const askForPermission = () => {
      try {
        if (typeof Notification === 'undefined') return
        if (Notification.permission !== 'default') return
        void Notification.requestPermission()
      } catch { /* a browser that refuses to be asked at all */ }
    }

    const notify = (seconds: number) => {
      try {
        if (typeof Notification === 'undefined') return
        if (Notification.permission !== 'granted') return
        new Notification('Back to the contest', {
          body: `You have been away for ${seconds} seconds. `
            + 'Time outside this tab is recorded and reported.',
          tag: 'cpintel-away',   // replaces the previous one rather than stacking
        })
      } catch { /* denied between the check and the call, or blocked by policy */ }
    }

    /**
     * The tab title, which is the one channel that always works.
     *
     * Alternated rather than set once: a static title is easy to miss in a strip of tabs, and
     * a changing one catches the eye in peripheral vision. This is the only part of the warning
     * that cannot be turned off or refused by the browser.
     */
    let flash = false
    const showAwayTitle = (seconds: number) => {
      flash = !flash
      document.title = flash
        ? `(${seconds}s) ⚠ Back to the contest`
        : `⚠ You are away — ${seconds}s`
    }
    const restoreTitle = () => {
      if (originalTitle.current) document.title = originalTitle.current
    }

    const onHide = () => {
      if (awaySince.current !== null) return
      awaySince.current = Date.now()
      focusLosses.current++
      nextWarningAt.current = Date.now() + AWAY_WARN_MS
      countedLong.current = false
      publish()
    }

    const onShow = () => {
      if (awaySince.current === null) return
      const duration = Date.now() - awaySince.current
      completedMs.current += duration
      awaySince.current = null
      nextWarningAt.current = null
      if (duration >= AWAY_WARN_MS && !countedLong.current) {
        longAbsences.current++
        countedLong.current = true
      }
      restoreTitle()
      publish()
    }

    const onVisibility = () => {
      if (document.visibilityState === 'hidden') onHide()
      else onShow()
    }

    // Both, deliberately. Switching tabs fires visibilitychange; switching to another
    // application often only fires blur, and leaving the contest is leaving it either way.
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('blur', onHide)
    window.addEventListener('focus', onShow)
    window.addEventListener('pointerdown', askForPermission)
    window.addEventListener('keydown', askForPermission)

    const ticker = setInterval(() => {
      if (awaySince.current === null) return
      const now = Date.now()
      const seconds = Math.round((now - awaySince.current) / 1000)

      if (nextWarningAt.current !== null && now >= nextWarningAt.current) {
        nextWarningAt.current = now + AWAY_REPEAT_MS
        warnings.current++
        if (!countedLong.current) {
          longAbsences.current++
          countedLong.current = true
        }
        notify(seconds)
      }

      // Starts at the threshold and keeps going, so the countdown in the tab strip is live.
      if (now - awaySince.current >= AWAY_WARN_MS) showAwayTitle(seconds)
      publish()
    }, TICK_MS)

    publish()

    return () => {
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('blur', onHide)
      window.removeEventListener('focus', onShow)
      window.removeEventListener('pointerdown', askForPermission)
      window.removeEventListener('keydown', askForPermission)
      clearInterval(ticker)
      restoreTitle()
      setState(null)
    }
  }, [active])

  return state
}
