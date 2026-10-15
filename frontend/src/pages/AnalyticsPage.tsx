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
import { RefreshCw, Loader2, TrendingUp, Target} from 'lucide-react'
import { tooltipStyle, axisStyle, CHROME, PLATFORM_COLORS } from '@/charts/ChartTheme'
import { SkillBasisPanel } from '@/components/analytics/SkillBasisPanel'
import { fmtRating, readSkill, type SkillReading } from '@/components/analytics/skill'
import type { TopicMastery } from '@/types'
import { clsx } from 'clsx'

const TABS = ['Overview', 'Topics', 'Contests'] as const
type Tab = typeof TABS[number]

export default function AnalyticsPage() {
  const [tab, setTab] = useState<Tab>('Overview')
  const refresh = useRefreshAnalytics()

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-gray-50">Analytics</h1>
          <p className="text-gray-400 text-sm mt-0.5">Deep dive into your CP performance</p>
        </div>
        <button
          onClick={() => refresh.mutate()}
          disabled={refresh.isPending}
          className="btn-secondary flex items-center gap-2 text-sm"
        >
          <RefreshCw size={14} className={refresh.isPending ? 'animate-spin' : ''} />
          Recompute
        </button>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 bg-gray-900 border border-gray-800 rounded-xl p-1 w-fit">
        {TABS.map(t => (
          <button key={t} onClick={() => setTab(t)}
            className={clsx(
              'px-4 py-1.5 rounded-lg text-sm font-medium transition-colors',
              tab === t
                ? 'bg-indigo-600 text-white'
                : 'text-gray-400 hover:text-gray-200'
            )}>
            {t}
          </button>
        ))}
      </div>

      {tab === 'Overview' && <OverviewTab />}
      {tab === 'Topics'   && <TopicsTab />}
      {tab === 'Contests' && <ContestsTab />}
    </div>
  )
}

function OverviewTab() {
  const { data: overview, isLoading } = useOverview()
  if (isLoading) return <LoadingCard />

  const platforms = overview?.platformStats ?? []

  return (
    <div className="space-y-6">
      {/* Platform stats */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {platforms.map((p: any) => (
          <div key={p.platform} className="card">
            <div className="flex items-center justify-between mb-3">
              <span className="text-sm font-medium" style={{ color: PLATFORM_COLORS[p.platform] }}>
                {p.platform}
              </span>
              <span className="badge badge-gray">
                {p.totalContests} contests
              </span>
            </div>
            <p className="text-2xl font-bold text-gray-50">{p.currentRating ?? '—'}</p>
            <p className="text-xs text-gray-500 mt-1">Peak: {p.maxRating ?? '—'}</p>
            <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
              <div>
                <p className="text-gray-500">Avg Δ rating</p>
                <p className={`font-medium ${
                  (p.avgRatingChange ?? 0) >= 0 ? 'text-green-400' : 'text-red-400'
                }`}>
                  {p.avgRatingChange >= 0 ? '+' : ''}{(p.avgRatingChange ?? 0).toFixed(1)}
                </p>
              </div>
              <div>
                <p className="text-gray-500">Accuracy</p>
                <p className="font-medium text-gray-200">{(p.accuracy ?? 0).toFixed(1)}%</p>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Summary numbers */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="card">
          <p className="card-header">Unified score</p>
          <p className="text-3xl font-bold text-indigo-400">
            {(overview?.unifiedScore ?? 0).toFixed(0)}
          </p>
        </div>
        <div className="card">
          <p className="card-header">Total solved</p>
          <p className="text-3xl font-bold text-gray-50">{overview?.totalSolved ?? 0}</p>
        </div>
        <div className="card">
          <p className="card-header">Total contests</p>
          <p className="text-3xl font-bold text-gray-50">{overview?.totalContests ?? 0}</p>
        </div>
        <div className="card">
          <p className="card-header">Consistency</p>
          <p className="text-3xl font-bold text-teal-400">
            {(overview?.overallConsistency ?? 0).toFixed(0)}%
          </p>
        </div>
      </div>
    </div>
  )
}

function TopicsTab() {
  const { data: topics, isLoading } = useTopicAnalytics()
  const { data: basis } = useSkillBasis()
  if (isLoading) return <LoadingCard />

  return (
    <div className="space-y-6">
      <SkillBasisPanel />
      {!topics?.length
        ? <EmptyCard text="No topic data yet — sync a platform first" />
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
      <div className="card">
        <p className="text-sm font-medium text-gray-300 mb-1 flex items-center gap-1.5">
          <Target size={14} className="text-indigo-400" /> Rating by topic
        </p>
        <p className="text-xs text-gray-500 mb-4">
          In Codeforces points. A topic is marked a strength or a weak spot only when it sits
          further from {base ?? 'your rating'} than its own uncertainty; most topics sit close to
          your overall rating, and saying otherwise would be inventing a difference.
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
            <Bar dataKey="span" radius={[0, 4, 4, 0]}>
              {chart.map((t, i) => (
                <Cell key={i} fill={READING_COLOR[readSkill(t, base).kind]} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>

      <div className="card overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
              <th className="pb-2 font-medium">Topic</th>
              <th className="pb-2 font-medium">Rating</th>
              <th className="pb-2 font-medium">Reading</th>
              {fromRounds && (
                <th className="pb-2 font-medium" title="Problems from your rated rounds behind the rating">
                  Round problems
                </th>
              )}
              <th className="pb-2 font-medium">Solved</th>
              <th className="pb-2 font-medium">Hardest solved</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(t => {
              const r = readSkill(t, base)
              return (
                <tr key={t.topic} className="border-b border-gray-800/50 last:border-0">
                  <td className="py-2.5 text-gray-200 font-medium">{t.topic}</td>
                  <td className="py-2.5 tabular-nums text-gray-200">
                    {fmtRating(t.skillRating)}
                    <span className="text-gray-500 text-xs"> ± {Math.round(t.skillSd ?? 0)}</span>
                  </td>
                  <td className="py-2.5 text-xs">
                    {r.kind === 'strength' && <span className="badge badge-green">
                      Strength +{Math.round(r.offset)}</span>}
                    {r.kind === 'weak' && <span className="badge badge-red">
                      Weak spot {Math.round(r.offset)}</span>}
                    {r.kind === 'in-line' && <span className="text-gray-500">In line with your rating</span>}
                  </td>
                  {fromRounds && (
                    <td className="py-2.5 text-gray-400 text-xs tabular-nums">{t.skillProblems ?? 0}</td>
                  )}
                  <td className="py-2.5 text-gray-400 text-xs tabular-nums">{t.problemsSolved ?? 0}</td>
                  <td className="py-2.5 text-gray-400 text-xs tabular-nums">{fmtRating(t.maxSolvedRating)}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
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
  if (!rows.length) return <EmptyCard text="Nothing practised yet — sync a platform first" />

  return (
    <>
      <div className="card">
        <p className="text-sm font-medium text-gray-300 mb-1 flex items-center gap-1.5">
          <Target size={14} className="text-indigo-400" /> What you have practised
        </p>
        <p className="text-xs text-gray-500 mb-4">
          Problems solved per topic. Practice says how much you have done, not how strong you
          are: nearly everything anybody submits in practice ends accepted. Take part in rated
          rounds or the gauntlet to get a rating per topic.
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
            <Bar dataKey="problemsSolved" fill="#6366f1" radius={[0, 4, 4, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>

      <div className="card overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
              <th className="pb-2 font-medium">Topic</th>
              <th className="pb-2 font-medium">Solved</th>
              <th className="pb-2 font-medium">Tried, not solved</th>
              <th className="pb-2 font-medium">Hardest solved</th>
              <th className="pb-2 font-medium">Last practised</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(t => (
              <tr key={t.topic} className="border-b border-gray-800/50 last:border-0">
                <td className="py-2.5 text-gray-200 font-medium">{t.topic}</td>
                <td className="py-2.5 text-gray-200 tabular-nums">{t.problemsSolved ?? 0}</td>
                <td className="py-2.5 text-gray-400 text-xs tabular-nums">
                  {(t.problemsAttempted ?? 0) - (t.problemsSolved ?? 0)}
                </td>
                <td className="py-2.5 text-gray-400 text-xs tabular-nums">{fmtRating(t.maxSolvedRating)}</td>
                <td className="py-2.5 text-gray-500 text-xs">
                  {t.lastPracticedAt ? new Date(t.lastPracticedAt).toLocaleDateString() : '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

const READING_COLOR: Record<SkillReading['kind'], string> = {
  strength: '#22c55e',
  weak: '#ef4444',
  'in-line': '#6366f1',
  none: '#6b7280',
}

function ContestsTab() {
  const { data: contests, isLoading } = useContestAnalytics()
  if (isLoading) return <LoadingCard />
  if (!contests?.ratingHistory?.length)
    return <EmptyCard text="No contest history — sync a platform to see data" />

  const chartData = contests.ratingHistory.map((c: any) => ({
    name: c.date ?? '',
    rating: c.ratingAfter,
    change: c.ratingChange,
    platform: c.platform,
  }))

  return (
    <div className="space-y-6">
      {/* Stats row */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="card">
          <p className="card-header">Peak rating</p>
          <p className="text-2xl font-bold text-gray-50">{contests.peakRating ?? '—'}</p>
        </div>
        <div className="card">
          <p className="card-header">Avg Δ rating</p>
          <p className={`text-2xl font-bold ${
            (contests.avgRatingChange ?? 0) >= 0 ? 'text-green-400' : 'text-red-400'
          }`}>
            {(contests.avgRatingChange ?? 0) >= 0 ? '+' : ''}
            {(contests.avgRatingChange ?? 0).toFixed(1)}
          </p>
        </div>
        <div className="card">
          <p className="card-header">Avg wrong subs</p>
          <p className="text-2xl font-bold text-amber-400">
            {(contests.avgWrongSubmissions ?? 0).toFixed(1)}
          </p>
        </div>
        <div className="card">
          <p className="card-header">Consistency</p>
          <p className="text-2xl font-bold text-teal-400">
            {(contests.consistencyScore ?? 0).toFixed(0)}%
          </p>
        </div>
      </div>

      {/* Rating history chart */}
      <div className="card">
        <p className="text-sm font-medium text-gray-300 mb-4 flex items-center gap-1.5">
          <TrendingUp size={14} className="text-indigo-400" /> Rating over time
        </p>
        <ResponsiveContainer width="100%" height={280}>
          <LineChart data={chartData} margin={{ top: 5, right: 10, bottom: 5, left: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={CHROME.grid} />
            <XAxis dataKey="name" {...axisStyle} tick={{ fill: CHROME.mutedTick, fontSize: 10 }} />
            <YAxis {...axisStyle} />
            <Tooltip
              contentStyle={tooltipStyle}
              formatter={(v: any, name: string) => [v, name === 'rating' ? 'Rating' : 'Change']}
            />
            <Line
              type="monotone" dataKey="rating"
              stroke="#6366f1" strokeWidth={2}
              dot={false} activeDot={{ r: 4 }}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>

      {/* Insights */}
      {contests.insights?.length > 0 && (
        <div className="card">
          <p className="text-sm font-medium text-gray-300 mb-3">Insights</p>
          <ul className="space-y-2">
            {contests.insights.map((insight: string, i: number) => (
              <li key={i} className="flex items-start gap-2 text-sm text-gray-400">
                <span className="text-indigo-400 mt-0.5 flex-shrink-0">→</span>
                {insight}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function LoadingCard() {
  return (
    <div className="flex items-center justify-center h-48">
      <Loader2 size={24} className="animate-spin text-indigo-400" />
    </div>
  )
}

function EmptyCard({ text }: { text: string }) {
  return (
    <div className="card text-center py-12">
      <p className="text-gray-500 text-sm">{text}</p>
    </div>
  )
}
