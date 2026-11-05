import { AlertTriangle, CheckCircle2, Loader2, RefreshCw } from 'lucide-react'
import { format } from 'date-fns'
import { Panel, Pill } from '@/components/admin/AdminUi'
import { useCodeforcesReadiness } from '@/hooks/useExams'
import type { CandidateReadiness } from '@/types'

/**
 * Whether a Codeforces exam's candidates can sit it: the classroom's key and the contest's
 * window, then per candidate whether they connected Codeforces and, once checked, whether
 * Codeforces lets them open the contest (for a group contest: they joined the group).
 */
export function CodeforcesReadiness({ eventId }: { eventId: number }) {
  const { data, isLoading, error, check } = useCodeforcesReadiness(eventId, true)
  const message = (error as { response?: { data?: { message?: string } } } | null)?.response?.data?.message

  if (isLoading) {
    return <Panel title="Codeforces readiness"><p className="p-4 text-xs text-gray-500">Loading…</p></Panel>
  }
  if (!data) {
    return (
      <Panel title="Codeforces readiness">
        <p className="p-4 text-xs text-red-400">{message ?? 'Could not read it.'}</p>
      </Panel>
    )
  }

  const notConnected = data.candidates.filter(c => !c.connected).length
  const blocked = data.candidates.filter(c => c.canOpen === false).length
  const problems = [data.contestProblem, data.windowProblem].filter(Boolean) as string[]

  return (
    <Panel title="Codeforces readiness"
      description={data.contestName
        ? `${data.contestName}${data.contestStartsAt ? ` · opens ${format(new Date(data.contestStartsAt), 'd MMM, HH:mm')}` : ''}`
        : data.groupContest ? 'Private group contest' : 'Public contest'}
      actions={
        <button type="button" onClick={() => check.mutate()} disabled={data.checking || check.isPending}
          title="Open the contest as each connected candidate"
          className="flex items-center gap-1.5 rounded-lg border border-gray-800 px-3 py-1.5 text-xs
            text-gray-300 hover:border-gray-700 disabled:opacity-40">
          {data.checking ? <Loader2 size={12} className="animate-spin" /> : <RefreshCw size={12} />}
          {data.checking ? 'Checking…' : 'Check candidates'}
        </button>
      }>
      <div className="space-y-3 p-4">
        {problems.map(p => (
          <p key={p} className="flex items-start gap-2 rounded-lg border border-amber-500/20 bg-amber-500/5
            px-3 py-2 text-xs text-amber-300">
            <AlertTriangle size={13} className="mt-0.5 shrink-0" /> {p}
          </p>
        ))}
        <p className="flex flex-wrap gap-2 text-xs text-gray-400">
          <span>{data.candidates.length} assigned</span>
          <span>· {notConnected} not connected</span>
          {data.checkedAt && <span>· {blocked} cannot open it</span>}
          {data.checkedAt && <span className="text-gray-600">· checked {format(new Date(data.checkedAt), 'HH:mm')}</span>}
        </p>
        {data.candidates.length > 0 && (
          <div className="max-h-72 overflow-y-auto rounded-lg border border-gray-800">
            <table className="w-full text-sm">
              <tbody className="divide-y divide-gray-800">
                {data.candidates.map(c => <Row key={c.userId} c={c} />)}
              </tbody>
            </table>
          </div>
        )}
        {problems.length === 0 && notConnected === 0 && data.checkedAt && blocked === 0 && !data.checking && (
          <p className="flex items-center gap-1.5 text-xs text-green-400">
            <CheckCircle2 size={13} /> Everyone can open the contest.
          </p>
        )}
      </div>
    </Panel>
  )
}

function Row({ c }: { c: CandidateReadiness }) {
  return (
    <tr>
      <td className="px-3 py-1.5">
        <span className="text-gray-200">{c.username}</span>
        {c.fullName && <span className="ml-2 text-xs text-gray-500">{c.fullName}</span>}
      </td>
      <td className="px-3 py-1.5 text-xs text-gray-500">{c.handle ?? '—'}</td>
      <td className="px-3 py-1.5 text-right">
        {!c.connected ? <Pill tone="amber">not connected</Pill>
          : c.canOpen === false ? <Pill tone="red">cannot open it</Pill>
          : c.canOpen ? <Pill tone="green">ready</Pill>
          : <Pill tone="gray">connected</Pill>}
      </td>
    </tr>
  )
}
