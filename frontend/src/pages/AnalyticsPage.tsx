import { useState } from 'react'
import {
  useOverview, useTopicAnalytics, useContestAnalytics,
  useRefreshAnalytics
} from '@/hooks/useAnalytics'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, BarChart, Bar,
  Cell, Legend
} from 'recharts'
import { RefreshCw, Loader2, TrendingUp, Target, Award } from 'lucide-react'
import { tooltipStyle, axisStyle, PLATFORM_COLORS, MASTERY_COLORS } from '@/charts/ChartTheme'
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
          <h1 className="text-2xl font-semibold text-white">Analytics</h1>
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
            <p className="text-2xl font-bold text-white">{p.currentRating ?? '—'}</p>
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
          <p className="text-3xl font-bold text-white">{overview?.totalSolved ?? 0}</p>
        </div>
        <div className="card">
          <p className="card-header">Total contests</p>
          <p className="text-3xl font-bold text-white">{overview?.totalContests ?? 0}</p>
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
  if (isLoading) return <LoadingCard />
  if (!topics?.length) return <EmptyCard text="No topic data yet — sync a platform first" />

  const barData = [...topics]
    .sort((a: any, b: any) => b.masteryScore - a.masteryScore)

  return (
    <div className="space-y-6">
      {/* Bar chart */}
      <div className="card">
        <p className="text-sm font-medium text-gray-300 mb-4 flex items-center gap-1.5">
          <Target size={14} className="text-indigo-400" /> Mastery by topic
        </p>
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={barData} layout="vertical"
            margin={{ top: 0, right: 20, bottom: 0, left: 100 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" horizontal={false} />
            <XAxis type="number" domain={[0, 100]} {...axisStyle} />
            <YAxis type="category" dataKey="topic" width={95}
              tick={{ fill: '#9ca3af', fontSize: 11 }} />
            <Tooltip
              contentStyle={tooltipStyle}
              formatter={(v: any) => [`${Number(v).toFixed(1)}%`, 'Mastery']}
            />
            <Bar dataKey="masteryScore" radius={[0, 4, 4, 0]}>
              {barData.map((entry: any, i: number) => (
                <Cell key={i} fill={MASTERY_COLORS[entry.masteryBand] ?? '#6366f1'} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
        {/* Legend */}
        <div className="flex gap-4 mt-3 justify-center flex-wrap">
          {Object.entries(MASTERY_COLORS).map(([band, color]) => (
            <div key={band} className="flex items-center gap-1.5 text-xs text-gray-400">
              <div className="w-2.5 h-2.5 rounded-full" style={{ background: color }} />
              {band}
            </div>
          ))}
        </div>
      </div>

      {/* Topic table */}
      <div className="card overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
              <th className="pb-2 font-medium">Topic</th>
              <th className="pb-2 font-medium">Mastery</th>
              <th className="pb-2 font-medium">Confidence</th>
              <th className="pb-2 font-medium">Decay</th>
              <th className="pb-2 font-medium">Solved</th>
              <th className="pb-2 font-medium">Band</th>
            </tr>
          </thead>
          <tbody>
            {barData.map((t: any) => (
              <tr key={t.topic} className="border-b border-gray-800/50 last:border-0">
                <td className="py-2.5 text-gray-200 font-medium">{t.topic}</td>
                <td className="py-2.5">
                  <div className="flex items-center gap-2">
                    <div className="w-20 bg-gray-800 rounded-full h-1.5">
                      <div className="h-1.5 rounded-full"
                        style={{
                          width: `${t.masteryScore}%`,
                          background: MASTERY_COLORS[t.masteryBand]
                        }} />
                    </div>
                    <span className="text-gray-300 text-xs w-9">
                      {(t.masteryScore ?? 0).toFixed(0)}%
                    </span>
                  </div>
                </td>
                <td className="py-2.5 text-gray-400 text-xs">
                  {(t.confidenceScore ?? 0).toFixed(0)}%
                </td>
                <td className="py-2.5 text-xs">
                  <span className={t.decayScore > 15 ? 'text-red-400' : 'text-gray-500'}>
                    {(t.decayScore ?? 0).toFixed(0)}%
                  </span>
                </td>
                <td className="py-2.5 text-gray-400 text-xs">{t.problemsSolved ?? 0}</td>
                <td className="py-2.5">
                  <span className={`badge text-xs ${
                    t.masteryBand === 'STRONG'   ? 'badge-green' :
                    t.masteryBand === 'MODERATE' ? 'badge-yellow' :
                    t.masteryBand === 'WEAK'     ? 'badge-red' : 'badge-gray'
                  }`}>
                    {t.masteryBand}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
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
          <p className="text-2xl font-bold text-white">{contests.peakRating ?? '—'}</p>
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
            <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
            <XAxis dataKey="name" {...axisStyle} tick={{ fill: '#6b7280', fontSize: 10 }} />
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
