import test from 'node:test'
import assert from 'node:assert/strict'
import { fitCount } from './filterFit.ts'

test('every chip stays on the line when they all fit, with no More', () => {
  assert.equal(fitCount([100, 80, 90], 70, 286, 8), 3)
  assert.equal(fitCount([100, 80, 90], 70, 400, 8), 3)
})

test('when they do not all fit, as many as fit beside More', () => {
  // 100 + 8 + 80 + 8 + More 70 = 266: two fit; the third would need 90 + 8 more.
  assert.equal(fitCount([100, 80, 90], 70, 285, 8), 2)
  assert.equal(fitCount([100, 80, 90], 70, 266, 8), 2)
  assert.equal(fitCount([100, 80, 90], 70, 265, 8), 1)
})

test('a line too narrow for one chip and More keeps only More', () => {
  assert.equal(fitCount([100, 80], 70, 120, 8), 0)
  assert.equal(fitCount([100], 70, 90, 8), 0)
})

test('the chips stay in their order: a narrow one later never jumps ahead of a wide one', () => {
  // The wide first chip does not fit beside More; the narrow ones after it are not drawn in its place.
  assert.equal(fitCount([200, 20, 20], 60, 200, 8), 0)
})

test('nothing to place is nothing on the line', () => {
  assert.equal(fitCount([], 70, 300, 8), 0)
})

test('a fraction of a pixel short still fits', () => {
  assert.equal(fitCount([100.4, 100.4], 70, 208.5, 8), 2)
})
