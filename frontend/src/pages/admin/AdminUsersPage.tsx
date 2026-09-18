import { useEffect, useState } from 'react'
import {
  useAdminUser, useAdminUsers, useCreateUser, useIsSuperAdmin, useRevokeSessions,
  useSetUserActive, useSetUserRole,
} from '@/hooks/useAdmin'
import { useAuth } from '@/contexts/AuthContext'
import { roleLabel } from '@/utils/roles'
import {
  Ago, EmptyRow, Pager, Panel, Pill, actionLabel, actionTone, bytes,
} from '@/components/admin/AdminUi'
import {
  Loader2, LogOut, Plus, Search, Shield, ShieldOff, UserCheck, UserX, X,
} from 'lucide-react'
import { clsx } from 'clsx'
import type { AdminUserRow, AssignableRole, Role } from '@/types'

/**
 * Accounts, and what an admin can do to one.
 *
 * Two tiers are at work on this screen. An ADMIN can look at anything here and can activate or
 * deactivate an account. Changing what someone *is*, and creating someone new, belong to a
 * SUPER_ADMIN — so those controls are not drawn for an ordinary admin rather than being drawn
 * and then refused. The server enforces the same split; this only keeps the screen honest.
 *
 * The destructive-looking actions ask first, and the ones the server would refuse are disabled
 * here with the reason attached — an admin should be able to see that they cannot demote
 * themselves without having to try it and read an error.
 *
 * There is no delete. Deactivating stops someone signing in and keeps their history; deleting
 * would take their contests, submissions and files with it, and nothing on this screen should
 * be able to do something that cannot be undone.
 */
export default function AdminUsersPage() {
  const { user: me } = useAuth()

  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [role, setRole] = useState<'' | Role>('')
  const [active, setActive] = useState<'' | 'true' | 'false'>('')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<number | null>(null)

  // Typing filters a list of people, so it should feel immediate — but not fire a request per
  // keystroke. A short pause after the last one is the compromise.
  useEffect(() => {
    const timer = setTimeout(() => { setQuery(search); setPage(0) }, 300)
    return () => clearTimeout(timer)
  }, [search])

  const { data, isLoading, isFetching } = useAdminUsers({
    query,
    role: role || undefined,
    active: active === '' ? null : active === 'true',
    page,
    size: 25,
  })

  const setRoleMutation = useSetUserRole()
  const setActiveMutation = useSetUserActive()
  const canAssignRoles = useIsSuperAdmin()
  const [creating, setCreating] = useState(false)

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-[16rem] flex-1">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-600" />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search by username, email or name"
            className="w-full rounded-lg border border-gray-800 bg-gray-900 py-2 pl-9 pr-3
                       text-sm text-gray-200 placeholder-gray-600 outline-none
                       focus:border-indigo-600"
          />
        </div>
        <select
          value={role}
          onChange={e => { setRole(e.target.value as typeof role); setPage(0) }}
          className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-300
                     outline-none focus:border-indigo-600"
        >
          <option value="">Any role</option>
          <option value="USER">Users</option>
          <option value="ADMIN">Admins</option>
          <option value="SUPER_ADMIN">Super admins</option>
        </select>
        <select
          value={active}
          onChange={e => { setActive(e.target.value as typeof active); setPage(0) }}
          className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-300
                     outline-none focus:border-indigo-600"
        >
          <option value="">Any status</option>
          <option value="true">Active</option>
          <option value="false">Deactivated</option>
        </select>
        {isFetching && <Loader2 size={14} className="animate-spin text-gray-600" />}
        {canAssignRoles && (
          <button
            onClick={() => setCreating(true)}
            className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                       font-medium text-white transition-colors hover:bg-indigo-500"
          >
            <Plus size={14} /> New account
          </button>
        )}
      </div>

      <Panel title="Accounts" description="Click a row to see the full record">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="px-4 py-2 font-medium">User</th>
                <th className="px-4 py-2 font-medium">Role</th>
                <th className="px-4 py-2 font-medium">Status</th>
                <th className="px-4 py-2 font-medium">Joined</th>
                <th className="px-4 py-2 font-medium">Last sign-in</th>
                <th className="px-4 py-2 font-medium text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {isLoading && (
                <EmptyRow colSpan={6}>Loading accounts…</EmptyRow>
              )}
              {!isLoading && data?.users.length === 0 && (
                <EmptyRow colSpan={6}>
                  {query || role || active
                    ? 'No account matches those filters.'
                    : 'No accounts yet.'}
                </EmptyRow>
              )}
              {data?.users.map(row => (
                <UserRow
                  key={row.userId}
                  row={row}
                  isSelf={row.userId === me?.userId}
                  canAssignRoles={canAssignRoles}
                  busy={setRoleMutation.isPending || setActiveMutation.isPending}
                  onOpen={() => setSelected(row.userId)}
                  onRole={next => setRoleMutation.mutate({ userId: row.userId, role: next })}
                  onActive={next => setActiveMutation.mutate({ userId: row.userId, active: next })}
                />
              ))}
            </tbody>
          </table>
        </div>
        <Pager
          page={data?.page ?? 0}
          totalPages={data?.totalPages ?? 0}
          total={data?.total ?? 0}
          onPage={setPage}
        />
      </Panel>

      {selected != null && (
        <UserDetailDrawer userId={selected} onClose={() => setSelected(null)} />
      )}

      {creating && <CreateUserDialog onClose={() => setCreating(false)} />}
    </div>
  )
}

function UserRow({ row, isSelf, canAssignRoles, busy, onOpen, onRole, onActive }: {
  row: AdminUserRow
  isSelf: boolean
  canAssignRoles: boolean
  busy: boolean
  onOpen: () => void
  onRole: (role: AssignableRole) => void
  onActive: (active: boolean) => void
}) {
  const isAdmin = row.role === 'ADMIN'
  const isSuper = row.role === 'SUPER_ADMIN'

  // Both of these are refused by the server for the same reason: an admin who demotes or
  // switches off their own account has locked themselves out of the screen they would need in
  // order to undo it. Saying so here is friendlier than letting them find out by clicking.
  const selfBlock = isSelf ? 'You cannot do this to your own account' : undefined

  return (
    <tr className="transition-colors hover:bg-gray-800/40">
      <td className="px-4 py-2.5">
        <button onClick={onOpen} className="text-left">
          <span className="block font-medium text-gray-200 hover:text-indigo-400">
            {row.username}
            {isSelf && <span className="ml-1.5 text-xs text-gray-600">(you)</span>}
          </span>
          <span className="block text-xs text-gray-500">{row.email}</span>
        </button>
      </td>
      <td className="px-4 py-2.5">
        <Pill tone={isSuper ? 'green' : isAdmin ? 'indigo' : 'gray'}>
          {roleLabel(row.role)}
        </Pill>
      </td>
      <td className="px-4 py-2.5">
        <Pill tone={row.active ? 'green' : 'red'}>
          {row.active ? 'Active' : 'Deactivated'}
        </Pill>
      </td>
      <td className="px-4 py-2.5 text-xs text-gray-500"><Ago at={row.createdAt} /></td>
      <td className="px-4 py-2.5 text-xs text-gray-500">
        <Ago at={row.lastLoginAt} fallback="not since auditing began" />
      </td>
      <td className="px-4 py-2.5">
        <div className="flex justify-end gap-1">
          {canAssignRoles && (
            <IconAction
              title={
                isSuper
                  ? 'A super admin is set in deployment configuration, not here'
                  : selfBlock ?? (isAdmin ? 'Demote to a regular user' : 'Promote to admin')
              }
              disabled={isSelf || isSuper || busy}
              onClick={() => onRole(isAdmin ? 'USER' : 'ADMIN')}
              icon={isAdmin ? <ShieldOff size={14} /> : <Shield size={14} />}
            />
          )}
          <IconAction
            title={
              isSuper
                ? 'A super admin cannot be deactivated from the console'
                : selfBlock ?? (row.active
                  ? 'Deactivate and sign out everywhere'
                  : 'Reactivate this account')
            }
            disabled={(isSelf && row.active) || isSuper || busy}
            danger={row.active}
            onClick={() => {
              if (row.active && !window.confirm(
                `Deactivate ${row.username}? They will be signed out everywhere and cannot `
                + 'sign in again until this is undone. Their data is kept.')) return
              onActive(!row.active)
            }}
            icon={row.active ? <UserX size={14} /> : <UserCheck size={14} />}
          />
        </div>
      </td>
    </tr>
  )
}

function IconAction({ title, icon, onClick, disabled, danger }: {
  title: string
  icon: React.ReactNode
  onClick: () => void
  disabled?: boolean
  danger?: boolean
}) {
  return (
    <button
      title={title}
      aria-label={title}
      disabled={disabled}
      onClick={onClick}
      className={clsx(
        'rounded-md border border-gray-800 p-1.5 transition-colors',
        'disabled:cursor-not-allowed disabled:opacity-30',
        danger
          ? 'text-gray-400 hover:bg-red-900/30 hover:text-red-400 disabled:hover:bg-transparent'
          : 'text-gray-400 hover:bg-gray-800 hover:text-gray-200 disabled:hover:bg-transparent'
      )}
    >
      {icon}
    </button>
  )
}

/** The whole record for one account, including what it has recently done. */
function UserDetailDrawer({ userId, onClose }: { userId: number; onClose: () => void }) {
  const { data, isLoading } = useAdminUser(userId)
  const revoke = useRevokeSessions()

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-40 flex justify-end">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <aside className="relative flex h-full w-[28rem] max-w-full flex-col border-l
        border-gray-800 bg-gray-950">
        <header className="flex items-center justify-between border-b border-gray-800 px-4 py-3">
          <h2 className="text-sm font-medium text-gray-200">
            {data?.user.username ?? 'Account'}
          </h2>
          <button onClick={onClose} aria-label="Close"
            className="rounded-md p-1 text-gray-500 hover:bg-gray-800 hover:text-gray-300">
            <X size={16} />
          </button>
        </header>

        <div className="flex-1 space-y-5 overflow-y-auto p-4">
          {isLoading && <p className="text-sm text-gray-500">Loading…</p>}

          {data && (
            <>
              <dl className="space-y-2 text-sm">
                <Field label="Email" value={data.user.email} />
                <Field label="Full name" value={data.user.fullName} />
                <Field label="Country" value={data.country} />
                <Field label="Institution" value={data.institution} />
                <Field label="Role" value={roleLabel(data.user.role)} />
                <Field label="Status" value={data.user.active ? 'Active' : 'Deactivated'} />
                <Field label="Verified" value={data.user.verified ? 'Yes' : 'No'} />
              </dl>

              <section>
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-gray-500">
                  Linked platforms
                </h3>
                {data.platforms.length === 0 ? (
                  <p className="text-sm text-gray-600">None linked.</p>
                ) : (
                  <ul className="space-y-1.5">
                    {data.platforms.map(p => (
                      <li key={p.accountId}
                        className="flex items-center justify-between rounded-lg border
                          border-gray-800 bg-gray-900 px-3 py-2 text-sm">
                        <span className="text-gray-300">{p.platform}</span>
                        <span className="text-xs text-gray-500">
                          {p.handle}{p.currentRating ? ` · ${p.currentRating}` : ''}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>

              <section>
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-gray-500">
                  Personal files
                </h3>
                <p className="text-sm text-gray-400">
                  {data.fileCount === 0
                    ? 'Nothing uploaded.'
                    : `${data.fileCount} file${data.fileCount === 1 ? '' : 's'}, ${bytes(data.fileBytes)}.`}
                </p>
                <p className="mt-1 text-xs text-gray-600">
                  Sizes only — an admin cannot see the names or open them.
                </p>
              </section>

              <section>
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-gray-500">
                  Recent activity
                </h3>
                {data.recentActivity.length === 0 ? (
                  <p className="text-sm text-gray-600">Nothing recorded for this account.</p>
                ) : (
                  <ul className="space-y-1.5">
                    {data.recentActivity.map(entry => (
                      <li key={entry.logId} className="flex items-center gap-2 text-xs">
                        <Pill tone={actionTone(entry.action)}>{actionLabel(entry.action)}</Pill>
                        <span className="flex-1 truncate text-gray-600">{entry.ipAddress}</span>
                        <span className="text-gray-600"><Ago at={entry.createdAt} /></span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </>
          )}
        </div>

        <footer className="border-t border-gray-800 p-4">
          <button
            onClick={() => {
              if (window.confirm('Sign this account out of every device? Their account is '
                + 'otherwise untouched and they can sign straight back in.')) {
                revoke.mutate({ userId })
              }
            }}
            disabled={revoke.isPending}
            className="flex w-full items-center justify-center gap-2 rounded-lg border
              border-gray-800 py-2 text-sm text-gray-300 transition-colors hover:bg-gray-800
              disabled:opacity-50"
          >
            <LogOut size={14} /> Sign out everywhere
          </button>
        </footer>
      </aside>
    </div>
  )
}

/**
 * Creating an account by hand.
 *
 * With self-registration closed this is the only door into the system, so it asks for exactly
 * what the sign-up form used to and nothing more. The password is set here and handed over out
 * of band — said plainly on the form, because an admin who does not realise they now know
 * someone else's password is the failure mode worth designing against.
 */
function CreateUserDialog({ onClose }: { onClose: () => void }) {
  const create = useCreateUser()
  const [form, setForm] = useState({
    username: '', email: '', password: '', fullName: '', role: 'USER' as AssignableRole,
  })

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }))

  const valid = form.username.trim().length >= 3
    && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(form.email.trim())
    && form.password.length >= 8

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!valid) return
    create.mutate(
      {
        username: form.username.trim(),
        email: form.email.trim(),
        password: form.password,
        fullName: form.fullName.trim() || undefined,
        role: form.role,
      },
      { onSuccess: onClose },
    )
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/60" onClick={onClose} />
      <form
        onSubmit={submit}
        className="relative w-full max-w-md rounded-xl border border-gray-800 bg-gray-950 p-5"
      >
        <header className="mb-4 flex items-center justify-between">
          <h2 className="text-sm font-medium text-gray-200">New account</h2>
          <button type="button" onClick={onClose} aria-label="Close"
            className="rounded-md p-1 text-gray-500 hover:bg-gray-800 hover:text-gray-300">
            <X size={16} />
          </button>
        </header>

        <div className="space-y-3">
          <TextField label="Username" value={form.username} onChange={set('username')}
            placeholder="letters, numbers and underscores" autoFocus />
          <TextField label="Email" type="email" value={form.email} onChange={set('email')}
            placeholder="name@example.com" />
          <TextField label="Full name" value={form.fullName} onChange={set('fullName')}
            placeholder="optional" />
          <TextField label="Password" type="text" value={form.password} onChange={set('password')}
            placeholder="at least 8 characters" />

          <label className="block">
            <span className="mb-1 block text-xs text-gray-500">Role</span>
            <select
              value={form.role}
              onChange={set('role')}
              className="w-full rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                         text-gray-200 outline-none focus:border-indigo-600"
            >
              <option value="USER">User — the normal product</option>
              <option value="ADMIN">Admin — can open this console</option>
            </select>
          </label>
        </div>

        <p className="mt-3 text-xs text-gray-600">
          The password is shown as you type it because you have to pass it on. Ask them to
          change it from their profile once they are in.
        </p>

        <div className="mt-4 flex justify-end gap-2">
          <button type="button" onClick={onClose}
            className="rounded-lg border border-gray-800 px-3 py-2 text-sm text-gray-400
                       hover:bg-gray-800">
            Cancel
          </button>
          <button
            type="submit"
            disabled={!valid || create.isPending}
            className="flex items-center gap-2 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                       font-medium text-white hover:bg-indigo-500 disabled:opacity-40"
          >
            {create.isPending && <Loader2 size={14} className="animate-spin" />}
            Create account
          </button>
        </div>
      </form>
    </div>
  )
}

function TextField({ label, ...props }: { label: string }
  & React.InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs text-gray-500">{label}</span>
      <input
        {...props}
        className="w-full rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                   text-gray-200 placeholder-gray-600 outline-none focus:border-indigo-600"
      />
    </label>
  )
}

function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div className="flex gap-3">
      <dt className="w-24 flex-shrink-0 text-xs text-gray-500">{label}</dt>
      <dd className="min-w-0 flex-1 break-words text-gray-300">
        {value || <span className="text-gray-600">—</span>}
      </dd>
    </div>
  )
}
