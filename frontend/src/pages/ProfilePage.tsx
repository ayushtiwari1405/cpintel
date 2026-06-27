import { useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { userApi } from '@/api/userApi'
import { useToast } from '@/components/common/Toaster'
import { User, Save, Loader2 } from 'lucide-react'

export default function ProfilePage() {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const toast = useToast()

  const [form, setForm] = useState({
    fullName:    user?.fullName ?? '',
    country:     user?.country ?? '',
    institution: user?.institution ?? '',
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
      <div>
        <h1 className="text-2xl font-semibold text-white">Profile</h1>
        <p className="text-gray-400 text-sm mt-0.5">Manage your account details</p>
      </div>

      {/* Avatar */}
      <div className="flex items-center gap-4">
        <div className="w-16 h-16 rounded-2xl bg-indigo-600 flex items-center
          justify-center text-2xl font-bold text-white flex-shrink-0">
          {user?.username?.[0]?.toUpperCase()}
        </div>
        <div>
          <p className="text-white font-medium">{user?.username}</p>
          <p className="text-gray-400 text-sm">{user?.email}</p>
          <div className="flex gap-2 mt-1">
            <span className="badge badge-blue">{user?.role}</span>
            {user?.isVerified
              ? <span className="badge badge-green">Verified</span>
              : <span className="badge badge-yellow">Unverified</span>
            }
          </div>
        </div>
      </div>

      {/* Form */}
      <div className="card space-y-4">
        {[
          { key: 'fullName',    label: 'Full name',    placeholder: 'Your name' },
          { key: 'country',     label: 'Country',      placeholder: 'India' },
          { key: 'institution', label: 'Institution',  placeholder: 'IIT Bombay' },
          { key: 'avatarUrl',   label: 'Avatar URL',   placeholder: 'https://...' },
        ].map(({ key, label, placeholder }) => (
          <div key={key}>
            <label className="block text-sm font-medium text-gray-300 mb-1.5">{label}</label>
            <input
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

      {/* Account info */}
      <div className="card space-y-3 text-sm">
        <p className="text-gray-300 font-medium">Account info</p>
        <div className="flex justify-between text-gray-400">
          <span>Member since</span>
          <span>{user?.createdAt ? new Date(user.createdAt).toLocaleDateString() : '—'}</span>
        </div>
        <div className="flex justify-between text-gray-400">
          <span>User ID</span>
          <span className="font-mono text-xs">{user?.userId}</span>
        </div>
      </div>
    </div>
  )
}
