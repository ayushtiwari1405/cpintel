import { describe, expect, it } from 'vitest'
import { brief, buildCallTree } from '../trace'
import { lineCounts, nextBreakpoint, nextRunOfLine, prevBreakpoint, stepBackOver, stepOver } from '../navigate'
import { frame, num, step, trace } from './fixtures'

const G = frame(1, 'Global')
// fib(2) calling fib(1) and fib(0), as a tracer reports it.
const fibTrace = trace([
  step([G], { line: 5 }),
  step([G, frame(2, 'fib', [['n', num(2)]])], { event: 'call', line: 1 }),
  step([G, frame(2, 'fib', [['n', num(2)]])], { line: 3 }),
  step([G, frame(2, 'fib', [['n', num(2)]]), frame(3, 'fib', [['n', num(1)]])], { event: 'call' }),
  step([G, frame(2, 'fib', [['n', num(2)]]), frame(3, 'fib', [['n', num(1)]])], { event: 'return', ret: num(1) }),
  step([G, frame(2, 'fib', [['n', num(2)]]), frame(4, 'fib', [['n', num(0)]])], { event: 'call' }),
  step([G, frame(2, 'fib', [['n', num(2)]]), frame(4, 'fib', [['n', num(0)]])], { event: 'return', ret: num(0) }),
  step([G, frame(2, 'fib', [['n', num(2)]])], { event: 'return', line: 3, ret: num(1) }),
  step([G], { line: 6 }),
])

describe('buildCallTree', () => {
  it('rebuilds calls, arguments and return values from consecutive stacks', () => {
    const root = buildCallTree(fibTrace)!
    expect(root.name).toBe('Global')
    expect(root.children).toHaveLength(1)
    const fib2 = root.children[0]
    expect(fib2.args).toEqual([['n', num(2)]])
    expect(fib2.ret).toEqual(num(1))
    expect(fib2.enter).toBe(1)
    expect(fib2.exit).toBe(7)
    expect(fib2.children.map(c => brief(c.ret))).toEqual(['1', '0'])
  })

  it('is empty for an empty trace', () => {
    expect(buildCallTree(trace([]))).toBeNull()
  })
})

describe('navigation', () => {
  it('steps over a call to the next step at the same depth', () => {
    expect(stepOver(fibTrace, 2)).toBe(7)
    expect(stepBackOver(fibTrace, 7)).toBe(2)
  })

  it('jumps between breakpoint lines and wraps line runs', () => {
    const lines = new Set([3])
    expect(nextBreakpoint(fibTrace, 0, lines)).toBe(2)
    expect(prevBreakpoint(fibTrace, 8, lines)).toBe(2)
    expect(nextRunOfLine(fibTrace, 8, 5)).toBe(0)
    expect(nextRunOfLine(fibTrace, 0, 99)).toBeNull()
  })

  it('counts how often each line ran', () => {
    expect(lineCounts(fibTrace).get(3)).toBe(1)
    expect(lineCounts(fibTrace).get(5)).toBe(1)
  })
})

describe('brief', () => {
  it('writes values the way the language would', () => {
    expect(brief({ k: 'str', v: 'a' })).toBe('"a"')
    expect(brief({ k: 'seq', t: 'tuple', items: [num(1), num(2)], more: 0 })).toBe('(1, 2)')
    expect(brief({ k: 'seq', t: 'list', items: [num(1)], more: 3 })).toBe('[1, …3]')
    expect(brief({ k: 'ptr', t: 'int*', addr: '0x0', to: null })).toBe('nullptr')
    expect(brief({ k: 'ref', id: 'h1' }, { h1: { k: 'map', t: 'dict', entries: [[num(1), num(2)]], more: 0 } }))
      .toBe('{1: 2}')
  })
})
