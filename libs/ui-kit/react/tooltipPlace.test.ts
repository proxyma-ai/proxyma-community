import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ABOVE_POINTER, BELOW_POINTER, FROM_CONTROL, TOOLTIP_EDGE, controlAnchor, pointerAnchor, tooltipPlace } from './tooltipPlace.ts'

const vp = { w: 1280, h: 720 }
const bubble = { w: 120, h: 28 }

test('opened by the mouse it sits above the pointer, centered on it', () => {
  const p = tooltipPlace(pointerAnchor(600, 400), bubble, vp)
  assert.deepEqual(p, { left: 540, top: 400 - ABOVE_POINTER - 28, side: 'top' })
})

test('with no room above the pointer it goes below, clear of the cursor', () => {
  const p = tooltipPlace(pointerAnchor(600, 20), bubble, vp)
  assert.deepEqual(p, { left: 540, top: 20 + BELOW_POINTER, side: 'bottom' })
})

test('the pointer tries above even where its control asked for below', () => {
  assert.equal(tooltipPlace(pointerAnchor(600, 400), bubble, vp, 'bottom').side, 'top')
})

test('it is slid back inside the viewport at either side', () => {
  assert.equal(tooltipPlace(pointerAnchor(5, 400), bubble, vp).left, TOOLTIP_EDGE)
  assert.equal(tooltipPlace(pointerAnchor(1278, 400), bubble, vp).left, 1280 - TOOLTIP_EDGE - 120)
})

test('fitting on neither side, it takes the side with more room and stays inside', () => {
  const tall = { w: 120, h: 400 }
  const p = tooltipPlace(pointerAnchor(600, 300), tall, { w: 1280, h: 600 })
  assert.equal(p.side, 'top', 'more room above')
  assert.equal(p.top, TOOLTIP_EDGE)
  const q = tooltipPlace(pointerAnchor(600, 250), tall, { w: 1280, h: 600 })
  assert.equal(q.side, 'bottom')
  assert.equal(q.top, 600 - TOOLTIP_EDGE - 400)
})

test('opened from the keyboard it is placed at the control, on the side asked for while it fits', () => {
  const r = { left: 100, top: 300, width: 40, bottom: 332 }
  assert.deepEqual(tooltipPlace(controlAnchor(r), bubble, vp), { left: 60, top: 300 - FROM_CONTROL - 28, side: 'top' })
  assert.deepEqual(tooltipPlace(controlAnchor(r), bubble, vp, 'bottom'), { left: 60, top: 332 + FROM_CONTROL, side: 'bottom' })
  const nearBottom = { left: 100, top: 690, width: 40, bottom: 712 }
  assert.equal(tooltipPlace(controlAnchor(nearBottom), bubble, vp, 'bottom').side, 'top')
})

test('a bubble wider than the viewport starts at its edge', () => {
  assert.equal(tooltipPlace(pointerAnchor(100, 400), { w: 2000, h: 28 }, vp).left, TOOLTIP_EDGE)
})
