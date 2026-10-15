import { test } from 'node:test'
import assert from 'node:assert/strict'
import { clampPage, LIST_PAGE_SIZE, pageCount, pageSlice, pageWindow } from './pageWindow.ts'

// Shown 1-based, as a reader sees them, with '.' for a gap.
const view = (page: number, pages: number) =>
  pageWindow(page, pages).map(p => (p == null ? '.' : String(p + 1))).join(' ')

test('few pages: every one of them', () => {
  assert.equal(view(0, 1), '1')
  assert.equal(view(2, 7), '1 2 3 4 5 6 7')
  assert.deepEqual(pageWindow(0, 0), [])
})

test('many pages: the first, the last and the neighbors of the current one', () => {
  assert.equal(view(9, 20), '1 . 9 10 11 . 20')
})

test('near either end the window slides inward and keeps its width', () => {
  assert.equal(view(0, 20), '1 2 3 4 5 . 20')
  assert.equal(view(3, 20), '1 2 3 4 5 . 20')
  assert.equal(view(19, 20), '1 . 16 17 18 19 20')
  assert.equal(view(16, 20), '1 . 16 17 18 19 20')
})

test('the row has the same number of slots wherever the current page is', () => {
  const widths = new Set(Array.from({ length: 50 }, (_, p) => pageWindow(p, 50).length))
  assert.deepEqual([...widths], [7])
})

test('the current page is always in the row, and a page out of range is clamped', () => {
  for (let p = 0; p < 30; p++) assert.ok(pageWindow(p, 30).includes(p), `page ${p}`)
  assert.equal(view(99, 20), '1 . 16 17 18 19 20')
  assert.equal(view(-3, 20), '1 2 3 4 5 . 20')
})

test('a list pages at twenty', () => {
  assert.equal(LIST_PAGE_SIZE, 20)
})

test('page count: none for an empty list, one up to twenty, then one more per twenty', () => {
  assert.equal(pageCount(0), 0)
  assert.equal(pageCount(1), 1)
  assert.equal(pageCount(20), 1)
  assert.equal(pageCount(21), 2)
  assert.equal(pageCount(40), 2)
  assert.equal(pageCount(41), 3)
  assert.equal(pageCount(7, 5), 2)
  assert.equal(pageCount(-1), 0)
  assert.equal(pageCount(10, 0), 0)
})

test('a page outside the list is brought back inside it', () => {
  assert.equal(clampPage(0, 0), 0)
  assert.equal(clampPage(3, 0), 0)
  assert.equal(clampPage(-2, 5), 0)
  assert.equal(clampPage(2, 5), 2)
  assert.equal(clampPage(9, 5), 4)
  assert.equal(clampPage(Number.NaN, 5), 0)
})

test('a page of a list holds at most twenty, and the last holds the rest', () => {
  const items = Array.from({ length: 45 }, (_, i) => i)
  assert.deepEqual(pageSlice(items, 0), items.slice(0, 20))
  assert.deepEqual(pageSlice(items, 1), items.slice(20, 40))
  assert.deepEqual(pageSlice(items, 2), [40, 41, 42, 43, 44])
  // A page past the end - the list shrank under the reader - shows the last one, never nothing.
  assert.deepEqual(pageSlice(items, 7), [40, 41, 42, 43, 44])
  assert.deepEqual(pageSlice([], 3), [])
  assert.deepEqual(pageSlice([1, 2, 3], 0), [1, 2, 3])
})
