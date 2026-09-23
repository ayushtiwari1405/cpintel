import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft, MailCheck, Zap } from 'lucide-react'

import { useForgotPassword } from '@/hooks/useAuth'
import { ThemeToggle } from '@/components/common/ThemeToggle'

/**
 * Asking for a reset link.
 *
 * <p>The screen says the same thing however it went, because the server answers the same way:
 * an address with no account behind it and an address with one produce an identical response.
 * Drawing the distinction here would put back exactly the enumeration the endpoint removes, on
 * a deployment whose user list is the roster of whoever is being examined.
 *
 * <p>Which is why the confirmation is worded as a conditional rather than a promise. "We have
 * sent you an email" would be a lie half the time; "if an account uses that address" is true
 * either way and tells somebody who mistyped it what to check.
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const request = useForgotPassword()

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    // Settled rather than success: a network failure would otherwise leave the form looking
    // like it had done nothing, and the honest recovery is the same either way — try again.
    request.mutate(email, { onSettled: () => setSent(true) })
  }

  return (
    <div className="relative min-h-screen bg-gray-950 flex items-center justify-center p-4">
      <ThemeToggle className="absolute top-4 right-4" />
      <div className="w-full max-w-md">

        <div className="flex items-center gap-3 mb-8 justify-center">
          <div className="w-10 h-10 rounded-xl bg-indigo-600 flex items-center justify-center">
            <Zap size={20} className="text-white" />
          </div>
          <span className="text-xl font-semibold text-gray-50">CPIntel</span>
        </div>

        <div className="bg-gray-900 border border-gray-800 rounded-2xl p-8">
          {sent ? (
            <div className="text-center">
              <div className="mx-auto w-11 h-11 rounded-xl bg-indigo-950 border border-indigo-900
                flex items-center justify-center">
                <MailCheck size={20} className="text-indigo-400" />
              </div>
              <h1 className="text-xl font-semibold text-gray-50 mt-4">Check your email</h1>
              <p className="text-sm leading-relaxed text-gray-400 mt-2">
                If an account uses <span className="text-gray-300">{email}</span>, a link to set
                a new password is on its way to it. The link works once and expires within the
                hour.
              </p>
              <p className="text-xs leading-relaxed text-gray-600 mt-4">
                Nothing arrived? Check the address, and your spam folder. If your account was
                set up for you, whoever made it can set a new password directly.
              </p>
            </div>
          ) : (
            <>
              <h1 className="text-2xl font-semibold text-gray-50 mb-1">Forgot your password?</h1>
              <p className="text-gray-400 text-sm mb-6">
                Give us the email address on your account and we will send you a link to set a
                new password.
              </p>

              <form onSubmit={handleSubmit} className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-gray-300 mb-1.5">Email</label>
                  {/*
                    The address, not the username — a link has to go somewhere, and the
                    username is not somewhere. Said on the label rather than discovered from a
                    refusal, since the sign-in box next door accepts either.
                  */}
                  <input
                    type="email"
                    value={email}
                    onChange={e => setEmail(e.target.value)}
                    className="input"
                    placeholder="you@example.com"
                    autoComplete="email"
                    required
                    autoFocus
                  />
                </div>

                <button
                  type="submit"
                  disabled={request.isPending}
                  className="btn-primary w-full mt-2"
                >
                  {request.isPending ? 'Sending…' : 'Send me a link'}
                </button>
              </form>
            </>
          )}

          <Link
            to="/login"
            className="mt-6 flex items-center justify-center gap-1.5 text-sm text-gray-500
              hover:text-gray-300"
          >
            <ArrowLeft size={14} /> Back to sign in
          </Link>
        </div>
      </div>
    </div>
  )
}
