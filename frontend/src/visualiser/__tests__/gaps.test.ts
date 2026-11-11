import { describe, expect, it } from 'vitest'
import { accessed, evalIndex } from '../structures'
import { layoutGraph } from '../graphLayout'
import { frame, num, step } from './fixtures'

const scope = new Map([['i', 2], ['j', 3], ['n', 6]])

describe('evalIndex', () => {
  it('evaluates the index expressions loops use', () => {
    expect(evalIndex('j + 1', scope)).toBe(4)
    expect(evalIndex('n - 1 - i', scope)).toBe(3)
    expect(evalIndex('(i + j) // 2', scope)).toBe(2)
    expect(evalIndex('(i+j)/2', scope)).toBe(2)
    expect(evalIndex('-1 + i * 2 % 3', scope)).toBe(0)
  })

  it('declines what it cannot be sure of', () => {
    expect(evalIndex('len(a)', scope)).toBeNull()
    expect(evalIndex('k', scope)).toBeNull()
    expect(evalIndex('p.x', scope)).toBeNull()
    expect(evalIndex('i / 0', scope)).toBeNull()
  })
})

describe('accessed', () => {
  const s = step([frame(1, 'main', [['i', num(1)], ['j', num(0)]])])

  it('finds the cells a line reads and writes', () => {
    expect(accessed('        if a[j] > a[j + 1]:', 'a', s)).toEqual([[0], [1]])
    expect(accessed('dp[i][j] = dp[i - 1][j] + 1;', 'dp', s)).toEqual([[1, 0], [0, 0]])
  })

  it('does not match other names or fields', () => {
    expect(accessed('b[j] = xa[j] + s.a[j];', 'a', s)).toEqual([])
  })
})

describe('layoutGraph', () => {
  const ids = (n: number) => Array.from({ length: n }, (_, i) => String(i))

  it('is deterministic, and keeps nodes apart', () => {
    const nodes = ids(12)
    const edges = nodes.flatMap((a, i) => [[a, String((i + 1) % 12)], [a, String((i + 5) % 12)]] as [string, string][])
    const one = layoutGraph(nodes, edges)
    const two = layoutGraph([...nodes], edges.map(e => [...e] as [string, string]))
    expect([...two.pos.values()]).toEqual([...one.pos.values()])
    const pts = [...one.pos.values()]
    for (let i = 0; i < pts.length; i++) {
      for (let j = i + 1; j < pts.length; j++) {
        expect(Math.hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y)).toBeGreaterThan(20)
      }
    }
  })

  it('draws a tree in layers from node 0', () => {
    const nodes = ids(10)
    const edges = nodes.slice(1).map(id => [String(Math.floor((Number(id) - 1) / 2)), id] as [string, string])
    const { pos } = layoutGraph(nodes, edges)
    expect(pos.get('1')!.y).toBe(pos.get('2')!.y)
    expect(pos.get('3')!.y).toBeGreaterThan(pos.get('1')!.y)
    expect(pos.get('0')!.y).toBeLessThan(pos.get('1')!.y)
  })
})
