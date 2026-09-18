import { Link } from 'react-router-dom'
import { Target, Check, ArrowUpRight, Loader2 } from 'lucide-react'
import { clsx } from 'clsx'
import type { RoadmapNodeData, RoadmapProblem } from '@/api/roadmapApi'
import type { ProblemSummary } from '@/types'

/**
 * What the practice workspace shows about the skill a problem was suggested for.
 *
 * <p>The roadmap used to be a dead end: its problems opened codeforces.com in a new tab and the
 * user left. Now a problem arrives here with its skill attached, which means the workspace can
 * answer the question that actually follows solving one - what next in the same skill - without
 * the user going back to the map to find out.
 */

/** A roadmap problem, in the shape the picker and the editor already speak. */
export function toProblemSummary(p: RoadmapProblem): ProblemSummary {
  return {
    contestId: p.contestId,
    index: p.index,
    name: p.name,
    rating: p.rating ?? undefined,
    tags: p.tags,
    url: p.judgeUrl ?? '',
  }
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

interface Props {
  node: RoadmapNodeData
  selected?: { contestId: number; index: string }
  onSelect: (p: ProblemSummary) => void
}

/** The banner above the statement: which skill this is, and how far along the user is. */
export function SkillBanner({ node }: { node: RoadmapNodeData }) {
  return (
    <div className="flex items-center gap-3 px-3 py-2 rounded-lg
                    border border-indigo-900/60 bg-indigo-950/25">
      <Target size={14} className="text-indigo-400 flex-shrink-0" />
      <div className="min-w-0 flex-1">
        <p className="text-xs text-gray-300 truncate">
          Practising <span className="font-medium text-indigo-200">{node.topic}</span>
          <span className="text-gray-600"> · rated {node.minDifficulty}–{node.maxDifficulty}</span>
        </p>
      </div>
      <div className="flex items-center gap-2 flex-shrink-0">
        <div className="w-16 h-1 rounded-full bg-gray-800 overflow-hidden">
          <div
            className="h-full bg-indigo-500"
            style={{ width: `${Math.min(100, Math.max(0, node.masteryScore))}%` }}
          />
        </div>
        <span className="text-[11px] text-gray-500 tabular-nums">
          {Math.round(node.masteryScore)}%
        </span>
        <Link
          to="/roadmap"
          className="text-[11px] text-gray-500 hover:text-indigo-300 flex items-center gap-0.5"
        >
          Roadmap <ArrowUpRight size={11} />
        </Link>
      </div>
    </div>
  )
}

/** The skill's own problem list, pinned above the general search. */
export function SkillProblemList({ node, selected, onSelect }: Props) {
  if (node.problems.length === 0) return null

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <p className="text-[11px] uppercase tracking-wider text-gray-500">
          More in {node.topic}
        </p>
        <p className="text-[11px] text-gray-600 tabular-nums">
          {node.problems.filter(p => p.solved).length}/{node.problems.length}
        </p>
      </div>

      <div className="rounded-lg border border-gray-800 divide-y divide-gray-800/70
                      overflow-hidden">
        {node.problems.map(p => {
          const active = selected?.contestId === p.contestId && selected?.index === p.index
          return (
            <button
              key={`${p.contestId}${p.index}`}
              onClick={() => onSelect(toProblemSummary(p))}
              className={clsx(
                'w-full flex items-center gap-2 px-2.5 py-1.5 text-left transition-colors',
                active
                  ? 'bg-indigo-600/15 border-l-2 border-l-indigo-500 pl-[8px]'
                  : 'border-l-2 border-l-transparent hover:bg-gray-900'
              )}
            >
              {p.solved
                ? <Check size={11} className="flex-shrink-0 text-green-500" />
                : <span className="w-[11px] flex-shrink-0" />}
              <span className={clsx(
                'flex-1 min-w-0 truncate text-xs',
                p.solved ? 'text-gray-500' : active ? 'text-indigo-200' : 'text-gray-300'
              )}>
                {p.name}
              </span>
              <span className={clsx(
                'text-[11px] tabular-nums flex-shrink-0', ratingColor(p.rating)
              )}>
                {p.rating ?? '—'}
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}

/**
 * What to work on when the workspace is opened cold, straight from the roadmap's own ranking.
 * An empty editor with an empty problem list answers nothing; this answers "where do I start".
 */
export function NextUpList({ nodes, isLoading, onSelect }: {
  nodes: RoadmapNodeData[] | undefined
  isLoading: boolean
  onSelect: (p: ProblemSummary) => void
}) {
  if (isLoading) {
    return (
      <div className="flex justify-center py-6">
        <Loader2 className="animate-spin text-gray-600" size={16} />
      </div>
    )
  }
  if (!nodes?.length) return null

  const withProblems = nodes.filter(n => n.problems.some(p => !p.solved)).slice(0, 3)
  if (withProblems.length === 0) return null

  return (
    <div className="space-y-2.5">
      <p className="text-[11px] uppercase tracking-wider text-gray-500">
        Suggested from your roadmap
      </p>
      {withProblems.map(node => {
        const next = node.problems.find(p => !p.solved)
        if (!next) return null
        return (
          <button
            key={node.nodeKey}
            onClick={() => onSelect(toProblemSummary(next))}
            className="w-full text-left rounded-lg border border-gray-800 bg-gray-900/50
                       px-2.5 py-2 hover:border-indigo-700 transition-colors"
          >
            <div className="flex items-center gap-2">
              <Target size={11} className="text-indigo-400 flex-shrink-0" />
              <span className="text-xs text-gray-300 truncate min-w-0 flex-1">{node.topic}</span>
              <span className="text-[10px] text-gray-600 tabular-nums flex-shrink-0">
                {Math.round(node.masteryScore)}%
              </span>
            </div>
            <div className="flex items-center gap-2 mt-1 pl-[19px]">
              <span className="text-[11px] text-gray-500 truncate min-w-0 flex-1">
                {next.name}
              </span>
              <span className={clsx(
                'text-[11px] tabular-nums flex-shrink-0', ratingColor(next.rating)
              )}>
                {next.rating ?? '—'}
              </span>
            </div>
          </button>
        )
      })}
    </div>
  )
}
