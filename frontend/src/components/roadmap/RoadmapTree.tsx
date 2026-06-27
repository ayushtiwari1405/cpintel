import { useMemo, useState } from 'react'
import type { RoadmapNodeData } from '@/api/roadmapApi'
import { Lock, CircleDot, CheckCircle2, Circle } from 'lucide-react'
import { clsx } from 'clsx'

const STATUS_STYLE: Record<string, { bg: string; border: string; text: string; icon: any }> = {
  COMPLETED:   { bg: 'bg-green-950/40',  border: 'border-green-700',  text: 'text-green-300',  icon: CheckCircle2 },
  IN_PROGRESS: { bg: 'bg-indigo-950/40', border: 'border-indigo-600', text: 'text-indigo-300', icon: CircleDot },
  UNLOCKED:    { bg: 'bg-gray-900',      border: 'border-gray-600',   text: 'text-gray-200',   icon: Circle },
  LOCKED:      { bg: 'bg-gray-950',      border: 'border-gray-800',   text: 'text-gray-600',   icon: Lock },
}

const NODE_W = 168
const NODE_H = 56
const COL_GAP = 48
const ROW_GAP = 18

interface Props {
  nodes: RoadmapNodeData[]
  selectedKey: string | null
  onSelect: (key: string) => void
}

function computeDepth(nodes: RoadmapNodeData[]): Map<string, number> {
  const byKey = new Map(nodes.map(n => [n.nodeKey, n]))
  const memo = new Map<string, number>()

  function depth(key: string, seen: Set<string>): number {
    if (memo.has(key)) return memo.get(key)!
    if (seen.has(key)) return 0
    const node = byKey.get(key)
    if (!node || node.prereqKeys.length === 0) {
      memo.set(key, 0)
      return 0
    }
    seen.add(key)
    const d = 1 + Math.max(
      0,
      ...node.prereqKeys
        .filter(p => byKey.has(p))
        .map(p => depth(p, seen))
    )
    memo.set(key, d)
    return d
  }

  nodes.forEach(n => depth(n.nodeKey, new Set()))
  return memo
}

export function RoadmapTree({ nodes, selectedKey, onSelect }: Props) {
  const { positions, columns, width, height } = useMemo(() => {
    const depthMap = computeDepth(nodes)
    const byCol = new Map<number, RoadmapNodeData[]>()
    nodes.forEach(n => {
      const d = depthMap.get(n.nodeKey) ?? 0
      if (!byCol.has(d)) byCol.set(d, [])
      byCol.get(d)!.push(n)
    })

    const cols = [...byCol.keys()].sort((a, b) => a - b)
    const positions = new Map<string, { x: number; y: number }>()
    let maxRows = 0

    cols.forEach((col, colIdx) => {
      const items = byCol.get(col)!.sort((a, b) => a.orderIndex - b.orderIndex)
      maxRows = Math.max(maxRows, items.length)
      items.forEach((n, rowIdx) => {
        positions.set(n.nodeKey, {
          x: colIdx * (NODE_W + COL_GAP),
          y: rowIdx * (NODE_H + ROW_GAP),
        })
      })
    })

    return {
      positions,
      columns: cols.length,
      width: cols.length * (NODE_W + COL_GAP) - COL_GAP,
      height: maxRows * (NODE_H + ROW_GAP) - ROW_GAP,
    }
  }, [nodes])

  const byKey = useMemo(() => new Map(nodes.map(n => [n.nodeKey, n])), [nodes])

  return (
    <div className="overflow-x-auto pb-4">
      <div
        className="relative"
        style={{ width: Math.max(width, 600), height: Math.max(height, 200), minWidth: '100%' }}
      >
        <svg
          className="absolute top-0 left-0 pointer-events-none"
          width={width}
          height={height}
          style={{ overflow: 'visible' }}
        >
          {nodes.flatMap(n =>
            n.prereqKeys
              .filter(p => byKey.has(p) && positions.has(p) && positions.has(n.nodeKey))
              .map(p => {
                const from = positions.get(p)!
                const to = positions.get(n.nodeKey)!
                const x1 = from.x + NODE_W
                const y1 = from.y + NODE_H / 2
                const x2 = to.x
                const y2 = to.y + NODE_H / 2
                const midX = (x1 + x2) / 2
                const isActive =
                  byKey.get(p)?.status === 'COMPLETED' &&
                  n.status !== 'LOCKED'
                return (
                  <path
                    key={`${p}-${n.nodeKey}`}
                    d={`M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${x2} ${y2}`}
                    fill="none"
                    stroke={isActive ? '#4f46e5' : '#374151'}
                    strokeWidth={1.5}
                  />
                )
              })
          )}
        </svg>

        {nodes.map(n => {
          const pos = positions.get(n.nodeKey)
          if (!pos) return null
          const style = STATUS_STYLE[n.status] ?? STATUS_STYLE.LOCKED
          const Icon = style.icon
          const isSelected = selectedKey === n.nodeKey
          const solvedCount = n.problems.filter(p => p.solved).length

          return (
            <button
              key={n.nodeKey}
              onClick={() => n.status !== 'LOCKED' && onSelect(n.nodeKey)}
              disabled={n.status === 'LOCKED'}
              className={clsx(
                'absolute rounded-lg border px-3 py-2 text-left transition-all',
                style.bg, style.border,
                n.status !== 'LOCKED' && 'hover:scale-[1.03] cursor-pointer',
                n.status === 'LOCKED' && 'cursor-not-allowed opacity-70',
                isSelected && 'ring-2 ring-indigo-400'
              )}
              style={{ left: pos.x, top: pos.y, width: NODE_W, height: NODE_H }}
            >
              <div className="flex items-center gap-1.5 mb-0.5">
                <Icon size={13} className={style.text} />
                <span className={clsx('text-xs font-medium truncate', style.text)}>
                  {n.topic}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-[10px] text-gray-500">
                  {n.minDifficulty}-{n.maxDifficulty}
                </span>
                {n.problems.length > 0 && (
                  <span className="text-[10px] text-gray-500">
                    {solvedCount}/{n.problems.length}
                  </span>
                )}
              </div>
            </button>
          )
        })}
      </div>
    </div>
  )
}
