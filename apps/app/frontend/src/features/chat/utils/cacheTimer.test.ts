import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cacheDeadline, cacheLifetime, cacheRingPercent, countdownText, nearlyCold, secondsUntil } from './cacheTimer.ts'

test('the deadline is taken from when the figure arrived, and a cold cache has none', () => {
  assert.equal(cacheDeadline(300, 1_000), 301_000)
  assert.equal(cacheDeadline(0, 1_000), null)
  assert.equal(cacheDeadline(null, 1_000), null)
  assert.equal(cacheDeadline(Number.NaN, 1_000), null)
})

test('seconds left round up, and never go below zero', () => {
  assert.equal(secondsUntil(10_500, 10_000), 1)
  assert.equal(secondsUntil(10_000, 10_000), 0)
  assert.equal(secondsUntil(5_000, 10_000), 0)
  assert.equal(secondsUntil(null, 10_000), 0)
})

test('a countdown reads as a clock', () => {
  assert.equal(countdownText(247), '4:07')
  assert.equal(countdownText(59), '0:59')
  assert.equal(countdownText(3599), '59:59')
  assert.equal(countdownText(3600), '1:00:00')
  assert.equal(countdownText(-3), '0:00')
})

test('an hour-long cache says so; anything shorter is five minutes', () => {
  assert.equal(cacheLifetime(3600), 'hour')
  assert.equal(cacheLifetime(300), 'fiveMinutes')
  assert.equal(cacheLifetime(null), 'fiveMinutes')
})

test('the last minute is nearly cold; a cold cache is not', () => {
  assert.equal(nearlyCold(60), true)
  assert.equal(nearlyCold(61), false)
  assert.equal(nearlyCold(0), false)
})

test('the ring drains against the lifetime the server stated', () => {
  assert.equal(cacheRingPercent(300, 300), 100)
  assert.equal(cacheRingPercent(150, 300), 50)
  assert.equal(cacheRingPercent(1800, 3600), 50)
  assert.equal(cacheRingPercent(0, 300), 0)
  assert.equal(cacheRingPercent(-5, 300), 0)
  assert.equal(cacheRingPercent(Number.NaN, 300), 0)
})

test('an unstated lifetime is five minutes, and a ring is never over full', () => {
  assert.equal(cacheRingPercent(150, null), 50)
  assert.equal(cacheRingPercent(150, 0), 50)
  // More left than the stated lifetime (a figure from a clock ahead of ours): full, not 120%.
  assert.equal(cacheRingPercent(360, 300), 100)
  assert.equal(cacheRingPercent(900, null), 100)
})

test('a write or read fills the ring for its whole lifetime, five minutes or an hour, and it drains as the clock runs', () => {
  for (const lifetime of [300, 3600]) {
    // The figure arrives as a write or read has just started the lifetime: full.
    const start = 1_000_000
    const deadline = cacheDeadline(lifetime, start)
    assert.equal(cacheRingPercent(secondsUntil(deadline, start), lifetime), 100)
    // A quarter, a half and all of it later: three quarters, half, empty.
    assert.equal(cacheRingPercent(secondsUntil(deadline, start + lifetime * 250), lifetime), 75)
    assert.equal(cacheRingPercent(secondsUntil(deadline, start + lifetime * 500), lifetime), 50)
    assert.equal(cacheRingPercent(secondsUntil(deadline, start + lifetime * 1000), lifetime), 0)
    // The next use restarts the lifetime: full again, from a new deadline.
    const later = start + lifetime * 600
    assert.equal(cacheRingPercent(secondsUntil(cacheDeadline(lifetime, later), later), lifetime), 100)
  }
})
