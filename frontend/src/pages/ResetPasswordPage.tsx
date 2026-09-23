import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { AlertTriangle, ArrowLeft, Eye, EyeOff, Zap } from 'lucide-react'

import { useResetPassword } from '@/hooks/useAuth'

/** The floor the server enforces. Stated here so the button explains itself before it is used. */
const MIN_LENGTH = 8

/**
 * Setting a new password from a link in an email.
 *
 * <p>Unlike the screen that asks for the link, this one is allowed to be honest about
 * failures: somebody holding a link that has expired or already been used needs to be told
 * which, and a token that resolves to nothing says nothing about who owns any account.
 *
 * <p>The confirmation field is checked here rather than server-side because it is not a
 * security property — it is about a typo in something the person cannot see, and the server
 * has no way to know the two boxes were meant to match.
 */
export default function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''

  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [showPass, setShowPass] = useState(false)
  const reset = useResetPassword()

  const tooShort = password.length > 0 && password.length < MIN_LENGTH
  const mismatch = confirm.length > 0 && password !== confirm
  const ready = password.length >= MIN_LENGTH && password === confirm

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!ready) return
    reset.mutate({ token, newPassword: password })
  }

  return (
    <div className="min-h-screen bg-gray-950 flex items-center justify-center p-4">
      <div className="w-full max-w-md">

        <div className="flex items-center gap-3 mb-8 justify-center">
          <div className="w-10 h-10 rounded-xl bg-indigo-600 flex items-center justify-center">
            <Zap size={20} className="text-white" />
          </div>
          <span className="text-xl font-semibold text-white">CPIntel</span>
        </div>

        <div className="bg-gray-900 border border-gray-800 rounded-2xl p-8">
          <h1 className="text-2xl font-semibold text-white mb-1">Set a new password</h1>
          <p className="text-gray-400 text-sm mb-6">
            Choose something only you know. Every device that is signed in will be signed out.
          </p>

          {!token ? (
            // A link opened without its token, which usually means a mail client wrapped it.
            // Said plainly, with the way out, rather than as a form that would fail on submit.
            <div className="flex items-start gap-2.5 rounded-lg border border-amber-900
              bg-amber-950/40 px-3 py-2.5 text-xs leading-relaxed text-amber-200">
              <AlertTriangle size={14} className="mt-0.5 flex-shrink-0" />
              <span>
                This link is missing its token. Some mail clients break long links across two
                lines — try copying the whole thing into the address bar, or ask for a new one.
              </span>
            </div>
          ) : (
            <form onSubmit={handleSubmit} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-300 mb-1.5">
                  New password
                </label>
                <div className="relative">
                  <input
                    type={showPass ? 'text' : 'password'}
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    className="input pr-10"
                    placeholder="••••••••"
                    autoComplete="new-password"
                    required
                    autoFocus
                  />
                  <button
                    type="button"
                    onClick={() => setShowPass(v => !v)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500
                      hover:text-gray-300"
                  >
                    {showPass ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
                <p className={`mt-1 text-xs ${tooShort ? 'text-amber-400' : 'text-gray-600'}`}>
                  At least {MIN_LENGTH} characters. Length is the only rule — a long thing you
                  will remember beats a short one with a symbol in it.
                </p>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-300 mb-1.5">
                  Type it again
                </label>
                <input
                  type={showPass ? 'text' : 'password'}
                  value={confirm}
                  onChange={e => setConfirm(e.target.value)}
                  className="input"
                  placeholder="••••••••"
                  autoComplete="new-password"
                  required
                />
                {mismatch && (
                  <p className="mt-1 text-xs text-amber-400">
                    These two do not match.
                  </p>
                )}
              </div>

              <button
                type="submit"
                disabled={!ready || reset.isPending}
                className="btn-primary w-full mt-2 disabled:cursor-not-allowed
                  disabled:opacity-40"
              >
                {reset.isPending ? 'Setting it…' : 'Set my password'}
              </button>
            </form>
          )}

          <Link
            to="/forgot-password"
            className="mt-6 flex items-center justify-center gap-1.5 text-sm text-gray-500
              hover:text-gray-300"
          >
            <ArrowLeft size={14} /> Ask for a new link
          </Link>
        </div>
      </div>
    </div>
  )
}
