import { useQuery } from '@tanstack/react-query'
import { userApi } from '@/api/userApi'
import { useAuth } from '@/contexts/AuthContext'
import { useRefreshAnalytics } from '@/hooks/useAnalytics'
import { useEffect } from 'react'
import {
  Zap, Target, Award, RefreshCw,
  Loader2, Link2, ChevronRight
} from 'lucide-react'
import { Link } from 'react-router-dom'
import {
  RadarChart, Radar, PolarGrid, PolarAngleAxis,
  ResponsiveContainer, Tooltip
} from 'recharts'
import { tooltipStyle } from '@/charts/ChartTheme'

export default function DashboardPage() {
  const { user } = useAuth()
  const refresh = useRefreshAnalytics()

  const { data: dashboard, isLoading } = useQuery({
    queryKey: ['dashboard'],
    queryFn: () => userApi.getDashboard().then(r => r.data),
    staleTime: 0,
    refetchOnMount: true,
  })
  useEffect(() => {
    refresh.mutate()
  }, [])

  const platforms = dashboard?.user?.platforms ?? []
  const score = dashboard?.unifiedScore
  const topics = dashboard?.topTopics ?? []
  const contests = dashboard?.recentContests ?? []

  const radarData = topics.slice(0, 7).map((t: any) => ({
    topic: t.topic.length > 10 ? t.topic.slice(0, 10) + '…' : t.topic,
    mastery: Math.round(t.masteryScore ?? 0),
  }))

  if (isLoading) return (
    <div className="flex items-center justify-center h-64">
      <Loader2 size={24} className="animate-spin text-indigo-400" />
    </div>
  )

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-white">
            Welcome back, {user?.username} 👋
          </h1>
          <p className="text-gray-400 text-sm mt-0.5">Your CP intelligence overview</p>
        </div>
        <button
          onClick={() => refresh.mutate()}
          disabled={refresh.isPending}
          className="btn-secondary flex items-center gap-2 text-sm"
        >
          <RefreshCw size={14} className={refresh.isPending ? 'animate-spin' : ''} />
          Refresh
        </button>
      </div>

      {/* Score row */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="card">
          <div className="flex items-center gap-1.5 mb-1">
            <p className="card-header mb-0">Unified score</p>
            <div className="group relative">
              <span className="text-gray-600 text-xs cursor-help border border-gray-700 
                       rounded-full w-3.5 h-3.5 flex items-center justify-center 
                       hover:border-gray-500">?</span>
              <div className="absolute left-0 top-5 z-10 hidden group-hover:block 
                      bg-gray-800 border border-gray-700 rounded-lg p-3 
                      w-56 text-xs text-gray-300 shadow-xl">
                <p className="font-medium text-white mb-1">How it's calculated</p>
                <p>Normalizes each platform's rating to a 0–1000 scale, then blends them:</p>
                <ul className="mt-1.5 space-y-0.5 text-gray-400">
                  <li>Codeforces — 40%</li>
                  <li>LeetCode — 35%</li>
                  <li>CodeChef — 25%</li>
                </ul>
                <p className="mt-1.5 text-gray-500">Only linked platforms contribute.</p>
              </div>
            </div>
          </div>
          <p className="text-3xl font-bold text-indigo-400">
            {score?.unifiedScore != null ? score.unifiedScore.toFixed(0) : '—'}
          </p>
          <p className="text-xs text-gray-500 mt-1">
            {platforms.length === 0
              ? 'Link accounts to compute'
              : `Based on ${platforms.length} platform${platforms.length > 1 ? 's' : ''}`}
          </p>
        </div>
        {['CODEFORCES', 'LEETCODE', 'CODECHEF'].map(p => {
          const acc = platforms.find((a: any) => a.platform === p)
          return (
            <div key={p} className="card">
              <p className="card-header">{p.charAt(0) + p.slice(1).toLowerCase()}</p>
              {acc ? (
                <>
                  <p className="text-2xl font-semibold text-white">
                    {acc.currentRating ?? '—'}
                  </p>
                  {acc.maxRating && (
                    <p className="text-xs text-gray-500 mt-1">Peak {acc.maxRating}</p>
                  )}
                </>
              ) : (
                <>
                  <p className="text-2xl font-semibold text-gray-600">—</p>
                  <Link to="/platforms"
                    className="text-xs text-indigo-400 mt-1 flex items-center gap-0.5 hover:underline">
                    <Link2 size={10} /> Link account
                  </Link>
                </>
              )}
            </div>
          )
        })}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Radar */}
        {radarData.length > 0 && (
          <div className="card">
            <div className="flex items-center justify-between mb-4">
              <p className="text-sm font-medium text-gray-300 flex items-center gap-1.5">
                <Target size={14} className="text-indigo-400" /> Topic mastery
              </p>
              <Link to="/analytics"
                className="text-xs text-indigo-400 flex items-center gap-0.5 hover:underline">
                Full breakdown <ChevronRight size={12} />
              </Link>
            </div>
            <ResponsiveContainer width="100%" height={220}>
              <RadarChart data={radarData}>
                <PolarGrid stroke="#1f2937" />
                <PolarAngleAxis dataKey="topic" tick={{ fill: '#9ca3af', fontSize: 11 }} />
                <Radar dataKey="mastery" stroke="#6366f1" fill="#6366f1" fillOpacity={0.25} />
                <Tooltip
                  contentStyle={tooltipStyle}
                  formatter={(v: any) => [`${v}%`, 'Mastery']}
                />
              </RadarChart>
            </ResponsiveContainer>
          </div>
        )}

        {/* Recent contests */}
        <div className="card">
          <div className="flex items-center justify-between mb-4">
            <p className="text-sm font-medium text-gray-300 flex items-center gap-1.5">
              <Award size={14} className="text-indigo-400" /> Recent contests
            </p>
            <Link to="/analytics"
              className="text-xs text-indigo-400 flex items-center gap-0.5 hover:underline">
              See all <ChevronRight size={12} />
            </Link>
          </div>
          {contests.length === 0 ? (
            <p className="text-gray-500 text-sm text-center py-8">
              No contest history — sync a platform to see data
            </p>
          ) : (
            <div className="space-y-3">
              {contests.map((c: any) => (
                <div key={c.contestId}
                  className="flex items-center justify-between py-2 border-b border-gray-800 last:border-0">
                  <div className="min-w-0 flex-1">
                    <p className="text-sm text-gray-200 truncate">{c.contestName}</p>
                    <p className="text-xs text-gray-500">{c.platform} · Rank {c.rank ?? '?'}</p>
                  </div>
                  <div className="flex flex-col items-end ml-3 flex-shrink-0">
                    <span className={`text-sm font-semibold ${(c.ratingChange ?? 0) >= 0 ? 'text-green-400' : 'text-red-400'
                      }`}>
                      {(c.ratingChange ?? 0) >= 0 ? '+' : ''}{c.ratingChange ?? 0}
                    </span>
                    <span className="text-xs text-gray-600">
                      {c.problemsSolved}/{c.totalProblems} solved
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Quick links */}
      {platforms.length === 0 && (
        <div className="card border-dashed border-indigo-800 bg-indigo-950/20 text-center py-10">
          <Zap size={24} className="text-indigo-400 mx-auto mb-3" />
          <p className="text-gray-300 font-medium mb-1">Get started with CPIntel</p>
          <p className="text-gray-500 text-sm mb-4">
            Link your Codeforces, LeetCode, or CodeChef account to start tracking
          </p>
          <Link to="/platforms" className="btn-primary inline-flex items-center gap-2">
            <Link2 size={14} /> Link a platform
          </Link>
        </div>
      )}
    </div>
  )
}
