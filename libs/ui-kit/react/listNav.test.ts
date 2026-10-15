import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createTypeahead, isNavKey, isTypeaheadKey, moveIndex, typeaheadIndex, PAGE_STEP, TYPEAHEAD_MS } from './listNav.ts'

test('arrows move one row and wrap at both ends', () => {
  assert.equal(moveIndex(0, 3, 'ArrowDown'), 1)
  assert.equal(moveIndex(2, 3, 'ArrowDown'), 0)
  assert.equal(moveIndex(0, 3, 'ArrowUp'), 2)
  assert.equal(moveIndex(1, 3, 'ArrowUp'), 0)
})

test('from nothing active, ArrowDown lands on the first row and ArrowUp on the last', () => {
  assert.equal(moveIndex(-1, 4, 'ArrowDown'), 0)
  assert.equal(moveIndex(-1, 4, 'ArrowUp'), 3)
})

test('Home and End go to the first and last enabled rows', () => {
  const off = (i: number) => i === 0 || i === 4
  assert.equal(moveIndex(2, 5, 'Home', off), 1)
  assert.equal(moveIndex(2, 5, 'End', off), 3)
})

test('disabled rows are skipped, wrapping included', () => {
  const off = (i: number) => i === 1 || i === 2
  assert.equal(moveIndex(0, 4, 'ArrowDown', off), 3)
  assert.equal(moveIndex(3, 4, 'ArrowDown', off), 0)
  assert.equal(moveIndex(0, 4, 'ArrowUp', off), 3)
})

test('a list with nothing enabled, or nothing at all, has no active row', () => {
  assert.equal(moveIndex(-1, 0, 'ArrowDown'), -1)
  assert.equal(moveIndex(-1, 3, 'ArrowDown', () => true), -1)
  assert.equal(moveIndex(-1, 3, 'Home', () => true), -1)
})

test('PageDown and PageUp move ten rows and stop at the ends rather than wrapping', () => {
  assert.equal(moveIndex(0, 40, 'PageDown'), PAGE_STEP)
  assert.equal(moveIndex(35, 40, 'PageDown'), 39)
  assert.equal(moveIndex(39, 40, 'PageDown'), 39)
  assert.equal(moveIndex(25, 40, 'PageUp'), 15)
  assert.equal(moveIndex(4, 40, 'PageUp'), 0)
  assert.equal(moveIndex(-1, 40, 'PageDown'), 9)
})

test('only navigation keys are navigation', () => {
  assert.ok(isNavKey('ArrowDown'))
  assert.ok(isNavKey('End'))
  assert.ok(!isNavKey('Enter'))
  assert.ok(!isNavKey('a'))
})

test('typeahead takes one printable character and nothing with a modifier', () => {
  assert.ok(isTypeaheadKey('a'))
  assert.ok(isTypeaheadKey('7'))
  assert.ok(!isTypeaheadKey(' '))
  assert.ok(!isTypeaheadKey('Enter'))
  assert.ok(!isTypeaheadKey('a', { ctrlKey: true }))
  assert.ok(!isTypeaheadKey('a', { metaKey: true }))
})

const LABELS = ['Anthropic', 'Gemini', 'Groq', 'Ollama', 'OpenAI', 'OpenRouter']

test('typeahead jumps to the next label starting with the letter, from the row after the current', () => {
  assert.equal(typeaheadIndex(LABELS, -1, 'o'), 3)
  assert.equal(typeaheadIndex(LABELS, 3, 'o'), 4)
  assert.equal(typeaheadIndex(LABELS, 5, 'o'), 3)
  assert.equal(typeaheadIndex(LABELS, 0, 'z'), -1)
})

test('a longer query refines the current row while it still matches', () => {
  assert.equal(typeaheadIndex(LABELS, 4, 'op'), 4)
  assert.equal(typeaheadIndex(LABELS, 4, 'openr'), 5)
  assert.equal(typeaheadIndex(LABELS, 1, 'gr'), 2)
})

test('the same letter typed again cycles through the rows that start with it', () => {
  assert.equal(typeaheadIndex(LABELS, 1, 'gg'), 2)
  assert.equal(typeaheadIndex(LABELS, 2, 'ggg'), 1)
})

test('typeahead ignores case and accents and skips disabled rows', () => {
  const names = ['English', 'Ti\u1ebfng Vi\u1ec7t']
  assert.equal(typeaheadIndex(names, 0, 'T'), 1)
  assert.equal(typeaheadIndex(names, -1, 'tie'), 1)
  assert.equal(typeaheadIndex(LABELS, -1, 'o', i => i === 3), 4)
})

test('characters typed close together add up to one query; a pause starts a new one', () => {
  const t = createTypeahead()
  assert.equal(t.push('o', 0), 'o')
  assert.equal(t.push('p', 100), 'op')
  assert.equal(t.push('e', 200), 'ope')
  assert.equal(t.push('g', 200 + TYPEAHEAD_MS + 1), 'g')
  t.reset()
  assert.equal(t.push('a', 5000), 'a')
})
