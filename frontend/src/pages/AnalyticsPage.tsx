import { useState } from 'react'
import {
  useOverview, useTopicAnalytics, useContestAnalytics,
  useRefreshAnalytics, useSkillBasis
} from '@/hooks/useAnalytics'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, BarChart, Bar,
  Cell, ReferenceLine
} from 'recharts'
import { RefreshCw, TrendingUp, Target, Lightbulb, BarChart2 } from 'lucide-react'
import { tooltipStyle, axisStyle, CHROME, ACCENT, ANIMATE } from '@/charts/ChartTheme'
import { PageHeader, Section } from '@/components/common/PageHeader'
import { Tabs, panelProps } from '@/components/common/Tabs'
import { Stat, Stats } from '@/components/common/Stat'
import { EmptyState, ErrorState, LoadingState } from '@/components/common/States'
import { SkillBasisPanel } from '@/components/analytics/SkillBasisPanel'
import { fmtRating, readSkill, type SkillReading } from '@/components/analytics/skill'
import type { TopicMastery } from '@/types'

const TABS = ['Overview', 'Topics', 'Contests'] as const
type Tab = typeof TABS[number]

export default function AnalyticsPage() {
  const [tab, setTab] = useState<Tab>('Overview')
  const refresh = useRefreshAnalytics()

  return (
    <div>
      <PageHeader
        title="Analytics"
        actions={
          <button
            type="button"
            onClick={() => refresh.mutate()}
            disabled={refresh.isPending}
            className="btn-secondary"
          >
            <RefreshCw size={14} className={refresh.isPending ? 'animate-spin' : ''} aria-hidden="true" />
            {refresh.isPending ? 'Recomputing…' : 'Recompute'}
          </button>
        }
      />

      <Tabs id="analytics" label="Analytics views" value={tab} onChange={setTab}
        tabs={TABS.map(t => ({ key: t, label: t }))} className="mb-6" />

      <div {...panelProps('analytics', tab)} className="focus:outline-hidden">
        {tab === 'Overview' && <OverviewTab />}
        {tab === 'Topics'   && <TopicsTab />}
        {tab === 'Contests' && <ContestsTab />}
      </div>
    </div>
  )
}

const PLATFORM_NAME: Record<string, string> = { CODEFORCES: 'Codeforces' }
const signed = (v: number, digits = 1) => `${v > 0 ? '+' : v < 0 ? '−' : ''}${Math.abs(v).toFixed(digits)}`

function OverviewTab() {
  const { data: overview, isLoading, isError, error, refetch } = useOverview()
  if (isLoading) return <LoadingState label="Loading your overview…" />
  // Shown rather than zeros: "0 solved" and "could not load" are different facts.
  if (isError) return <ErrorState title="Could not load your overview" error={error} onRetry={() => refetch()} />

  const platforms = overview?.platformStats ?? []

  return (
    <div className="space-y-6">
      <Stats>
        <Stat label="Unified score" tone="accent" value={(overview?.unifiedScore ?? 0).toFixed(0)}
          hint="Codeforces rating on a 0–1000 scale" />
        <Stat label="Problems solved" value={overview?.totalSolved ?? 0} />
        <Stat label="Contests" value={overview?.totalContests ?? 0} />
        <Stat label="Consistency" value={`${(overview?.overallConsistency ?? 0).toFixed(0)}%`} />
      </Stats>

      {platforms.length === 0 ? (
        <Section title="Platforms" icon={<BarChart2 size={14} />}>
          <EmptyState compact title="No platform statistics yet">Link and sync a platform first.</EmptyState>
        </Section>
      ) : platforms.map((p: any) => (
        <Section key={p.platform} title={PLATFORM_NAME[p.platform] ?? p.platform}
          icon={<BarChart2 size={14} />} bodyClassName="p-0"
          actions={<span className="badge-gray font-mono tabular-nums">{p.totalContests} contests</span>}>
          <div className="grid grid-cols-2 gap-px bg-gray-800 lg:grid-cols-4">
            <Stat label="Rating" value={p.currentRating ?? '—'} />
            <Stat label="Peak" value={p.maxRating ?? '—'} />
            <Stat label="Average change" value={signed(p.avgRatingChange ?? 0)}
              tone={(p.avgRatingChange ?? 0) > 0 ? 'good' : (p.avgRatingChange ?? 0) < 0 ? 'danger' : 'default'}
              hint="per rated round" />
            <Stat label="Accuracy" value={`${(p.accuracy ?? 0).toFixed(1)}%`} hint="accepted submissions" />
          </div>
        </Section>
      ))}
    </div>
  )
}

function TopicsTab() {
  const { data: topics, isLoading, isError, error, refetch } = useTopicAnalytics()
  const { data: basis } = useSkillBasis()
  if (isLoading) return <LoadingState label="Loading topic ratings…" />
  if (isError) return <ErrorState title="Could not load topic ratings" error={error} onRetry={() => refetch()} />

  return (
    <div className="space-y-6">
      <SkillBasisPanel />
      {!topics?.length
        ? <div className="card"><EmptyState title="No topic data yet">Sync a platform first.</EmptyState></div>
        : basis?.basis
          ? <RatedTopics topics={topics} base={basis.rating} fromRounds={basis.basis === 'CODEFORCES'} />
          : <CoverageTopics topics={topics} />}
    </div>
  )
}

/** Topic ratings in Codeforces points, each read against the rating it started from. */
function RatedTopics({ topics, base, fromRounds }:
  { topics: TopicMastery[]; base: number | null; fromRounds: boolean }) {
  const rows = [...topics].sort((a, b) => (b.skillRating ?? 0) - (a.skillRating ?? 0))
  const values = rows.map(t => t.skillRating ?? 0)
  const lo = Math.floor((Math.min(...values, base ?? Infinity) - 150) / 100) * 100
  const hi = Math.ceil((Math.max(...values, base ?? -Infinity) + 150) / 100) * 100
  // Bars are drawn as the distance above the axis floor and labelled back in rating points:
  // a bar chart measures from zero, and an axis that starts at 0 would flatten every rating
  // into the same long bar.
  const chart = rows.map(t => ({ ...t, value: Math.round(t.skillRating ?? 0), span: Math.round(t.skillRating ?? 0) - lo }))

  return (
    <>
      <Section title="Rating by topic" icon={<Target size={14} />}>
        <p className="text-xs text-gray-500 mb-4">
          In Codeforces points. A topic counts as a strength or weak spot only when it differs
          from {base ?? 'your rating'} by more than its uncertainty.
        </p>
        <ResponsiveContainer width="100%" height={Math.max(240, rows.length * 30)}>
          <BarChart data={chart} layout="vertical"
            margin={{ top: 0, right: 20, bottom: 0, left: 10 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={CHROME.grid} horizontal={false} />
            <XAxis type="number" domain={[0, hi - lo]} {...axisStyle}
              tickFormatter={(v: number) => `${v + lo}`} />
            <YAxis type="category" dataKey="topic" width={130} interval={0}
              tick={{ fill: CHROME.label, fontSize: 11 }} />
            <Tooltip
              contentStyle={tooltipStyle}
              formatter={(_v: any, _n: any, item: any) =>
                [`${item.payload.value} ± ${Math.round(item.payload.skillSd ?? 0)}`, 'Rating']}
            />
            {base != null && <ReferenceLine x={base - lo} stroke={CHROME.label} strokeDasharray="4 4" />}
            <Bar dataKey="span" radius={[0, 3, 3, 0]} barSize={14} isAnimationActive={ANIMATE}>
              {chart.map((t, i) => (
                <Cell key={i} fill={READING_COLOR[readSkill(t, base).kind]} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </Section>

      <Section title="All topics" bodyClassName="p-0">
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Topic</th>
                <th scope="col">Rating</th>
                <th scope="col">Reading</th>
                {fromRounds && (
                  <th scope="col" title="Problems from your rated rounds behind the rating">
                    Round problems
                  </th>
                )}
                <th scope="col" className="text-right">Solved</th>
                <th scope="col" className="text-right">Hardest solved</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(t => {
                const r = readSkill(t, base)
                return (
                  <tr key={t.topic}>
                    <td className="font-medium text-gray-100">{t.topic}</td>
                    <td className="whitespace-nowrap font-mono text-gray-100">
                      {fmtRating(t.skillRating)}
                      <span className="text-xs text-gray-500"> ± {Math.round(t.skillSd ?? 0)}</span>
                    </td>
                    <td className="whitespace-nowrap text-xs">
                      {r.kind === 'strength' && <span className="badge-green">
                        Strength +{Math.round(r.offset)}</span>}
                      {r.kind === 'weak' && <span className="badge-red">
                        Weak spot {Math.round(r.offset)}</span>}
                      {r.kind === 'in-line' && <span className="text-gray-500">In line with your rating</span>}
                    </td>
                    {fromRounds && (
                      <td className="font-mono text-xs text-gray-400">{t.skillProblems ?? 0}</td>
                    )}
                    <td className="text-right font-mono text-xs text-gray-400">{t.problemsSolved ?? 0}</td>
                    <td className="text-right font-mono text-xs text-gray-400">{fmtRating(t.maxSolvedRating)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </Section>
    </>
  )
}

/**
 * What has been practised, for someone with no rating to show: how many problems on each
 * topic, and how hard the hardest one was. Says what was done, not how good anyone is.
 */
function CoverageTopics({ topics }: { topics: TopicMastery[] }) {
  const rows = [...topics]
    .filter(t => (t.problemsAttempted ?? 0) > 0)
    .sort((a, b) => (b.problemsSolved ?? 0) - (a.problemsSolved ?? 0))
  if (!rows.length) return <div className="card"><EmptyState title="Nothing practised yet">Sync a platform first.</EmptyState></div>

  return (
    <>
      <Section title="What you have practised" icon={<Target size={14} />}>
        <p className="text-xs text-gray-500 mb-4">
          Problems solved per topic — volume, not strength. Rated rounds or the gauntlet give a
          rating per topic.
        </p>
        <ResponsiveContainer width="100%" height={Math.max(240, rows.length * 30)}>
          <BarChart data={rows} layout="vertical"
            margin={{ top: 0, right: 20, bottom: 0, left: 10 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={CHROME.grid} horizontal={false} />
            <XAxis type="number" allowDecimals={false} {...axisStyle} />
            <YAxis type="category" dataKey="topic" width={130} interval={0}
              tick={{ fill: CHROME.label, fontSize: 11 }} />
            <Tooltip contentStyle={tooltipStyle}
              formatter={(v: any) => [v, 'Solved']} />
            <Bar dataKey="problemsSolved" fill={ACCENT} radius={[0, 3, 3, 0]} barSize={14} isAnimationActive={ANIMATE} />
          </BarChart>
        </ResponsiveContainer>
      </Section>

      <Section title="All topics" bodyClassName="p-0">
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Topic</th>
                <th scope="col" className="text-right">Solved</th>
                <th scope="col" className="text-right">Tried, not solved</th>
                <th scope="col" className="text-right">Hardest solved</th>
                <th scope="col">Last practised</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(t => (
                <tr key={t.topic}>
                  <td className="font-medium text-gray-100">{t.topic}</td>
                  <td className="text-right font-mono text-gray-100">{t.problemsSolved ?? 0}</td>
                  <td className="text-right font-mono text-xs text-gray-400">
                    {(t.problemsAttempted ?? 0) - (t.problemsSolved ?? 0)}
                  </td>
                  <td className="text-right font-mono text-xs text-gray-400">{fmtRating(t.maxSolvedRating)}</td>
                  <td className="whitespace-nowrap text-xs text-gray-500">
                    {t.lastPracticedAt ? new Date(t.lastPracticedAt).toLocaleDateString() : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
    </>
  )
}

const READING_COLOR: Record<SkillReading['kind'], string> = {
  strength: 'rgb(var(--c-green-500))',
  weak: 'rgb(var(--c-red-500))',
  'in-line': ACCENT,
  none: 'rgb(var(--c-gray-600))',
}

function ContestsTab() {
  const { data: contests, isLoading, isError, error, refetch } = useContestAnalytics()
  if (isLoading) return <LoadingState label="Loading contest history…" />
  if (isError) return <ErrorState title="Could not load contest history" error={error} onRetry={() => refetch()} />
  if (!contests?.ratingHistory?.length)
    return <div className="card"><EmptyState title="No contest history yet">Sync a platform to see your rounds.</EmptyState></div>

  const chartData = contests.ratingHistory.map((c: any) => ({
    name: c.date ?? '',
    rating: c.ratingAfter,
    change: c.ratingChange,
    platform: c.platform,
  }))

  return (
    <div className="space-y-6">
      <Stats>
        <Stat label="Peak rating" value={contests.peakRating ?? '—'} />
        <Stat label="Average change" value={signed(contests.avgRatingChange ?? 0)}
          tone={(contests.avgRatingChange ?? 0) > 0 ? 'good' : (contests.avgRatingChange ?? 0) < 0 ? 'danger' : 'default'}
          hint="per rated round" />
        <Stat label="Wrong submissions" value={(contests.avgWrongSubmissions ?? 0).toFixed(1)}
          hint="average per round" />
        <Stat label="Consistency" value={`${(contests.consistencyScore ?? 0).toFixed(0)}%`} />
      </Stats>

      <Section title="Rating over time" icon={<TrendingUp size={14} />}>
        <ResponsiveContainer width="100%" height={280}>
          <LineChart data={chartData} margin={{ top: 5, right: 10, bottom: 5, left: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={CHROME.grid} />
            <XAxis dataKey="name" {...axisStyle} tick={{ fill: CHROME.mutedTick, fontSize: 10 }} />
            {/* The range of the ratings themselves: from zero, a 1200-1750 career is a flat line. */}
            <YAxis {...axisStyle} domain={['dataMin - 50', 'dataMax + 50']} allowDecimals={false} />
            <Tooltip
              contentStyle={tooltipStyle}
              formatter={(v: any, name: string) => [v, name === 'rating' ? 'Rating' : 'Change']}
            />
            <Line
              type="monotone" dataKey="rating"
              stroke={ACCENT} strokeWidth={2}
              dot={false} activeDot={{ r: 4 }} isAnimationActive={ANIMATE}
            />
          </LineChart>
        </ResponsiveContainer>
      </Section>

      {contests.insights?.length > 0 && (
        <Section title="Insights" icon={<Lightbulb size={14} />}>
          <ul className="space-y-2">
            {contests.insights.map((insight: string, i: number) => (
              <li key={i} className="flex items-start gap-2 text-sm text-gray-300">
                <span className="mt-0.5 shrink-0 text-accent-400" aria-hidden="true">→</span>
                {insight}
              </li>
            ))}
          </ul>
        </Section>
      )}
    </div>
  )
}
