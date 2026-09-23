import { useEffect, useRef } from 'react'
import { groupsApi } from '@/api/groupsApi'

/** Starting interval, replaced by whatever the server says it wants. */
const DEFAULT_INTERVAL_MS = 15_000

interface Options {
  /** The group contest to report into, or null when this round is not run for a group. */
  contestId: number | null
  /** True while the contest window is open. Nothing is sent outside it. */
  active: boolean
}

/**
 * Tells the server this contestant's monitoring is still running.
 *
 * <p>Separate from {@link useViolationReporter}, and the split is the point. That hook batches
 * observations and only sends when there is something to say; a contestant who never leaves
 * the window sends nothing at all. So silence there cannot mean "the monitor stopped", because
 * silence is also what perfect behaviour looks like. This says "still here" on a fixed timer
 * whether or not anything has happened, which is the only signal a submission gate can read.
 *
 * <p>The first beat is sent immediately rather than after one interval. A contestant who opens
 * the page and submits within the first fifteen seconds would otherwise be refused for not
 * being monitored, which is both wrong and the worst possible first impression of the feature.
 *
 * <p>Failures are deliberately silent. A missed beat is recoverable — the server's window is
 * several intervals wide — and a toast on every transient network blip during a contest would
 * be noise at the exact moment a contestant can least afford it. If beats keep failing, the
 * submission itself carries the explanation.
 */
export function useMonitorHeartbeat({ contestId, active }: Options): void {
  const intervalMs = useRef(DEFAULT_INTERVAL_MS)

  useEffect(() => {
    if (!active || contestId == null) return

    let stopped = false
    let timer: ReturnType<typeof setTimeout> | null = null

    const beat = async () => {
      if (stopped) return
      try {
        const res = await groupsApi.heartbeat(contestId)
        const seconds = res.data?.intervalSeconds
        // The server owns the cadence, so a deployment can widen its tolerance and the page
        // follows without a release.
        if (seconds && seconds > 0) intervalMs.current = seconds * 1000
      } catch {
        // Silent on purpose — see above.
      }
      if (!stopped) timer = setTimeout(beat, intervalMs.current)
    }

    // Immediately, so submitting in the first few seconds is not refused.
    void beat()

    return () => {
      stopped = true
      if (timer) clearTimeout(timer)
    }
  }, [contestId, active])
}
