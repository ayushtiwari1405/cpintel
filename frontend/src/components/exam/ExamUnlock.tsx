import { useState } from 'react'
import { AlertTriangle, ArrowLeft, Clock, KeyRound, Loader2, Lock } from 'lucide-react'
import { formatDistanceToNow } from 'date-fns'

import { useUnlockExam } from '@/hooks/useExams'
import type { MyExam } from '@/types'

interface Props {
  exam: MyExam
  onLeave: () => void
}

/**
 * The door to a live examination.
 *
 * <p>What stands between being on a roster and being inside a paper. The roster was decided a
 * fortnight ago and cannot say whether somebody is in the room; the clock says the paper is
 * open and cannot say it is open for this person, here. The passwords handed out at the desk
 * are the only part of the arrangement that can, which is why this screen exists at all.
 *
 * <p><b>It tells the candidate what it wants before they get it wrong.</b> Whether the paper
 * asks for the room's password, their own code, or both, is stated up front rather than
 * discovered from a refusal — somebody in an examination hall with a clock running should
 * never be guessing at how many boxes they were meant to fill in.
 *
 * <p><b>The refusal does not say which half failed.</b> That is not obstruction: naming the
 * wrong half would turn a pair of secrets into two independent guesses, which is exactly what
 * the pair exists to prevent. The candidate has both slips in front of them and the message
 * says to check both.
 */
export function ExamUnlock({ exam, onLeave }: Props) {
  const [examPassword, setExamPassword] = useState('')
  const [passcode, setPasscode] = useState('')
  const unlock = useUnlockExam()

  const live = exam.event.lifecycle === 'ACTIVE'
  const wantsShared = exam.needsExamPassword
  const wantsPersonal = exam.needsPasscode

  const error = unlock.error
    ? ((unlock.error as any).response?.data?.message as string | undefined)
      ?? 'That did not work. Try again.'
    : null

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!live) return
    unlock.mutate({
      examId: exam.event.eventId,
      examPassword: examPassword.trim() || undefined,
      passcode: passcode.trim() || undefined,
    })
  }

  return (
    <div className="mx-auto max-w-lg py-10">
      <button
        onClick={onLeave}
        className="mb-5 flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-300"
      >
        <ArrowLeft size={14} /> Back to your examinations
      </button>

      <div className="rounded-2xl border border-gray-800 bg-gray-900 p-7">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl
            bg-indigo-950 border border-indigo-900">
            <Lock size={18} className="text-indigo-400" />
          </div>
          <div className="min-w-0">
            <h1 className="truncate text-lg font-semibold text-gray-50">{exam.event.name}</h1>
            <p className="text-xs text-gray-500">
              {live
                ? exam.event.endsAt
                  ? `Running — ends ${formatDistanceToNow(new Date(exam.event.endsAt),
                      { addSuffix: true })}`
                  : 'Running'
                : 'Not open yet'}
            </p>
          </div>
        </div>

        <p className="mt-5 text-sm leading-relaxed text-gray-400">
          {wantsShared && wantsPersonal
            ? 'This paper needs two things: the examination password your invigilator gives '
              + 'the room, and the personal code printed on your own slip.'
            : wantsPersonal
              ? 'This paper needs the personal code printed on your own slip.'
              : 'This paper needs the examination password your invigilator gives the room.'}
        </p>

        {!live && (
          // A password is not a key to a paper that is not running. Said here so somebody who
          // was handed their slip early does not sit pressing a button that will refuse them.
          <p className="mt-4 flex items-start gap-2 rounded-lg border border-amber-900
            bg-amber-950/40 px-3 py-2.5 text-xs leading-relaxed text-amber-200">
            <Clock size={13} className="mt-0.5 flex-shrink-0" />
            <span>
              It cannot be unlocked until it starts, even with the right password. This page
              notices by itself when the window opens — you can leave it where it is.
            </span>
          </p>
        )}

        <form onSubmit={handleSubmit} className="mt-6 space-y-4">
          {wantsShared && (
            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1.5">
                Examination password
              </label>
              <input
                type="text"
                value={examPassword}
                onChange={e => setExamPassword(e.target.value)}
                className="input font-mono tracking-[0.2em]"
                placeholder="XXXX-XXXX"
                // Off, all of it. These are read off paper and typed once; a browser
                // autocorrecting or capitalising them helps nobody and a saved-password prompt
                // for a code that expires with the paper is noise.
                autoComplete="off"
                autoCapitalize="characters"
                autoCorrect="off"
                spellCheck={false}
                disabled={!live}
                autoFocus
              />
            </div>
          )}

          {wantsPersonal && (
            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1.5">
                Your own code
              </label>
              <input
                type="text"
                value={passcode}
                onChange={e => setPasscode(e.target.value)}
                className="input font-mono tracking-[0.2em]"
                placeholder="XXXX-XXXX"
                autoComplete="off"
                autoCapitalize="characters"
                autoCorrect="off"
                spellCheck={false}
                disabled={!live}
                autoFocus={!wantsShared}
              />
              <p className="mt-1 text-xs text-gray-600">
                The one printed on the slip on your desk, not the one read out to the room.
              </p>
            </div>
          )}

          {error && (
            <p className="flex items-start gap-2 rounded-lg border border-red-900
              bg-red-950/40 px-3 py-2.5 text-xs leading-relaxed text-red-200">
              <AlertTriangle size={13} className="mt-0.5 flex-shrink-0" />
              <span>{error}</span>
            </p>
          )}

          <button
            type="submit"
            disabled={!live || unlock.isPending}
            className="btn-primary flex w-full items-center justify-center gap-2
              disabled:cursor-not-allowed disabled:opacity-40"
          >
            {unlock.isPending
              ? <><Loader2 size={14} className="animate-spin" /> Opening…</>
              : <><KeyRound size={14} /> Open the examination</>}
          </button>
        </form>

        <p className="mt-5 text-xs leading-relaxed text-gray-600">
          Typing it without the hyphens, or in lower case, works the same. If neither code opens
          the paper, put your hand up — your invigilator can issue you a new one without
          affecting anybody else.
        </p>
      </div>
    </div>
  )
}
