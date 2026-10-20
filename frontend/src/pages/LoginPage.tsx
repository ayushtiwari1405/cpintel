import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useLogin } from '@/hooks/useAuth'
import { Eye, EyeOff } from 'lucide-react'
import { BrandLockup } from '@/components/common/BrandMark'
import { ThemeToggle } from '@/components/common/ThemeToggle'
import { isDesktop } from '@/utils/desktopBridge'

export default function LoginPage() {
  const [identifier, setIdentifier] = useState('')
  const [password, setPassword]     = useState('')
  const [showPass, setShowPass]     = useState(false)
  const login = useLogin()
  // Set when an examination session was closed because its paper ended.
  const examEnded = new URLSearchParams(window.location.search).has('exam-ended')
  // Set when an ordinary session was closed because the person's examination is running.
  const examRunning = new URLSearchParams(window.location.search).has('exam-in-progress')
  // Set when an examination session was closed because its paper must be sat in the desktop app,
  // and shown too when signing in here was refused for that reason.
  const desktopRequired = new URLSearchParams(window.location.search).has('desktop-app-required')
    || (login.error as { response?: { data?: { code?: string } } } | null)
      ?.response?.data?.code === 'DESKTOP_APP_REQUIRED'

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    login.mutate({ identifier, password })
  }

  return (
    <div className="relative min-h-screen bg-gray-950 flex items-center justify-center p-4">
      <ThemeToggle className="absolute top-4 right-4" />
      <div className="w-full max-w-md">

        {/* Brand */}
        <div className="mb-8 flex justify-center">
          <BrandLockup className="scale-110" />
        </div>

        {/* Card */}
        <div className="bg-gray-900 border border-gray-800 rounded-2xl p-8">
          <h1 className="text-2xl font-semibold text-gray-50 mb-1">Welcome back</h1>
          <p className="text-gray-400 text-sm mb-6">Sign in to your account</p>
          {examEnded && (
            <p className="mb-4 rounded-lg border border-gray-700 bg-gray-800/60 px-3 py-2 text-xs
              leading-relaxed text-gray-300">
              The examination has ended and its session is closed. Sign in with your own
              password to read your code under Past examinations.
            </p>
          )}
          {examRunning && (
            <p className="mb-4 rounded-lg border border-gray-700 bg-gray-800/60 px-3 py-2 text-xs
              leading-relaxed text-gray-300">
              Your examination is running, so you have been signed out of your account until it
              ends. Sign in with your username and the examination password on your slip.
            </p>
          )}

          {desktopRequired && (
            <p className="mb-4 rounded-lg border border-amber-900 bg-amber-950/40 px-3 py-2 text-xs
              leading-relaxed text-amber-200">
              Your examination can only be sat in the CPIntel desktop app. Open the app on this
              computer and sign in there with the same examination password.{' '}
              {!isDesktop() && (
                <Link to="/download" className="underline hover:text-amber-100">
                  Get the desktop app.
                </Link>
              )}
              {isDesktop() && 'This copy of the app is too old to be recognised: update it from '
                + 'the download page.'}
            </p>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            {/*
              One field for both forms of identifier, and type="text" rather than "email" —
              accounts here are handed out with a username and a first password, and the
              address on the account may be one the person never uses. A browser refusing to
              submit "ada" because it is not an address would be the product telling half the
              room they typed their own name wrongly.
            */}
            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1.5">
                Email or username
              </label>
              <input
                type="text"
                value={identifier}
                onChange={e => setIdentifier(e.target.value)}
                className="input"
                placeholder="you@example.com or yourname"
                autoComplete="username"
                required
                autoFocus
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1.5">Password</label>
              <div className="relative">
                <input
                  type={showPass ? 'text' : 'password'}
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  className="input pr-10"
                  placeholder="••••••••"
                  autoComplete="current-password"
                  required
                />
                <button
                  type="button"
                  onClick={() => setShowPass(v => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-300"
                >
                  {showPass ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
              <div className="flex justify-end mt-1">
                <Link to="/forgot-password" className="text-xs text-accent-400 hover:text-accent-300">
                  Forgot password?
                </Link>
              </div>
            </div>

            <button
              type="submit"
              disabled={login.isPending}
              className="btn-primary w-full mt-2"
            >
              {login.isPending ? 'Signing in…' : 'Sign in'}
            </button>
          </form>

          {/* Not inside the desktop app, which is what the link offers. */}
          {!isDesktop() && (
            <p className="mt-4 text-center text-xs text-gray-500">
              Sitting an examination on your own computer?{' '}
              <Link to="/download" className="text-accent-400 hover:text-accent-300">
                Get the desktop app
              </Link>
            </p>
          )}

          {/*
            No sign-up link, because there is no sign-up. Accounts are created by a super
            admin, so the useful thing to tell someone without one is who to ask rather than
            a button that would answer 403.
          */}
          <p className="text-center text-sm text-gray-500 mt-6">
            Accounts are created by an administrator, who gives you your username and first
            password. You can change it once you are in.
          </p>
          {/* The same form signs in to an examination: the password on the slip opens that
              paper, in examination mode, instead of the account. */}
          <p className="mt-3 rounded-lg border border-accent-900 bg-accent-950/30 px-3 py-2
            text-center text-xs leading-relaxed text-accent-200">
            Sitting an examination? Sign in with your username and the examination password
            on your slip instead of your own.
          </p>
        </div>

        {/* Platforms hint */}
        <p className="text-center text-xs text-gray-600 mt-6">
          Built on Codeforces and DOMjudge
        </p>
      </div>
    </div>
  )
}
