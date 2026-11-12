import { test } from 'node:test'
import assert from 'node:assert/strict'
import { usageNames } from './connectionUsage.ts'

test('the first few sources are named and the rest counted', () => {
  assert.deepEqual(usageNames({ sources: 7, names: ['Alpha', 'Beta', 'Gamma', 'Delta', 'Epsilon'] }),
    { names: ['Alpha', 'Beta', 'Gamma'], more: 4 })
})

test('a few sources are all named, with nothing more', () => {
  assert.deepEqual(usageNames({ sources: 2, names: ['Alpha', 'Beta'] }), { names: ['Alpha', 'Beta'], more: 0 })
  assert.deepEqual(usageNames({ sources: 0, names: [] }), { names: [], more: 0 })
})

test('a source with no name is counted, never listed blank', () => {
  assert.deepEqual(usageNames({ sources: 2, names: ['Alpha', ' '] }), { names: ['Alpha'], more: 1 })
})
