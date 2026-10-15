// Run: node --test libs/ui-kit/react/autoHide.test.ts
//
// Plain `.ts` with no JSX, so Node's own type stripping runs it. The hooks in useAutoHide.ts need
// touch and scroll events and are not reachable this way; when the bar leaves and returns is.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { SCROLL_TOP_ZONE, scrollVerdict, swipeVerdict } from './autoHide.ts'

describe('swipeVerdict', () => {
  test('a finger moving up the screen hides the bar', () => {
    assert.equal(swipeVerdict(-12), true)
    assert.equal(swipeVerdict(-80), true)
  })

  test('a finger moving down the screen brings it back', () => {
    assert.equal(swipeVerdict(12), false)
  })

  test('a resting finger says nothing', () => {
    assert.equal(swipeVerdict(0), null)
    assert.equal(swipeVerdict(-11), null)
    assert.equal(swipeVerdict(11), null)
  })
})

describe('scrollVerdict', () => {
  test('scrolling down past the top zone hides the bar', () => {
    const next = scrollVerdict({ hidden: false, lastY: 300 }, 340)
    assert.deepEqual(next, { hidden: true, lastY: 340 })
  })

  test('the bar stays while the page is near its top', () => {
    const next = scrollVerdict({ hidden: false, lastY: 0 }, SCROLL_TOP_ZONE)
    assert.equal(next.hidden, false)
  })

  test('any real move back up shows it', () => {
    const next = scrollVerdict({ hidden: true, lastY: 900 }, 880)
    assert.deepEqual(next, { hidden: false, lastY: 880 })
  })

  test('jitter changes nothing, and does not move the reference point', () => {
    const state = { hidden: true, lastY: 900 }
    assert.equal(scrollVerdict(state, 905), state)
    assert.equal(scrollVerdict(state, 893), state)
  })
})
