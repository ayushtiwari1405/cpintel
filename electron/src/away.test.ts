import assert from 'node:assert/strict'
import test from 'node:test'
import { AwayTracker, AWAY_WARN_MS } from './away'

/**
 * The away accounting.
 *
 * Every one of these is a case that produces a plausible-looking wrong number rather than a
 * crash: a warning that fires once per tick instead of once, an absence counted twice because
 * it was counted both while away and again on return, or a total that silently stops moving
 * while someone is still away.
 */

const T0 = 1_000_000

test('a glance is counted but never warned about', () => {
  const tracker = new AwayTracker()
  tracker.onBlur(T0)

  assert.equal(tracker.dueForWarning(T0 + 3_000), false)
  const ended = tracker.onFocus(T0 + 4_000)

  assert.equal(ended?.long, false)
  assert.equal(ended?.durationMs, 4_000)

  const snap = tracker.snapshot(T0 + 5_000)
  assert.equal(snap.focusLosses, 1, 'the switch still happened and is still counted')
  assert.equal(snap.longAbsences, 0, 'but four seconds is not worth warning about')
  assert.equal(snap.warnings, 0)
  assert.equal(snap.awayMs, 4_000)
})

test('an absence past the threshold warns exactly once, not once per tick', () => {
  const tracker = new AwayTracker()
  tracker.onBlur(T0)

  // A caller ticking every second must not produce a warning every second.
  let warnings = 0
  for (let t = 1_000; t <= 25_000; t += 1_000) {
    if (tracker.dueForWarning(T0 + t)) warnings++
  }

  assert.equal(warnings, 1, 'one at the threshold, and the repeat is not due until 40s')
  assert.equal(tracker.snapshot(T0 + 25_000).longAbsences, 1)
})

test('a long absence repeats the warning on schedule', () => {
  const tracker = new AwayTracker(10_000, 30_000)
  tracker.onBlur(T0)

  let warnings = 0
  for (let t = 1_000; t <= 75_000; t += 1_000) {
    if (tracker.dueForWarning(T0 + t)) warnings++
  }

  // 10s, 40s, 70s.
  assert.equal(warnings, 3)
  assert.equal(tracker.snapshot(T0 + 75_000).warnings, 3)
})

test('an absence is counted as long exactly once, whether warned or returned', () => {
  const tracker = new AwayTracker()
  tracker.onBlur(T0)

  assert.equal(tracker.dueForWarning(T0 + AWAY_WARN_MS), true)
  const ended = tracker.onFocus(T0 + 15_000)

  assert.equal(ended?.long, true)
  // Counted while away by the ticker; returning must not count it a second time.
  assert.equal(tracker.snapshot(T0 + 15_000).longAbsences, 1)
})

test('an absence that ends exactly on the threshold counts as long', () => {
  const tracker = new AwayTracker()
  tracker.onBlur(T0)
  const ended = tracker.onFocus(T0 + AWAY_WARN_MS)

  assert.equal(ended?.long, true, 'ten seconds is "more than ten seconds" for this purpose')
  assert.equal(tracker.snapshot(T0 + AWAY_WARN_MS).longAbsences, 1)
})

test('the total keeps moving while someone is still away', () => {
  const tracker = new AwayTracker()
  tracker.onBlur(T0)

  // A total that only updated on return would show zero for as long as it mattered most.
  assert.equal(tracker.snapshot(T0 + 5_000).awayMs, 5_000)
  assert.equal(tracker.snapshot(T0 + 9_000).currentAwayMs, 9_000)
  assert.equal(tracker.snapshot(T0 + 9_000).away, true)
})

test('separate absences accumulate', () => {
  const tracker = new AwayTracker()
  tracker.onBlur(T0)
  tracker.onFocus(T0 + 4_000)
  tracker.onBlur(T0 + 10_000)
  tracker.onFocus(T0 + 22_000)

  const snap = tracker.snapshot(T0 + 30_000)
  assert.equal(snap.focusLosses, 2)
  assert.equal(snap.longAbsences, 1, 'only the twelve-second one')
  assert.equal(snap.awayMs, 16_000)
  assert.equal(snap.away, false)
})

test('a duplicate blur does not invent a second absence', () => {
  // Window managers do emit these, and each one would otherwise reset the warning clock and
  // let someone stay away indefinitely without ever being warned.
  const tracker = new AwayTracker()
  tracker.onBlur(T0)
  tracker.onBlur(T0 + 2_000)
  tracker.onBlur(T0 + 4_000)

  assert.equal(tracker.snapshot(T0 + 4_000).focusLosses, 1)
  assert.equal(tracker.dueForWarning(T0 + 10_000), true, 'still measured from the first blur')
})

test('focus without a preceding blur is ignored', () => {
  const tracker = new AwayTracker()
  assert.equal(tracker.onFocus(T0), null)
  assert.equal(tracker.snapshot(T0).focusLosses, 0)
})

test('reset clears the accounting and honours whether the window is already away', () => {
  const tracker = new AwayTracker()
  tracker.onBlur(T0)
  tracker.onFocus(T0 + 20_000)

  tracker.reset(T0 + 30_000, false)
  assert.deepEqual(tracker.snapshot(T0 + 30_000), {
    away: false, focusLosses: 0, longAbsences: 0, awayMs: 0, currentAwayMs: 0, warnings: 0,
  })

  // A contest that starts while the window is in the background is already an absence.
  tracker.reset(T0 + 40_000, true)
  assert.equal(tracker.snapshot(T0 + 45_000).away, true)
  assert.equal(tracker.snapshot(T0 + 45_000).awayMs, 5_000)
})
