import { useMemo, useState } from 'react'
import type { RoadmapNodeData } from '@/api/roadmapApi'
import { Lock, CircleDot, CheckCircle2, Circle, ChevronRight } from 'lucide-react'
import { clsx } from 'clsx'

/**
 * The skill tree, at a hundred and forty nodes.
 *
 * The previous layout placed every node on one free-floating canvas, in columns by dependency
 * depth, with bezier edges drawn between them. That reads well at thirty-five nodes and not at
 * all at a hundred and forty: the deepest column held more nodes than fit on a screen, and the
 * edges crossed into an unreadable mat.
 *
 * So the structure is carried by grouping instead of by lines. Nodes sit under their track, and
 * within a track under the tier their prerequisites put them in - tier 1 needs nothing in the
 * track, tier 2 needs something from tier 1, and so on. That is the same information the edges
 * encoded, minus the crossings, and it stays legible however far the tree grows. The exact
 * prerequisites of a single node are named in the detail panel, where there is room to say
 * which ones the user has actually cleared.
 */

const STATUS_STYLE: Record<string, { dot: string; text: string; ring: string; icon: any }> = {
  COMPLETED:   { dot: 'bg-green-500',  text: 'text-green-300',  ring: 'border-green-800 bg-green-950/30',   icon: CheckCircle2 },
  IN_PROGRESS: { dot: 'bg-indigo-400', text: 'text-indigo-200', ring: 'border-indigo-700 bg-indigo-950/30', icon: CircleDot },
  UNLOCKED:    { dot: 'bg-gray-500',   text: 'text-gray-200',   ring: 'border-gray-700 bg-gray-900',        icon: Circle },
  LOCKED:      { dot: 'bg-gray-800',   text: 'text-gray-600',   ring: 'border-gray-850 bg-gray-950',        icon: Lock },
}

interface Props {
  nodes: RoadmapNodeData[]
  selectedKey: string | null
  onSelect: (key: string) => void
}

/** Depth of a node within its own track, counting only prerequisites from the same track. */
function tiersWithinTrack(nodes: RoadmapNodeData[]): Map<string, number> {
  const byKey = new Map(nodes.map(n => [n.nodeKey, n]))
  const memo = new Map<string, number>()

  function depth(key: string, seen: Set<string>): number {
    const cached = memo.get(key)
    if (cached !== undefined) return cached
    // A prerequisite cycle cannot reach here - the backend test suite rejects one - but a
    // partial response could still look like one, and recursing forever is not an option.
    if (seen.has(key)) return 0

    const node = byKey.get(key)
    if (!node) return 0

    const inTrack = node.prereqKeys.filter(p => byKey.has(p))
    if (inTrack.length === 0) {
      memo.set(key, 0)
      return 0
    }
    seen.add(key)
    const d = 1 + Math.max(...inTrack.map(p => depth(p, seen)))
    seen.delete(key)
    memo.set(key, d)
    return d
  }

  nodes.forEach(n => depth(n.nodeKey, new Set()))
  return memo
}

export function RoadmapTree({ nodes, selectedKey, onSelect }: Props) {
  const tracks = useMemo(() => {
    const order: string[] = []
    const byTrack = new Map<string, RoadmapNodeData[]>()

    for (const n of [...nodes].sort((a, b) => a.orderIndex - b.orderIndex)) {
      const track = n.track ?? 'Other'
      if (!byTrack.has(track)) {
        byTrack.set(track, [])
        order.push(track)
      }
      byTrack.get(track)!.push(n)
    }

    return order.map(track => {
      const members = byTrack.get(track)!
      const tierOf = tiersWithinTrack(members)
      const tierMap = new Map<number, RoadmapNodeData[]>()
      for (const n of members) {
        const t = tierOf.get(n.nodeKey) ?? 0
        if (!tierMap.has(t)) tierMap.set(t, [])
        tierMap.get(t)!.push(n)
      }
      const tiers = [...tierMap.keys()].sort((a, b) => a - b)
        .map(t => ({ tier: t + 1, members: tierMap.get(t)! }))

      return {
        track,
        members,
        tiers,
        completed: members.filter(n => n.status === 'COMPLETED').length,
        open: members.filter(n => n.status !== 'LOCKED').length,
      }
    })
  }, [nodes])

  // Tracks the user has any foothold in open by default; the rest stay folded so the page
  // starts as a map you can read rather than a wall of a hundred and forty tiles.
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>(() => {
    const initial: Record<string, boolean> = {}
    for (const t of tracks) initial[t.track] = t.open === 0
    return initial
  })

  const toggle = (track: string) =>
    setCollapsed(prev => ({ ...prev, [track]: !prev[track] }))

  return (
    <div className="flex flex-col gap-3">
      {tracks.map(({ track, members, tiers, completed }) => {
        const isCollapsed = collapsed[track]
        return (
          <section key={track} className="rounded-lg border border-gray-800 bg-gray-950/40">
            <button
              onClick={() => toggle(track)}
              aria-expanded={!isCollapsed}
              className="w-full flex items-center gap-2 px-3 py-2.5 text-left
                         hover:bg-gray-900/60 transition-colors rounded-lg"
            >
              <ChevronRight
                size={14}
                className={clsx('text-gray-500 transition-transform flex-shrink-0',
                  !isCollapsed && 'rotate-90')}
              />
              <span className="text-sm font-medium text-gray-200 flex-1 min-w-0 truncate">
                {track}
              </span>
              <span className="text-[11px] text-gray-500 tabular-nums flex-shrink-0">
                {completed}/{members.length} done
              </span>
              <div className="w-16 h-1 rounded-full bg-gray-800 flex-shrink-0 overflow-hidden">
                <div
                  className="h-full bg-indigo-500"
                  style={{ width: `${(completed / members.length) * 100}%` }}
                />
              </div>
            </button>

            {!isCollapsed && (
              <div className="px-3 pb-3 flex flex-col gap-3">
                {tiers.map(({ tier, members: tierMembers }) => (
                  <div key={tier} className="flex gap-3">
                    <div className="flex-shrink-0 w-12 pt-1.5">
                      <span className="text-[10px] uppercase tracking-wider text-gray-600">
                        Tier {tier}
                      </span>
                    </div>
                    <div className="flex-1 min-w-0 grid gap-1.5
                                    grid-cols-1 sm:grid-cols-2 xl:grid-cols-3">
                      {tierMembers.map(n => (
                        <NodeChip
                          key={n.nodeKey}
                          node={n}
                          selected={selectedKey === n.nodeKey}
                          onSelect={onSelect}
                        />
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </section>
        )
      })}
    </div>
  )
}

function NodeChip({ node, selected, onSelect }: {
  node: RoadmapNodeData
  selected: boolean
  onSelect: (key: string) => void
}) {
  const style = STATUS_STYLE[node.status] ?? STATUS_STYLE.LOCKED
  const Icon = style.icon
  const locked = node.status === 'LOCKED'

  return (
    <button
      onClick={() => onSelect(node.nodeKey)}
      className={clsx(
        'rounded-md border px-2.5 py-2 text-left transition-colors min-w-0',
        style.ring,
        locked ? 'opacity-60 hover:opacity-80' : 'hover:border-indigo-600',
        selected && 'ring-1 ring-indigo-400 border-indigo-500'
      )}
      // Locked nodes stay clickable: the panel is where the user finds out *what* is blocking
      // them. A disabled tile that does nothing when pressed answers no question at all.
      title={locked ? `${node.topic} - locked` : node.topic}
    >
      <div className="flex items-center gap-1.5 min-w-0">
        <Icon size={12} className={clsx('flex-shrink-0', style.text)} />
        <span className={clsx('text-xs font-medium truncate min-w-0', style.text)}>
          {node.topic}
        </span>
      </div>

      <div className="flex items-center gap-2 mt-1.5">
        <div className="flex-1 h-1 rounded-full bg-gray-800 overflow-hidden min-w-0">
          <div
            className={clsx('h-full', style.dot)}
            style={{ width: `${Math.min(100, Math.max(0, node.masteryScore))}%` }}
          />
        </div>
        <span className="text-[10px] text-gray-600 tabular-nums flex-shrink-0">
          {node.minDifficulty}-{node.maxDifficulty}
        </span>
      </div>
    </button>
  )
}
