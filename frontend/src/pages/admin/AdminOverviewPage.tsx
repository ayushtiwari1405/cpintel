import { Link } from 'react-router-dom'
import { useAdminOverview } from '@/hooks/useAdmin'
import { Ago, Panel, Pill, StatCard, actionLabel, actionTone, bytes } from '@/components/admin/AdminUi'
import { AlertTriangle, ArrowRight, Loader2 } from 'lucide-react'
import { PageHeader } from '@/components/common/PageHeader'

/**
 * Where an admin lands, and usually the only screen they need.
 *
 * The figures are grouped by the question they answer — who is using this, is the sync
 * pipeline healthy, how much has been uploaded, what are contests allowed to do — with the
 * audit trail underneath, because "what changed recently" is the other half of every
 * investigation that starts here.
 */
export default function AdminOverviewPage() {
  const { data, isLoading, error } = useAdminOverview()

  if (isLoading) {
    return (
      <div className="flex items-center gap-2 py-20 justify-center text-sm text-gray-500">
        <Loader2 size={16} className="animate-spin" /> Loading the deployment…
      </div>
    )
  }

  if (error || !data) {
    // A 403 here means the account is not an admin — most often a stale profile in this browser
    // after a demotion, or a hand-edited one. Saying "the database is probably down" to someone
    // who was simply refused sends them to debug the wrong system entirely, so the two cases
    // are told apart and named.
    const status = (error as { response?: { status?: number } } | null)?.response?.status
    const denied = status === 401 || status === 403

    return (
      <div className="rounded-xl border border-red-900 bg-red-950/30 p-6 text-sm text-red-300">
        {denied ? (
          <>
            This account is not an administrator. If your role changed recently, sign out and
            back in.
          </>
        ) : (
          <>
            Could not load the overview. Check the backend and the database.
          </>
        )}
      </div>
    )
  }

  const { users, sync, files, policy, recentActivity } = data

  return (
    <div className="space-y-6">
      <PageHeader title="Overview" className="mb-2" />
      <section>
        <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-gray-500">
          Accounts
        </h2>
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <StatCard label="Total users" value={users.total.toLocaleString()}
            hint={`${users.joinedLast7Days} joined this week`} />
          <StatCard label="Active" value={users.active.toLocaleString()} tone="good"
            hint={users.inactive > 0 ? `${users.inactive} deactivated` : 'none deactivated'} />
          <StatCard label="Admins" value={users.admins.toLocaleString()}
            hint={users.admins === 1 ? 'only one — consider a second' : 'console access'} />
          <StatCard label="Verified" value={users.verified.toLocaleString()}
            hint={`${users.total - users.verified} unverified`} />
        </div>
      </section>

      <section>
        <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-gray-500">
          Platform sync
        </h2>
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <StatCard label="Queued" value={sync.queued} tone={sync.queued > 20 ? 'warn' : 'default'} />
          <StatCard label="Running" value={sync.running} />
          <StatCard label="Failed (24h)" value={sync.failedLast24h}
            tone={sync.failedLast24h > 0 ? 'danger' : 'default'} />
          <StatCard label="Completed (24h)" value={sync.completedLast24h} tone="good" />
        </div>
      </section>

      <section>
        <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-gray-500">
          Personal files
        </h2>
        {files.available ? (
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatCard label="Files stored" value={files.files.toLocaleString()} />
            <StatCard label="Total size" value={bytes(files.bytes)} />
            <StatCard label="Users with files" value={files.owners.toLocaleString()} />
            <StatCard
              label="Contests allowing files"
              value={policy.contestFilesEnabledByDefault ? 'All by default' : 'None by default'}
              tone={policy.contestFilesEnabledByDefault ? 'good' : 'warn'}
              hint={policy.contestExceptions === 0
                ? 'no per-contest exceptions'
                : `${policy.contestExceptions} exception${policy.contestExceptions === 1 ? '' : 's'}`}
            />
          </div>
        ) : (
          <div className="flex items-center gap-2 rounded-xl border border-amber-900 bg-amber-950/30
            p-4 text-sm text-amber-300">
            <AlertTriangle size={16} className="shrink-0" />
            File store unreachable; figures unavailable. Contest access still follows the{' '}
            {policy.contestFilesEnabledByDefault ? 'enabled' : 'disabled'} default.
          </div>
        )}
        <p className="mt-2 text-xs text-gray-600">
          Sizes only — admins cannot see file names or contents.
        </p>
      </section>

      <Panel
        title="Recent activity"

        actions={
          <Link to="/admin/audit"
            className="flex items-center gap-1 text-xs text-accent-400 hover:text-accent-300">
            Full trail <ArrowRight size={12} />
          </Link>
        }
      >
        {recentActivity.length === 0 ? (
          <p className="px-4 py-8 text-center text-sm text-gray-600">
            No activity yet.
          </p>
        ) : (
          <ul className="divide-y divide-gray-800">
            {recentActivity.map(entry => (
              <li key={entry.logId} className="flex items-center gap-3 px-4 py-2.5 text-sm">
                <Pill tone={actionTone(entry.action)}>{actionLabel(entry.action)}</Pill>
                <span className="min-w-0 flex-1 truncate text-gray-300">
                  {entry.username ?? entry.entityId ?? 'unknown'}
                </span>
                <span className="shrink-0 text-xs text-gray-600">
                  <Ago at={entry.createdAt} />
                </span>
              </li>
            ))}
          </ul>
        )}
      </Panel>
    </div>
  )
}
