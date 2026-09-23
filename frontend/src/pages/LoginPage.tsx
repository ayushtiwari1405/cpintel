import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useLogin } from '@/hooks/useAuth'
import { Eye, EyeOff, Zap } from 'lucide-react'

export default function LoginPage() {
  const [identifier, setIdentifier] = useState('')
  const [password, setPassword]     = useState('')
  const [showPass, setShowPass]     = useState(false)
  const login = useLogin()

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    login.mutate({ identifier, password })
  }

  return (
    <div className="min-h-screen bg-gray-950 flex items-center justify-center p-4">
      <div className="w-full max-w-md">

        {/* Logo */}
        <div className="flex items-center gap-3 mb-8 justify-center">
          <div className="w-10 h-10 rounded-xl bg-indigo-600 flex items-center justify-center">
            <Zap size={20} className="text-white" />
          </div>
          <span className="text-xl font-semibold text-white">CPIntel</span>
        </div>

        {/* Card */}
        <div className="bg-gray-900 border border-gray-800 rounded-2xl p-8">
          <h1 className="text-2xl font-semibold text-white mb-1">Welcome back</h1>
          <p className="text-gray-400 text-sm mb-6">Sign in to your account</p>

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
                <Link to="/forgot-password" className="text-xs text-indigo-400 hover:text-indigo-300">
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

          {/*
            No sign-up link, because there is no sign-up. Accounts are created by a super
            admin, so the useful thing to tell someone without one is who to ask rather than
            a button that would answer 403.
          */}
          <p className="text-center text-sm text-gray-500 mt-6">
            Accounts are created by an administrator, who gives you your username and first
            password. You can change it once you are in.
          </p>
        </div>

        {/* Platforms hint */}
        <p className="text-center text-xs text-gray-600 mt-6">
          Supports Codeforces · LeetCode · CodeChef
        </p>
      </div>
    </div>
  )
}
