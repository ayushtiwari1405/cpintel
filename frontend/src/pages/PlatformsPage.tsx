import { useState } from 'react'
import { useLinkedAccounts, useLinkAccount, useUnlinkAccount, useSyncAccount } from '@/hooks/usePlatforms'
import { RefreshCw, Link2, Unlink, CheckCircle, Clock, AlertCircle, Loader2 } from 'lucide-react'
import { formatDistanceToNow } from 'date-fns'
import { clsx } from 'clsx'

const PLATFORMS = [
  {
    id: 'CODEFORCES',
    name: 'Codeforces',
    color: 'text-blue-400',
    bg: 'bg-blue-900/20 border-blue-800',
    desc: 'Competitive programming contests, ratings, and problems',
    placeholder: 'tourist',
  },
  {
    id: 'LEETCODE',
    name: 'LeetCode',
    color: 'text-yellow-400',
    bg: 'bg-yellow-900/20 border-yellow-800',
    desc: 'Interview prep problems and weekly contests',
    placeholder: 'neal_wu',
  },
  {
    id: 'CODECHEF',
    name: 'CodeChef',
    color: 'text-orange-400',
    bg: 'bg-orange-900/20 border-orange-800',
    desc: 'Long challenges, cook-offs, and lunchtime contests',
    placeholder: 'gennady',
  },
]

const statusIcon = (status: string) => {
  switch (status) {
    case 'COMPLETED': return <CheckCircle size={14} className="text-green-400" />
    case 'RUNNING':   return <Loader2 size={14} className="text-blue-400 animate-spin" />
    case 'FAILED':    return <AlertCircle size={14} className="text-red-400" />
    default:          return <Clock size={14} className="text-gray-400" />
  }
}

export default function PlatformsPage() {
  const { data: accounts = [], isLoading } = useLinkedAccounts()
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
      <div>
        <h1 className="text-2xl font-semibold text-white">Platforms</h1>
        <p className="text-gray-400 text-sm mt-1">Link your competitive programming accounts to sync data</p>
      </div>

      {isLoading ? (
        <div className="flex items-center gap-2 text-gray-400 text-sm">
          <Loader2 size={16} className="animate-spin" /> Loading accounts…
        </div>
      ) : (
        <div className="grid gap-4">
          {PLATFORMS.map(p => {
            const account = linked(p.id)
            const isLinking = linking === p.id && linkMutation.isPending
            const isSyncing = syncMutation.isPending && syncMutation.variables === p.id

            return (
              <div key={p.id} className={clsx(
                'rounded-xl border p-5 transition-colors',
                account ? p.bg : 'bg-gray-900 border-gray-800'
              )}>
                <div className="flex items-start justify-between gap-4">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <h2 className={clsx('font-semibold text-base', account ? p.color : 'text-white')}>
                        {p.name}
                      </h2>
                      {account && (
                        <span className="flex items-center gap-1 text-xs text-gray-400">
                          {statusIcon(account.syncStatus)}
                          {account.syncStatus}
                        </span>
                      )}
                    </div>
                    <p className="text-gray-400 text-sm">{p.desc}</p>

                    {account ? (
                      <div className="mt-3 flex flex-wrap gap-4">
                        <div>
                          <p className="text-xs text-gray-500">Handle</p>
                          <p className="text-sm font-mono text-gray-200">{account.handle}</p>
                        </div>
                        {account.currentRating != null && (
                          <div>
                            <p className="text-xs text-gray-500">Rating</p>
                            <p className="text-sm font-semibold text-white">{account.currentRating}</p>
                          </div>
                        )}
                        {account.maxRating != null && (
                          <div>
                            <p className="text-xs text-gray-500">Max rating</p>
                            <p className="text-sm text-gray-300">{account.maxRating}</p>
                          </div>
                        )}
                        {account.lastSyncedAt && (
                          <div>
                            <p className="text-xs text-gray-500">Last synced</p>
                            <p className="text-sm text-gray-400">
                              {formatDistanceToNow(new Date(account.lastSyncedAt), { addSuffix: true })}
                            </p>
                          </div>
                        )}
                      </div>
                    ) : (
                      <div className="mt-3 flex gap-2">
                        <input
                          className="input max-w-xs text-sm"
                          placeholder={`Handle, e.g. ${p.placeholder}`}
                          value={handles[p.id] ?? ''}
                          onChange={e => setHandles(h => ({ ...h, [p.id]: e.target.value }))}
                          onKeyDown={e => e.key === 'Enter' && handleLink(p.id)}
                        />
                        <button
                          className="btn-primary flex items-center gap-1.5 text-sm whitespace-nowrap"
                          onClick={() => handleLink(p.id)}
                          disabled={isLinking || !handles[p.id]?.trim()}
                        >
                          {isLinking ? <Loader2 size={14} className="animate-spin" /> : <Link2 size={14} />}
                          Link
                        </button>
                      </div>
                    )}
                  </div>

                  {account && (
                    <div className="flex gap-2 flex-shrink-0">
                      <button
                        className="btn-secondary flex items-center gap-1.5 text-xs"
                        onClick={() => syncMutation.mutate(p.id)}
                        disabled={isSyncing || account.syncStatus === 'RUNNING'}
                      >
                        <RefreshCw size={13} className={isSyncing ? 'animate-spin' : ''} />
                        Sync
                      </button>
                      <button
                        className="flex items-center gap-1.5 px-3 py-2 text-xs rounded-lg
                                   text-red-400 hover:bg-red-900/20 border border-red-900/30 transition-colors"
                        onClick={() => unlinkMutation.mutate(p.id)}
                        disabled={unlinkMutation.isPending}
                      >
                        <Unlink size={13} />
                        Unlink
                      </button>
                    </div>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      )}

      <div className="bg-gray-900 border border-gray-800 rounded-xl p-4 text-sm text-gray-400">
        <p className="font-medium text-gray-300 mb-1">How sync works</p>
        <ul className="space-y-1 text-xs">
          <li>• Initial sync fetches your full history (may take a minute)</li>
          <li>• Incremental syncs run nightly at 02:00 UTC automatically</li>
          <li>• Manual sync fetches submissions since the last sync</li>
          <li>• Analytics update after each sync completes</li>
        </ul>
      </div>
    </div>
  )
}
