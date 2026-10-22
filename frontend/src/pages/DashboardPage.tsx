import { useQuery } from '@tanstack/react-query'
import { userApi } from '@/api/userApi'
import { useRefreshAnalytics } from '@/hooks/useAnalytics'
import { Target, Award, RefreshCw, Link2, ChevronRight, Gauge } from 'lucide-react'
import { Link } from 'react-router-dom'
import { formatDistanceToNow } from 'date-fns'
import {
  RadarChart, Radar, PolarGrid, PolarAngleAxis, PolarRadiusAxis,
  ResponsiveContainer, Tooltip
} from 'recharts'
import { tooltipStyle, CHROME, ACCENT, ANIMATE } from '@/charts/ChartTheme'
import { PageHeader, Section } from '@/components/common/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/common/States'
import { clsx } from 'clsx'

export default function DashboardPage() {
  const refresh = useRefreshAnalytics()

  const { data: dashboard, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['dashboard'],
    queryFn: () => userApi.getDashboard().then(r => r.data),
    staleTime: 0,
    refetchOnMount: true,
  })
  // No recompute on opening the page: a sync rebuilds the analytics when it finishes, and the
  // Refresh button is there for anything else. Recomputing on every visit cost a full rebuild
  // per page view and, until the eviction was made per user, emptied everybody's cache.

  const platforms = dashboard?.user?.platforms ?? []
  const score = dashboard?.unifiedScore
  const topics = dashboard?.topTopics ?? []
  const contests = dashboard?.recentContests ?? []
  const cf = platforms.find((a: any) => a.platform === 'CODEFORCES')

  // Ratings when there is a basis for them, otherwise how much was solved (coverage).
  const rated = topics.some((t: any) => t.skillRating != null)
  const valueOf = (t: any) => rated ? Math.round(t.skillRating ?? 0) : (t.problemsSolved ?? 0)
  const shown = topics.slice(0, 7)
  const radarData = shown.map((t: any) => ({
    topic: t.topic.length > 10 ? t.topic.slice(0, 10) + '…' : t.topic,
    value: valueOf(t),
  }))
  const values = radarData.map((d: any) => d.value)
  const radarDomain: [number, number] = rated
    ? [Math.floor((Math.min(...values) - 200) / 100) * 100, Math.ceil((Math.max(...values) + 100) / 100) * 100]
    : [0, Math.max(1, ...values)]
  const [lo, hi] = radarDomain
  const width = (v: number) => Math.min(100, Math.max(0, 100 * (v - lo) / Math.max(1, hi - lo)))

  const header = (
    <PageHeader
      title="Dashboard"
      actions={
        <button
          type="button"
          onClick={() => refresh.mutate()}
          disabled={refresh.isPending}
          className="btn-secondary"
        >
          <RefreshCw size={14} className={refresh.isPending ? 'animate-spin' : ''} aria-hidden="true" />
          {refresh.isPending ? 'Refreshing…' : 'Refresh'}
        </button>
      }
    />
  )

  if (isLoading) return <>{header}<LoadingState label="Loading your dashboard…" /></>
  if (isError) return (
    <>{header}<ErrorState title="Could not load your dashboard" error={error} onRetry={() => refetch()} /></>
  )

  return (
    <div>
      {header}

      {/* Headline numbers */}
      <div className="mb-6 grid grid-cols-1 overflow-hidden rounded-xl border border-gray-800
                      bg-gray-900 sm:grid-cols-2">
        <div className="border-b border-gray-800 p-4 sm:border-b-0 sm:border-r">
          <p className="card-header flex items-center gap-1.5"><Gauge size={12} aria-hidden="true" /> Unified score</p>
          <p className="font-mono text-3xl font-semibold tabular-nums text-accent-400">
            {score?.unifiedScore != null ? score.unifiedScore.toFixed(0) : '—'}
            <span className="ml-1 text-sm font-normal text-gray-500">/ 1000</span>
          </p>
          {platforms.length > 0 && (
            <p className="mt-1 text-xs text-gray-500">Codeforces rating scaled to 0–1000.</p>
          )}
        </div>
        <div className="p-4">
          <p className="card-header">Codeforces</p>
          {cf ? (
            <>
              <p className="font-mono text-3xl font-semibold tabular-nums text-gray-50">
                {cf.currentRating ?? '—'}
                {cf.maxRating != null && (
                  <span className="ml-2 text-sm font-normal text-gray-500">peak {cf.maxRating}</span>
                )}
              </p>
              <p className="mt-1 truncate text-xs text-gray-500">
                <span className="font-mono text-gray-400">{cf.handle}</span>
                {cf.lastSyncedAt && <> · synced {formatDistanceToNow(new Date(cf.lastSyncedAt), { addSuffix: true })}</>}
              </p>
            </>
          ) : (
            <>
              <p className="font-mono text-3xl font-semibold text-gray-600">—</p>
              <Link to="/platforms"
                className="mt-1 inline-flex items-center gap-1 text-xs text-accent-400 hover:underline">
                <Link2 size={11} aria-hidden="true" /> Not linked
              </Link>
            </>
          )}
        </div>
      </div>

      {platforms.length === 0 && (
        <div className="mb-6 rounded-xl border border-dashed border-accent-800/70 bg-accent-950/20">
          <EmptyState
            icon={<Link2 size={18} />}
            title="Connect Codeforces"
            action={<Link to="/platforms" className="btn-primary"><Link2 size={14} aria-hidden="true" /> Link a platform</Link>}
          >
            Topic ratings and practice plans are built from your submission history.
          </EmptyState>
        </div>
      )}

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        <Section
          title={rated ? 'Topic ratings' : 'What you have practised'}
          icon={<Target size={14} />}
          actions={
            <Link to="/analytics" className="inline-flex items-center gap-0.5 text-accent-400 hover:underline">
              Full breakdown <ChevronRight size={12} aria-hidden="true" />
            </Link>
          }
        >
          {topics.length === 0 ? (
            <EmptyState compact title="No solved problems analysed yet">
              Link a platform and refresh.
            </EmptyState>
          ) : (
            <div className={clsx('grid gap-4', radarData.length >= 3 && 'md:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]')}>
              {/* A radar needs three axes to be a shape; with one or two it draws a lone spoke
                  that reads as a broken chart, so only the list is shown then. */}
              {radarData.length >= 3 && (
                <div className="hidden md:block" aria-hidden="true">
                  <ResponsiveContainer width="100%" height={230}>
                    <RadarChart data={radarData} outerRadius="68%" margin={{ top: 8, right: 28, bottom: 8, left: 28 }}>
                      <PolarGrid stroke={CHROME.grid} />
                      <PolarAngleAxis dataKey="topic" tick={{ fill: CHROME.label, fontSize: 11 }} />
                      <PolarRadiusAxis domain={radarDomain} tick={false} axisLine={false} />
                      <Radar dataKey="value" stroke={ACCENT} fill={ACCENT} fillOpacity={0.18} isAnimationActive={ANIMATE} />
                      <Tooltip
                        contentStyle={tooltipStyle}
                        formatter={(v: any) => [v, rated ? 'Rating' : 'Solved']}
                      />
                    </RadarChart>
                  </ResponsiveContainer>
                </div>
              )}
              <ul className="space-y-2.5" aria-label={rated ? 'Rating by topic' : 'Problems solved by topic'}>
                {shown.map((t: any) => {
                  const value = valueOf(t)
                  return (
                    <li key={t.topic}>
                      <div className="mb-1 flex items-baseline justify-between gap-2 text-xs">
                        <span className="truncate text-gray-300">{t.topic}</span>
                        <span className="shrink-0 font-mono tabular-nums text-gray-400">
                          {rated
                            ? <>{value}<span className="text-gray-600"> ± {Math.round(t.skillSd ?? 0)}</span></>
                            : `${value} solved`}
                        </span>
                      </div>
                      <div className="h-1 overflow-hidden rounded-full bg-gray-800">
                        <div className="h-full rounded-full bg-accent-500" style={{ width: `${width(value)}%` }} />
                      </div>
                    </li>
                  )
                })}
              </ul>
            </div>
          )}
        </Section>

        <Section
          title="Recent contests"
          icon={<Award size={14} />}
          bodyClassName="p-0"
          actions={
            <Link to="/analytics" className="inline-flex items-center gap-0.5 text-accent-400 hover:underline">
              See all <ChevronRight size={12} aria-hidden="true" />
            </Link>
          }
        >
          {contests.length === 0 ? (
            <EmptyState compact title="No contest history yet">Sync a platform to see your rounds.</EmptyState>
          ) : (
            <ul className="divide-y divide-gray-800">
              {contests.map((c: any) => {
                const delta = c.ratingChange ?? 0
                return (
                  <li key={c.contestId} className="flex items-center gap-3 px-4 py-3">
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm text-gray-100" title={c.contestName}>{c.contestName}</p>
                      <p className="mt-0.5 text-xs text-gray-500">
                        {c.platform === 'CODEFORCES' ? 'Codeforces' : c.platform}
                        {' · '}rank <span className="font-mono tabular-nums text-gray-400">{c.rank ?? '?'}</span>
                        {' · '}<span className="font-mono tabular-nums text-gray-400">{c.problemsSolved}/{c.totalProblems}</span> solved
                      </p>
                    </div>
                    <span className={clsx('shrink-0 rounded-md px-2 py-0.5 font-mono text-sm font-medium tabular-nums',
                      delta > 0 ? 'bg-green-950/50 text-green-300' : delta < 0 ? 'bg-red-950/50 text-red-300' : 'bg-gray-850 text-gray-400')}
                      title="Rating change">
                      {delta > 0 ? '+' : delta < 0 ? '−' : '±'}{Math.abs(delta)}
                    </span>
                  </li>
                )
              })}
            </ul>
          )}
        </Section>
      </div>
    </div>
  )
}
