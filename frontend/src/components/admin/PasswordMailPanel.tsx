import { useState } from 'react'
import { Loader2, Mail } from 'lucide-react'

import { mailKey, type usePasswordMail } from '@/hooks/usePasswordMail'
import type { SheetPerson } from '@/components/admin/PasswordSheetDialog'

interface Props {
  mail: ReturnType<typeof usePasswordMail>
  /** Undefined while the server is being asked. */
  status: { configured: boolean; from: string | null } | undefined
  statusFailed: boolean
  people: SheetPerson[]
  subject: string
  onSubject: (subject: string) => void
  /** Why the message cannot be sent yet, if it cannot. */
  blocked: string | null
  exam: boolean
  onSend: (people: SheetPerson[]) => void
}

const people_ = (n: number) => `${n} ${n === 1 ? 'person' : 'people'}`

/** The emailing half of the password sheet editor. Nothing goes until it is confirmed. */
export function PasswordMailPanel({
  mail, status, statusFailed, people, subject, onSubject, blocked, exam, onSend,
}: Props) {
  const [confirming, setConfirming] = useState(false)
  const pending = people.filter(p => p.userId != null && !mail.sent.has(mailKey(p)))
  const gone = people.filter(p => mail.sent.has(mailKey(p))).length

  return (
    <section aria-label="Email" className="space-y-2 border-t border-gray-800 pt-3">
      <h3 className="text-sm font-medium text-gray-300">Email</h3>

      {statusFailed ? (
        <p className="text-xs text-amber-300">Could not check whether email is set up.</p>
      ) : !status ? (
        <p className="text-xs text-gray-500">Checking whether email is set up…</p>
      ) : !status.configured ? (
        <p className="text-xs text-gray-500">
          Email is not set up on this server (<code>SMTP_HOST</code>), so these can only be
          downloaded.
        </p>
      ) : (
        <>
          <div>
            <label htmlFor="sheet-subject" className="mb-1 block text-xs text-gray-500">
              Subject
            </label>
            <input
              id="sheet-subject"
              value={subject}
              onChange={e => { onSubject(e.target.value); setConfirming(false) }}
              disabled={mail.sending}
              maxLength={200}
              className="input text-sm"
            />
          </div>

          {blocked && <p className="text-xs text-amber-300">{blocked}</p>}

          {mail.sending ? (
            <div className="flex items-center gap-3 text-xs text-gray-300" role="status">
              <Loader2 size={13} className="animate-spin" />
              <span className="tabular-nums">
                Sending… {mail.progress!.done} of {mail.progress!.total}
              </span>
              <button type="button" onClick={mail.stop} className="btn-secondary">Stop</button>
            </div>
          ) : confirming && !blocked ? (
            // Brought into view: it opens at the bottom of a column that scrolls.
            <div ref={box => { box?.scrollIntoView?.({ block: 'nearest' }) }}
              className="space-y-2 rounded-lg border border-amber-900 bg-amber-950/30 p-3">
              <p className="text-sm text-gray-200">
                Send {pending.length === 1 && pending[0].email
                  ? `this password to ${pending[0].email}`
                  : `${people_(pending.length)} their passwords, each to the address on their account`}
                {status.from ? `, from ${status.from}` : ''}?
              </p>
              <p className="text-xs text-amber-300">
                {exam
                  ? 'Whoever can read one of these emails can sign in for the examination as that '
                    + 'candidate, from wherever they are. A sheet handed out in the room cannot '
                    + 'be used that way.'
                  : 'A password in a mailbox stays there. The message should ask them to change it.'}
              </p>
              <div className="flex gap-2">
                <button type="button" onClick={() => setConfirming(false)}
                  className="btn-secondary">Cancel</button>
                <button type="button" className="btn-primary"
                  onClick={() => { setConfirming(false); onSend(pending) }}>
                  <Mail size={14} /> Send now
                </button>
              </div>
            </div>
          ) : pending.length > 0 ? (
            <button type="button" onClick={() => setConfirming(true)} disabled={!!blocked}
              className="btn-secondary">
              <Mail size={14} />
              {gone > 0 ? `Email the ${pending.length} not sent` : `Email ${people_(pending.length)}`}
            </button>
          ) : (
            <button type="button" onClick={mail.reset} className="btn-secondary">
              Send again
            </button>
          )}

          {gone > 0 && (
            <p className="text-xs text-green-300" role="status">
              {gone} of {people.length} handed to the mail server.
            </p>
          )}
          {mail.note && <p className="text-xs text-amber-300">{mail.note}</p>}
          {mail.failed.length > 0 && (
            <ul className="max-h-32 space-y-0.5 overflow-y-auto text-xs text-red-300">
              {mail.failed.map((f, i) => <li key={i}>{f.username}: {f.error}</li>)}
            </ul>
          )}
        </>
      )}
    </section>
  )
}
