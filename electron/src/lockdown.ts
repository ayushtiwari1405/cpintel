import { app, BrowserWindow, Notification, clipboard, session } from 'electron'
import { AwayTracker, AWAY_REPEAT_MS, AWAY_WARN_MS } from './away'

/**
 * Contest monitoring.
 *
 * Engaged automatically while a contest is running, released when it ends. It watches for the
 * contestant leaving the contest window, warns them when they have been gone long enough to
 * matter, and reports what it saw.
 *
 * It deliberately does not try to hold the machine shut. An earlier version grabbed Alt+Tab and
 * the Super key system-wide and refused a long list of keystrokes; that broke ordinary use of
 * the computer, and on Windows and macOS it could not actually take the window-switching keys
 * anyway. Prevention that half works is worse than none: it is disruptive, and it invites
 * someone to trust it. What is left is the part that does work — noticing, saying so, and
 * keeping an accurate record.
 *
 * Nothing about the keyboard is touched any more, and it no longer needs to be: the accounting
 * lives in the main process, so reloading the page or opening DevTools cannot forge away-time
 * or erase it. Navigation and new windows are still refused, but that is about keeping the
 * contest on screen rather than about keystrokes.
 */

export interface LockdownState {
  engaged: boolean
  /** What engaged it — a contest name, shown back to the user. */
  reason: string | null
  since: number | null
  /** True while the contest window does not have focus. */
  away: boolean
  /** Every time focus left, however briefly. */
  focusLosses: number
  /** Only the absences that ran past the warning threshold. */
  longAbsences: number
  /** Total time away, including an absence still running. */
  awayMs: number
  /** How long the current absence has run, or 0 when present. */
  currentAwayMs: number
  /** Warnings actually shown to the contestant. */
  warnings: number
  /** Times the clipboard was wiped because it changed while the window was away. */
  clipboardWipes: number
  /** Navigations and window-opens refused. */
  blocked: number
  /** How long someone may be away before being warned, so the UI can say the same number. */
  warnAfterMs: number
}

/** How often the away timer is checked while the contestant is gone. */
const TICK_MS = 1_000

export class Lockdown {
  private win: BrowserWindow | null = null
  private engaged = false
  private reason: string | null = null
  private since: number | null = null

  private readonly tracker = new AwayTracker(AWAY_WARN_MS, AWAY_REPEAT_MS)
  private ticker: NodeJS.Timeout | null = null

  private clipboardWipes = 0
  private blocked = 0

  /** What the clipboard held when focus left, so a change while away can be spotted. */
  private clipboardOnBlur: string | null = null

  private readonly onChange: (state: LockdownState) => void

  /** Bound once so the same references can be removed on release. */
  private readonly handleBlur = () => this.onBlur()
  private readonly handleFocus = () => this.onFocus()

  constructor(_devBuild: boolean, onChange: (state: LockdownState) => void) {
    this.onChange = onChange
  }

  attach(win: BrowserWindow): void {
    this.win = win

    // Navigating the window elsewhere would take the contest page — and the monitor with it —
    // off the screen entirely. Refused from the moment the window exists, not on engage.
    win.webContents.on('will-navigate', (event, url) => {
      if (this.engaged && !this.isInternal(url)) {
        event.preventDefault()
        this.blocked++
        this.emit()
      }
    })

    // A renderer that has crashed cannot ask to be released, and monitoring that outlives its
    // contest would keep warning someone about a round that finished.
    win.webContents.on('render-process-gone', () => this.release('renderer gone'))
    win.on('unresponsive', () => this.release('renderer unresponsive'))
    win.on('closed', () => { this.win = null })
  }

  state(): LockdownState {
    const snapshot = this.tracker.snapshot(Date.now())
    return {
      engaged: this.engaged,
      reason: this.reason,
      since: this.since,
      away: this.engaged && snapshot.away,
      focusLosses: snapshot.focusLosses,
      longAbsences: snapshot.longAbsences,
      awayMs: snapshot.awayMs,
      currentAwayMs: snapshot.currentAwayMs,
      warnings: snapshot.warnings,
      clipboardWipes: this.clipboardWipes,
      blocked: this.blocked,
      warnAfterMs: AWAY_WARN_MS,
    }
  }

  /** True while a contest is being written under monitoring. Gates openExternal in main. */
  isEngaged(): boolean {
    return this.engaged
  }

  countBlocked(): void {
    this.blocked++
    this.emit()
  }

  engage(reason: string): LockdownState {
    if (this.engaged || !this.win) return this.state()

    this.engaged = true
    this.reason = reason
    this.since = Date.now()
    this.clipboardWipes = 0
    this.blocked = 0
    // A contest that starts while the window is in the background is already an absence.
    this.tracker.reset(Date.now(), !this.win.isFocused())

    const win = this.win
    win.on('blur', this.handleBlur)
    win.on('focus', this.handleFocus)

    // Nothing on this screen has any business asking for a camera or a microphone, and a
    // permission sheet is a modal window over the contest.
    session.defaultSession.setPermissionRequestHandler((_wc, _permission, callback) => {
      if (this.engaged) {
        this.blocked++
        callback(false)
        return
      }
      callback(true)
    })

    this.startTicker()
    this.emit()
    return this.state()
  }

  release(why = 'released'): LockdownState {
    if (!this.engaged) return this.state()

    this.engaged = false
    this.reason = null
    this.since = null
    // Close any absence still open, so the final total is not missing its last stretch.
    this.tracker.onFocus(Date.now())
    this.stopTicker()

    const win = this.win
    if (win && !win.isDestroyed()) {
      win.off('blur', this.handleBlur)
      win.off('focus', this.handleFocus)
    }

    session.defaultSession.setPermissionRequestHandler(null)

    console.log('[monitor]', why)
    this.emit()
    return this.state()
  }

  /** Called from app teardown, so nothing is left running after the window is gone. */
  dispose(): void {
    this.release('app quitting')
  }

  // ------------------------------------------------------------------ internals

  private isInternal(url: string): boolean {
    return url.startsWith('file://')
      || url.startsWith('http://localhost:5173')
      || url.startsWith('app://')
  }

  private onBlur(): void {
    if (!this.engaged) return
    this.tracker.onBlur(Date.now())
    // Read now, compare on the way back. Anything that appears in the clipboard while the
    // window is away came from somewhere else.
    this.clipboardOnBlur = safeReadClipboard()
    this.emit()
  }

  private onFocus(): void {
    if (!this.engaged) return
    this.tracker.onFocus(Date.now())

    // Copy and paste inside the app are untouched — that is how code gets written. What is
    // refused is content that arrived from outside while the contestant was elsewhere.
    const now = safeReadClipboard()
    if (now !== null && now !== '' && now !== this.clipboardOnBlur) {
      clipboard.clear()
      this.clipboardWipes++
    }
    this.clipboardOnBlur = null
    this.emit()
  }

  /**
   * Checks once a second whether a warning has come due.
   *
   * A timer rather than a single scheduled callback, because the interesting case is someone
   * who stays away: the tracker decides when the next warning is due and this only has to ask
   * often enough to be prompt.
   */
  private startTicker(): void {
    this.stopTicker()
    this.ticker = setInterval(() => {
      if (!this.engaged) return
      const now = Date.now()
      if (this.tracker.dueForWarning(now)) {
        this.warn(this.tracker.currentAwayMs(now))
        this.emit()
      } else if (this.tracker.snapshot(now).away) {
        // Keep the renderer's clock moving so the badge counts up rather than jumping.
        this.emit()
      }
    }, TICK_MS)
    // Never a reason to hold the process open for this.
    this.ticker.unref?.()
  }

  private stopTicker(): void {
    if (this.ticker) {
      clearInterval(this.ticker)
      this.ticker = null
    }
  }

  /**
   * Tells the contestant they have been away, while they are still away.
   *
   * A native notification rather than something in the page, deliberately: the page is behind
   * whatever they switched to, so a banner there would only be seen once they had already come
   * back. The point is to reach them where they are.
   */
  private warn(awayMs: number): void {
    const seconds = Math.round(awayMs / 1000);
    const where = this.reason ?? 'the contest'
    console.log(`[monitor] warned after ${seconds}s away`)

    try {
      if (!Notification.isSupported()) return
      new Notification({
        title: 'Back to the contest',
        body: `You have been away from ${where} for ${seconds} seconds. `
          + 'Time outside the window is recorded and reported.',
        urgency: 'critical',
      }).show()
    } catch (e) {
      // A desktop with no notification daemon is a normal Linux configuration. The absence is
      // still measured and still reported; only the nudge is lost.
      console.log('[monitor] could not show a notification:',
        e instanceof Error ? e.message : String(e))
    }
  }

  private emit(): void {
    this.onChange(this.state())
  }
}

/**
 * Reading the clipboard can throw when another process holds it open, which on Windows is
 * common enough to matter. A failed read means "cannot tell", and cannot-tell must not be
 * mistaken for "it changed" — that would wipe the clipboard on a timing accident.
 */
function safeReadClipboard(): string | null {
  try {
    return clipboard.readText()
  } catch {
    return null
  }
}

/** Kept out of the class so main.ts can wire teardown without holding the instance. */
export function disposeOnQuit(lockdown: Lockdown): void {
  app.on('before-quit', () => lockdown.dispose())
}
