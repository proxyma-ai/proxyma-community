import { test } from 'node:test'
import assert from 'node:assert/strict'
import { applyClick, headerState, toggleAllOnPage } from './selection.ts'

const page = ['a', 'b', 'c', 'd', 'e']
const sorted = (s: Set<string>) => [...s].sort()

test('a plain click toggles only the clicked row', () => {
  assert.deepEqual(sorted(applyClick(new Set(['a']), page, 'a', 'c', true, false)), ['a', 'c'])
  assert.deepEqual(sorted(applyClick(new Set(['a', 'c']), page, 'a', 'c', false, false)), ['a'])
})

test('shift-click fills the run between the anchor and the row, in either direction', () => {
  assert.deepEqual(sorted(applyClick(new Set(['b']), page, 'b', 'd', true, true)), ['b', 'c', 'd'])
  assert.deepEqual(sorted(applyClick(new Set(['d']), page, 'd', 'b', true, true)), ['b', 'c', 'd'])
})

test('shift-click that unchecks clears the run', () => {
  const all = new Set(page)
  assert.deepEqual(sorted(applyClick(all, page, 'b', 'd', false, true)), ['a', 'e'])
})

test('shift-click with no anchor, or an anchor no longer on the page, is a plain click', () => {
  assert.deepEqual(sorted(applyClick(new Set(), page, null, 'c', true, true)), ['c'])
  assert.deepEqual(sorted(applyClick(new Set(), page, 'gone', 'c', true, true)), ['c'])
})

test('the header reads none, some or all of this page', () => {
  assert.equal(headerState(new Set(), page), 'none')
  assert.equal(headerState(new Set(['a', 'x']), page), 'some')
  assert.equal(headerState(new Set(page), page), 'all')
})

test('the header click fills a partial page, clears a full one, and keeps ids from elsewhere', () => {
  assert.deepEqual(sorted(toggleAllOnPage(new Set(['a', 'x']), page)), ['a', 'b', 'c', 'd', 'e', 'x'])
  assert.deepEqual(sorted(toggleAllOnPage(new Set([...page, 'x']), page)), ['x'])
})
