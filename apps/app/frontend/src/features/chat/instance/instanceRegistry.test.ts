// Run: node --test apps/app/frontend/src/features/chat/instance/instanceRegistry.test.ts
//
// Chat instances held by key: one per chat on screen, surviving the remount of the component that
// shows it, gone once nothing has shown it for a while.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { createInstanceRegistry, type RegistryTimers } from './instanceRegistry.ts'

// Timers run by hand: `run()` fires everything due, as if the grace period had passed.
function fakeTimers() {
  let next = 1
  const pending = new Map<number, () => void>()
  const timers: RegistryTimers = {
    set: fn => { const id = next++; pending.set(id, fn); return id },
    clear: handle => { pending.delete(handle as number) },
  }
  const run = () => {
    const due = [...pending.values()]
    pending.clear()
    due.forEach(fn => fn())
  }
  return { timers, run, pending: () => pending.size }
}

function registry() {
  const t = fakeTimers()
  const disposed: string[] = []
  let made = 0
  const r = createInstanceRegistry<{ key: string; n: number }>({
    dispose: (_, key) => { disposed.push(key) },
    graceMs: 5000,
    timers: t.timers,
  })
  const get = (key: string) => r.get(key, () => ({ key, n: ++made }))
  return { r, get, run: t.run, pending: t.pending, disposed }
}

describe('chat instances by key', () => {
  test('one key is one instance, and two keys are two', () => {
    const { get } = registry()
    assert.equal(get('a'), get('a'))
    assert.notEqual(get('a'), get('b'))
  })

  test('a remount keeps the instance: released and retained again before the grace ends', () => {
    const { r, get, run, disposed } = registry()
    const first = get('panel')
    const release = r.retain('panel')
    release()
    // The replacing component retains it in the same commit.
    const again = r.retain('panel')
    run()
    assert.deepEqual(disposed, [])
    assert.equal(get('panel'), first)
    again()
  })

  test('an instance nobody shows any more is disposed after the grace, and a new one is made next time', () => {
    const { r, get, run, disposed } = registry()
    const first = get('panel')
    r.retain('panel')()
    run()
    assert.deepEqual(disposed, ['panel'])
    assert.equal(r.peek('panel'), undefined)
    assert.notEqual(get('panel'), first)
  })

  test('an instance made by a render that never committed does not stay', () => {
    const { r, get, run, disposed } = registry()
    get('orphan')
    run()
    assert.deepEqual(disposed, ['orphan'])
    assert.equal(r.size(), 0)
  })

  test('two components showing one instance: it stays until both are gone', () => {
    const { r, get, run, disposed } = registry()
    get('shared')
    const one = r.retain('shared')
    const two = r.retain('shared')
    one()
    run()
    assert.deepEqual(disposed, [])
    two()
    run()
    assert.deepEqual(disposed, ['shared'])
  })

  test('releasing twice counts once', () => {
    const { r, get, run, disposed } = registry()
    get('x')
    const one = r.retain('x')
    const two = r.retain('x')
    one()
    one()
    run()
    assert.deepEqual(disposed, [], 'the second holder still holds it')
    two()
    run()
    assert.deepEqual(disposed, ['x'])
  })

  test('disposing one key leaves the others alone', () => {
    const { r, get, run, disposed } = registry()
    get('a')
    get('b')
    const keepB = r.retain('b')
    r.retain('a')()
    run()
    assert.deepEqual(disposed, ['a'])
    assert.ok(r.peek('b'))
    keepB()
  })

  test('retaining a key that was never made holds nothing', () => {
    const { r, pending } = registry()
    const release = r.retain('never')
    release()
    assert.equal(pending(), 0)
    assert.equal(r.size(), 0)
  })
})
