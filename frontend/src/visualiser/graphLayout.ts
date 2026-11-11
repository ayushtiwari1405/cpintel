/**
 * Where to draw a graph's nodes.
 *
 * Small graphs go on a circle, which is readable and never moves between steps. Larger ones get
 * a force-directed layout (Fruchterman–Reingold) started from that circle, so it is deterministic:
 * the same graph lands in the same place at every step, and a node does not jump while the
 * student is following a traversal. A graph that is a tree (an edge count of n - 1, connected)
 * is drawn in layers from node 0 instead, which is what a BFS/DFS tree looks like on a board.
 */
export interface GraphLayout { pos: Map<string, { x: number; y: number }>; size: number }

const PAD = 40
const cache = new Map<string, GraphLayout>()

export function layoutGraph(nodes: string[], edges: [string, string, string?][]): GraphLayout {
  const key = `${nodes.join(',')}|${edges.map(([a, b]) => `${a}-${b}`).join(',')}`
  const hit = cache.get(key)
  if (hit) return hit
  const n = nodes.length
  const result = n <= 8 ? circle(nodes) : asTree(nodes, edges) ?? force(nodes, edges)
  if (cache.size > 50) cache.clear()
  cache.set(key, result)
  return result
}

function circle(nodes: string[]): GraphLayout {
  const n = nodes.length
  const R = Math.max(70, n * 16)
  const size = 2 * R + 2 * PAD
  const pos = new Map(nodes.map((id, i) => {
    const a = (2 * Math.PI * i) / Math.max(1, n) - Math.PI / 2
    return [id, { x: size / 2 + R * Math.cos(a), y: size / 2 + R * Math.sin(a) }]
  }))
  return { pos, size }
}

/** Undirected neighbours, ignoring direction and duplicates. */
function neighbours(nodes: string[], edges: [string, string, string?][]) {
  const adj = new Map(nodes.map(id => [id, new Set<string>()]))
  for (const [a, b] of edges) {
    if (a === b || !adj.has(a) || !adj.has(b)) continue
    adj.get(a)!.add(b)
    adj.get(b)!.add(a)
  }
  return adj
}

function asTree(nodes: string[], edges: [string, string, string?][]): GraphLayout | null {
  const adj = neighbours(nodes, edges)
  const undirected = [...adj.values()].reduce((s, x) => s + x.size, 0) / 2
  if (undirected !== nodes.length - 1) return null
  const depth = new Map<string, number>([[nodes[0], 0]])
  const order: string[] = [nodes[0]]
  for (let i = 0; i < order.length; i++) {
    for (const next of [...adj.get(order[i])!].sort((x, y) => Number(x) - Number(y) || x.localeCompare(y))) {
      if (!depth.has(next)) { depth.set(next, depth.get(order[i])! + 1); order.push(next) }
    }
  }
  if (order.length !== nodes.length) return null
  const layers: string[][] = []
  for (const id of order) (layers[depth.get(id)!] ??= []).push(id)
  const widest = Math.max(...layers.map(l => l.length))
  const W = Math.max(widest * 56, 160)
  const pos = new Map<string, { x: number; y: number }>()
  layers.forEach((layer, d) => layer.forEach((id, i) => {
    pos.set(id, { x: PAD + ((i + 0.5) / layer.length) * W, y: PAD + d * 70 })
  }))
  return { pos, size: Math.max(W + 2 * PAD, layers.length * 70 + 2 * PAD) }
}

function force(nodes: string[], edges: [string, string, string?][]): GraphLayout {
  const n = nodes.length
  const size = Math.min(900, Math.max(320, Math.sqrt(n) * 110))
  const area = (size - 2 * PAD) ** 2
  const k = Math.sqrt(area / n)
  const ring = circle(nodes)
  const start = ring.pos
  const scale = (size - 2 * PAD) / ring.size
  const p = nodes.map(id => ({ ...start.get(id)! }))
  for (const q of p) { q.x *= scale; q.y *= scale }
  const index = new Map(nodes.map((id, i) => [id, i]))
  const links = [...neighbours(nodes, edges)].flatMap(([a, set]) =>
    [...set].filter(b => index.get(a)! < index.get(b)!).map(b => [index.get(a)!, index.get(b)!]))

  let t = size / 8
  for (let iter = 0; iter < 300; iter++) {
    const d = p.map(() => ({ x: 0, y: 0 }))
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const dx = p[i].x - p[j].x || 0.01
        const dy = p[i].y - p[j].y || 0.01
        const dist = Math.hypot(dx, dy)
        const f = (k * k) / dist
        d[i].x += (dx / dist) * f; d[i].y += (dy / dist) * f
        d[j].x -= (dx / dist) * f; d[j].y -= (dy / dist) * f
      }
    }
    for (const [i, j] of links) {
      const dx = p[i].x - p[j].x
      const dy = p[i].y - p[j].y
      const dist = Math.hypot(dx, dy) || 0.01
      const f = (dist * dist) / k
      d[i].x -= (dx / dist) * f; d[i].y -= (dy / dist) * f
      d[j].x += (dx / dist) * f; d[j].y += (dy / dist) * f
    }
    for (let i = 0; i < n; i++) {
      const len = Math.hypot(d[i].x, d[i].y) || 1
      p[i].x += (d[i].x / len) * Math.min(len, t)
      p[i].y += (d[i].y / len) * Math.min(len, t)
    }
    t *= 0.985
  }
  // Fit into the box.
  const xs = p.map(q => q.x); const ys = p.map(q => q.y)
  const [x0, x1, y0, y1] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)]
  const s = (size - 2 * PAD) / Math.max(x1 - x0, y1 - y0, 1)
  const pos = new Map(nodes.map((id, i) => [id, { x: PAD + (p[i].x - x0) * s, y: PAD + (p[i].y - y0) * s }]))
  return { pos, size }
}
