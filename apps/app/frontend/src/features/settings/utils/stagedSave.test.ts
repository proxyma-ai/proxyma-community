import { test } from 'node:test'
import assert from 'node:assert/strict'
import { lowered, stagedChanges, stagedProblems } from './stagedSave.ts'
import { DAYS } from './settingNumbers.ts'

test('only what differs from the saved value is saved, as it is stored', () => {
  const saved = { a: '7', b: '90', c: '365' }
  assert.deepEqual(stagedChanges(saved, { a: '007', b: '30' }), { b: '30' })
  assert.deepEqual(stagedChanges(saved, {}), {})
  assert.deepEqual(stagedChanges({}, { d: '5' }), { d: '5' })
})

test('an empty, fractional, negative or too large number of days is not saved', () => {
  assert.deepEqual(stagedProblems({ a: '', b: '1.5', c: '-1', d: '99999', e: '30', f: '0' }, DAYS), ['a', 'b', 'c', 'd'])
})

test('a retention lowered, or a first limit where it was kept forever, is what deletes data', () => {
  assert.deepEqual(lowered({ a: '90', b: '30', c: '0', d: '90' }, { a: '30', b: '60', c: '7', d: '0' }), ['a', 'c'])
})
