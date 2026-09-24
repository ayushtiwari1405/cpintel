import { useState, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { useGauntlet, useRoadmap, useRegenerateRoadmap } from '@/hooks/useRoadmap'
import { RoadmapTree } from '@/components/roadmap/RoadmapTree'
import { NodeDetailPanel } from '@/components/roadmap/NodeDetailPanel'
import { ArrowRight, RefreshCw, Loader2, Swords } from 'lucide-react'

export default function RoadmapPage() {
  const { data: nodes = [], isLoading } = useRoadmap()
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

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader2 size={24} className="animate-spin text-indigo-400" />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-gray-50">Learning roadmap</h1>
          <p className="text-gray-400 text-sm mt-0.5">
            {stats.total} sub-skills, unlocked by what you have actually solved
          </p>
        </div>
        <button
          onClick={() => regenerate.mutate()}
          disabled={regenerate.isPending}
          className="btn-secondary flex items-center gap-2 text-sm"
        >
          <RefreshCw size={14} className={regenerate.isPending ? 'animate-spin' : ''} />
          Update from progress
        </button>
      </div>

      {/* The tree starts everyone at the first node until solve history says otherwise, which
          is the wrong first screen for anybody who can already solve things. The gauntlet is
          how they say so. */}
      <Link
        to="/roadmap/gauntlet"
        className="card flex items-center gap-4 transition-colors hover:border-indigo-800"
      >
        <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl
                        bg-indigo-600/15 text-indigo-300">
          <Swords size={18} />
        </div>
        <div className="min-w-0 flex-1">
          {placement ? (
            <>
              <p className="text-sm font-medium text-gray-200">
                Placed at ~{placement.overallRating} by the gauntlet
              </p>
              <p className="text-xs text-gray-500">
                {new Date(placement.takenAt).toLocaleDateString()} · retake it any time — it
                only ever moves your roadmap forward
              </p>
            </>
          ) : (
            <>
              <p className="text-sm font-medium text-gray-200">
                Not a beginner? Take the gauntlet
              </p>
              <p className="text-xs text-gray-500">
                Ten minutes across six areas, and your roadmap starts at your level instead of
                at Input &amp; Output
              </p>
            </>
          )}
        </div>
        <ArrowRight size={16} className="flex-shrink-0 text-gray-600" />
      </Link>

      <div className="card">
        <div className="flex items-center justify-between mb-2">
          <span className="text-sm text-gray-300 font-medium">Overall progress</span>
          <span className="text-sm font-bold text-indigo-400">{pct}%</span>
        </div>
        <div className="w-full bg-gray-800 rounded-full h-2 mb-4">
          <div
            className="bg-indigo-500 h-2 rounded-full transition-all duration-500"
            style={{ width: `${pct}%` }}
          />
        </div>
        <div className="grid grid-cols-4 gap-3 text-center">
          {[
            { label: 'Completed',   value: stats.completed,  color: 'text-green-400' },
            { label: 'In progress', value: stats.inProgress, color: 'text-indigo-400' },
            { label: 'Unlocked',    value: stats.unlocked,   color: 'text-gray-300' },
            { label: 'Locked',      value: stats.locked,     color: 'text-gray-600' },
          ].map(s => (
            <div key={s.label}>
              <p className={`text-xl font-bold ${s.color}`}>{s.value}</p>
              <p className="text-xs text-gray-500">{s.label}</p>
            </div>
          ))}
        </div>
      </div>

      {nodes.length === 0 ? (
        <div className="card text-center py-12">
          <p className="text-gray-500 text-sm mb-3">No roadmap generated yet</p>
          <button onClick={() => regenerate.mutate()} className="btn-primary">
            Generate roadmap
          </button>
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

      <p className="text-xs text-gray-600 text-center">
        Pick any skill to see its problems and open them in the practice workspace ·
        a skill unlocks once its prerequisites reach 35% mastery, and completes at 75% with
        enough attempts behind it to trust the number
      </p>
    </div>
  )
}
