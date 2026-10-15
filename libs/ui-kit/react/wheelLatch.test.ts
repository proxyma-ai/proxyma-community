// Run: node --test libs/ui-kit/react/wheelLatch.test.ts
//
// Plain `.ts` with no JSX, so Node's own type stripping runs it. The listener in useWheelScrollX.ts
// needs a DOM; where each wheel event goes is decided here.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { LATCH_MS, LINE_PX, wheelVerdict, type WheelInput } from './wheelLatch.ts'

// A table 600px wider than its box, scrolled to `from`, with no gesture under way.
function wheel(over: Partial<WheelInput>): WheelInput {
  return { deltaX: 0, deltaY: 0, deltaMode: 0, ctrlKey: false, from: 300, max: 600, now: 1000, latchedUntil: 0, ...over }
}

describe('a wheel notch over a row that overflows', () => {
  test('scrolls the row sideways, eased, and latches the gesture', () => {
    const v = wheelVerdict(wheel({ deltaY: 100 }))
    assert.deepEqual(v, { kind: 'scroll', to: 400, ease: true, latchedUntil: 1000 + LATCH_MS })
  })

  test('stops at the end rather than past it', () => {
    const v = wheelVerdict(wheel({ deltaY: 500 }))
    assert.equal(v.kind === 'scroll' && v.to, 600)
  })

  test('lines are turned into pixels', () => {
    const v = wheelVerdict(wheel({ deltaY: 3, deltaMode: 1 }))
    assert.equal(v.kind === 'scroll' && v.to, 300 + 3 * LINE_PX)
  })
})

describe('at the edge of the row', () => {
  test('a gesture that carried the row there keeps it: the thread does not move', () => {
    const v = wheelVerdict(wheel({ deltaY: 100, from: 600, latchedUntil: 1100 }))
    assert.deepEqual(v, { kind: 'hold', latchedUntil: 1000 + LATCH_MS })
  })

  test('the same at the start of the row', () => {
    assert.equal(wheelVerdict(wheel({ deltaY: -100, from: 0, latchedUntil: 1100 })).kind, 'hold')
  })

  test('a new gesture after a pause goes to the page, so the thread can be scrolled past a table', () => {
    assert.equal(wheelVerdict(wheel({ deltaY: 100, from: 600, latchedUntil: 900 })).kind, 'page')
    assert.equal(wheelVerdict(wheel({ deltaY: -100, from: 0 })).kind, 'page')
  })

  test('turning back from the edge scrolls the row again', () => {
    assert.equal(wheelVerdict(wheel({ deltaY: -100, from: 600 })).kind, 'scroll')
  })
})

describe('a sideways swipe', () => {
  test('moves the row directly, by its own pixels, with its vertical drift dropped', () => {
    const v = wheelVerdict(wheel({ deltaX: 40, deltaY: 6 }))
    assert.deepEqual(v, { kind: 'scroll', to: 340, ease: false, latchedUntil: 1000 + LATCH_MS })
  })

  test('never reaches the page, even at the edge with no gesture under way', () => {
    assert.equal(wheelVerdict(wheel({ deltaX: 40, deltaY: 6, from: 600 })).kind, 'hold')
    assert.equal(wheelVerdict(wheel({ deltaX: -40, deltaY: -6, from: 0 })).kind, 'hold')
  })
})

describe('left alone', () => {
  test('a row that does not overflow', () => {
    assert.equal(wheelVerdict(wheel({ deltaY: 100, max: 0 })).kind, 'page')
  })

  test('ctrl+wheel and pinch zoom', () => {
    assert.equal(wheelVerdict(wheel({ deltaY: 100, ctrlKey: true })).kind, 'page')
  })

  test('an event with no movement', () => {
    assert.equal(wheelVerdict(wheel({})).kind, 'page')
  })
})
