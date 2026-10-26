import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cacheDeadline, cacheLifetime, countdownText, nearlyCold, secondsUntil } from './cacheTimer.ts'

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
