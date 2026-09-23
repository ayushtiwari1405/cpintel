import { useState } from 'react'
import { KeyRound, Loader2, ShieldCheck } from 'lucide-react'

import { useChangePassword } from '@/hooks/useAuth'

/** The floor the server enforces, stated so the button explains itself before it is pressed. */
const MIN_LENGTH = 8

/**
 * Changing your own password, from your own profile.
 *
 * <p>This is the half of the arrangement that makes the password yours. The first one was
 * chosen by whoever created the account and handed over with the username, which means
 * somebody other than the owner knows it until this screen is used — so the card says so
 * rather than leaving it to be worked out.
 *
 * <p>The current password is asked for even though the page already holds a valid session,
 * because a session can be an unlocked laptop and this field is what makes the person at the
 * keyboard the owner rather than whoever sat down after them.
 *
 * <p>Succeeding signs every device out, including this one. That is stated on the button's
 * own label rather than discovered when the page bounces to sign-in, because an unexplained
 * sign-out reads as a failure.
 */
export function ChangePasswordCard({ neverChanged }: { neverChanged?: boolean }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const change = useChangePassword()

  const tooShort = next.length > 0 && next.length < MIN_LENGTH
  const mismatch = confirm.length > 0 && next !== confirm
  const ready = current.length > 0 && next.length >= MIN_LENGTH && next === confirm

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!ready) return
    change.mutate({ currentPassword: current, newPassword: next })
  }

  return (
    <div className="card space-y-4">
      <div className="flex items-start gap-2.5">
        <KeyRound size={16} className="mt-0.5 flex-shrink-0 text-gray-500" />
        <div>
          <p className="text-gray-300 font-medium">Password</p>
          <p className="mt-0.5 text-xs leading-relaxed text-gray-500">
            Signing in is the only thing this changes — your examination passwords are handed
            out by your invigilator and are not affected.
          </p>
        </div>
      </div>

      {neverChanged && (
        // Worth saying out loud. Until this is used, the password on the account is the one an
        // administrator typed, and they still know it.
        <p className="flex items-start gap-2 rounded-lg border border-amber-900 bg-amber-950/40
          px-3 py-2 text-xs leading-relaxed text-amber-200">
          <ShieldCheck size={13} className="mt-0.5 flex-shrink-0" />
          <span>
            You are still using the password you were given. Whoever set up your account chose
            it and knows it — changing it here makes the account yours alone.
          </span>
        </p>
      )}

      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label className="block text-sm font-medium text-gray-300 mb-1.5">
            Current password
          </label>
          <input
            type="password"
            className="input"
            value={current}
            onChange={e => setCurrent(e.target.value)}
            autoComplete="current-password"
            placeholder="••••••••"
          />
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-300 mb-1.5">New password</label>
          <input
            type="password"
            className="input"
            value={next}
            onChange={e => setNext(e.target.value)}
            autoComplete="new-password"
            placeholder="••••••••"
          />
          <p className={`mt-1 text-xs ${tooShort ? 'text-amber-400' : 'text-gray-600'}`}>
            At least {MIN_LENGTH} characters. Length is the only rule.
          </p>
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-300 mb-1.5">Type it again</label>
          <input
            type="password"
            className="input"
            value={confirm}
            onChange={e => setConfirm(e.target.value)}
            autoComplete="new-password"
            placeholder="••••••••"
          />
          {mismatch && <p className="mt-1 text-xs text-amber-400">These two do not match.</p>}
        </div>

        <button
          type="submit"
          disabled={!ready || change.isPending}
          className="btn-primary flex items-center gap-2 disabled:cursor-not-allowed
            disabled:opacity-40"
        >
          {change.isPending
            ? <><Loader2 size={14} className="animate-spin" /> Changing…</>
            : <><KeyRound size={14} /> Change password and sign out everywhere</>}
        </button>
      </form>
    </div>
  )
}
