// Run: node --test apps/app/frontend/src/features/chat/utils/threadReveal.test.ts
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { revealScrollTop, revealsAgent } from './threadReveal.ts'

// A thread 600px tall, scrolled to 1000 of 5000, with the pinned question's bar 52px deep.
const view = { scrollTop: 1000, viewHeight: 600, endTop: 4400, clearTop: 52 }

describe('where the thread goes to reveal an agent', () => {
  test('the latest turn lands at the top, clear of the pinned question', () => {
    assert.equal(revealScrollTop({ ...view, kind: 'main', top: 2000, height: 900 }), 2948)
  })

  test('a sub-agent panel is centered', () => {
    // The panel's middle (1000 + 300 + 18) at the window's middle (300).
    assert.equal(revealScrollTop({ ...view, kind: 'agent', top: 300, height: 36 }), 1018)
  })

  test('a panel taller than the window keeps its header clear of the pinned question', () => {
    assert.equal(revealScrollTop({ ...view, kind: 'agent', top: 300, height: 1400 }), 1248)
  })

  test('the target above the window scrolls up to it', () => {
    assert.equal(revealScrollTop({ ...view, kind: 'agent', top: -500, height: 36 }), 218)
  })

  test('never past either end of the scroller', () => {
    assert.equal(revealScrollTop({ ...view, kind: 'main', top: -1000, height: 40 }), 0)
    assert.equal(revealScrollTop({ ...view, kind: 'agent', top: 4000, height: 36 }), 4400)
  })
})

describe('which panel a reveal names', () => {
  test('its own agent, and nothing else', () => {
    const request = { target: { kind: 'agent' as const, agentId: 'a1' } }
    assert.equal(revealsAgent(request, 'a1'), true)
    assert.equal(revealsAgent(request, 'a2'), false)
    assert.equal(revealsAgent(request, undefined), false)
    assert.equal(revealsAgent({ target: { kind: 'main' } }, 'a1'), false)
    assert.equal(revealsAgent(null, 'a1'), false)
  })
})
