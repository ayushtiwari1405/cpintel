import { useEffect, useMemo, useRef } from 'react'
import { clsx } from 'clsx'
import { GitFork } from 'lucide-react'
import { EmptyState } from '@/components/common/States'
import { brief, type CallNode, type Trace } from '@/visualiser/trace'

const NODE_W = 112
const NODE_H = 46
const GAP_X = 14
const GAP_Y = 34

interface Placed { node: CallNode; x: number; y: number }

/**
 * The calls made so far, as a tree: who called whom, with what, and what came back. Calls on
 * the stack right now are outlined, the executing one is filled, finished ones show their
 * return value, and calls that have not happened yet are not drawn — so playing the trace
 * grows the tree the way the recursion actually unfolds. Clicking a call jumps to it.
 */
export function RecursionView({ tree, trace, step, onStep, zoom }: {
  tree: CallNode | null
  trace: Trace
  step: number
  onStep: (i: number) => void
  zoom: number
}) {
  const active = useMemo(() => new Set(trace.steps[step]?.frames.map(f => f.id) ?? []), [trace, step])
  const top = trace.steps[step]?.frames[trace.steps[step].frames.length - 1]?.id

  const { placed, edges, width, height } = useMemo(() => layout(tree, step), [tree, step])

  // Keeps the running call on screen as the tree outgrows the panel.
  const scroller = useRef<HTMLDivElement>(null)
  const running = placed.find(p => p.node.frameId === top)
  useEffect(() => {
    const el = scroller.current
    if (!el || !running) return
    const pad = 16
    const x = (running.x + pad) * zoom
    const y = (running.y + pad) * zoom
    const w = NODE_W * zoom
    const h = NODE_H * zoom
    const outside = x < el.scrollLeft || x + w > el.scrollLeft + el.clientWidth
      || y < el.scrollTop || y + h > el.scrollTop + el.clientHeight
    if (outside) {
      el.scrollTo({ left: x - el.clientWidth / 2 + w / 2, top: y - el.clientHeight / 2 + h / 2,
        behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
    }
  }, [running, zoom])

  if (!tree || tree.children.length === 0) {
    return (
      <EmptyState icon={<GitFork size={18} />} title="No function calls yet"
        className="h-full justify-center">
        The tree fills in as the program calls functions. Recursive ones (fib, DFS, merge sort)
        are the most interesting to watch.
      </EmptyState>
    )
  }

  const heap = trace.steps[step]?.heap
  return (
    <div ref={scroller} className="h-full overflow-auto">
      <div style={{ zoom }} className="p-4">
        <svg width={width} height={height} role="img" aria-label="Recursion tree">
          {edges.map(([a, b]) => (
            <line key={`${a.node.frameId}-${b.node.frameId}`}
              x1={a.x + NODE_W / 2} y1={a.y + NODE_H} x2={b.x + NODE_W / 2} y2={b.y}
              strokeWidth={1.25}
              className={active.has(b.node.frameId) ? 'stroke-accent-400' : 'stroke-gray-700'} />
          ))}
          {placed.map(({ node, x, y }) => {
            const onStack = active.has(node.frameId)
            const done = node.exit < step && !onStack
            const isTop = node.frameId === top
            const args = node.parent ? node.args.map(([, v]) => brief(v, heap)).join(', ') : ''
            return (
              <g key={node.frameId} transform={`translate(${x},${y})`} className="cursor-pointer"
                onClick={() => onStep(node.enter)} role="button" tabIndex={0}
                aria-label={`${node.name}(${args}) ${done && node.ret ? `returned ${brief(node.ret)}` : ''}`}
                onKeyDown={e => { if (e.key === 'Enter') onStep(node.enter) }}>
                <title>{`${node.name}(${args})${node.ret ? ` → ${brief(node.ret)}` : ''}\nClick to jump to this call`}</title>
                <rect width={NODE_W} height={NODE_H} rx={7}
                  className={clsx(
                    isTop ? 'fill-accent-600 stroke-accent-400'
                      : onStack ? 'fill-gray-900 stroke-accent-400'
                      : 'fill-gray-900 stroke-gray-700',
                  )} strokeWidth={onStack ? 1.5 : 1} />
                <text x={NODE_W / 2} y={18} textAnchor="middle"
                  className={clsx('font-mono text-[11px]', isTop ? 'fill-white' : 'fill-gray-200')}>
                  {clip(node.parent ? `${node.name}(${args})` : node.name, 16)}
                </text>
                <text x={NODE_W / 2} y={35} textAnchor="middle"
                  className={clsx('font-mono text-[10px]',
                    isTop ? 'fill-accent-100' : done ? 'fill-green-300' : 'fill-gray-500')}>
                  {done && node.ret ? clip(`→ ${brief(node.ret)}`, 17)
                    : onStack ? (isTop ? 'running' : 'waiting') : ''}
                </text>
              </g>
            )
          })}
        </svg>
      </div>
    </div>
  )
}

const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, n - 1)}…` : s)

/**
 * A simple tidy layout: leaves take consecutive columns in call order, a parent sits centred
 * over its children. Only calls that have begun by `step` take part, so the tree grows.
 */
function layout(tree: CallNode | null, step: number) {
  const placed: Placed[] = []
  const edges: [Placed, Placed][] = []
  if (!tree) return { placed, edges, width: 0, height: 0 }
  let column = 0
  let maxDepth = 0
  const visit = (node: CallNode, d: number): Placed => {
    maxDepth = Math.max(maxDepth, d)
    const kids = node.children.filter(c => c.enter <= step)
    let x: number
    const placedKids = kids.map(k => visit(k, d + 1))
    if (placedKids.length === 0) {
      x = column * (NODE_W + GAP_X)
      column++
    } else {
      x = (placedKids[0].x + placedKids[placedKids.length - 1].x) / 2
    }
    const me = { node, x, y: d * (NODE_H + GAP_Y) }
    placed.push(me)
    for (const k of placedKids) edges.push([me, k])
    return me
  }
  visit(tree, 0)
  return {
    placed, edges,
    width: Math.max(1, column) * (NODE_W + GAP_X),
    height: (maxDepth + 1) * (NODE_H + GAP_Y),
  }
}
