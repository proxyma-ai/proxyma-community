// Run: node --test apps/app/frontend/src/components/ui/formSnapshot.test.ts
//
// The comparison behind every dialog's "Discard unsaved changes?" prompt. It decides whether the
// prompt appears at all, so both directions matter: a false "dirty" nags about nothing, and a
// false "clean" throws work away without asking.
import assert from 'node:assert/strict'
import { describe, test } from 'node:test'

import { sameValue } from './formSnapshot.ts'

describe('sameValue', () => {
  test('primitives', () => {
    assert.equal(sameValue('a', 'a'), true)
    assert.equal(sameValue('a', 'b'), false)
    assert.equal(sameValue(1, 1), true)
    assert.equal(sameValue(1, '1'), false)
    assert.equal(sameValue(NaN, NaN), true)
    assert.equal(sameValue(null, undefined), false)
    assert.equal(sameValue(true, false), false)
  })

  test('typing a character and deleting it again is not a change', () => {
    const opened = { name: 'Summarize', body: 'Read {doc}' }
    const edited = { ...opened, name: 'Summarize!' }
    const reverted = { ...edited, name: 'Summarize' }
    assert.equal(sameValue(opened, edited), false)
    assert.equal(sameValue(opened, reverted), true)
  })

  test('nested objects and arrays compare by value, not identity', () => {
    const a = { tools: ['read', 'grep'], limits: { turns: 5 } }
    const b = { tools: ['read', 'grep'], limits: { turns: 5 } }
    assert.equal(sameValue(a, b), true)
    assert.equal(sameValue(a, { ...b, tools: ['grep', 'read'] }), false)
    assert.equal(sameValue(a, { ...b, limits: { turns: 6 } }), false)
    assert.equal(sameValue(a, { ...b, tools: ['read'] }), false)
  })

  test('an optional field left unset equals one set to undefined', () => {
    assert.equal(sameValue({ a: 1 }, { a: 1, b: undefined }), true)
    assert.equal(sameValue({ a: 1 }, { a: 1, b: null }), false)
    assert.equal(sameValue({ a: 1 }, { a: 1, b: '' }), false)
  })

  test('an array is not an object with numeric keys', () => {
    assert.equal(sameValue([], {}), false)
    assert.equal(sameValue(['x'], { 0: 'x' }), false)
  })

  test('sets and maps', () => {
    assert.equal(sameValue(new Set(['a', 'b']), new Set(['b', 'a'])), true)
    assert.equal(sameValue(new Set(['a']), new Set(['a', 'b'])), false)
    assert.equal(sameValue(new Map([['k', { v: 1 }]]), new Map([['k', { v: 1 }]])), true)
    assert.equal(sameValue(new Map([['k', 1]]), new Map([['k', 2]])), false)
  })

  test('dates compare by instant', () => {
    assert.equal(sameValue(new Date(1000), new Date(1000)), true)
    assert.equal(sameValue(new Date(1000), new Date(2000)), false)
    assert.equal(sameValue(new Date(1000), 1000), false)
  })
})
