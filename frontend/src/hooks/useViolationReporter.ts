import { useEffect, useRef } from 'react'
import { groupsApi } from '@/api/groupsApi'
import { isDesktop, type LockdownState } from '@/utils/desktopBridge'
import type { ViolationEvent } from '@/types'

/** How often the queue is flushed to the server. */
const FLUSH_MS = 15_000

/**
 * Refused keystrokes are collapsed into one event per window.
 *
 * Holding a blocked key produces a stream of refusals; sending one report each would drown the
 * trail in noise and tell an admin nothing that a count does not.
 */
const BLOCKED_WINDOW_MS = 60_000

interface Options {
  /** The group contest to report into, or null when this round is not being run for a group. */
  contestId: number | null
  /** True while the contest window is open. Nothing is reported outside it. */
  active: boolean
  /** What the desktop lock currently reports, or null in the browser. */
  lockdown: LockdownState | null
}

function newId(): string {
  try {
    return crypto.randomUUID()
  } catch {
    // Older webviews, and any context without a secure origin.
    return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  }
}

/**
 * Reports what this participant's own monitor observed.
 *
 * The monitor exposes running totals; the server stores discrete events. Turning one into the
 * other is the whole job here, and two properties make the result trustworthy.
 *
 * Every event carries a stable id derived from the session and the counter value it came from,
 * so a report that fails and is retried — or sent twice by a reconnecting client — cannot turn
 * one focus loss into five. The server enforces the same thing with a unique index; this just
 * means the common case never reaches it.
 *
 * And the queue is only ever drained on success. A contestant who loses connectivity mid-round
 * still reports everything once it returns, rather than silently having the quietest possible
 * record.
 */
export function useViolationReporter({ contestId, active, lockdown }: Options): void {
  const sessionId = useRef(newId())
  const queue = useRef<ViolationEvent[]>([])
  const sending = useRef(false)

  /** Counter values already turned into events, so only increments produce new ones. */
  const seen = useRef({ longAbsences: 0, clipboardWipes: 0, blocked: 0, awayMs: 0 })
  /** One-off findings that describe the session rather than an incident. */
  const reported = useRef({ unavailable: false, released: false })
  const wasEngaged = useRef(false)

  const enqueue = (event: ViolationEvent) => { queue.current.push(event) }

  // The desktop app may not be in use at all, which is itself worth recording once.
  useEffect(() => {
    if (!active || contestId == null) return
    if (isDesktop() || reported.current.unavailable) return

    reported.current.unavailable = true
    enqueue({
      eventId: `${sessionId.current}-unavailable`,
      type: 'LOCKDOWN_UNAVAILABLE',
      detail: 'Sat in a browser, where monitoring is weaker and can be closed',
      occurredAt: new Date().toISOString(),
    })
  }, [active, contestId])

  useEffect(() => {
    if (!active || contestId == null || !lockdown) return
    const now = new Date().toISOString()

    /*
     * Only absences that ran past the warning threshold are reported.
     *
     * Glancing at a clock or dismissing a notification is not worth an admin's attention, and
     * a feed full of two-second blips would bury the four-minute absence that is. The brief
     * ones are still counted locally and still visible in the badge; they are simply not
     * escalated. The threshold is the same one the contestant was warned at, so nothing is
     * reported that they were not told about first.
     */
    if (lockdown.longAbsences > seen.current.longAbsences) {
      const awayDelta = Math.max(0, lockdown.awayMs - seen.current.awayMs)
      for (let n = seen.current.longAbsences + 1; n <= lockdown.longAbsences; n++) {
        enqueue({
          eventId: `${sessionId.current}-away-${n}`,
          type: 'FOCUS_LOST',
          detail: `Away from the contest for more than ${Math.round(lockdown.warnAfterMs / 1000)}s`,
          durationMs: n === lockdown.longAbsences ? awayDelta : undefined,
          occurredAt: now,
        })
      }
      seen.current.longAbsences = lockdown.longAbsences
      seen.current.awayMs = lockdown.awayMs
    }

    if (lockdown.clipboardWipes > seen.current.clipboardWipes) {
      for (let n = seen.current.clipboardWipes + 1; n <= lockdown.clipboardWipes; n++) {
        enqueue({
          eventId: `${sessionId.current}-clipboard-${n}`,
          type: 'CLIPBOARD_FOREIGN',
          detail: 'Clipboard content appeared while the window was away, and was discarded',
          occurredAt: now,
        })
      }
      seen.current.clipboardWipes = lockdown.clipboardWipes
    }

    if (lockdown.blocked > seen.current.blocked) {
      const bucket = Math.floor(Date.now() / BLOCKED_WINDOW_MS)
      const delta = lockdown.blocked - seen.current.blocked
      enqueue({
        eventId: `${sessionId.current}-blocked-${bucket}`,
        type: 'BLOCKED_ACTION',
        detail: `${delta} keystroke${delta === 1 ? '' : 's'} or actions refused`,
        occurredAt: now,
      })
      seen.current.blocked = lockdown.blocked
    }

    // Monitoring that was running and then was not, while the round is still open.
    if (lockdown.engaged) wasEngaged.current = true
    if (wasEngaged.current && !lockdown.engaged && !reported.current.released) {
      reported.current.released = true
      enqueue({
        eventId: `${sessionId.current}-released`,
        type: 'LOCKDOWN_RELEASED',
        detail: 'Monitoring stopped while the contest was still running',
        occurredAt: now,
      })
    }
  }, [active, contestId, lockdown])

  useEffect(() => {
    if (!active || contestId == null) return

    const flush = async () => {
      if (sending.current || queue.current.length === 0) return
      sending.current = true
      const batch = queue.current.slice(0, 200)
      try {
        await groupsApi.reportViolations(contestId, batch)
        // Only now: a failed send must leave the queue intact, or a contestant with a flaky
        // connection ends up with the cleanest record in the group.
        queue.current = queue.current.slice(batch.length)
      } catch {
        /* keep the queue and try again on the next tick */
      } finally {
        sending.current = false
      }
    }

    const timer = setInterval(flush, FLUSH_MS)
    void flush()
    return () => {
      clearInterval(timer)
      void flush()
    }
  }, [active, contestId])
}
