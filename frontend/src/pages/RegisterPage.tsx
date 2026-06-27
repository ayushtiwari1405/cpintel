import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useRegister } from '@/hooks/useAuth'
import { Eye, EyeOff, Zap, Check } from 'lucide-react'

const requirements = [
  { label: 'At least 8 characters', test: (p: string) => p.length >= 8 },
  { label: 'Contains a number',     test: (p: string) => /\d/.test(p) },
]

export default function RegisterPage() {
  const [form, setForm] = useState({ username: '', email: '', password: '', fullName: '' })
  const [showPass, setShowPass] = useState(false)
  const register = useRegister()

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    register.mutate(form)
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
          <h1 className="text-2xl font-semibold text-white mb-1">Create account</h1>
          <p className="text-gray-400 text-sm mb-6">Start tracking your CP journey</p>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-sm font-medium text-gray-300 mb-1.5">Username</label>
                <input
                  type="text"
                  value={form.username}
                  onChange={set('username')}
                  className="input"
                  placeholder="tourney_ace"
                  required
                  autoFocus
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-300 mb-1.5">Full name</label>
                <input
                  type="text"
                  value={form.fullName}
                  onChange={set('fullName')}
                  className="input"
                  placeholder="Optional"
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1.5">Email</label>
              <input
                type="email"
                value={form.email}
                onChange={set('email')}
                className="input"
                placeholder="you@example.com"
                required
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1.5">Password</label>
              <div className="relative">
                <input
                  type={showPass ? 'text' : 'password'}
                  value={form.password}
                  onChange={set('password')}
                  className="input pr-10"
                  placeholder="••••••••"
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

              {form.password.length > 0 && (
                <div className="mt-2 space-y-1">
                  {requirements.map(r => (
                    <div key={r.label} className={`flex items-center gap-1.5 text-xs
                      ${r.test(form.password) ? 'text-green-400' : 'text-gray-500'}`}>
                      <Check size={11} />
                      {r.label}
                    </div>
                  ))}
                </div>
              )}
            </div>

            <button
              type="submit"
              disabled={register.isPending}
              className="btn-primary w-full mt-2"
            >
              {register.isPending ? 'Creating account…' : 'Create account'}
            </button>
          </form>

          <p className="text-center text-sm text-gray-400 mt-6">
            Already have an account?{' '}
            <Link to="/login" className="text-indigo-400 hover:text-indigo-300 font-medium">
              Sign in
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}
