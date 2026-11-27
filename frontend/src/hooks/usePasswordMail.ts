import { useEffect, useRef, useState } from 'react'

import type { PasswordMail, PasswordMailResult } from '@/api/adminApi'

/** As many as the server takes in one request. */
const BATCH = 5

export interface OutgoingMail extends PasswordMail {
  username: string
}

/** A password counts as sent only while it is the same one: a reissued code is unsent again. */
export const mailKey = (person: { userId?: number; password: string }) =>
  `${person.userId}:${person.password}`

export interface MailFailure {
  username: string
  error: string
}

/**
 * Sending drafted passwords a few at a time, so there is progress to show, a way to stop, and an
 * answer for each person. Leaving the screen stops it after the request in flight.
 */
export function usePasswordMail(
  deliver: (batch: PasswordMail[]) => Promise<PasswordMailResult[]>,
) {
  const [sent, setSent] = useState<ReadonlySet<string>>(new Set())
  const [failed, setFailed] = useState<MailFailure[]>([])
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const stopped = useRef(false)

  useEffect(() => () => { stopped.current = true }, [])

  const send = async (messages: OutgoingMail[]) => {
    stopped.current = false
    setFailed([])
    setNote(null)
    setProgress({ done: 0, total: messages.length })

    for (let start = 0; start < messages.length; start += BATCH) {
      if (stopped.current) {
        setNote(`Stopped. ${messages.length - start} not sent.`)
        break
      }
      const batch = messages.slice(start, start + BATCH)
      let results: PasswordMailResult[]
      try {
        results = await deliver(batch.map(({ userId, password, subject, lines }) =>
          ({ userId, password, subject, lines })))
      } catch (e: any) {
        // No answer at all leaves it unknown whether the server sent them before it was lost.
        setNote(e.response
          ? `Stopped: ${e.response.data?.message ?? 'CPIntel refused the request'}`
          : `Stopped: no answer from CPIntel. The last ${batch.length} may or may not have been `
            + 'sent.')
        break
      }
      // The server answers in the order asked.
      const went = batch.filter((_, i) => results[i]?.sent).map(mailKey)
      const missed = batch.flatMap((m, i) => results[i]?.sent
        ? [] : [{ username: m.username, error: results[i]?.error ?? 'Not sent' }])
      setSent(current => new Set([...current, ...went]))
      setFailed(current => [...current, ...missed])
      setProgress({ done: start + batch.length, total: messages.length })

      if (went.length === 0 && missed.every(m => m.error.startsWith('The mail server'))
          && start + batch.length < messages.length) {
        setNote('Stopped: the mail server is not taking mail. Nothing further was tried.')
        break
      }
    }
    setProgress(null)
  }

  return {
    sent, failed, note, progress,
    sending: progress !== null,
    send,
    stop: () => { stopped.current = true },
    /** Forgets what has gone, to send it all again. */
    reset: () => { setSent(new Set()); setFailed([]); setNote(null) },
  }
}
