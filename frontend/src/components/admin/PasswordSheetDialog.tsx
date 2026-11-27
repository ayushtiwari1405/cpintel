import { useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, ChevronLeft, ChevronRight, Download, FileText } from 'lucide-react'

import { adminApi } from '@/api/adminApi'
import { adminEventsApi } from '@/api/examsApi'
import { PasswordMailPanel } from '@/components/admin/PasswordMailPanel'
import { Dialog } from '@/components/common/Dialog'
import { usePasswordMail } from '@/hooks/usePasswordMail'
import { downloadPdf, PAGE_HEIGHT, PAGE_WIDTH, textWidth, type Run } from '@/utils/pdf'
import {
  allSheets, checkSheets, fillSubject, mailLines, sheetPages, usedVariables,
  type SheetValues, type SheetVariable,
} from '@/utils/passwordSheet'

/** Somebody whose password has just been generated, set or read back. */
export interface SheetPerson {
  /** Whose account it is; without it the sheet can be printed but not emailed. */
  userId?: number
  username: string
  password: string
  fullName?: string | null
  email?: string | null
  /** Roster imports only. */
  team?: string | null
}

/** Which screen the sheets are for. Each keeps its own draft. */
export type SheetKind = 'roster' | 'new-account' | 'new-password' | 'ta' | 'exam'

/** A value that is the same on every sheet, such as the examination's name. */
export interface SharedValue extends SheetVariable {
  value: string
}

const SIGN_IN = `Address: {{site}}
Username: **{{username}}**
Password: {{password}}`

const CHANGE_IT = `Sign in and change this password from your profile before doing anything else.
Do not share it with anyone.`

const DEFAULT_DRAFTS: Record<SheetKind, string> = {
  roster: `# Your CPIntel account

Dear {{name}},

An account has been created for you on CPIntel.

${SIGN_IN}

${CHANGE_IT}`,
  'new-account': `# Your CPIntel account

Dear {{name}},

An account has been created for you on CPIntel.

${SIGN_IN}

${CHANGE_IT}`,
  'new-password': `# Your new CPIntel password

Dear {{name}},

An administrator has set a new password for your account.

${SIGN_IN}

${CHANGE_IT}`,
  ta: `# Your CPIntel account

Dear {{name}},

You have been added as a teaching assistant for {{classroom}}.

${SIGN_IN}

${CHANGE_IT}`,
  exam: `# {{exam}}

Dear {{name}},

Starts: {{start}}
Ends: {{end}}

Address: {{site}}
Username: **{{username}}**
Examination password: {{password}}

Use this password to sign in for this examination. Keep it to yourself.`,
}

const DEFAULT_SUBJECTS: Record<SheetKind, string> = {
  roster: 'Your CPIntel account',
  'new-account': 'Your CPIntel account',
  'new-password': 'Your new CPIntel password',
  ta: 'Your CPIntel account',
  exam: '{{exam}}: your sign-in password',
}

const draftKey = (kind: SheetKind) => `cpintel.passwordSheet.${kind}`
const subjectKey = (kind: SheetKind) => `${draftKey(kind)}.subject`

/** The wording last used here. Only that is kept, never anybody's values. */
function saved(key: string, original: string): string {
  try {
    return localStorage.getItem(key) ?? original
  } catch {
    return original
  }
}

function save(key: string, text: string, original: string) {
  try {
    if (text === original) localStorage.removeItem(key)
    else localStorage.setItem(key, text)
  } catch {
    // Storage switched off or full: the wording still holds until the page is left.
  }
}

/** As many lines as the server will mail in one message. */
const MAIL_LINES = 200

interface Props {
  open: boolean
  onClose: () => void
  kind: SheetKind
  people: SheetPerson[]
  shared?: SharedValue[]
  fileName: string
  /** The examination, for sheets of kind "exam": its passwords are mailed through it. */
  eventId?: number
}

/**
 * Drafting the message that goes out with a password, then downloading one sheet per person or
 * emailing each their own.
 *
 * <p>The PDF is built in the browser from the passwords already on this screen. They leave it
 * only if the admin confirms an email, and then only for the account each belongs to.
 */
export function PasswordSheetDialog({
  open, onClose, kind, people, shared = [], fileName, eventId,
}: Props) {
  const [draft, setDraft] = useState(() => saved(draftKey(kind), DEFAULT_DRAFTS[kind]))
  const [subject, setSubject] = useState(() => saved(subjectKey(kind), DEFAULT_SUBJECTS[kind]))
  const [index, setIndex] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const editor = useRef<HTMLTextAreaElement>(null)

  const variables: SheetVariable[] = [
    { key: 'name', label: 'Full name, or the username if there is none' },
    { key: 'username', label: 'Username' },
    { key: 'password', label: kind === 'exam' ? 'Examination sign-in password' : 'Password' },
    { key: 'email', label: 'Email address' },
    ...(kind === 'roster' ? [{ key: 'team', label: 'Team' }] : []),
    ...shared,
    { key: 'site', label: 'The address of this site' },
    { key: 'date', label: 'Today’s date' },
  ]

  const date = new Date().toLocaleDateString('en-GB',
    { day: 'numeric', month: 'long', year: 'numeric' })
  const values: SheetValues[] = people.map(person => ({
    ...Object.fromEntries(shared.map(s => [s.key, s.value])),
    name: person.fullName?.trim() || person.username,
    username: person.username,
    password: person.password,
    email: person.email ?? '',
    team: person.team ?? '',
    site: window.location.origin,
    date,
  }))

  const problems = checkSheets(draft, variables, values)
  const ready = draft.trim() !== '' && people.length > 0 && problems.unknown.length === 0
  const printable = ready && problems.unprintable.length === 0

  const subjectUses = usedVariables(subject)
  const blocked = !ready ? 'Finish the message first.'
    : !subject.trim() ? 'Give the email a subject.'
    : subjectUses.includes('password') ? 'Keep the password out of the subject.'
    : subjectUses.some(k => !variables.some(v => v.key === k))
      ? 'The subject uses a value that does not exist.'
    : draft.trim().split('\n').length > MAIL_LINES
      ? `The message is too long to email: at most ${MAIL_LINES} lines.`
    : null

  const status = useQuery({
    queryKey: ['admin', 'password-mail-status'],
    queryFn: () => adminApi.passwordMailStatus().then(r => r.data),
    enabled: open,
  })
  const mail = usePasswordMail(batch => (kind === 'exam'
    ? adminEventsApi.mailPasscodes(eventId!, batch)
    : adminApi.mailPasswords(batch)).then(r => r.data))

  const send = (targets: SheetPerson[]) => mail.send(targets.map(person => {
    const mine = values[people.indexOf(person)]
    return {
      userId: person.userId!,
      username: person.username,
      password: person.password,
      subject: fillSubject(subject, mine).slice(0, 200),
      lines: mailLines(draft, mine),
    }
  }))

  const shown = Math.min(index, Math.max(values.length - 1, 0))
  const preview = useMemo(
    () => ready && values[shown] ? sheetPages(draft, values[shown]) : null,
    // The values are rebuilt on every render; these are what actually change them.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [ready, draft, shown, people],
  )

  const change = (next: string) => {
    setDraft(next)
    setError(null)
    save(draftKey(kind), next, DEFAULT_DRAFTS[kind])
  }

  const changeSubject = (next: string) => {
    setSubject(next)
    save(subjectKey(kind), next, DEFAULT_SUBJECTS[kind])
  }

  const insert = (key: string) => {
    const area = editor.current
    const start = area?.selectionStart ?? draft.length
    const end = area?.selectionEnd ?? draft.length
    const token = `{{${key}}}`
    change(draft.slice(0, start) + token + draft.slice(end))
    requestAnimationFrame(() => {
      area?.focus()
      area?.setSelectionRange(start + token.length, start + token.length)
    })
  }

  const download = () => {
    setError(null)
    try {
      downloadPdf(fileName, allSheets(draft, values))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not build the PDF')
    }
  }

  const count = `${people.length} ${people.length === 1 ? 'person' : 'people'}`

  return (
    <Dialog
      open={open}
      onClose={onClose}
      size="xl"
      dismissible={!mail.sending}
      title="Password sheets"
      description={`${count}: download one PDF with a page each, or email each their own.`}
      footer={
        <>
          <span className="mr-auto text-xs text-amber-300">
            The PDF shows every password. Delete it once it is handed out.
          </span>
          <button type="button" onClick={onClose} disabled={mail.sending} className="btn-secondary">
            Close
          </button>
          <button type="button" onClick={download} disabled={!printable} className="btn-primary">
            <Download size={14} /> Download PDF
          </button>
        </>
      }
    >
      <div className="grid gap-5 lg:grid-cols-2">
        <div className="space-y-3">
          <div>
            <label htmlFor="sheet-draft" className="mb-1.5 block text-sm font-medium text-gray-300">
              Message
            </label>
            <textarea
              id="sheet-draft"
              ref={editor}
              value={draft}
              onChange={e => change(e.target.value)}
              rows={16}
              spellCheck
              disabled={mail.sending}
              className="input font-mono text-xs leading-relaxed"
            />
            <p className="mt-1 text-xs text-gray-500">
              Start a line with <code># </code> for a heading; put <code>**</code> around words
              to make them bold. Your wording is remembered in this browser.
            </p>
          </div>

          <div>
            <p className="mb-1.5 text-xs text-gray-500">Insert a value that is filled in per sheet</p>
            <div className="flex flex-wrap gap-1.5">
              {variables.map(v => (
                <button
                  key={v.key}
                  type="button"
                  title={v.label}
                  onClick={() => insert(v.key)}
                  className="rounded-md border border-gray-800 px-2 py-1 font-mono text-xs
                    text-gray-300 transition-colors hover:bg-gray-800"
                >
                  {`{{${v.key}}}`}
                </button>
              ))}
            </div>
          </div>

          {draft !== DEFAULT_DRAFTS[kind] && (
            <button
              type="button"
              onClick={() => change(DEFAULT_DRAFTS[kind])}
              className="text-xs text-gray-500 underline hover:text-gray-300"
            >
              Restore the original wording
            </button>
          )}

          {problems.unknown.length > 0 && (
            <Warning>
              There is no value called {problems.unknown.map(k => `{{${k}}}`).join(', ')}. Use one
              of the buttons above.
            </Warning>
          )}
          {problems.unprintable.length > 0 && (
            <Warning>
              The PDF can only print English and western European characters. Cannot print{' '}
              {problems.unprintable.slice(0, 5).map(u =>
                `“${u.characters.join(' ')}” ${u.where ? `for ${u.where}` : 'in your message'}`)
                .join('; ')}
              {problems.unprintable.length > 5 && ` and ${problems.unprintable.length - 5} more`}.
              {problems.unprintable.some(u => u.where)
                && ' Removing the value that holds them from the message also works.'}
              {' '}Email is not affected.
            </Warning>
          )}
          {!problems.unknown.length && !/\{\{\s*password\s*\}\}/.test(draft) && (
            <Warning>The message has no {'{{password}}'}, so nobody will be given one.</Warning>
          )}
          {problems.blank.map(b => (
            <Warning key={b.key}>
              {`{{${b.key}}}`} is blank for {b.count} of {people.length}.
            </Warning>
          ))}
          {error && <Warning>{error}</Warning>}

          <PasswordMailPanel
            mail={mail}
            status={status.data}
            statusFailed={status.isError}
            people={people}
            subject={subject}
            onSubject={changeSubject}
            blocked={blocked}
            exam={kind === 'exam'}
            onSend={send}
          />
        </div>

        <div className="min-w-0 space-y-2">
          <div className="flex items-center justify-between gap-2 text-xs text-gray-400">
            <span className="flex min-w-0 items-center gap-1.5">
              <FileText size={13} className="shrink-0" />
              <span className="truncate">Preview: {people[shown]?.username ?? 'nobody'}</span>
            </span>
            {people.length > 1 && (
              <span className="flex shrink-0 items-center gap-1">
                <button type="button" aria-label="Previous person" disabled={shown === 0}
                  onClick={() => setIndex(shown - 1)}
                  className="rounded-md p-1 hover:bg-gray-800 disabled:opacity-40">
                  <ChevronLeft size={14} />
                </button>
                <span className="tabular-nums">{shown + 1} of {people.length}</span>
                <button type="button" aria-label="Next person" disabled={shown >= people.length - 1}
                  onClick={() => setIndex(shown + 1)}
                  className="rounded-md p-1 hover:bg-gray-800 disabled:opacity-40">
                  <ChevronRight size={14} />
                </button>
              </span>
            )}
          </div>
          {preview
            ? preview.map((runs, page) => <Page key={page} runs={runs} />)
            : (
              <p className="rounded-lg border border-gray-800 px-4 py-10 text-center text-sm
                text-gray-500">
                The preview appears once the message is ready to print.
              </p>
            )}
        </div>
      </div>
    </Dialog>
  )
}

function Warning({ children }: { children: React.ReactNode }) {
  return (
    <p className="flex items-start gap-2 rounded-lg border border-amber-900 bg-amber-950/30
      px-3 py-2 text-xs text-amber-300">
      <AlertTriangle size={13} className="mt-0.5 shrink-0" />
      <span className="min-w-0 wrap-break-word">{children}</span>
    </p>
  )
}

const FACES = {
  regular: { fontFamily: 'Helvetica, Arial, sans-serif', fontWeight: 400 },
  bold: { fontFamily: 'Helvetica, Arial, sans-serif', fontWeight: 700 },
  mono: { fontFamily: '"Courier New", Courier, monospace', fontWeight: 700 },
}

/**
 * A page as the PDF will lay it out: the same runs at the same places, each held to the width
 * the PDF's font gives it. White paper in either theme.
 */
function Page({ runs }: { runs: Run[] }) {
  return (
    <svg
      viewBox={`0 0 ${PAGE_WIDTH} ${PAGE_HEIGHT}`}
      className="w-full rounded-lg border border-gray-800 bg-white"
    >
      {runs.map((run, i) => (
        <text
          key={i}
          x={run.x}
          y={run.y}
          fontSize={run.size}
          fill="#000"
          xmlSpace="preserve"
          style={{ whiteSpace: 'pre' }}
          textLength={textWidth(run.text, run.font, run.size)}
          lengthAdjust="spacingAndGlyphs"
          {...FACES[run.font]}
        >
          {run.text}
        </text>
      ))}
    </svg>
  )
}
