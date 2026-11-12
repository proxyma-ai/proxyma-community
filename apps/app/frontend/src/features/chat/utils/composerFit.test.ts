import { test } from 'node:test'
import assert from 'node:assert/strict'
import { composerFit, neededWidth, type ComposerWidths } from './composerFit.ts'

// A left side like the feedback's screenshots: two 32px controls besides the two that fold (others),
// "Ask" with its chevron, "LiteLLM SAI - claude-sonnet-5-5", 32px icon buttons, 4px gaps.
const ROW: ComposerWidths = { room: 0, others: 32 + 4 + 32, gap: 4, modeFull: 72, modeIcon: 32, modelFull: 190, modelIcon: 32 }
const at = (room: number, w: Partial<ComposerWidths> = {}) => composerFit({ ...ROW, ...w, room })

test('the width each level needs counts every control and its gap', () => {
  assert.equal(neededWidth(ROW, 'full'), 68 + 4 + 72 + 4 + 190)
  assert.equal(neededWidth(ROW, 'modelIcon'), 68 + 4 + 72 + 4 + 32)
  assert.equal(neededWidth(ROW, 'icons'), 68 + 4 + 32 + 4 + 32)
})

test('a wide composer keeps every word', () => {
  assert.equal(at(600), 'full')
  assert.equal(at(338), 'full')
})

test('the model folds first, then the mode', () => {
  assert.equal(at(337), 'modelIcon')
  assert.equal(at(180), 'modelIcon')
  assert.equal(at(179), 'icons')
})

test('a room too small for anything still folds both rather than giving up', () => {
  assert.equal(at(80), 'icons')
})

test('a label that fits to the pixel does not fold on a rounding', () => {
  assert.equal(at(337.6), 'full')
  assert.equal(at(337.4), 'modelIcon')
})

test('with no model shown, only the mode decides', () => {
  const none = { modelFull: 0, modelIcon: 0 }
  assert.equal(neededWidth({ ...ROW, ...none }, 'full'), 68 + 4 + 72)
  assert.equal(at(144, none), 'full')
  assert.equal(at(143, none), 'icons')
})

test('an unmeasured room keeps the words until it is measured', () => {
  assert.equal(at(0), 'full')
  assert.equal(at(Number.NaN), 'full')
})

test('a longer translation of the mode folds sooner', () => {
  // "Hoi truoc" is wider than "Ask": the same room that held both labels now holds the mode only.
  assert.equal(at(338), 'full')
  assert.equal(at(338, { modeFull: 100 }), 'modelIcon')
})
