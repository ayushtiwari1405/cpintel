/**
 * The two monitoring queues (examinations and team contests) send events in batches and keep a
 * batch until the server has taken it. The server refuses a whole batch when one event breaks
 * its limits, so an event that did would be retried for ever and nothing behind it would arrive.
 * These keep events inside the limits, and drop the ones the server names if one still gets
 * through.
 */

interface Reportable {
  eventId: string
  type: string
  detail?: string | null
  problemLabel?: string | null
}

/** The server's limits on one event: ClientEvent and ViolationEvent. */
const LIMITS = { eventId: 64, type: 40, problemLabel: 16, detail: 500 } as const

export function fitEvent<T extends Reportable>(event: T): T {
  const fitted = { ...event } as unknown as Record<string, unknown>
  for (const [field, max] of Object.entries(LIMITS)) {
    const value = fitted[field]
    if (typeof value === 'string') fitted[field] = value.split('\u0000').join('').slice(0, max)
  }
  return fitted as T
}

/** Positions in the batch the server refused as invalid, from a 400's field errors. */
export function rejectedEvents(error: unknown): Set<number> {
  const response = (error as {
    response?: { status?: number; data?: { errors?: Record<string, string> } }
  } | null)?.response
  const found = new Set<number>()
  if (response?.status !== 400) return found
  for (const field of Object.keys(response.data?.errors ?? {})) {
    const match = /^events\[(\d+)\]/.exec(field)
    if (match) found.add(Number(match[1]))
  }
  return found
}

/** The queue without the refused events of the batch that was just sent from its front. */
export function withoutRejected<T>(queue: T[], batchSize: number, rejected: Set<number>): T[] {
  return queue.filter((_, index) => index >= batchSize || !rejected.has(index))
}
