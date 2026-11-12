// Run: node --test apps/app/frontend/src/features/settings/utils/settingsSave.test.ts
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import {
  changesEmbeddingModel, draftChanges, draftValue, pendingValue, withEdit, withPending, withoutPending,
} from './settingsSave.ts'

describe("a group's draft", () => {
  test('collects edits and sends them all when the group is left', () => {
    let d = withEdit({}, { prefix: 'proxy', field: 'mode', value: 'custom' })
    d = withEdit(d, { prefix: 'proxy', field: 'https-proxy', value: 'http://p:8080' })
    assert.deepEqual(draftChanges(d), [
      { prefix: 'proxy', field: 'mode', value: 'custom' },
      { prefix: 'proxy', field: 'https-proxy', value: 'http://p:8080' },
    ])
  })

  test('a later edit of a field replaces the earlier one', () => {
    let d = withEdit({}, { prefix: 'chat', field: 'model', value: 'a' })
    d = withEdit(d, { prefix: 'chat', field: 'model', value: 'b' })
    assert.deepEqual(draftChanges(d), [{ prefix: 'chat', field: 'model', value: 'b' }])
  })

  test('an edit back to what the server holds is no edit', () => {
    let d = withEdit({}, { prefix: 'proxy', field: 'mode', value: 'custom' }, { saved: 'none' })
    d = withEdit(d, { prefix: 'proxy', field: 'mode', value: 'none' }, { saved: 'none' })
    assert.deepEqual(draftChanges(d), [])
  })

  test('a blank secret is never sent', () => {
    let d = withEdit({}, { prefix: 'm365', field: 'client-secret', value: 'abc' }, { secret: true })
    assert.equal(draftChanges(d).length, 1)
    d = withEdit(d, { prefix: 'm365', field: 'client-secret', value: '' }, { secret: true })
    assert.deepEqual(draftChanges(d), [])
  })

  test('a cleared text that is not a secret is sent as a clear', () => {
    const d = withEdit({}, { prefix: 'm365', field: 'scopes', value: '' }, { saved: 'openid' })
    assert.deepEqual(draftChanges(d), [{ prefix: 'm365', field: 'scopes', value: '' }])
  })

  test('shows its own value, else what the server holds', () => {
    const d = withEdit({}, { prefix: 'chat', field: 'provider', value: 'p2' })
    assert.equal(draftValue(d, 'chat', 'provider', 'p1'), 'p2')
    assert.equal(draftValue(d, 'chat', 'model', 'm1'), 'm1')
  })

  test('does not change the draft it was given', () => {
    const d = {}
    withEdit(d, { prefix: 'a', field: 'b', value: 'c' })
    assert.deepEqual(d, {})
  })
})

describe('saves in flight', () => {
  test('the newest pending value is the one shown', () => {
    const first = [{ prefix: 'retention', field: 'usage-days', value: '30' }]
    const second = [{ prefix: 'retention', field: 'usage-days', value: '60' }]
    const pending = withPending(withPending([], first), second)
    assert.equal(pendingValue(pending, 'retention', 'usage-days'), '60')
    assert.equal(pendingValue(pending, 'retention', 'audit-days'), undefined)
  })

  test('a save that settles takes only its own changes away', () => {
    const first = [{ prefix: 'retention', field: 'usage-days', value: '30' }]
    const second = [{ prefix: 'retention', field: 'usage-days', value: '60' }]
    const pending = withPending(withPending([], first), second)
    const left = withoutPending(pending, first)
    assert.equal(pendingValue(left, 'retention', 'usage-days'), '60')
    assert.deepEqual(withoutPending(left, second), [])
  })
})

describe('the embedding model asks first', () => {
  test('a change of the embedding model, or a provider change clearing it, asks', () => {
    assert.equal(changesEmbeddingModel([{ prefix: 'embedding', field: 'model', value: 'x' }]), true)
    assert.equal(changesEmbeddingModel([
      { prefix: 'embedding', field: 'provider', value: 'p' },
      { prefix: 'embedding', field: 'model', value: '' },
    ]), true)
  })

  test('nothing else does', () => {
    assert.equal(changesEmbeddingModel([{ prefix: 'embedding', field: 'max-concurrent-requests', value: '8' }]), false)
    assert.equal(changesEmbeddingModel([{ prefix: 'chat', field: 'model', value: 'x' }]), false)
  })
})
