import React from 'react'
import type { RoadmapNodeData } from '@/api/roadmapApi'
import { ExternalLink, CheckCircle2, Circle, Lock, CircleDot } from 'lucide-react'
import { clsx } from 'clsx'

const STATUS_LABEL: Record<string, { label: string; cls: string; icon: any }> = {
  COMPLETED:   { label: 'Completed',   cls: 'badge-green',  icon: CheckCircle2 },
  IN_PROGRESS: { label: 'In progress', cls: 'badge-blue',    icon: CircleDot },
  UNLOCKED:    { label: 'Unlocked',    cls: 'badge-gray',    icon: Circle },
  LOCKED:      { label: 'Locked',      cls: 'badge-gray',    icon: Lock },
}

interface Props {
  node: RoadmapNodeData | null
}

export function NodeDetailPanel({ node }: Props) {
  if (!node) {
    return (
      <div className="card text-center py-12">
        <p className="text-gray-500 text-sm">Select an unlocked node to see practice problems</p>
      </div>
    )
  }

  const status = STATUS_LABEL[node.status] ?? STATUS_LABEL.LOCKED
  const StatusIcon = status.icon
  const solvedCount = node.problems.filter(p => p.solved).length

  return (
    <div className="card">
      <div className="flex items-start justify-between mb-3">
        <div>
          <h3 className="text-base font-medium text-gray-100">{node.topic}</h3>
          <p className="text-xs text-gray-500 mt-0.5">
            Difficulty {node.minDifficulty}-{node.maxDifficulty} - part of {node.parentTopic}
          </p>
        </div>
        <span className={clsx('badge flex items-center gap-1', status.cls)}>
          <StatusIcon size={11} /> {status.label}
        </span>
      </div>

      {node.problems.length > 0 ? (
        <>
          <div className="flex items-center justify-between mb-2">
            <p className="text-xs text-gray-400">Practice problems</p>
            <p className="text-xs text-gray-500">{solvedCount}/{node.problems.length} solved</p>
          </div>
          <div className="space-y-1.5">
            {node.problems.map(function renderProblem(p) {
              const problemKey = String(p.contestId) + p.index
              return React.createElement(
                'a',
                {
                  key: problemKey,
                  href: p.url,
                  target: '_blank',
                  rel: 'noopener noreferrer',
                  className: clsx(
                    'flex items-center justify-between gap-2 px-3 py-2 rounded-lg text-sm transition-colors',
                    p.solved
                      ? 'bg-green-950/20 text-green-300 hover:bg-green-950/30'
                      : 'bg-gray-800/60 text-gray-300 hover:bg-gray-800'
                  )
                },
                React.createElement('span', { className: 'truncate flex-1' }, p.name),
                React.createElement('span', { className: 'text-xs text-gray-500 flex-shrink-0' }, p.rating),
                React.createElement(ExternalLink, { size: 12, className: 'flex-shrink-0 text-gray-500' })
              )
            })}
          </div>
        </>
      ) : (
        <p className="text-sm text-gray-500 text-center py-6">
          No problems available yet - refresh the CF problemset cache or check back soon.
        </p>
      )}
    </div>
  )
}
