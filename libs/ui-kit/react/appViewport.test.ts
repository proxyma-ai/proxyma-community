// Run: node --test libs/ui-kit/react/appViewport.test.ts
//
// Plain `.ts` with no JSX, so Node's own type stripping runs it. installAppViewport needs a window
// and is not reachable this way; which box the shell is pinned to is.
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { appBox } from './appViewport.ts'

test('the shell fits the visible viewport, keyboard up or not', () => {
  assert.deepEqual(appBox({ height: 844, offsetTop: 0, scale: 1 }), { height: 844, top: 0 })
  // The keyboard is up, and Safari has slid the page 120px to show the field.
  assert.deepEqual(appBox({ height: 380.5, offsetTop: 120.4, scale: 1 }), { height: 381, top: 120 })
})

test('a pinched-in page is left alone', () => {
  assert.equal(appBox({ height: 422, offsetTop: 210, scale: 2 }), null)
})

test('an overscroll bounce does not drag the shell with it', () => {
  assert.deepEqual(appBox({ height: 844, offsetTop: -14, scale: 1 }), { height: 844, top: 0 })
})
