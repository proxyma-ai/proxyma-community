// Run: node --test apps/app/frontend/src/features/chat/utils/hiddenPause.test.ts
//
// When a hidden tab releases its chat streams and when it takes them back. The document's
// visibility is fed in by chatInstance.ts; the timing is decided here.
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { createHiddenPause, type PauseTimers } from './hiddenPause.ts'

function fakeTimers() {
  let next = 1
  const pending = new Map<number, () => void>()
  const timers: PauseTimers = {
    set: fn => { const id = next++; pending.set(id, fn); return id },
    clear: handle => { pending.delete(handle as number) },
  }
  const fire = () => { for (const [id, fn] of [...pending]) { pending.delete(id); fn() } }
  return { timers, fire, pending: () => pending.size }
}

function setup() {
  const clock = fakeTimers()
  const calls: string[] = []
  const tab = createHiddenPause({
    graceMs: 30_000,
    pause: () => calls.push('pause'),
    resume: () => calls.push('resume'),
    timers: clock.timers,
  })
  return { clock, calls, tab }
}

test('a tab hidden past the grace pauses, and resumes when shown', () => {
  const { clock, calls, tab } = setup()
  tab.hidden()
  assert.deepEqual(calls, [], 'nothing at once')
  clock.fire()
  assert.deepEqual(calls, ['pause'])
  tab.visible()
  assert.deepEqual(calls, ['pause', 'resume'])
})

test('a glance at another tab pauses nothing and resumes nothing', () => {
  const { clock, calls, tab } = setup()
  tab.hidden()
  tab.visible()
  assert.equal(clock.pending(), 0, 'the grace timer is cleared')
  clock.fire()
  assert.deepEqual(calls, [])
})

test('hidden twice is one grace and one pause; shown twice is one resume', () => {
  const { clock, calls, tab } = setup()
  tab.hidden()
  tab.hidden()
  assert.equal(clock.pending(), 1)
  clock.fire()
  tab.hidden()
  assert.equal(clock.pending(), 0, 'already paused: no second timer')
  tab.visible()
  tab.visible()
  assert.deepEqual(calls, ['pause', 'resume'])
})

test('a tab hidden again after resuming pauses again', () => {
  const { clock, calls, tab } = setup()
  tab.hidden(); clock.fire(); tab.visible()
  tab.hidden(); clock.fire(); tab.visible()
  assert.deepEqual(calls, ['pause', 'resume', 'pause', 'resume'])
})
