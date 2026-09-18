import { useEffect, useRef, useState } from 'react'
import { desktopLockdown, isDesktop, type LockdownState } from '@/utils/desktopBridge'

interface Options {
  /** True while the lock should be held — a contest that is actually running. */
  active: boolean
  /** Shown back to the user as the reason the machine is locked. */
  reason: string
}

/**
 * Runs the desktop contest monitor for as long as this component says it should run.
 *
 * Engaged by the page that owns the contest rather than by a button, so there is no moment
 * where a contestant has to remember to turn it on and no moment where they can quietly turn
 * it off. Leaving the page, the contest ending, and the window closing all stop it, because
 * monitoring that outlives its contest would keep warning someone about a finished round.
 *
 * In the browser build every call is a no-op and `state` stays null — see useAwayMonitor,
 * which covers that case by watching the tab instead.
 */
export function useLockdown({ active, reason }: Options): LockdownState | null {
  const [state, setState] = useState<LockdownState | null>(null)

  // Held in a ref so the engage/release effect does not re-run when only the name changes —
  // re-engaging mid-contest would reset the away-time accounting.
  const reasonRef = useRef(reason)
  reasonRef.current = reason

  useEffect(() => {
    if (!isDesktop()) return
    return desktopLockdown.onChange(setState)
  }, [])

  useEffect(() => {
    if (!isDesktop()) return
    let cancelled = false

    if (active) {
      desktopLockdown.engage(reasonRef.current).then(s => { if (!cancelled) setState(s) })
    } else {
      desktopLockdown.release().then(s => { if (!cancelled) setState(s) })
    }

    // Unmounting means the contest page is gone — navigated away from, or the app is closing.
    // Either way nothing is left to lock, and the main process must not be left holding kiosk
    // mode with no renderer able to ask for it back.
    return () => {
      cancelled = true
      if (active) desktopLockdown.release()
    }
  }, [active])

  useStatementGuard(active)

  return state
}

/**
 * Stops the problem statement being copied out while the contest is being monitored.
 *
 * The sample inputs and outputs are exempt, marked in the markup with `data-clipboard-allow`:
 * copying those is how test cases get built, and refusing it would break the contest rather
 * than protect it.
 *
 * This is a speed bump and nothing more. A screenshot, a second machine, or a phone camera all
 * walk straight past it, and no renderer-side guard can change that. It is here because the
 * one-keystroke path from a statement into another window is worth closing, not because
 * closing it is sufficient.
 */
function useStatementGuard(active: boolean): void {
  useEffect(() => {
    if (!active || !isDesktop()) return

    const isProtected = (element: Element | null | undefined): boolean => {
      if (!element) return false
      if (element.closest('[data-clipboard-allow]')) return false
      return !!element.closest('.cf-statement')
    }

    const elementOf = (target: EventTarget | null): Element | null =>
      target instanceof Element ? target : null

    // The selection, not the event target, is what actually gets copied: a Ctrl+C with the
    // statement selected fires against whatever holds focus, which is often the document.
    const selectedElement = (): Element | null | undefined => {
      const node = window.getSelection()?.anchorNode
      return node instanceof Element ? node : node?.parentElement
    }

    const onCopy = (event: Event) => {
      if (isProtected(elementOf(event.target)) || isProtected(selectedElement())) {
        event.preventDefault()
      }
    }
    const onDrag = (event: Event) => {
      if (isProtected(elementOf(event.target))) event.preventDefault()
    }

    document.addEventListener('copy', onCopy, true)
    document.addEventListener('cut', onCopy, true)
    document.addEventListener('dragstart', onDrag, true)
    return () => {
      document.removeEventListener('copy', onCopy, true)
      document.removeEventListener('cut', onCopy, true)
      document.removeEventListener('dragstart', onDrag, true)
    }
  }, [active])
}
