import { Link } from 'react-router-dom'
import type { RoadmapNodeData } from '@/api/roadmapApi'
import {
  ExternalLink, CheckCircle2, Circle, Lock, CircleDot, Code2, AlertTriangle,
} from 'lucide-react'
import { formatDistanceToNow } from 'date-fns'
import { clsx } from 'clsx'

const STATUS_LABEL: Record<string, { label: string; cls: string; icon: any }> = {
  COMPLETED:   { label: 'Completed',   cls: 'badge-green', icon: CheckCircle2 },
  IN_PROGRESS: { label: 'In progress', cls: 'badge-blue',  icon: CircleDot },
  UNLOCKED:    { label: 'Unlocked',    cls: 'badge-gray',  icon: Circle },
  LOCKED:      { label: 'Locked',      cls: 'badge-gray',  icon: Lock },
}

interface Props {
  node: RoadmapNodeData | null
  /** Every node, so prerequisites can be named and their status shown. */
  allNodes: RoadmapNodeData[]
}

function ratingColor(rating?: number | null) {
  if (!rating) return 'text-gray-500'
  if (rating < 1200) return 'text-gray-400'
  if (rating < 1400) return 'text-green-400'
  if (rating < 1600) return 'text-cyan-400'
  if (rating < 1900) return 'text-blue-400'
  if (rating < 2100) return 'text-purple-400'
  if (rating < 2400) return 'text-amber-400'
  return 'text-red-400'
}

export function NodeDetailPanel({ node, allNodes }: Props) {
  if (!node) {
    return (
      <div className="card text-center py-12">
        <p className="text-gray-500 text-sm">Pick a skill to see what to practise</p>
      </div>
    )
  }

  const status = STATUS_LABEL[node.status] ?? STATUS_LABEL.LOCKED
  const StatusIcon = status.icon
  const solvedCount = node.problems.filter(p => p.solved).length

  const byKey = new Map(allNodes.map(n => [n.nodeKey, n]))
  const prereqs = node.prereqKeys
    .map(k => byKey.get(k))
    .filter((n): n is RoadmapNodeData => !!n)
  const blocking = prereqs.filter(p => p.status !== 'COMPLETED' && p.masteryScore < 35)

  return (
    <div className="card space-y-4">
      <div>
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <h3 className="text-base font-medium text-gray-100">{node.topic}</h3>
            <p className="text-xs text-gray-500 mt-0.5">
              {node.track} · rated {node.minDifficulty}–{node.maxDifficulty}
            </p>
          </div>
          <span className={clsx('badge flex items-center gap-1 flex-shrink-0', status.cls)}>
            <StatusIcon size={11} /> {status.label}
          </span>
        </div>
        {node.blurb && (
          <p className="text-xs text-gray-400 mt-2 leading-relaxed">{node.blurb}</p>
        )}
      </div>

      {/* Mastery in this node specifically. It used to be impossible to show anything here:
          mastery was only ever stored per coarse topic, so every node under "Dynamic
          Programming" would have reported the same number. */}
      <div className="grid grid-cols-3 gap-2 text-center">
        <Stat label="Mastery" value={`${Math.round(node.masteryScore)}%`} tone="text-indigo-300" />
        <Stat
          label="Confidence"
          value={`${Math.round(node.confidenceScore)}%`}
          tone="text-gray-300"
          hint={node.problemsAttempted < 13 ? 'Small sample so far' : undefined}
        />
        <Stat
          label="Decayed"
          value={`${Math.round(node.decayScore)}%`}
          tone={node.decayScore > 15 ? 'text-amber-400' : 'text-gray-300'}
        />
      </div>

      <div className="flex items-center justify-between text-xs text-gray-500">
        <span>{node.problemsSolved} solved of {node.problemsAttempted} attempted</span>
        {node.lastPracticedAt && (
          <span>
            last {formatDistanceToNow(new Date(node.lastPracticedAt), { addSuffix: true })}
          </span>
        )}
      </div>

      {blocking.length > 0 && (
        <div className="rounded-lg border border-gray-800 bg-gray-900/60 px-3 py-2.5">
          <p className="flex items-center gap-1.5 text-xs font-medium text-amber-400 mb-1.5">
            <AlertTriangle size={12} /> Build these first
          </p>
          <ul className="space-y-1">
            {blocking.map(p => (
              <li key={p.nodeKey} className="text-xs text-gray-400 flex justify-between gap-2">
                <span className="truncate">{p.topic}</span>
                <span className="text-gray-600 tabular-nums flex-shrink-0">
                  {Math.round(p.masteryScore)}% / 35%
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {node.problems.length > 0 ? (
        <div>
          <div className="flex items-center justify-between mb-2">
            <p className="text-xs text-gray-400">Practice problems</p>
            <p className="text-xs text-gray-500">
              {solvedCount}/{node.problems.length} solved
            </p>
          </div>
          <div className="space-y-1.5">
            {node.problems.map(p => (
              <div
                key={`${p.contestId}${p.index}`}
                className={clsx(
                  'group flex items-center gap-2 rounded-lg px-2.5 py-2 text-sm transition-colors',
                  p.solved
                    ? 'bg-green-950/20 hover:bg-green-950/30'
                    : 'bg-gray-800/60 hover:bg-gray-800'
                )}
              >
                {/* The whole row opens the workspace. This link used to point at
                    codeforces.com with target="_blank", which sent the user out of the app at
                    the exact moment they decided to practise - past the editor, the local
                    runner and the sample console built for this. */}
                <Link
                  to={p.practicePath ?? '/practice'}
                  className="flex items-center gap-2 min-w-0 flex-1"
                  title={`Open ${p.name} in the practice workspace`}
                >
                  <Code2
                    size={12}
                    className="flex-shrink-0 text-gray-600 group-hover:text-indigo-400"
                  />
                  <span className={clsx(
                    'truncate',
                    p.solved ? 'text-green-300' : 'text-gray-300'
                  )}>
                    {p.name}
                  </span>
                </Link>

                <span className={clsx(
                  'text-xs tabular-nums flex-shrink-0', ratingColor(p.rating)
                )}>
                  {p.rating ?? '—'}
                </span>

                {p.judgeUrl && (
                  <a
                    href={p.judgeUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={e => e.stopPropagation()}
                    className="flex-shrink-0 text-gray-600 hover:text-gray-300"
                    title="Open on codeforces.com instead"
                  >
                    <ExternalLink size={11} />
                  </a>
                )}
              </div>
            ))}
          </div>
        </div>
      ) : node.status === 'LOCKED' ? (
        <p className="text-sm text-gray-500 text-center py-4">
          Problems appear once this skill unlocks.
        </p>
      ) : (
        <p className="text-sm text-gray-500 text-center py-4">
          No problems matched this skill's tags and rating band. The Codeforces problemset cache
          may still be warming up.
        </p>
      )}
    </div>
  )
}

function Stat({ label, value, tone, hint }: {
  label: string; value: string; tone: string; hint?: string
}) {
  return (
    <div className="rounded-lg bg-gray-900/60 border border-gray-800 py-2" title={hint}>
      <p className={clsx('text-lg font-semibold tabular-nums', tone)}>{value}</p>
      <p className="text-[10px] uppercase tracking-wider text-gray-600">{label}</p>
    </div>
  )
}
