import { describe, expect, it } from 'vitest'
import { isRight, questionsFor } from '../predict'
import { notesFromLesson } from '../lessons'
import { handoutSteps } from '../handout'
import { EXAMPLES } from '../examples'
import type { Val } from '../trace'
import { frame, num, step, trace } from './fixtures'

const str = (v: string): Val => ({ k: 'str', v })
const list = (...xs: number[]) => ({ k: 'seq' as const, t: 'list', items: xs.map(num), more: 0 })

describe('predict: questions', () => {
  it('asks about changed variables, list elements and return values', () => {
    const before = step([frame(1, 'Global', [['a', { k: 'ref', id: 'h1' }], ['j', num(0)]])], { heap: { h1: list(5, 2) } })
    const after = step([frame(1, 'Global', [['a', { k: 'ref', id: 'h1' }], ['j', num(0)], ['s', str('x')]])],
      { heap: { h1: list(2, 5) } })
    expect(questionsFor(before, after).map(q => q.label)).toEqual(['a[0]', 'a[1]', 's'])
    expect(questionsFor(before, after)[0].before).toEqual(num(5))

    const ret = step([frame(1, 'Global'), frame(2, 'fib', [['n', num(1)]])], { event: 'return', ret: num(1) })
    expect(questionsFor(ret, ret).map(q => q.label)).toEqual(['fib() returns'])
  })

  it('asks nothing when nothing typeable changed', () => {
    const s = step([frame(1, 'Global', [['x', num(1)]])])
    expect(questionsFor(s, s)).toEqual([])
  })
})

describe('predict: answers', () => {
  it('accepts what a student would type', () => {
    expect(isRight('5', num(5))).toBe(true)
    expect(isRight(' 5.0 ', num(5))).toBe(true)
    expect(isRight("'ab'", str('ab'))).toBe(true)
    expect(isRight('ab', str('ab'))).toBe(true)
    expect(isRight('true', { k: 'bool', v: 'True' })).toBe(true)
    expect(isRight('nullptr', { k: 'none', v: 'None' })).toBe(true)
    expect(isRight('6', num(5))).toBe(false)
    expect(isRight('', num(0))).toBe(false)
  })
})

describe('lessons and handouts', () => {
  const t = trace([
    step([frame(1, 'G')], { line: 1 }),
    step([frame(1, 'G')], { line: 3 }),
    step([frame(1, 'G')], { line: 3 }),
    step([frame(1, 'G'), frame(2, 'f')], { line: 5, event: 'return' }),
  ])

  it('places a note on the nth run of its line, or on a return', () => {
    expect(notesFromLesson(t, [{ line: 3, nth: 2, text: 'b' }, { line: 5, on: 'return', text: 'r' }, { line: 9, text: 'x' }]))
      .toEqual({ 2: 'b', 3: 'r' })
  })

  it('prints noted steps, else breakpoint hits, else an even spread', () => {
    expect(handoutSteps(t, { 2: 'n' }, new Set())).toEqual([2])
    expect(handoutSteps(t, {}, new Set([3]))).toEqual([1, 2])
    expect(handoutSteps(t, {}, new Set())).toEqual([0, 1, 2, 3])
  })
})

describe('examples', () => {
  it('anchor every lesson note to a line with code on it, and pair with real examples', () => {
    for (const ex of EXAMPLES) {
      const lines = ex.code.split('\n')
      for (const n of ex.lesson ?? []) {
        expect(lines[n.line - 1]?.trim(), `${ex.id} line ${n.line}`).toBeTruthy()
      }
      if (ex.compareWith) {
        const other = EXAMPLES.find(e => e.id === ex.compareWith)
        expect(other?.language, ex.id).toBe(ex.language)
      }
    }
  })
})
