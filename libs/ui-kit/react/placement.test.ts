import { test } from 'node:test'
import assert from 'node:assert/strict'
import { EDGE, place } from './placement.ts'

const at = (w: number, h: number) => { (globalThis as { window?: unknown }).window = { innerWidth: w, innerHeight: h } }
const rect = (top: number, bottom: number, left = 100, right = 200) =>
  ({ top, bottom, left, right, width: right - left, height: bottom - top, x: left, y: top, toJSON() {} }) as DOMRect

test('a panel below a low anchor is capped by the room below it, not by the window', () => {
  // 1280x720 with a trial banner: the Connect button sits low, and a tall panel still opens down
  // because the room above is smaller.
  at(1280, 720)
  const p = place(rect(170, 206), { width: 320, height: 600 }, 'bottom-end')
  assert.equal(p.top, 210)
  assert.equal(p['--float-room'], `${720 - 206 - EDGE - 4}px`)
})

test('a panel that flips up is capped by the room above', () => {
  at(1280, 720)
  const p = place(rect(600, 636), { width: 224, height: 300 }, 'bottom-start')
  assert.equal(p.bottom, 720 - 600 + 4)
  assert.equal(p['--float-room'], `${600 - EDGE - 4}px`)
})

test('a panel beside its anchor may use the whole window height', () => {
  at(1280, 720)
  const p = place(rect(100, 130), { width: 200, height: 100 }, 'right-start')
  assert.equal(p.left, 204)
  assert.equal(p['--float-room'], `${720 - 2 * EDGE}px`)
})

test('the room is never negative', () => {
  at(400, 300)
  const p = place(rect(-50, 310), { width: 200, height: 100 }, 'bottom-start')
  assert.equal(p['--float-room'], '0px')
})
