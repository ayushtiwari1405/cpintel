/**
 * Time spent away from the contest window.
 *
 * Pure state machine, driven by whoever has the real clock — the Electron window's focus
 * events in the desktop build. Kept free of Electron so the rules can be tested directly,
 * because the interesting parts are all edge cases: an absence that ends exactly on the
 * threshold, a warning that must not repeat every tick, and totals that have to stay correct
 * across an absence that is still running when someone asks for them.
 */

/** How long someone may be away before it is worth saying anything. */
export const AWAY_WARN_MS = 10_000

/** How often to repeat the warning while they are still away. */
export const AWAY_REPEAT_MS = 30_000

export interface AwaySnapshot {
  /** True while the window does not have focus. */
  away: boolean
  /** Every time focus left, however briefly. */
  focusLosses: number
  /** Only the absences that ran past the warning threshold. */
  longAbsences: number
  /** Total time away, including an absence still in progress. */
  awayMs: number
  /** How long the current absence has run, or 0 when present. */
  currentAwayMs: number
  /** Warnings actually shown to the contestant. */
  warnings: number
}

export interface AbsenceEnded {
  durationMs: number
  /** True when this absence ran past the threshold and was therefore warned about. */
  long: boolean
}

export class AwayTracker {
  private awaySince: number | null = null
  private completedMs = 0
  private focusLosses = 0
  private longAbsences = 0
  private warnings = 0
  /** When the next warning for the current absence is due, in epoch ms. */
  private nextWarningAt: number | null = null
  /** Whether the current absence has already been counted as a long one. */
  private countedLong = false

  constructor(
    private readonly warnAfterMs: number = AWAY_WARN_MS,
    private readonly repeatMs: number = AWAY_REPEAT_MS,
  ) {}

  /** Discards all accounting — called when a contest starts, not when one is paused. */
  reset(now: number, away: boolean): void {
    this.awaySince = away ? now : null
    this.completedMs = 0
    this.focusLosses = 0
    this.longAbsences = 0
    this.warnings = 0
    this.nextWarningAt = away ? now + this.warnAfterMs : null
    this.countedLong = false
  }

  onBlur(now: number): void {
    if (this.awaySince !== null) return    // already away; a duplicate event changes nothing
    this.awaySince = now
    this.focusLosses++
    this.nextWarningAt = now + this.warnAfterMs
    this.countedLong = false
  }

  /**
   * Ends the current absence.
   *
   * Returns what it was, so the caller can report a long absence and ignore a glance. Null
   * when there was no absence to end, which happens on the focus event that follows startup.
   */
  onFocus(now: number): AbsenceEnded | null {
    if (this.awaySince === null) return null

    const durationMs = Math.max(0, now - this.awaySince)
    this.completedMs += durationMs
    this.awaySince = null
    this.nextWarningAt = null

    const long = durationMs >= this.warnAfterMs
    // Counted here only if the ticker did not already count it: an absence that ran past the
    // threshold while away has been counted, and counting it again on return would double it.
    if (long && !this.countedLong) {
      this.longAbsences++
      this.countedLong = true
    }
    return { durationMs, long }
  }

  /**
   * Whether a warning is due right now.
   *
   * Called on a timer while away. Advances its own schedule, so a caller that ticks every
   * second still produces one warning at the threshold and then one per repeat interval,
   * rather than one per tick.
   */
  dueForWarning(now: number): boolean {
    if (this.awaySince === null || this.nextWarningAt === null) return false
    if (now < this.nextWarningAt) return false

    this.nextWarningAt = now + this.repeatMs
    this.warnings++
    if (!this.countedLong) {
      this.longAbsences++
      this.countedLong = true
    }
    return true
  }

  /** How long the current absence has run — what a warning message quotes. */
  currentAwayMs(now: number): number {
    return this.awaySince === null ? 0 : Math.max(0, now - this.awaySince)
  }

  snapshot(now: number): AwaySnapshot {
    const current = this.currentAwayMs(now)
    return {
      away: this.awaySince !== null,
      focusLosses: this.focusLosses,
      longAbsences: this.longAbsences,
      awayMs: this.completedMs + current,
      currentAwayMs: current,
      warnings: this.warnings,
    }
  }
}
