import { describe, expect, it } from 'vitest'
import { fitEvent, rejectedEvents, withoutRejected } from './reportQueue'

describe('the monitoring queues', () => {
  it('cuts an event to what the server accepts', () => {
    const fitted = fitEvent({
      eventId: 'e'.repeat(80), type: 'FOCUS_LOST', detail: `a\u0000b${'x'.repeat(600)}`,
      problemLabel: 'A'.repeat(20), durationMs: 5,
    })
    expect(fitted.eventId).toHaveLength(64)
    expect(fitted.problemLabel).toHaveLength(16)
    expect(fitted.detail).toHaveLength(500)
    expect(fitted.detail?.startsWith('abx')).toBe(true)
    expect(fitted.durationMs).toBe(5)
  })

  it('leaves absent fields absent', () => {
    expect(fitEvent({ eventId: 'a', type: 'T' })).toEqual({ eventId: 'a', type: 'T' })
  })

  it('names the refused events of a 400, and nothing for any other failure', () => {
    const errors = { 'events[1].detail': 'too long', 'events[3].eventId': 'required' }
    expect(rejectedEvents({ response: { status: 400, data: { errors } } }))
      .toEqual(new Set([1, 3]))
    expect(rejectedEvents({ response: { status: 500, data: { errors } } }).size).toBe(0)
    expect(rejectedEvents({ response: { status: 400, data: { message: 'no' } } }).size).toBe(0)
    expect(rejectedEvents(new Error('offline')).size).toBe(0)
  })

  it('drops only those, keeping the rest of the batch and what arrived since', () => {
    expect(withoutRejected(['a', 'b', 'c', 'later'], 3, new Set([1, 3])))
      .toEqual(['a', 'c', 'later'])
  })
})
