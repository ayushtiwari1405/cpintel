import { useState, useMemo } from 'react'
import { useRoadmap, useRegenerateRoadmap } from '@/hooks/useRoadmap'
import { RoadmapTree } from '@/components/roadmap/RoadmapTree'
import { NodeDetailPanel } from '@/components/roadmap/NodeDetailPanel'
import { RefreshCw, Loader2 } from 'lucide-react'

export default function RoadmapPage() {
  const { data: nodes = [], isLoading } = useRoadmap()
  const regenerate = useRegenerateRoadmap()
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
          <h1 className="text-2xl font-semibold text-white">Learning roadmap</h1>
          <p className="text-gray-400 text-sm mt-0.5">
            {stats.total} skills mapped from Codeforces tags and your solved problems
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
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <div className="lg:col-span-2 card">
            <RoadmapTree
              nodes={nodes}
              selectedKey={selectedKey}
              onSelect={setSelectedKey}
            />
          </div>
          <div>
            <NodeDetailPanel node={selectedNode} />
          </div>
        </div>
      )}

      <p className="text-xs text-gray-600 text-center">
        Click any unlocked node to see its recommended Codeforces problems · nodes unlock once prerequisites reach sufficient mastery
      </p>
    </div>
  )
}
