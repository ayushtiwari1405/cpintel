import { useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { userApi } from '@/api/userApi'
import { useToast } from '@/components/common/Toaster'
import { Save, Loader2 } from 'lucide-react'
import { ChangePasswordCard } from '@/components/common/ChangePasswordCard'
import { PageHeader } from '@/components/common/PageHeader'
import { roleLabel } from '@/utils/roles'

export default function ProfilePage() {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const toast = useToast()

  const [form, setForm] = useState({
    fullName:    user?.fullName ?? '',
    avatarUrl:   user?.avatarUrl ?? '',
  })

  const mutation = useMutation({
    mutationFn: () => userApi.updateMe(form),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['me'] })
      toast.push('success', 'Profile updated')
    },
    onError: () => toast.push('error', 'Update failed'),
  })

  const set = (k: keyof typeof form) =>
    (e: React.ChangeEvent<HTMLInputElement>) =>
      setForm(f => ({ ...f, [k]: e.target.value }))

  return (
    <div className="space-y-6 max-w-xl">
      <PageHeader title="Profile" className="mb-0" />

      <div className="flex items-center gap-4 rounded-xl border border-gray-800 bg-gray-900 p-4">
        <span aria-hidden="true" className="flex h-14 w-14 shrink-0 items-center justify-center
          rounded-lg border border-accent-800/70 bg-accent-950/60 font-mono text-xl font-semibold
          text-accent-300">
          {user?.username?.[0]?.toUpperCase()}
        </span>
        <div className="min-w-0">
          <p className="truncate font-mono font-medium text-gray-50">{user?.username}</p>
          <p className="truncate text-sm text-gray-400">{user?.email}</p>
          <div className="mt-1.5 flex flex-wrap gap-1.5">
            {user?.role && <span className="badge-blue">{roleLabel(user.role)}</span>}
            {user?.isVerified
              ? <span className="badge-green">Verified</span>
              : <span className="badge-yellow">Unverified</span>
            }
          </div>
        </div>
      </div>

      {/* Form */}
      <div className="card space-y-4">
        {[
          { key: 'fullName',    label: 'Full name',    placeholder: 'Your name' },
          { key: 'avatarUrl',   label: 'Avatar URL',   placeholder: 'https://...' },
        ].map(({ key, label, placeholder }) => (
          <div key={key}>
            <label htmlFor={`profile-${key}`} className="mb-1.5 block text-sm font-medium text-gray-300">{label}</label>
            <input
              id={`profile-${key}`}
              className="input"
              placeholder={placeholder}
              value={form[key as keyof typeof form]}
              onChange={set(key as keyof typeof form)}
            />
          </div>
        ))}

        <button
          onClick={() => mutation.mutate()}
          disabled={mutation.isPending}
          className="btn-primary flex items-center gap-2"
        >
          {mutation.isPending
            ? <><Loader2 size={14} className="animate-spin" /> Saving…</>
            : <><Save size={14} /> Save changes</>
          }
        </button>
      </div>

      {/*
        Changing your own password.
        
        On this page rather than on a settings screen of its own, because the account is what
        this page is about and "where do I change my password" should not be a question
        anybody has to ask twice.
      */}
      <ChangePasswordCard neverChanged={user?.passwordChangedAt == null} />

      {/* Account info */}
      <section className="card text-sm" aria-labelledby="account-info">
        <h2 id="account-info" className="mb-3 font-medium text-gray-200">Account</h2>
        <dl className="divide-y divide-gray-800">
          <div className="flex justify-between gap-4 py-2">
            <dt className="text-gray-400">Member since</dt>
            <dd className="text-gray-200">{user?.createdAt ? new Date(user.createdAt).toLocaleDateString() : '—'}</dd>
          </div>
          <div className="flex justify-between gap-4 py-2">
            <dt className="text-gray-400">User ID</dt>
            <dd className="font-mono text-xs text-gray-200">{user?.userId}</dd>
          </div>
        </dl>
      </section>
    </div>
  )
}
