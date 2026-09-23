import { useState } from 'react'
import {
  AlertTriangle, Copy, Download, KeyRound, Loader2, Printer, RefreshCw, Trash2,
} from 'lucide-react'

import { Ago, EmptyRow, Panel, Pill } from '@/components/admin/AdminUi'
import { useToast } from '@/components/common/Toaster'
import { adminEventsApi } from '@/api/examsApi'
import {
  useClearExamPassword, useExamPasswordStatus, useGenerateExamPassword, useIssuePasscodes,
  useReissuePasscode, useRevokePasscodes,
} from '@/hooks/useExams'
import type { IssuedPasscode } from '@/types'

/**
 * The passwords that open one examination, and the screen that prints them.
 *
 * <h2>Why this tab exists</h2>
 *
 * <p>Being on a roster cannot say somebody is in the room, and a clock cannot say a paper is
 * open <em>for this person, here</em>. The passwords handed out at the desk are the only part
 * of the arrangement that can, which is what this screen produces.
 *
 * <p>Two of them, answering different questions. The <b>examination password</b> is one string
 * for the whole paper, read out when the invigilator starts it — it means "this sitting has
 * begun". A <b>candidate's code</b> is theirs alone, printed on the slip on their desk — it
 * means "the person typing this is the person this seat belongs to", which the shared password
 * cannot mean, because by the time the paper starts everyone in the room has it.
 *
 * <h2>Nothing here is emailed</h2>
 *
 * <p>Deliberately, and there is no route that would. These are printed and carried into the
 * room: the credential in somebody's mailbox gets them into CPIntel, and getting into the paper
 * additionally needs something only the invigilator can hand them. Mailing an examination
 * password would collapse the two, and a candidate at home with a phone would have everything.
 *
 * <h2>Reading them is an action, not a page load</h2>
 *
 * <p>The status above loads with the tab and says only whether passwords exist. The codes
 * themselves arrive from a separate request that is audited every time it is made — so an
 * admin who opened this screen has not thereby read the room's codes.
 */
export function ExamPasswordsTab({ eventId }: { eventId: number }) {
  const toast = useToast()
  const { data: status, isLoading } = useExamPasswordStatus(eventId)

  const [sharedPassword, setSharedPassword] = useState<string | null>(null)
  const [codes, setCodes] = useState<IssuedPasscode[] | null>(null)
  const [revealing, setRevealing] = useState(false)

  const generate = useGenerateExamPassword()
  const clear = useClearExamPassword()
  const issue = useIssuePasscodes()
  const reissue = useReissuePasscode()
  const revoke = useRevokePasscodes()

  const copy = (value: string, what: string) => {
    navigator.clipboard?.writeText(value)
      .then(() => toast.push('success', `${what} copied`))
      // Clipboard access is refused in plenty of ordinary situations — an insecure origin, a
      // browser that wants a gesture it did not see. The code is on the screen either way, so
      // this is worth a note rather than an error.
      .catch(() => toast.push('info', 'Could not reach the clipboard — copy it by hand'))
  }

  const revealShared = async () => {
    const res = await adminEventsApi.revealExamPassword(eventId)
    setSharedPassword(res.data?.password ?? null)
  }

  const revealCodes = async () => {
    setRevealing(true)
    try {
      setCodes((await adminEventsApi.revealPasscodes(eventId)).data)
    } finally {
      setRevealing(false)
    }
  }

  if (isLoading || !status) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-sm text-gray-500">
        <Loader2 size={14} className="animate-spin" /> Loading…
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {!status.keyConfigured && (
        // Without the key nothing can be encrypted, so nothing can be generated. Named exactly,
        // because the person reading this is the one who can set it.
        <p className="flex items-start gap-2 rounded-lg border border-amber-900
          bg-amber-950/40 px-3 py-2.5 text-xs leading-relaxed text-amber-200">
          <AlertTriangle size={14} className="mt-0.5 flex-shrink-0" />
          <span>
            <span className="font-medium">CPINTEL_EXAM_PASSWORD_KEY is not set</span>, so no
            passwords can be generated and this examination will open for anyone assigned to
            it. Set it in the environment and restart before running a paper that needs one.
          </span>
        </p>
      )}

      <Panel
        title="Examination password"
        description="One string for the whole paper, read out when you start it. Rotating it
          ends every session opened with the previous one, which is what makes it worth
          rotating if it reaches the wrong room."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {status.examPasswordSet && (
              <button
                onClick={revealShared}
                className="rounded-md border border-gray-800 px-2.5 py-1.5 text-xs
                  text-gray-300 transition-colors hover:bg-gray-800"
              >
                Show it
              </button>
            )}
            <button
              onClick={() => generate.mutate({ eventId }, {
                onSuccess: (res) => setSharedPassword(res.data.password),
              })}
              disabled={!status.keyConfigured || generate.isPending}
              className="flex items-center gap-1.5 rounded-md bg-indigo-600 px-2.5 py-1.5
                text-xs font-medium text-white transition-colors hover:bg-indigo-500
                disabled:cursor-not-allowed disabled:opacity-40"
            >
              {generate.isPending
                ? <Loader2 size={12} className="animate-spin" />
                : status.examPasswordSet ? <RefreshCw size={12} /> : <KeyRound size={12} />}
              {status.examPasswordSet ? 'Rotate' : 'Generate'}
            </button>
            {status.examPasswordSet && (
              <button
                onClick={() => clear.mutate({ eventId }, {
                  onSuccess: () => setSharedPassword(null),
                })}
                className="rounded-md border border-gray-800 px-2.5 py-1.5 text-xs
                  text-gray-400 transition-colors hover:bg-gray-800"
              >
                Run without one
              </button>
            )}
          </div>
        }
      >
        {status.examPasswordSet ? (
          <div className="space-y-2">
            <div className="flex flex-wrap items-center gap-3">
              <code className="rounded-lg border border-gray-800 bg-gray-950 px-4 py-2.5
                font-mono text-lg tracking-[0.25em] text-gray-100">
                {sharedPassword ?? '••••-••••'}
              </code>
              {sharedPassword && (
                <button
                  onClick={() => copy(sharedPassword, 'The examination password')}
                  className="flex items-center gap-1.5 rounded-md border border-gray-800
                    px-2.5 py-1.5 text-xs text-gray-400 transition-colors hover:bg-gray-800"
                >
                  <Copy size={12} /> Copy
                </button>
              )}
            </div>
            <p className="text-xs text-gray-500">
              Set <Ago at={status.examPasswordSetAt} />
              {status.examPasswordSetBy && <> by {status.examPasswordSetBy}</>}
              {status.generation > 1 && <> · rotated {status.generation - 1} time
                {status.generation - 1 === 1 ? '' : 's'}</>}
            </p>
          </div>
        ) : (
          <p className="text-sm text-gray-500">
            No shared password. This examination will not ask the room for one — which is a
            reasonable choice for a take-home paper, and the wrong one for an invigilated hall.
          </p>
        )}
      </Panel>

      <Panel
        title="Candidates' own codes"
        description="One per person, printed on the slip on their desk. Issue is additive —
          somebody added the morning of the paper gets a code without invalidating the slips
          already printed."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {status.passcodesIssued > 0 && (
              <>
                <button
                  onClick={revealCodes}
                  disabled={revealing}
                  className="rounded-md border border-gray-800 px-2.5 py-1.5 text-xs
                    text-gray-300 transition-colors hover:bg-gray-800"
                >
                  {revealing ? 'Reading…' : 'Show them'}
                </button>
                <button
                  onClick={() => issue.mutate({ eventId, regenerate: true }, {
                    onSuccess: (res) => setCodes(res.data),
                  })}
                  className="flex items-center gap-1.5 rounded-md border border-gray-800
                    px-2.5 py-1.5 text-xs text-gray-400 transition-colors hover:bg-gray-800"
                >
                  <RefreshCw size={12} /> Reissue all
                </button>
                <button
                  onClick={() => revoke.mutate({ eventId }, {
                    onSuccess: () => setCodes(null),
                  })}
                  className="flex items-center gap-1.5 rounded-md border border-gray-800
                    px-2.5 py-1.5 text-xs text-red-400 transition-colors
                    hover:bg-red-950/40"
                >
                  <Trash2 size={12} /> Withdraw all
                </button>
              </>
            )}
            <button
              onClick={() => issue.mutate({ eventId }, {
                onSuccess: (res) => setCodes(res.data),
              })}
              disabled={!status.keyConfigured || issue.isPending}
              className="flex items-center gap-1.5 rounded-md bg-indigo-600 px-2.5 py-1.5
                text-xs font-medium text-white transition-colors hover:bg-indigo-500
                disabled:cursor-not-allowed disabled:opacity-40"
            >
              {issue.isPending
                ? <Loader2 size={12} className="animate-spin" />
                : <KeyRound size={12} />}
              Issue codes
            </button>
          </div>
        }
      >
        <div className="flex flex-wrap items-center gap-2 text-xs text-gray-500">
          <Pill tone={status.passcodesIssued > 0 ? 'indigo' : 'gray'}>
            {status.passcodesIssued} issued
          </Pill>
          <span>of {status.participantCount} assigned</span>
          {status.passcodesIssued > 0
            && status.passcodesIssued < status.participantCount && (
            // The case that costs somebody their paper: a candidate assigned after the codes
            // were printed, who arrives to find nothing on their desk.
            <span className="text-amber-400">
              — {status.participantCount - status.passcodesIssued} assigned candidate
              {status.participantCount - status.passcodesIssued === 1 ? ' has' : 's have'} no
              code yet
            </span>
          )}
        </div>

        {codes && codes.length > 0 && (
          <div className="mt-4 space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <button
                onClick={() => downloadSlips(codes)}
                className="flex items-center gap-1.5 rounded-md border border-gray-800
                  px-2.5 py-1.5 text-xs text-gray-300 transition-colors hover:bg-gray-800"
              >
                <Download size={12} /> Download as CSV
              </button>
              <button
                onClick={() => window.print()}
                className="flex items-center gap-1.5 rounded-md border border-gray-800
                  px-2.5 py-1.5 text-xs text-gray-300 transition-colors hover:bg-gray-800"
              >
                <Printer size={12} /> Print
              </button>
            </div>

            <div className="overflow-x-auto rounded-lg border border-gray-800">
              <table className="w-full text-sm">
                <thead className="bg-gray-900/60 text-left text-xs uppercase
                  tracking-wider text-gray-500">
                  <tr>
                    <th className="px-4 py-2.5 font-medium">Candidate</th>
                    <th className="px-4 py-2.5 font-medium">Code</th>
                    <th className="px-4 py-2.5 font-medium">First used</th>
                    <th className="px-4 py-2.5 font-medium" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-800">
                  {codes.length === 0 && (
                    <EmptyRow colSpan={4}>No codes have been issued.</EmptyRow>
                  )}
                  {codes.map(row => (
                    <tr key={row.userId} className="hover:bg-gray-900/40">
                      <td className="px-4 py-2.5">
                        <span className="text-gray-200">{row.fullName ?? row.username}</span>
                        {row.fullName && (
                          <span className="ml-1.5 text-xs text-gray-600">{row.username}</span>
                        )}
                      </td>
                      <td className="px-4 py-2.5">
                        <code className="font-mono tracking-[0.2em] text-gray-100">
                          {row.code}
                        </code>
                      </td>
                      <td className="px-4 py-2.5 text-xs text-gray-500">
                        <Ago at={row.firstUsedAt} fallback="not yet" />
                        {row.useCount > 1 && (
                          // Not a finding. A code that opened the paper twice is usually a
                          // reload; it is worth an invigilator's eye and nothing more.
                          <span className="ml-1.5 text-amber-400">
                            · used {row.useCount} times
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-2.5 text-right">
                        <button
                          onClick={() => reissue.mutate({ eventId, userId: row.userId }, {
                            onSuccess: (res) => setCodes(current => current?.map(c =>
                              c.userId === row.userId ? res.data : c) ?? null),
                          })}
                          className="rounded-md border border-gray-800 px-2 py-1 text-xs
                            text-gray-400 transition-colors hover:bg-gray-800"
                        >
                          Reissue
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <p className="text-xs leading-relaxed text-gray-600">
              These are the only copies you will be handed on screen. They stay readable here —
              a candidate whose slip goes missing mid-paper can be given theirs again, or a new
              one — but nothing is emailed, and nothing reaches a candidate except from you.
            </p>
          </div>
        )}
      </Panel>
    </div>
  )
}

/**
 * The desk slips, as a file.
 *
 * CSV rather than a PDF because what this actually feeds is a mail merge or a spreadsheet
 * somebody already has a slip template in, and generating a layout nobody asked for would be
 * one more thing to fight at eight in the morning.
 */
function downloadSlips(codes: IssuedPasscode[]) {
  const escape = (value: string) => `"${value.replace(/"/g, '""')}"`
  const rows = [
    ['username', 'name', 'code'].join(','),
    ...codes.map(row =>
      [row.username, row.fullName ?? '', row.code].map(escape).join(',')),
  ].join('\n')

  const url = URL.createObjectURL(new Blob([rows], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = 'exam-codes.csv'
  link.click()
  URL.revokeObjectURL(url)
}
