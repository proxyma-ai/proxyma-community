import { test } from 'node:test'
import assert from 'node:assert/strict'
import { END_SLACK_PX, HOLD_MS, RESUME_SLACKS, catchUpIn, followsChange, holdAfterScroll, keyPushesDown, promptScrollTop, pushesPastEnd } from './threadFollow.ts'

const NOW = 1_000_000

test('the thread follows until the reader scrolls up', () => {
  assert.equal(followsChange({ now: NOW, heldUntil: 0, promptAppeared: false, userSent: false }), true)
  const held = holdAfterScroll({ top: 400, lastTop: 900, fromEnd: 500, now: NOW, heldUntil: 0 })
  assert.equal(held, NOW + HOLD_MS)
  assert.equal(followsChange({ now: NOW + 1, heldUntil: held, promptAppeared: false, userSent: false }), false)
})

test('its own scrolling down, and content growing under it, start no hold', () => {
  assert.equal(holdAfterScroll({ top: 900, lastTop: 400, fromEnd: 300, now: NOW, heldUntil: 0 }), 0)
  assert.equal(holdAfterScroll({ top: 900, lastTop: 900, fromEnd: 300, now: NOW, heldUntil: 0 }), 0)
})

test('every scroll while held starts the wait again, in either direction', () => {
  const held = NOW + HOLD_MS
  const later = NOW + HOLD_MS - 2_000
  assert.equal(holdAfterScroll({ top: 500, lastTop: 400, fromEnd: 400, now: later, heldUntil: held }), later + HOLD_MS)
  assert.equal(holdAfterScroll({ top: 300, lastTop: 400, fromEnd: 600, now: later, heldUntil: held }), later + HOLD_MS)
})

test('following resumes once the wait is over, and scrolling down then is the thread, not the reader', () => {
  const held = NOW + HOLD_MS
  const after = held + 1
  assert.equal(followsChange({ now: after, heldUntil: held, promptAppeared: false, userSent: false }), true)
  assert.equal(holdAfterScroll({ top: 700, lastTop: 400, fromEnd: 200, now: after, heldUntil: held }), held)
})

test('back at the end, following is on at once', () => {
  const held = NOW + HOLD_MS
  assert.equal(holdAfterScroll({ top: 950, lastTop: 400, fromEnd: END_SLACK_PX - 1, now: NOW + 5, heldUntil: held }), 0)
})

test('a panel folding at the end of the thread lowers the scroll position and is not the reader', () => {
  assert.equal(holdAfterScroll({ top: 640, lastTop: 900, fromEnd: 0, now: NOW, heldUntil: 0 }), 0)
})

test('a box five rows tall takes a smaller end, and a scroll up inside it is still a hold', () => {
  assert.equal(holdAfterScroll({ top: 60, lastTop: 100, fromEnd: 40, now: NOW, heldUntil: 0, slack: 8 }), NOW + HOLD_MS)
  assert.equal(holdAfterScroll({ top: 96, lastTop: 100, fromEnd: 4, now: NOW, heldUntil: 0, slack: 8 }), 0)
})

test('once the hold runs out, the end is caught up with - but only if something arrived', () => {
  const held = NOW + HOLD_MS
  assert.equal(catchUpIn({ now: NOW, heldUntil: held, missed: false }), null)
  assert.equal(catchUpIn({ now: NOW, heldUntil: held, missed: true }), HOLD_MS)
  assert.equal(catchUpIn({ now: held + 5, heldUntil: held, missed: true }), 0)
})

test('a prompt the turn waits on, and the reader own message, are shown whatever the hold', () => {
  const held = NOW + HOLD_MS
  assert.equal(followsChange({ now: NOW + 1, heldUntil: held, promptAppeared: true, userSent: false }), true)
  assert.equal(followsChange({ now: NOW + 1, heldUntil: held, promptAppeared: false, userSent: true }), true)
})

test('a prompt that fits is shown whole, at the end; a taller one opens at its first line', () => {
  // At the end, the prompt's first line would be 300px, then exactly 52px, down the window.
  assert.equal(promptScrollTop({ top: 2000, endTop: 1700, clearTop: 52 }), null)
  assert.equal(promptScrollTop({ top: 2000, endTop: 1948, clearTop: 52 }), null)
  // One pixel less room, and a prompt whose first line the end would scroll right off.
  assert.equal(promptScrollTop({ top: 2000, endTop: 1949, clearTop: 52 }), 1948)
  assert.equal(promptScrollTop({ top: 2000, endTop: 2166, clearTop: 52 }), 1948)
  assert.equal(promptScrollTop({ top: 30, endTop: 400, clearTop: 52 }), 0)
})

test('a scroll down that lands a few lines short of a running-away end follows again', () => {
  const held = NOW + HOLD_MS
  // Heading down while an answer streams: within three slacks of the end resumes...
  assert.equal(holdAfterScroll({ top: 900, lastTop: 700, fromEnd: END_SLACK_PX * RESUME_SLACKS - 1, now: NOW + 5, heldUntil: held }), 0)
  // ...further away is still reading, and so is any move UP, however close to the end.
  assert.equal(holdAfterScroll({ top: 900, lastTop: 700, fromEnd: END_SLACK_PX * RESUME_SLACKS + 1, now: NOW + 5, heldUntil: held }), NOW + 5 + HOLD_MS)
  assert.equal(holdAfterScroll({ top: 690, lastTop: 700, fromEnd: END_SLACK_PX + 10, now: NOW + 5, heldUntil: 0 }), NOW + 5 + HOLD_MS)
  // A box's own slack scales the same way.
  assert.equal(holdAfterScroll({ top: 96, lastTop: 80, fromEnd: 20, now: NOW, heldUntil: held, slack: 8 }), 0)
})

test('pushing down at the end follows again, though the scroller cannot move', () => {
  assert.equal(pushesPastEnd({ delta: 120, fromEnd: 0 }), true)
  assert.equal(pushesPastEnd({ delta: 1, fromEnd: END_SLACK_PX }), true)
  assert.equal(pushesPastEnd({ delta: -120, fromEnd: 0 }), false)
  assert.equal(pushesPastEnd({ delta: 120, fromEnd: 2000 }), false)
  assert.equal(pushesPastEnd({ delta: 120, fromEnd: 10, slack: 8 }), true)
  assert.equal(keyPushesDown('End', false), true)
  assert.equal(keyPushesDown(' ', false), true)
  assert.equal(keyPushesDown(' ', true), false)
  assert.equal(keyPushesDown('ArrowUp', false), false)
})
