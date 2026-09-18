import { useEffect, useState } from 'react'
import { useAdminAudit } from '@/hooks/useAdmin'
import { Ago, EmptyRow, Pager, Panel, Pill, actionLabel, actionTone } from '@/components/admin/AdminUi'
import { Loader2, Search } from 'lucide-react'

const RANGES = [
  { label: 'Last 24 hours', hours: 24 },
  { label: 'Last 7 days', hours: 24 * 7 },
  { label: 'Last 30 days', hours: 24 * 30 },
  { label: 'All time', hours: 0 },
]

/**
 * The audit trail.
 *
 * Records sign-ins, failed sign-ins, registrations, and every administrative change, with the
 * address it came from. It is deliberately append-only and has no delete: a trail an admin can
 * edit is not evidence of anything.
 *
 * Failed sign-ins are shown against the address that was tried rather than an account, because
 * the case worth spotting — repeated attempts on an address that does not exist — has no
 * account to attach to.
 */
export default function AdminAuditPage() {
  const [action, setAction] = useState('')
  const [range, setRange] = useState(24 * 7)
  const [userFilter, setUserFilter] = useState('')
  const [page, setPage] = useState(0)

  const [since, setSince] = useState<string | undefined>(
    () => new Date(Date.now() - 24 * 7 * 3600_000).toISOString())

  useEffect(() => {
    setSince(range === 0 ? undefined : new Date(Date.now() - range * 3600_000).toISOString())
    setPage(0)
  }, [range])

  const parsedUserId = userFilter.trim() === '' ? null : Number(userFilter.trim())
  const userIdIsValid = parsedUserId === null || Number.isInteger(parsedUserId)

  const { data, isLoading, isFetching } = useAdminAudit({
    action: action || undefined,
    userId: userIdIsValid ? parsedUserId : null,
    since,
    page,
    size: 50,
  })

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <select
          value={action}
          onChange={e => { setAction(e.target.value); setPage(0) }}
          className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-300
                     outline-none focus:border-indigo-600"
        >
          <option value="">Every action</option>
          {(data?.actions ?? []).map(a => (
            <option key={a} value={a}>{actionLabel(a)}</option>
          ))}
        </select>

        <select
          value={range}
          onChange={e => setRange(Number(e.target.value))}
          className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm text-gray-300
                     outline-none focus:border-indigo-600"
        >
          {RANGES.map(r => <option key={r.hours} value={r.hours}>{r.label}</option>)}
        </select>

        <div className="relative">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-600" />
          <input
            value={userFilter}
            onChange={e => { setUserFilter(e.target.value); setPage(0) }}
            placeholder="User ID"
            inputMode="numeric"
            className="w-32 rounded-lg border border-gray-800 bg-gray-900 py-2 pl-9 pr-3 text-sm
                       text-gray-200 placeholder-gray-600 outline-none focus:border-indigo-600"
          />
        </div>

        {!userIdIsValid && (
          <span className="text-xs text-amber-500">User ID must be a number</span>
        )}
        {isFetching && <Loader2 size={14} className="animate-spin text-gray-600" />}
      </div>

      <Panel
        title="Audit trail"
        description="Append-only. Nothing in this console can edit or remove an entry."
      >
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="px-4 py-2 font-medium">Action</th>
                <th className="px-4 py-2 font-medium">Account</th>
                <th className="px-4 py-2 font-medium">Detail</th>
                <th className="px-4 py-2 font-medium">From</th>
                <th className="px-4 py-2 font-medium">When</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {isLoading && <EmptyRow colSpan={5}>Loading the trail…</EmptyRow>}
              {!isLoading && data?.entries.length === 0 && (
                <EmptyRow colSpan={5}>
                  Nothing recorded in this window. Try a wider range.
                </EmptyRow>
              )}
              {data?.entries.map(entry => (
                <tr key={entry.logId} className="transition-colors hover:bg-gray-800/40">
                  <td className="px-4 py-2.5">
                    <Pill tone={actionTone(entry.action)}>{actionLabel(entry.action)}</Pill>
                  </td>
                  <td className="px-4 py-2.5">
                    {entry.username
                      ? <span className="text-gray-300">{entry.username}</span>
                      : <span className="text-gray-600">
                          {entry.userId ? `#${entry.userId}` : 'no account'}
                        </span>}
                  </td>
                  <td className="max-w-xs truncate px-4 py-2.5 text-xs text-gray-500"
                    title={entry.entityId ?? undefined}>
                    {entry.entityId ?? '—'}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-gray-500"
                    title={entry.userAgent ?? undefined}>
                    {entry.ipAddress ?? '—'}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-gray-500">
                    <Ago at={entry.createdAt} />
                  </td>
                </tr>
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
    </div>
  )
}
