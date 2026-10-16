import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  nextReconnectDelay,
  RECONNECT_BUDGET_MS,
  RECONNECT_CEILING_MS,
  RECONNECT_FIRST_MS,
} from './reconnect.ts'

test('the wait doubles from one second up to its ceiling', () => {
  const waits = [0, 1, 2, 3, 4, 10].map(a => nextReconnectDelay(a, 0, 0))
  assert.deepEqual(waits, [RECONNECT_FIRST_MS, 2000, 4000, RECONNECT_CEILING_MS, RECONNECT_CEILING_MS, RECONNECT_CEILING_MS])
})

test('a restart that takes a minute and a half is waited out', () => {
  // The 0.36.0 feedback's laptop: 77 seconds to start, after a shutdown of a few.
  let now = 0
  let attempt = 0
  while (now < 90_000) {
    const wait = nextReconnectDelay(attempt, 0, now)
    assert.notEqual(wait, null, `gave up at ${now}ms`)
    now += wait as number
    attempt += 1
  }
})

test('three minutes after the stream was last open, the page stops trying', () => {
  assert.equal(nextReconnectDelay(40, 0, RECONNECT_BUDGET_MS), null)
  assert.notEqual(nextReconnectDelay(40, 0, RECONNECT_BUDGET_MS - 1), null)
})

test('the budget counts from the last time the stream was open, not from the first try', () => {
  const reopened = 500_000
  assert.equal(nextReconnectDelay(0, reopened, reopened + 1000), RECONNECT_FIRST_MS)
})
