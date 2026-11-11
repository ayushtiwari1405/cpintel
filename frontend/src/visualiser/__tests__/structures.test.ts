import { describe, expect, it } from 'vitest'
import type { Compound, Val } from '../trace'
import { adjacency, detectView, indexMarkers, nodeAnnotations } from '../structures'
import { heapRows } from '@/components/visualiser/MemoryView'
import { frame, num, step } from './fixtures'

const seq = (items: Val[], t = 'list'): Compound => ({ k: 'seq', t, items, more: 0 })
const ref = (id: string): Val => ({ k: 'ref', id })
const node = (v: number, next: Val): Compound =>
  ({ k: 'obj', t: 'Node', fields: [['val', num(v)], ['next', next]] })
const none: Val = { k: 'none', v: 'None' }

describe('detectView', () => {
  const heap: Record<string, Compound> = {
    a: seq([num(3), num(1)]),
    grid: seq([ref('r0'), ref('r1')]),
    r0: seq([num(0)]), r1: seq([num(1)]),
    n1: node(1, ref('n2')), n2: node(2, none),
    t: { k: 'obj', t: 'T', fields: [['key', num(5)], ['left', none], ['right', none]] },
    m: { k: 'map', t: 'dict', entries: [[num(1), num(2)]], more: 0 },
  }

  it('reads shapes and names', () => {
    expect(detectView('a', ref('a'), heap)).toBe('array')
    expect(detectView('dp', ref('grid'), heap)).toBe('grid')
    expect(detectView('adj', ref('grid'), heap)).toBe('graph')
    expect(detectView('head', ref('n1'), heap)).toBe('list')
    expect(detectView('root', ref('t'), heap)).toBe('tree')
    expect(detectView('m', ref('m'), heap)).toBe('table')
    expect(detectView('heap', ref('a'), heap)).toBe('heap')
    expect(detectView('n', num(1), heap)).toBe('raw')
  })

  it('reads C++ containers held in place', () => {
    expect(detectView('st', seq([num(1)], 'stack'), {})).toBe('stack')
    expect(detectView('q', seq([num(1)], 'queue'), {})).toBe('queue')
    expect(detectView('pq', seq([num(1)], 'priority_queue'), {})).toBe('heap')
  })
})

describe('markers and annotations', () => {
  it('marks integer index variables, but not a searched-for x', () => {
    const s = step([frame(1, 'main', [['lo', num(0)], ['hi', num(4)], ['x', num(2)], ['i', num(9)]])])
    expect(indexMarkers(s, 5, 'a')).toEqual([['lo', 0], ['hi', 4]])
    expect(indexMarkers(s, 5, 'a', true)).toContainEqual(['x', 2])
  })

  it('finds per-node arrays beside a graph', () => {
    const s = step([frame(1, 'g', [['vis', seq([num(1), num(0)])]])])
    expect(nodeAnnotations(s, 2, 'adj').map(([n]) => n)).toEqual(['vis'])
  })

  it('reads weighted adjacency lists', () => {
    const heap = { e: seq([num(1), num(7)], 'pair') }
    const g = adjacency(seq([seq([ref('e')]), seq([])]), heap)
    expect(g.edges).toEqual([['0', '1', '7']])
  })
})

describe('heapRows', () => {
  it('lays a linked list out as one row', () => {
    const heap = { n1: node(1, ref('n2')), n2: node(2, ref('n3')), n3: node(3, none), x: seq([]) }
    expect(heapRows(heap)).toEqual([['n1', 'n2', 'n3'], ['x']])
  })
})
