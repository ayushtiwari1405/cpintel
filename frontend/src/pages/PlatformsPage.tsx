import { useState, type ReactNode } from 'react'
import { CfSessionCard } from '@/components/practice/CfSessionCard'
import { useLinkedAccounts, useLinkAccount, useUnlinkAccount, useSyncAccount } from '@/hooks/usePlatforms'
import { RefreshCw, Link2, Unlink, CheckCircle, Clock, AlertCircle, Loader2 } from 'lucide-react'
import { formatDistanceToNow } from 'date-fns'
import { clsx } from 'clsx'
import { PageHeader } from '@/components/common/PageHeader'
import { ErrorState, LoadingState } from '@/components/common/States'

const PLATFORMS = [
  {
    id: 'CODEFORCES',
    name: 'Codeforces',
    desc: 'Contests, ratings and your full submission history.',
    placeholder: 'tourist',
  },
]

/** A sync status as words with an icon: the colour is never the only signal. */
const SYNC: Record<string, { label: string; icon: ReactNode; className: string }> = {
  COMPLETED: { label: 'Synced',         icon: <CheckCircle size={12} />, className: 'badge-green' },
  RUNNING:   { label: 'Syncing',        icon: <Loader2 size={12} className="animate-spin" />, className: 'badge-blue' },
  FAILED:    { label: 'Sync failed',    icon: <AlertCircle size={12} />, className: 'badge-red' },
  PENDING:   { label: 'Waiting to sync', icon: <Clock size={12} />, className: 'badge-gray' },
}

export default function PlatformsPage() {
  const { data: accounts = [], isLoading, isError, error, refetch } = useLinkedAccounts()
  const linkMutation   = useLinkAccount()
  const unlinkMutation = useUnlinkAccount()
  const syncMutation   = useSyncAccount()

  const [handles, setHandles] = useState<Record<string, string>>({})
  const [linking, setLinking] = useState<string | null>(null)

  const linked = (platformId: string) =>
    accounts.find(a => a.platform === platformId)

  const handleLink = (platformId: string) => {
    const handle = handles[platformId]?.trim()
    if (!handle) return
    linkMutation.mutate({ platform: platformId, handle }, {
      onSettled: () => setLinking(null),
    })
    setLinking(platformId)
  }

  return (
    <div className="space-y-6">
      <PageHeader title="Platforms"
        description="Link your competitive programming accounts so CPIntel can read your history." />

      {isLoading ? (
        <LoadingState label="Loading your accounts…" />
      ) : isError ? (
        <ErrorState title="Could not load your linked accounts" error={error} onRetry={() => refetch()} />
      ) : (
        <div className="grid gap-4">
          {PLATFORMS.map(p => {
            const account = linked(p.id)
            const isLinking = linking === p.id && linkMutation.isPending
            const isSyncing = syncMutation.isPending && syncMutation.variables === p.id

            return (
              <section key={p.id} aria-labelledby={`platform-${p.id}`} className={clsx(
                'rounded-xl border bg-gray-900 p-5 transition-colors',
                account ? 'border-gray-700' : 'border-gray-800'
              )}>
                <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
                  <div className="min-w-0 flex-1">
                    <div className="mb-1 flex flex-wrap items-center gap-2">
                      <h2 id={`platform-${p.id}`} className="text-base font-semibold text-gray-50">
                        {p.name}
                      </h2>
                      {account && (() => {
                        const st = SYNC[account.syncStatus] ?? SYNC.PENDING
                        return <span className={st.className}>{st.icon}{st.label}</span>
                      })()}
                    </div>
                    <p className="text-sm text-gray-400">{p.desc}</p>

                    {account ? (
                      <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 sm:flex sm:flex-wrap">
                        <div>
                          <dt className="text-[11px] uppercase tracking-wide text-gray-500">Handle</dt>
                          <dd className="font-mono text-sm text-gray-100">{account.handle}</dd>
                        </div>
                        {account.currentRating != null && (
                          <div>
                            <dt className="text-[11px] uppercase tracking-wide text-gray-500">Rating</dt>
                            <dd className="font-mono text-sm font-semibold tabular-nums text-gray-50">{account.currentRating}</dd>
                          </div>
                        )}
                        {account.maxRating != null && (
                          <div>
                            <dt className="text-[11px] uppercase tracking-wide text-gray-500">Peak</dt>
                            <dd className="font-mono text-sm tabular-nums text-gray-300">{account.maxRating}</dd>
                          </div>
                        )}
                        {account.lastSyncedAt && (
                          <div>
                            <dt className="text-[11px] uppercase tracking-wide text-gray-500">Last synced</dt>
                            <dd className="text-sm text-gray-400">
                              {formatDistanceToNow(new Date(account.lastSyncedAt), { addSuffix: true })}
                            </dd>
                          </div>
                        )}
                      </dl>
                    ) : (
                      <div className="mt-4">
                        <label htmlFor={`handle-${p.id}`} className="field-label">
                          Your {p.name} handle
                        </label>
                        <div className="flex max-w-md gap-2">
                        <input
                          id={`handle-${p.id}`}
                          className="input font-mono"
                          autoComplete="off"
                          spellCheck={false}
                          placeholder={`e.g. ${p.placeholder}`}
                          value={handles[p.id] ?? ''}
                          onChange={e => setHandles(h => ({ ...h, [p.id]: e.target.value }))}
                          onKeyDown={e => e.key === 'Enter' && handleLink(p.id)}
                        />
                        <button
                          type="button"
                          className="btn-primary whitespace-nowrap"
                          onClick={() => handleLink(p.id)}
                          disabled={isLinking || !handles[p.id]?.trim()}
                        >
                          {isLinking ? <Loader2 size={14} className="animate-spin" aria-hidden="true" /> : <Link2 size={14} aria-hidden="true" />}
                          {isLinking ? 'Linking…' : 'Link'}
                        </button>
                        </div>
                      </div>
                    )}
                  </div>

                  {account && (
                    <div className="flex flex-shrink-0 gap-2">
                      <button
                        type="button"
                        className="btn-secondary btn-sm"
                        onClick={() => syncMutation.mutate(p.id)}
                        disabled={isSyncing || account.syncStatus === 'RUNNING'}
                      >
                        <RefreshCw size={13} className={isSyncing ? 'animate-spin' : ''} aria-hidden="true" />
                        {isSyncing ? 'Syncing…' : 'Sync now'}
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm border border-red-900/60 text-red-300 hover:bg-red-950/50"
                        onClick={() => unlinkMutation.mutate(p.id)}
                        disabled={unlinkMutation.isPending}
                      >
                        <Unlink size={13} aria-hidden="true" />
                        Unlink
                      </button>
                    </div>
                  )}
                </div>

                {/* Linking a handle is how CPIntel reads your history; connecting a session is
                    how it submits for you. Two different things about one account, so they
                    belong on one row rather than on a screen you have to know about. The
                    practice workspace hides this once it is connected, which left no way to
                    see who you are submitting as, or to drop the session. */}
                {p.id === 'CODEFORCES' && <CfSessionCard variant="inline" />}
              </section>
            )
          })}
        </div>
      )}

      <aside className="rounded-xl border border-gray-800 p-4 text-sm text-gray-400">
        <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-500">How sync works</h2>
        <ul className="list-disc space-y-1 pl-4 text-xs marker:text-gray-600">
          <li>The first sync fetches your full history, which may take a minute.</li>
          <li>Incremental syncs run automatically every night at 02:00 UTC.</li>
          <li>Sync now fetches submissions since the last sync.</li>
          <li>Analytics update after each sync completes.</li>
          <li>Linking a handle is what reads your history. Submitting from Practice needs the
            Codeforces session connected above, which is separate.</li>
        </ul>
      </aside>
    </div>
  )
}
