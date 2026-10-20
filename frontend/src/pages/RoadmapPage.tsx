import { useState, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { useGauntlet, useRoadmap, useRegenerateRoadmap } from '@/hooks/useRoadmap'
import { RoadmapTree } from '@/components/roadmap/RoadmapTree'
import { NodeDetailPanel } from '@/components/roadmap/NodeDetailPanel'
import { ArrowRight, RefreshCw, Swords, Map as MapIcon } from 'lucide-react'
import { PageHeader } from '@/components/common/PageHeader'
import { Stat, Stats } from '@/components/common/Stat'
import { EmptyState, ErrorState, LoadingState } from '@/components/common/States'

export default function RoadmapPage() {
  const { data: nodes = [], isLoading, isError, error, refetch } = useRoadmap()
  const regenerate = useRegenerateRoadmap()
  const { data: gauntlet } = useGauntlet()
  const placement = gauntlet?.lastResult ?? null
  const [selectedKey, setSelectedKey] = useState<string | null>(null)

  const selectedNode = useMemo(
    () => nodes.find(n => n.nodeKey === selectedKey) ?? null,
    [nodes, selectedKey]
  )

  const stats = useMemo(() => {
    const completed = nodes.filter(n => n.status === 'COMPLETED').length
    const inProgress = nodes.filter(n => n.status === 'IN_PROGRESS').length
    const unlocked = nodes.filter(n => n.status === 'UNLOCKED').length
    const locked = nodes.filter(n => n.status === 'LOCKED').length
    return { completed, inProgress, unlocked, locked, total: nodes.length }
  }, [nodes])

  const pct = stats.total ? Math.round((stats.completed / stats.total) * 100) : 0

  const header = (
    <PageHeader
      title="Learning roadmap"
      description={stats.total > 0
        ? <><span className="font-mono tabular-nums">{stats.total}</span> sub-skills</> : undefined}
      actions={
        <button
          type="button"
          onClick={() => regenerate.mutate()}
          disabled={regenerate.isPending}
          className="btn-secondary"
        >
          <RefreshCw size={14} className={regenerate.isPending ? 'animate-spin' : ''} aria-hidden="true" />
          {regenerate.isPending ? 'Updating…' : 'Update from progress'}
        </button>
      }
    />
  )

  if (isLoading) return <>{header}<LoadingState label="Loading your roadmap…" /></>
  if (isError) return <>{header}<ErrorState title="Could not load your roadmap" error={error} onRetry={() => refetch()} /></>

  return (
    <div className="space-y-6">
      {header}

      {/* The tree starts everyone at the first node until solve history says otherwise, which
          is the wrong first screen for anybody who can already solve things. The gauntlet is
          how they say so. */}
      <Link
        to="/roadmap/gauntlet"
        className="group flex items-center gap-4 rounded-xl border border-gray-800 bg-gray-900 p-4
                   transition-colors hover:border-accent-800 hover:bg-gray-850/60"
      >
        <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-lg border
                        border-accent-800/70 bg-accent-950/50 text-accent-300" aria-hidden="true">
          <Swords size={18} />
        </div>
        <div className="min-w-0 flex-1">
          {placement ? (
            <>
              <p className="text-sm font-medium text-gray-200">
                Placed at <span className="font-mono tabular-nums text-accent-300">~{placement.overallRating}</span> by the gauntlet
              </p>
              <p className="text-xs text-gray-500">
                Taken {new Date(placement.takenAt).toLocaleDateString()} · a retake only moves you forward
              </p>
            </>
          ) : (
            <>
              <p className="text-sm font-medium text-gray-200">
                Not a beginner? Take the gauntlet
              </p>
              <p className="text-xs text-gray-500">
                Ten minutes, six areas — start the roadmap at your level
              </p>
            </>
          )}
        </div>
        <ArrowRight size={16} aria-hidden="true"
          className="flex-shrink-0 text-gray-500 transition-transform group-hover:translate-x-0.5 group-hover:text-accent-400" />
      </Link>

      <div>
        <div className="mb-2 flex items-baseline justify-between">
          <span id="roadmap-progress" className="text-sm font-medium text-gray-300">Overall progress</span>
          <span className="font-mono text-sm font-semibold tabular-nums text-accent-400">{pct}%</span>
        </div>
        <div role="progressbar" aria-labelledby="roadmap-progress" aria-valuenow={pct}
          aria-valuemin={0} aria-valuemax={100}
          className="mb-4 h-1.5 w-full overflow-hidden rounded-full bg-gray-800">
          <div className="h-full rounded-full bg-accent-500 transition-[width] duration-500"
            style={{ width: `${pct}%` }} />
        </div>
        <Stats>
          <Stat label="Completed" value={stats.completed} tone="good" />
          <Stat label="In progress" value={stats.inProgress} tone="accent" />
          <Stat label="Unlocked" value={stats.unlocked} />
          <Stat label="Locked" value={stats.locked} className="[&>p:nth-child(2)]:text-gray-500" />
        </Stats>
      </div>

      {nodes.length === 0 ? (
        <div className="card">
          <EmptyState icon={<MapIcon size={18} />} title="No roadmap generated yet"
            action={
              <button type="button" onClick={() => regenerate.mutate()} disabled={regenerate.isPending}
                className="btn-primary">
                Generate roadmap
              </button>
            }>
            Built from the problems you have solved.
          </EmptyState>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 items-start">
          <div className="lg:col-span-2">
            <RoadmapTree
              nodes={nodes}
              selectedKey={selectedKey}
              onSelect={setSelectedKey}
            />
          </div>
          {/* The tree is now long enough to scroll well past the panel, and the panel is what
              the user is reading while they scan it. */}
          <div className="lg:sticky lg:top-4">
            <NodeDetailPanel node={selectedNode} allNodes={nodes} />
          </div>
        </div>
      )}

      <p className="text-center text-xs text-gray-500">
        Skills unlock when their prerequisites reach 35% mastery and complete at 75% with enough attempts.
      </p>
    </div>
  )
}
