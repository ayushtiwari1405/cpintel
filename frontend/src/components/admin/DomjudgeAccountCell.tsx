import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Link2, Loader2, X } from 'lucide-react'

import { adminDomjudgeApi } from '@/api/adminApi'
import { useToast } from '@/components/common/Toaster'

interface Props {
  userId: number
  username: string
}

/**
 * Whether a member has a DOMjudge account attached, and the control to attach one.
 *
 * <p>Provisioning lives with the admin rather than the contestant, so this is where a round is
 * set up — before anybody logs in, which is usually how it happens. The contestant then signs
 * into CPIntel as normal and the arena competes as them.
 *
 * <p><b>The form is a dialog, not an inline edit.</b> It was inline once, four controls squeezed
 * into a table cell, and the result was an attach affordance nobody could find: a 12px icon and
 * the word "attach" in the same grey as disabled text, inside a column that scrolls sideways.
 * The control was rendering and the endpoint was answering; it simply could not be seen. A cell
 * this narrow cannot hold a form with a password field and a dropdown, so it holds a button.
 */
export function DomjudgeAccountCell({ userId, username }: Props) {
  const [open, setOpen] = useState(false)

  const key = ['admin', 'domjudge', userId]
  const { data: account, isLoading } = useQuery({
    queryKey: key,
    queryFn: () => adminDomjudgeApi.status(userId).then(r => r.data),
  })

  if (isLoading) {
    return <Loader2 size={12} className="animate-spin text-gray-700" />
  }

  return (
    <>
      {account?.linked ? (
        <button
          onClick={() => setOpen(true)}
          title="Change or detach this DOMjudge account"
          className="group flex min-w-0 items-start gap-2 text-left text-xs"
        >
          <span className="min-w-0">
            <span className="block truncate font-mono text-gray-300
                             group-hover:text-indigo-300">
              {account.username}
            </span>
            {account.name && (
              <span className="block truncate text-[11px] text-gray-500">{account.name}</span>
            )}
            <span className="flex items-center gap-1 text-[11px] text-gray-600">
              {account.teamMismatch && (
                <AlertTriangle
                  size={10}
                  className="flex-shrink-0 text-yellow-500"
                  aria-label="assigned team differs from the judge's"
                />
              )}
              <span className="truncate">
                {account.teamMismatch
                  ? `${account.assignedTeamName ?? account.assignedTeamId} (judge: ${account.teamName})`
                  : account.teamName}
              </span>
            </span>
          </span>
        </button>
      ) : (
        // A real button rather than a grey text link. This is the only route to attaching an
        // account, so it has to look like something you can press.
        <button
          onClick={() => setOpen(true)}
          className="flex items-center gap-1.5 rounded-md border border-indigo-900/80
                     bg-indigo-950/40 px-2 py-1 text-xs text-indigo-300 transition-colors
                     hover:border-indigo-700 hover:bg-indigo-900/40 hover:text-indigo-200"
        >
          <Link2 size={12} /> Attach
        </button>
      )}

      {open && (
        <AttachDialog
          userId={userId}
          username={username}
          linked={!!account?.linked}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  )
}

/**
 * Attaching, changing or detaching one contestant's DOMjudge login.
 *
 * <p><b>The team picker does not change where code lands.</b> DOMjudge attributes a submission
 * to the team behind the login and reads that off the login itself, so nothing chosen here is
 * sent with a submission. What it sets is the team CPIntel groups this person under for its own
 * standings. Left on the judge's own answer — the right choice whenever the account is already
 * on the correct team — the two can never disagree.
 *
 * <p>The password is write-only. There is no read path for it anywhere in the API, so a wrong
 * one is corrected by attaching again rather than by editing what is stored.
 */
function AttachDialog(
  { userId, username, linked, onClose }:
  { userId: number; username: string; linked: boolean; onClose: () => void }
) {
  const qc = useQueryClient()
  const toast = useToast()

  const [djUser, setDjUser] = useState('')
  const [name, setName] = useState('')
  const [djPass, setDjPass] = useState('')
  const [teamId, setTeamId] = useState('')

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const { data: teams, isLoading: teamsLoading } = useQuery({
    queryKey: ['admin', 'domjudge', 'teams'],
    queryFn: () => adminDomjudgeApi.teams().then(r => r.data),
    staleTime: 1000 * 60 * 10,
  })

  const key = ['admin', 'domjudge', userId]

  const attach = useMutation({
    mutationFn: () => adminDomjudgeApi.attach({
      userId,
      username: djUser.trim(),
      password: djPass,
      name: name.trim() || undefined,
      teamId: teamId || undefined,
    }),
    onSuccess: (res) => {
      setDjPass('')
      qc.setQueryData(key, res.data)
      // Names the judge's team, not the assigned one: this confirms where their code will
      // actually land, which is the thing worth checking in the moment.
      toast.push('info',
        `${username} competes as ${res.data.teamName ?? res.data.username}`)
      onClose()
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Could not attach that account')
    },
  })

  const detach = useMutation({
    mutationFn: () => adminDomjudgeApi.detach(userId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: key })
      toast.push('info', `Detached ${username}'s DOMjudge account`)
      onClose()
    },
  })

  const valid = djUser.trim().length > 0 && djPass.length > 0

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    if (valid) attach.mutate()
  }

  const field = `w-full rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                 text-gray-200 placeholder-gray-700 outline-none focus:border-indigo-600`

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/60" onClick={onClose} />
      <form
        onSubmit={submit}
        className="relative w-full max-w-md rounded-xl border border-gray-800 bg-gray-950 p-5"
      >
        <header className="mb-4 flex items-center justify-between">
          <h2 className="text-sm font-medium text-gray-200">
            DOMjudge account for <span className="text-indigo-300">{username}</span>
          </h2>
          <button type="button" onClick={onClose} aria-label="Close"
            className="rounded-md p-1 text-gray-500 hover:bg-gray-800 hover:text-gray-300">
            <X size={16} />
          </button>
        </header>

        <div className="space-y-3">
          <label className="block">
            <span className="mb-1 block text-xs text-gray-500">DOMjudge username</span>
            <input value={djUser} onChange={e => setDjUser(e.target.value)}
              autoComplete="off" autoFocus placeholder="their login on the judge"
              className={`${field} font-mono`} />
          </label>

          <label className="block">
            <span className="mb-1 block text-xs text-gray-500">Name</span>
            <input value={name} onChange={e => setName(e.target.value)}
              autoComplete="off" placeholder="optional — the judge's is used otherwise"
              className={field} />
          </label>

          <label className="block">
            <span className="mb-1 block text-xs text-gray-500">DOMjudge password</span>
            <input value={djPass} onChange={e => setDjPass(e.target.value)}
              type="password" autoComplete="new-password"
              placeholder="verified against the judge before it is stored"
              className={`${field} font-mono`} />
          </label>

          <label className="block">
            <span className="mb-1 block text-xs text-gray-500">Team</span>
            <select value={teamId} onChange={e => setTeamId(e.target.value)}
              className={field}>
              <option value="">
                {teamsLoading ? 'Loading teams…' : "Whatever the judge says (recommended)"}
              </option>
              {teams?.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
          </label>
        </div>

        <p className="mt-3 text-xs leading-relaxed text-gray-600">
          The team only decides how CPIntel groups them for its own standings. Submissions are
          always attributed by DOMjudge to the team behind the login, whatever is chosen here.
        </p>

        <div className="mt-4 flex items-center justify-between gap-2">
          {linked ? (
            <button type="button" onClick={() => detach.mutate()} disabled={detach.isPending}
              className="rounded-lg border border-gray-800 px-3 py-2 text-sm text-red-400
                         hover:bg-red-950/30 disabled:opacity-40">
              Detach
            </button>
          ) : <span />}

          <div className="flex gap-2">
            <button type="button" onClick={onClose}
              className="rounded-lg border border-gray-800 px-3 py-2 text-sm text-gray-400
                         hover:bg-gray-800">
              Cancel
            </button>
            <button type="submit" disabled={!valid || attach.isPending}
              className="flex items-center gap-2 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                         font-medium text-white hover:bg-indigo-500 disabled:opacity-40">
              {attach.isPending && <Loader2 size={14} className="animate-spin" />}
              {linked ? 'Replace account' : 'Attach account'}
            </button>
          </div>
        </div>
      </form>
    </div>
  )
}
