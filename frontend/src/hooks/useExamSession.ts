import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import { examsApi } from '@/api/examsApi'
import { isDesktop, type LockdownState } from '@/utils/desktopBridge'
import type { ExamClientEvent, ExamEventType, MyExam } from '@/types'

/** How often the queue is flushed to the server. */
const FLUSH_MS = 10_000

/** Starting heartbeat interval, replaced by whatever the server says it wants. */
const DEFAULT_HEARTBEAT_MS = 15_000

/** How long before the start and the end a candidate is warned. */
const STARTING_SOON_S = 5 * 60
const ENDING_SOON_S = 5 * 60
const ENDING_IMMINENT_S = 60

export interface ExamNotice {
  id: string
  tone: 'info' | 'warn' | 'danger'
  title: string
  body: string
}

interface Options {
  /** The examination being sat, or null when none is open. */
  exam: MyExam | null
  /** What the monitor currently reports — the desktop lock, or the browser's away watcher. */
  lockdown: LockdownState | null
}

export interface ExamSession {
  /** What to tell the candidate right now, newest first. */
  notices: ExamNotice[]
  dismiss: (id: string) => void
  /** Records that a problem was opened. Called by the page, not by a listener. */
  problemOpened: (label: string) => void
  /** Records that the candidate considers themselves finished. */
  completed: () => void
  away: boolean
  awayMs: number
  focusLosses: number
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
 * Everything the examination client does while somebody is sitting one.
 *
 * <p>Three jobs, kept in one hook because they share a session and must agree about it: turning
 * what the monitor observes into the events the log is built from, telling the candidate what
 * is happening to them, and saying on a timer that the monitoring is still running.
 *
 * <p><b>The candidate is told everything that is recorded about them.</b> A focus loss produces
 * a notice as well as an event; an absence past the threshold produces a warning naming the
 * threshold. A monitor that reports on somebody without telling them is a different and much
 * worse product, and the notices here are not a courtesy — they are the other half of what
 * makes the log fair to rely on.
 *
 * <p><b>The queue drains only on success.</b> A candidate whose connection drops mid-paper
 * still reports everything once it returns, rather than ending up with the quietest record in
 * the room. Every event carries a stable id, so resending a batch cannot turn one absence into
 * five — the server enforces that with a unique index, and this means the common case never
 * reaches it.
 */
export function useExamSession({ exam, lockdown }: Options): ExamSession {
  const examId = exam?.event.eventId ?? null
  const live = exam?.event.lifecycle === 'ACTIVE'

  const sessionId = useRef(newId())
  const queue = useRef<ExamClientEvent[]>([])
  const sending = useRef(false)
  const [notices, setNotices] = useState<ExamNotice[]>([])

  /** Counter values already turned into events, so only increments produce new ones. */
  const seen = useRef({ focusLosses: 0, longAbsences: 0, awayMs: 0, blocked: 0, clipboard: 0 })
  const wasAway = useRef(false)
  /** Notices that are shown once per session rather than every time their condition holds. */
  const announced = useRef<Record<string, boolean>>({})
  const heartbeatMs = useRef(DEFAULT_HEARTBEAT_MS)

  const thresholdMs = (exam?.event.awayThresholdSeconds ?? 10) * 1000

  const notify = useCallback((id: string, tone: ExamNotice['tone'],
                              title: string, body: string) => {
    setNotices(current => {
      // Replacing rather than stacking: the fourth "you are away" says nothing the first did
      // not, and a column of them would push the examination off the screen.
      const without = current.filter(n => n.id !== id)
      return [{ id, tone, title, body }, ...without].slice(0, 4)
    })
  }, [])

  const dismiss = useCallback((id: string) => {
    setNotices(current => current.filter(n => n.id !== id))
  }, [])

  const enqueue = useCallback((type: ExamEventType, extra: Partial<ExamClientEvent> = {}) => {
    queue.current.push({
      eventId: extra.eventId ?? `${sessionId.current}-${type}-${queue.current.length}-${Date.now()}`,
      type,
      occurredAt: new Date().toISOString(),
      ...extra,
    })
  }, [])

  // ------------------------------------------------------------- reporting

  /** Records that this examination is being sat in a browser, where monitoring is weaker. */
  useEffect(() => {
    if (!live || examId == null || isDesktop()) return
    if (announced.current.browser) return
    announced.current.browser = true

    enqueue('SUSPICIOUS_ACTIVITY', {
      eventId: `${sessionId.current}-browser`,
      detail: 'Sat in a browser: monitoring is weaker here and the page can simply be closed',
    })
  }, [live, examId, enqueue])

  // Focus, absences and anything the desktop lock refused, derived from the monitor's counters.
  useEffect(() => {
    if (!live || examId == null || !lockdown) return

    if (lockdown.focusLosses > seen.current.focusLosses) {
      for (let n = seen.current.focusLosses + 1; n <= lockdown.focusLosses; n++) {
        enqueue('FOCUS_LOST', {
          eventId: `${sessionId.current}-focus-${n}`,
          detail: 'The examination window stopped being the one in front',
        })
      }
      seen.current.focusLosses = lockdown.focusLosses
      notify('focus', 'info', 'That was noticed',
        'Leaving the examination window is recorded. Nothing is decided by it — an '
        + 'invigilator sees what happened, not a verdict.')
    }

    // An absence that has ended: its length is only known now, so this is where it is reported.
    if (wasAway.current && !lockdown.away) {
      const delta = Math.max(0, lockdown.awayMs - seen.current.awayMs)
      seen.current.awayMs = lockdown.awayMs
      enqueue('FOCUS_REGAINED', {
        eventId: `${sessionId.current}-back-${lockdown.focusLosses}`,
        durationMs: delta,
        detail: `Back after ${Math.round(delta / 1000)}s`,
      })
      dismiss('away')
    }
    wasAway.current = lockdown.away

    if (lockdown.longAbsences > seen.current.longAbsences) {
      for (let n = seen.current.longAbsences + 1; n <= lockdown.longAbsences; n++) {
        enqueue('AWAY_THRESHOLD_EXCEEDED', {
          eventId: `${sessionId.current}-threshold-${n}`,
          durationMs: lockdown.currentAwayMs || undefined,
          detail: `Away for longer than the ${Math.round(thresholdMs / 1000)}s allowed`,
        })
      }
      seen.current.longAbsences = lockdown.longAbsences
      notify('away', 'warn', 'You have been away',
        `You have been away from the examination for longer than the `
        + `${Math.round(thresholdMs / 1000)} seconds this paper allows. Please return to the `
        + 'examination interface — the absence has been recorded and its length is reported.')
    }

    const refused = lockdown.blocked - seen.current.blocked
        + (lockdown.clipboardWipes - seen.current.clipboard)
    if (refused > 0) {
      enqueue('LOCKDOWN_TRIGGERED', {
        eventId: `${sessionId.current}-lockdown-${lockdown.blocked}-${lockdown.clipboardWipes}`,
        detail: `${refused} action${refused === 1 ? '' : 's'} refused by the examination lock`,
      })
      seen.current.blocked = lockdown.blocked
      seen.current.clipboard = lockdown.clipboardWipes
    }
  }, [live, examId, lockdown, enqueue, notify, dismiss, thresholdMs])

  // --------------------------------------------------------------- notices

  // The clock. Driven by the page's own ticker via the exam's seconds fields, which the
  // caller re-reads; everything here is idempotent so a repeated render says nothing twice.
  useEffect(() => {
    if (!exam) return
    const { lifecycle } = exam.event

    if (lifecycle === 'SCHEDULED' && exam.secondsUntilStart <= STARTING_SOON_S
        && exam.secondsUntilStart > 0 && !announced.current.soon) {
      announced.current.soon = true
      notify('clock', 'info', 'Starting shortly',
        `${exam.event.name} opens in ${Math.ceil(exam.secondsUntilStart / 60)} minute(s). `
        + 'Stay on this page — it opens by itself.')
    }
    if (lifecycle === 'ACTIVE' && !announced.current.started) {
      announced.current.started = true
      notify('clock', 'info', 'The examination has started',
        exam.event.lockdownRequired
          ? 'You are being monitored while this paper is open, and you are told about '
            + 'everything that is recorded.'
          : 'Good luck.')
    }
    if (lifecycle === 'ACTIVE' && exam.secondsRemaining <= ENDING_IMMINENT_S
        && exam.secondsRemaining > 0 && !announced.current.imminent) {
      announced.current.imminent = true
      notify('clock', 'danger', 'Less than a minute left',
        'Submit what you have now. Submissions stop when the window closes.')
    } else if (lifecycle === 'ACTIVE' && exam.secondsRemaining <= ENDING_SOON_S
        && exam.secondsRemaining > ENDING_IMMINENT_S && !announced.current.ending) {
      announced.current.ending = true
      notify('clock', 'warn', 'Time is running out',
        `${Math.ceil(exam.secondsRemaining / 60)} minute(s) left in this examination.`)
    }
    if (lifecycle === 'ENDED' && !announced.current.ended) {
      announced.current.ended = true
      notify('clock', 'info', 'The examination has ended',
        'No further submissions are accepted. Your results appear here once they are published.')
    }
  }, [exam, notify])

  // ------------------------------------------------------- flush and beat

  useEffect(() => {
    if (examId == null) return

    const flush = async () => {
      if (sending.current || queue.current.length === 0) return
      sending.current = true
      const batch = queue.current.slice(0, 200)
      try {
        await examsApi.report(examId, batch)
        // Only now: a failed send must leave the queue intact.
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
  }, [examId])

  useEffect(() => {
    if (!live || examId == null) return

    let stopped = false
    let timer: ReturnType<typeof setTimeout> | null = null

    const beat = async () => {
      if (stopped) return
      try {
        const res = await examsApi.heartbeat(examId)
        const seconds = res.data?.intervalSeconds
        // The server owns the cadence, so a deployment can widen its tolerance without a
        // release of the page.
        if (seconds && seconds > 0) heartbeatMs.current = seconds * 1000
      } catch {
        // Silent: a missed beat is recoverable, and a toast per network blip mid-examination
        // is noise at the worst possible moment. A refused submission explains itself.
      }
      if (!stopped) timer = setTimeout(beat, heartbeatMs.current)
    }

    // Immediately, so submitting in the first few seconds is not refused for want of a beat.
    void beat()
    return () => {
      stopped = true
      if (timer) clearTimeout(timer)
    }
  }, [live, examId])

  /**
   * Leaving the page mid-paper.
   *
   * The confirmation is the browser's own and cannot be styled or relied upon — some browsers
   * ignore it entirely — so the event is queued first and flushed by the effect above on
   * unmount. Closing the tab outright may still lose the last few seconds of the queue; that
   * is what the heartbeat's expiry is for, since a monitor that stops reporting is exactly
   * what the dashboard shows as having left.
   */
  useEffect(() => {
    if (!live || examId == null) return

    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      enqueue('EXAM_EXITED', {
        eventId: `${sessionId.current}-exit-${Date.now()}`,
        detail: 'Navigated away from the examination interface',
      })
      event.preventDefault()
      event.returnValue = ''
    }

    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [live, examId, enqueue])

  const problemOpened = useCallback((label: string) => {
    if (!live || examId == null) return
    enqueue('PROBLEM_OPENED', {
      eventId: `${sessionId.current}-open-${label}-${Date.now()}`,
      problemLabel: label,
    })
  }, [live, examId, enqueue])

  const completed = useCallback(() => {
    if (examId == null) return
    enqueue('EXAM_COMPLETED', {
      eventId: `${sessionId.current}-completed`,
      detail: 'Marked as finished by the candidate',
    })
    notify('done', 'info', 'Marked as finished',
      'Your invigilator can see that you are done. Anything already submitted still counts.')
  }, [examId, enqueue, notify])

  return useMemo(() => ({
    notices,
    dismiss,
    problemOpened,
    completed,
    away: !!lockdown?.away,
    awayMs: lockdown?.awayMs ?? 0,
    focusLosses: lockdown?.focusLosses ?? 0,
  }), [notices, dismiss, problemOpened, completed, lockdown])
}
